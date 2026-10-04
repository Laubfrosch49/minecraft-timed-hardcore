package dev.laubfrosch.timedhardcore;

import static dev.laubfrosch.timedhardcore.Colors.BLOOD;
import static dev.laubfrosch.timedhardcore.Colors.GOLD;
import static dev.laubfrosch.timedhardcore.Colors.GREEN;
import static dev.laubfrosch.timedhardcore.Colors.LIGHT;
import static dev.laubfrosch.timedhardcore.Colors.MUTED;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.core.ClientAsset;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket.PositionedAdvancement;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.gamerules.GameRules;
import org.jspecify.annotations.Nullable;

/**
 * Advancement tab "Graveyard": the most important rules at the top (always visible, always up to date)
 * and all dead players below. The entries only exist on the client: they are sent directly and never
 * saved as real advancements. Works without the client mod.
 */
public final class Graveyard {

	private static final String PATH_PREFIX = "graveyard/";
	private static final String RULE_PREFIX = "rule_";
	private static final Identifier ROOT_ID = id("root");
	private static final String CRITERION = "dead";
	private static final AdvancementRequirements REQUIREMENTS = AdvancementRequirements.allOf(List.of(CRITERION));
	private static final int PLAYERS_PER_ROW = 5;
	/** The dead players start below the row of rules. */
	private static final float FIRST_PLAYER_ROW = 1.5F;

	/**
	 * The client makes a hover box as wide as its title and wraps the description to that width.
	 * A title narrower than 80 pixels counts as 80, and one wider than 163 is wrapped itself.
	 * Titles are therefore padded with spaces to just below that limit.
	 */
	private static final int TITLE_WIDTH = 156;
	private static final int SPACE_WIDTH = 4;

	/** What was last sent to everyone (to detect changes and to remove old entries). */
	private static Set<Identifier> sentIds = Set.of();
	private static String sentSignature = "";

	private Graveyard() {
	}

	/** Called every second: sends changes (deaths, revivals, settings, player count) to everyone. */
	public static void tick(MinecraftServer server, DeadPlayerManager manager) {
		List<PositionedAdvancement> entries = build(server, manager.stillDead());
		String signature = signature(entries);
		if (signature.equals(sentSignature)) {
			return;
		}
		ClientboundUpdateAdvancementsPacket packet = new ClientboundUpdateAdvancementsPacket(false, entries, sentIds, progress(entries), false);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			player.connection.send(packet);
		}
		sentIds = ids(entries);
		sentSignature = signature;
	}

	/** Sends the tab to one player again, after their advancements were reloaded completely (join, /reload). */
	public static void sendTo(ServerPlayer player, DeadPlayerManager manager) {
		List<PositionedAdvancement> entries = build(player.level().getServer(), manager.stillDead());
		player.connection.send(new ClientboundUpdateAdvancementsPacket(false, entries, Set.of(), progress(entries), false));
	}

	public static void reset() {
		sentIds = Set.of();
		sentSignature = "";
	}

	/** The whole tab: root, the rules as a chain to the right, and the dead players in the rows below. */
	public static List<PositionedAdvancement> build(MinecraftServer server, List<DeadPlayerManager.DeadPlayer> deadPlayers) {
		List<PositionedAdvancement> entries = new ArrayList<>();
		DisplayInfo rootDisplay = new DisplayInfo(
			new ItemStackTemplate(Items.SKELETON_SKULL),
			Component.literal("Graveyard").withColor(BLOOD),
			lines(
				line("The dead rest here until they are revived."),
				Component.empty(),
				line("Dead right now: ", String.valueOf(deadPlayers.size()), deadPlayers.isEmpty() ? GREEN : BLOOD, "")),
			Optional.of(new ClientAsset.ResourceTexture(Identifier.withDefaultNamespace("block/soul_soil"))),
			AdvancementType.TASK, false, false, false
		);
		entries.add(new PositionedAdvancement(advancement(ROOT_ID, null, rootDisplay), 0, 0));

		Identifier parent = ROOT_ID;
		List<Rule> rules = rules(server);
		for (int i = 0; i < rules.size(); i++) {
			Rule rule = rules.get(i);
			DisplayInfo display = new DisplayInfo(rule.icon(), title(rule.title(), GOLD), rule.description(),
				Optional.empty(), AdvancementType.GOAL, false, false, false);
			entries.add(new PositionedAdvancement(advancement(rule.id(), parent, display), i + 1, 0));
			parent = rule.id();
		}

		HolderLookup.Provider registries = server.registryAccess();
		for (int i = 0; i < deadPlayers.size(); i++) {
			DeadPlayerManager.DeadPlayer deadPlayer = deadPlayers.get(i);
			ItemStackTemplate head = new ItemStackTemplate(Items.PLAYER_HEAD, DataComponentPatch.builder()
				.set(DataComponents.PROFILE, ResolvableProfile.createUnresolved(deadPlayer.uuid))
				.build());
			DisplayInfo display = new DisplayInfo(head, title(deadPlayer.name, LIGHT), describe(registries, deadPlayer),
				Optional.empty(), AdvancementType.TASK, false, false, false);
			float x = 1 + i % PLAYERS_PER_ROW;
			float y = FIRST_PLAYER_ROW + (float) i / PLAYERS_PER_ROW;
			entries.add(new PositionedAdvancement(advancement(id(deadPlayer.uuid.toString()), ROOT_ID, display), x, y));
		}
		return entries;
	}

	private record Rule(Identifier id, ItemStackTemplate icon, String title, Component description) {
	}

	/** The most important settings, each with its current value. */
	private static List<Rule> rules(MinecraftServer server) {
		TimedHardcoreConfig config = TimedHardcore.config();
		int online = server.getPlayerList().getPlayerCount();
		long now = System.currentTimeMillis();
		boolean deathsCount = config.revivalTime(now) > now;
		boolean keepOnTimedDeath = server.getGameRules().get(TimedHardcoreGameRules.KEEP_INVENTORY_ON_TIMED_DEATH);
		boolean keepInventory = server.getGameRules().get(GameRules.KEEP_INVENTORY);
		List<Rule> rules = new ArrayList<>();

		rules.add(rule("revival", Items.CLOCK, "Revival", deathsCount
			? lines(
				line("The dead return ", config.describe(), GOLD, "."),
				line("Until then they cannot join."))
			: lines(line("Deaths have ", "no consequences", GREEN, "."))));

		rules.add(rule("safe", Items.SHIELD, "Safe rule", config.safePlayerCount > 0
			? lines(
				line("Deaths do not count while"),
				line("", config.safePlayerCount + " or more players", GREEN, " are online."),
				Component.empty(),
				line("Online right now: ", String.valueOf(online), config.isSafe(online) ? GREEN : BLOOD, ""))
			: lines(line("Off. ", "Every death counts", BLOOD, "."))));

		rules.add(new Rule(id(RULE_PREFIX + "extra_life"), ItemStackTemplate.fromNonEmptyStack(GreenApple.create()), "Extra life", config.extraLivesEnabled
			? lines(
				line("A green apple saves you ", "once", GREEN, "."),
				line("Only one extra life at a time."),
				line("Sold only by the ", "Wandering Healer", LIGHT, "."),
				line("Healer chance: ", Math.round(config.healerSpawnChance * 100) + " %", LIGHT, " per trader."))
			: lines(line("Extra lives are ", "disabled", BLOOD, "."))));

		rules.add(rule("inventory", Items.CHEST, "Inventory", lines(
			line(keepOnTimedDeath ? "While you are dead you keep" : "While you are dead the normal"),
			keepOnTimedDeath
				? line("", "your inventory and XP", GREEN, ".")
				: line("", "keepInventory rule", LIGHT, " applies."),
			line("keepInventory is ", keepInventory ? "on" : "off", keepInventory ? GREEN : BLOOD, "."))));
		return rules;
	}

	private static Rule rule(String name, Item icon, String title, Component description) {
		return new Rule(id(RULE_PREFIX + name), new ItemStackTemplate(icon), title, description);
	}

	private static Component describe(HolderLookup.Provider registries, DeadPlayerManager.DeadPlayer deadPlayer) {
		MutableComponent text = Component.empty();
		Component deathMessage = decodeDeathMessage(registries, deadPlayer.deathMessage);
		if (deathMessage != null) {
			text.append(Component.literal("☠ ").withColor(BLOOD)).append(deathMessage.copy().withColor(LIGHT)).append("\n\n");
		}
		return text.append(lines(
			line("Died: ", TimeFormat.date(deadPlayer.diedAt), BLOOD, "."),
			line("Revival: ", TimeFormat.date(deadPlayer.reviveAt), GREEN, ".")));
	}

	private static Component title(String text, int color) {
		int missing = TITLE_WIDTH - approximateWidth(text);
		return Component.literal(text + " ".repeat(Math.max(0, missing / SPACE_WIDTH))).withColor(color);
	}

	/**
	 * Width of a text in the default font, in pixels. The server has no font, so this uses the widths
	 * of the common characters and assumes the usual 6 pixels for everything else.
	 */
	private static int approximateWidth(String text) {
		int width = 0;
		for (char c : text.toCharArray()) {
			if ("i!,.:;|'".indexOf(c) >= 0) {
				width += 2;
			} else if (c == 'l' || c == '`') {
				width += 3;
			} else if ("It[] \"".indexOf(c) >= 0) {
				width += 4;
			} else if ("fk<>(){}*".indexOf(c) >= 0) {
				width += 5;
			} else if (c == '@' || c == '~') {
				width += 7;
			} else {
				width += 6;
			}
		}
		return width;
	}

	/**
	 * One line of a description. The lines are kept short enough to fit the hover box, because the
	 * client would otherwise wrap them wherever it likes.
	 */
	private static Component line(String text) {
		return Component.literal(text).withColor(MUTED);
	}

	/** A line with a colored value in the middle: gray text before it, the value, gray text after it. */
	private static Component line(String before, String value, int valueColor, String after) {
		return Component.literal(before).withColor(MUTED)
			.append(Component.literal(value).withColor(valueColor))
			.append(Component.literal(after).withColor(MUTED));
	}

	private static Component lines(Component... lines) {
		MutableComponent text = Component.empty();
		for (int i = 0; i < lines.length; i++) {
			if (i > 0) {
				text.append("\n");
			}
			text.append(lines[i]);
		}
		return text;
	}

	private static AdvancementHolder advancement(Identifier id, @Nullable Identifier parent, DisplayInfo display) {
		return new AdvancementHolder(id, new Advancement(
			Optional.ofNullable(parent), Optional.of(display), AdvancementRewards.EMPTY, Map.of(), REQUIREMENTS, false
		));
	}

	/** Root and rules count as "achieved" (colored), the dead players do not (gray, like ghosts). */
	private static Map<Identifier, AdvancementProgress> progress(List<PositionedAdvancement> entries) {
		Map<Identifier, AdvancementProgress> result = new HashMap<>();
		for (PositionedAdvancement entry : entries) {
			AdvancementProgress progress = new AdvancementProgress();
			progress.update(REQUIREMENTS);
			Identifier id = entry.advancement().id();
			if (id.equals(ROOT_ID) || id.getPath().startsWith(PATH_PREFIX + RULE_PREFIX)) {
				progress.grantProgress(CRITERION);
			}
			result.put(id, progress);
		}
		return result;
	}

	private static Set<Identifier> ids(List<PositionedAdvancement> entries) {
		Set<Identifier> ids = new HashSet<>();
		entries.forEach(entry -> ids.add(entry.advancement().id()));
		return ids;
	}

	/** Everything that is visible in the tab; if any of it changes, the tab is sent again. */
	private static String signature(List<PositionedAdvancement> entries) {
		StringBuilder signature = new StringBuilder();
		for (PositionedAdvancement entry : entries) {
			signature.append(entry.advancement().id()).append('@').append(entry.x()).append(',').append(entry.y()).append(':');
			entry.advancement().value().display().ifPresent(display ->
				signature.append(display.title().getString()).append('|').append(display.description().getString()));
			signature.append(';');
		}
		return signature.toString();
	}

	private static Identifier id(String path) {
		return TimedHardcore.id(PATH_PREFIX + path);
	}

	/** Death messages contain player and item names with hover texts, so they are stored as text JSON. */
	public static @Nullable JsonElement encodeDeathMessage(HolderLookup.Provider registries, Component message) {
		return ComponentSerialization.CODEC.encodeStart(registries.createSerializationContext(JsonOps.INSTANCE), message).result().orElse(null);
	}

	private static @Nullable Component decodeDeathMessage(HolderLookup.Provider registries, @Nullable JsonElement json) {
		if (json == null) {
			return null;
		}
		return ComponentSerialization.CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE), json).result().orElse(null);
	}
}
