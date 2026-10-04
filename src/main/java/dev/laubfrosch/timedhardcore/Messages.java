package dev.laubfrosch.timedhardcore;

import static dev.laubfrosch.timedhardcore.Colors.BLOOD;
import static dev.laubfrosch.timedhardcore.Colors.DARK_BLOOD;
import static dev.laubfrosch.timedhardcore.Colors.GOLD;
import static dev.laubfrosch.timedhardcore.Colors.GREEN;
import static dev.laubfrosch.timedhardcore.Colors.LIGHT;
import static dev.laubfrosch.timedhardcore.Colors.MUTED;

import com.mojang.authlib.GameProfile;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.objects.AtlasSprite;
import net.minecraft.network.chat.contents.objects.PlayerSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.component.ResolvableProfile;

/**
 * The formatted texts players see: chat, kick screens, player list, action bar, and server list.
 * Icons are sprites from the vanilla textures (object text components), so the client needs neither
 * the mod nor a resource pack. The one exception is the green apple icon.
 */
public final class Messages {

	private static final Identifier GUI_ATLAS = Identifier.withDefaultNamespace("gui");
	private static final Identifier ITEMS_ATLAS = Identifier.withDefaultNamespace("items");

	private Messages() {
	}

	// --- Icons ---

	/** Empty hardcore heart = dead. */
	private static MutableComponent deadHeart() {
		return Component.object(new AtlasSprite(GUI_ATLAS, Identifier.withDefaultNamespace("hud/heart/container_hardcore")), Component.literal("☠"));
	}

	private static MutableComponent clock() {
		return Component.object(new AtlasSprite(ITEMS_ATLAS, Identifier.withDefaultNamespace("item/clock_00")), Component.literal("⌚"));
	}

	private static MutableComponent totem() {
		return Component.object(new AtlasSprite(ITEMS_ATLAS, Identifier.withDefaultNamespace("item/totem_of_undying")), Component.literal("✚"));
	}

	private static MutableComponent bundle() {
		return Component.object(new AtlasSprite(ITEMS_ATLAS, Identifier.withDefaultNamespace("item/bundle")), Component.literal("▣"));
	}

	private static MutableComponent heartIcon(String sprite) {
		return Component.object(new AtlasSprite(GUI_ATLAS, Identifier.withDefaultNamespace(sprite)), Component.literal("❤"));
	}

	/**
	 * Apple icon for extra life messages. Only clients with the mod know the green apple (for everyone
	 * else it would be a "missing texture" square), so all others get the normal apple.
	 */
	private static MutableComponent appleIcon(boolean green) {
		Identifier sprite = green ? TimedHardcore.id("item/green_apple") : Identifier.withDefaultNamespace("item/apple");
		return Component.object(new AtlasSprite(ITEMS_ATLAS, sprite), Component.literal("❤"));
	}

	private static MutableComponent head(ResolvableProfile profile) {
		return Component.object(new PlayerSprite(profile, true));
	}

	/** Head with skin data (visible immediately). */
	private static MutableComponent head(GameProfile profile) {
		return head(ResolvableProfile.createResolved(profile));
	}

	/** Head by UUID only; the client loads the skin itself. */
	private static MutableComponent head(UUID uuid) {
		return head(ResolvableProfile.createUnresolved(uuid));
	}

	// --- Building blocks ---

	private static MutableComponent text(String text, int color) {
		return Component.literal(text).withColor(color);
	}

	private static MutableComponent bold(String text, int color) {
		return text(text, color).withStyle(style -> style.withBold(true));
	}

	private static MutableComponent header(String title) {
		return Component.empty()
			.append(deadHeart())
			.append(" ")
			.append(bold(title, BLOOD))
			.append(" ")
			.append(deadHeart());
	}

	private static MutableComponent durationLine(String label, long millis) {
		return Component.empty()
			.append(clock())
			.append(text(" " + label + " ", MUTED))
			.append(text(TimeFormat.duration(millis), GOLD));
	}

	private static MutableComponent reviveLine(long reviveAt) {
		return Component.empty()
			.append(totem())
			.append(text(" Revival on ", MUTED))
			.append(text(TimeFormat.date(reviveAt), GREEN));
	}

	// --- Timed death ---

	/** Kick screen right after the death. */
	public static Component deathKick(DeadPlayerManager.DeadPlayer deadPlayer, GameProfile profile, Component deathMessage) {
		return Component.empty()
			.append(header("YOU DIED"))
			.append("\n\n")
			.append(head(profile))
			.append(" ")
			.append(deathMessage.copy().withColor(LIGHT))
			.append("\n\n")
			.append(durationLine("You are dead for", deadPlayer.remainingMillis()))
			.append("\n")
			.append(reviveLine(deadPlayer.reviveAt));
	}

	/** Rejected login attempt. */
	public static Component loginDenied(DeadPlayerManager.DeadPlayer deadPlayer) {
		return Component.empty()
			.append(header("YOU ARE STILL DEAD"))
			.append("\n\n")
			.append(durationLine("Time left:", deadPlayer.remainingMillis()))
			.append("\n")
			.append(reviveLine(deadPlayer.reviveAt));
	}

	/** Chat message to everyone when somebody dies and stays dead. */
	public static Component deathBroadcast(GameProfile profile, long durationMillis) {
		return Component.empty()
			.append(deadHeart())
			.append(" ")
			.append(head(profile))
			.append(text(" " + profile.name(), LIGHT))
			.append(text(" is dead for ", MUTED))
			.append(text(TimeFormat.duration(durationMillis), GOLD))
			.append(text(".", MUTED));
	}

	/** To the dying player when enough players are online. */
	public static Component safeDeath(int onlinePlayers) {
		return Component.empty()
			.append(text("Lucky you: ", GREEN))
			.append(text(onlinePlayers + " players online, this death has no consequences.", MUTED));
	}

	/** Second MOTD line; the client mod shows it to its own player while they are dead. */
	public static Component motdSecondLine(long remainingMillis) {
		return Component.empty()
			.append(deadHeart())
			.append(" ")
			.append(text("You are dead for another ", MUTED))
			.append(text(TimeFormat.duration(remainingMillis), GOLD))
			.append(text(".", MUTED));
	}

	/** Second MOTD line for a player who is alive again but has not joined since. */
	public static Component motdRevived() {
		return Component.empty()
			.append(totem())
			.append(" ")
			.append(text("You have been revived · join again!", GREEN));
	}

	// --- Revival ---

	/** Chat to everyone: revived by command or button. */
	public static Component revived(DeadPlayerManager.DeadPlayer deadPlayer, String by) {
		return Component.empty()
			.append(totem())
			.append(" ")
			.append(head(deadPlayer.uuid))
			.append(text(" " + deadPlayer.name, LIGHT))
			.append(text(" was revived by ", GREEN))
			.append(text(by, LIGHT))
			.append(text(" and can join again.", GREEN));
	}

	/** Chat to everyone: the time is up. */
	public static Component revivedByTime(DeadPlayerManager.DeadPlayer deadPlayer) {
		return Component.empty()
			.append(totem())
			.append(" ")
			.append(head(deadPlayer.uuid))
			.append(text(" " + deadPlayer.name, LIGHT))
			.append(text(" was revived and can join again.", GREEN));
	}

	// --- Extra life ---

	public static Component extraLifeGained(GameProfile profile, boolean greenIcon) {
		return Component.empty()
			.append(appleIcon(greenIcon))
			.append(" ")
			.append(head(profile))
			.append(text(" " + profile.name(), LIGHT))
			.append(text(" now has an ", MUTED))
			.append(bold("extra life", GREEN))
			.append(text("!", MUTED));
	}

	public static Component extraLifeUsed(GameProfile profile, boolean greenIcon) {
		return Component.empty()
			.append(appleIcon(greenIcon))
			.append(" ")
			.append(head(profile))
			.append(text(" " + profile.name(), LIGHT))
			.append(text(" died and used up their ", MUTED))
			.append(text("extra life", GREEN))
			.append(text(".", MUTED));
	}

	public static Component alreadyHasExtraLife() {
		return text("You already have an extra life. You cannot have more than one.", GREEN);
	}

	// --- Risk: at risk / protected / safe ---

	/** Icon per risk: red hardcore heart = at risk, apple = protected, normal heart = safe. */
	private static MutableComponent riskIcon(Risk risk, boolean greenIcon) {
		return switch (risk) {
			case TIMED_DEATH -> heartIcon("hud/heart/hardcore_full");
			case EXTRA_LIFE -> appleIcon(greenIcon);
			case SAFE, EXEMPT -> heartIcon("hud/heart/full");
		};
	}

	private static MutableComponent riskLabel(Risk risk) {
		return switch (risk) {
			case TIMED_DEATH -> bold("at risk", BLOOD);
			case EXTRA_LIFE -> bold("protected", GREEN);
			case SAFE, EXEMPT -> bold("safe", LIGHT);
		};
	}

	/** Name in the player list (TAB): risk icon and name (with team color). */
	public static Component tabListName(Component name, Risk risk, boolean greenIcon) {
		return Component.empty().append(riskIcon(risk, greenIcon)).append(" ").append(name);
	}

	/** Footer of the player list; every player sees their own risk. */
	public static Component tabListFooter(Risk risk, int onlinePlayers, int safePlayerCount, boolean greenIcon) {
		MutableComponent footer = Component.empty()
			.append("\n")
			.append(riskIcon(risk, greenIcon))
			.append(text(" You are ", MUTED))
			.append(riskLabel(risk))
			.append(text(".", MUTED))
			.append("\n")
			.append(switch (risk) {
				case TIMED_DEATH -> text("If you die now, you stay dead until your revival.", MUTED);
				case EXTRA_LIFE -> text("Your extra life brings you straight back if you die.", MUTED);
				case SAFE -> text("A death has no consequences right now.", MUTED);
				case EXEMPT -> text("Creative or spectator mode, you respawn normally.", MUTED);
			});
		if (safePlayerCount > 0) {
			footer.append("\n").append(text("Safe from " + safePlayerCount + " players · currently " + onlinePlayers + " online", MUTED));
		}
		return footer;
	}

	/** Action bar when the player's own risk changes. */
	public static Component riskChanged(Risk risk, int onlinePlayers, boolean greenIcon) {
		return riskMessage(risk, onlinePlayers, greenIcon, false);
	}

	/** Action bar shortly after joining: the current risk. */
	public static Component riskOnJoin(Risk risk, int onlinePlayers, boolean greenIcon) {
		return riskMessage(risk, onlinePlayers, greenIcon, true);
	}

	private static Component riskMessage(Risk risk, int onlinePlayers, boolean greenIcon, boolean join) {
		MutableComponent message = Component.empty().append(riskIcon(risk, greenIcon)).append(" ");
		String prefix = join ? "You are " : "You are now ";
		return switch (risk) {
			case TIMED_DEATH -> message
				.append(text(join ? prefix : "Warning: " + prefix, BLOOD))
				.append(bold("at risk", BLOOD))
				.append(text(" – if you die, you stay dead!", BLOOD));
			case EXTRA_LIFE -> message
				.append(text(prefix, GREEN))
				.append(bold("protected", GREEN))
				.append(text(" – your extra life absorbs the next death.", GREEN));
			case SAFE -> message
				.append(text(prefix, LIGHT))
				.append(bold("safe", LIGHT))
				.append(text(" – " + onlinePlayers + " players online.", LIGHT));
			case EXEMPT -> message
				.append(text(prefix, LIGHT))
				.append(bold("safe", LIGHT))
				.append(text(" – creative or spectator mode.", LIGHT));
		};
	}

	// --- /timedhardcore info ---

	/** The rules of the server at a glance. */
	public static Component info(TimedHardcoreConfig config, int onlinePlayers, boolean keepInventoryOnTimedDeath, int deadPlayers, boolean greenIcon) {
		long now = System.currentTimeMillis();
		long revivalTime = config.revivalTime(now);
		boolean deathsCount = revivalTime > now;
		MutableComponent info = header("TIMED HARDCORE");

		info.append(deathsCount
			? infoLine(clock(), "Revival", config.describe(), LIGHT, " · if you died now: " + TimeFormat.date(revivalTime))
			: infoLine(clock(), "Revival", "at once", GREEN, " · deaths have no consequences"));

		info.append(config.safePlayerCount > 0
			? infoLine(heartIcon("hud/heart/full"), "Safe rule", "no consequences with " + config.safePlayerCount + " or more players online", LIGHT,
				" · currently " + onlinePlayers)
			: infoLine(heartIcon("hud/heart/hardcore_full"), "Safe rule", "off", BLOOD, " · every death counts"));

		info.append(config.extraLivesEnabled
			? infoLine(appleIcon(greenIcon), "Extra life", "a green apple saves you once", LIGHT,
				" · sold by the Wandering Healer")
			: infoLine(appleIcon(greenIcon), "Extra life", "disabled", BLOOD, ""));

		info.append(keepInventoryOnTimedDeath
			? infoLine(bundle(), "Inventory", "kept while you are dead", GREEN, "")
			: infoLine(bundle(), "Inventory", "follows the keepInventory rule", LIGHT, ""));

		info.append(infoLine(deadHeart(), "Dead right now", String.valueOf(deadPlayers), deadPlayers == 0 ? GREEN : BLOOD, ""));
		if (deadPlayers > 0) {
			info.append(" ").append(commandButton());
		}
		return info;
	}

	/** One line of the info: icon, label, value, and a note in gray. Starts on a new line. */
	private static MutableComponent infoLine(Component icon, String label, String value, int valueColor, String note) {
		return Component.empty()
			.append("\n")
			.append(icon)
			.append(bold(" " + label + ": ", GOLD))
			.append(text(value, valueColor))
			.append(text(note, MUTED));
	}

	private static Component commandButton() {
		return bold("[Show]", GREEN).withStyle(style -> style
			.withClickEvent(new ClickEvent.RunCommand("/timedhardcore dead"))
			.withHoverEvent(new HoverEvent.ShowText(text("/timedhardcore dead", MUTED))));
	}

	// --- /timedhardcore list and dead ---

	/** Risk of an online player for the status line, with an explanation shown on hover. */
	public record PlayerRisk(GameProfile profile, Risk risk, Component details) {
		public static PlayerRisk of(ServerPlayer player) {
			TimedHardcoreConfig config = TimedHardcore.config();
			int online = player.level().getServer().getPlayerList().getPlayerCount();
			long revivalTime = config.revivalTime(System.currentTimeMillis());
			Risk risk = Risk.of(player);
			return new PlayerRisk(player.getGameProfile(), risk, riskDetails(risk, online, config.safePlayerCount, revivalTime));
		}
	}

	/** Status line: who is at risk right now? Details on hover. */
	public static Component statusLine(List<PlayerRisk> players, boolean greenIcon) {
		MutableComponent line = Component.empty().append(bold("Status: ", GOLD));
		if (players.isEmpty()) {
			return line.append(text("nobody online", MUTED));
		}
		for (int i = 0; i < players.size(); i++) {
			PlayerRisk player = players.get(i);
			if (i > 0) {
				line.append(text("   ", MUTED));
			}
			Component hover = Component.empty()
				.append(head(player.profile()))
				.append(text(" " + player.profile().name(), LIGHT))
				.append("\n")
				.append(player.details());
			line.append(Component.empty()
				.append(head(player.profile()))
				.append(text(" " + player.profile().name() + " ", LIGHT))
				.append(riskIcon(player.risk(), greenIcon))
				.append(" ")
				.append(riskLabel(player.risk()))
				.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(hover))));
		}
		return line;
	}

	private static Component riskDetails(Risk risk, int onlinePlayers, int safePlayerCount, long revivalTime) {
		return switch (risk) {
			case TIMED_DEATH -> Component.empty()
				.append(text("Dying now means staying ", MUTED))
				.append(text("dead", BLOOD))
				.append(text(" until " + TimeFormat.date(revivalTime) + ".", MUTED))
				.append(safePlayerCount > 0
					? text("\nSafe only from " + safePlayerCount + " players online (currently " + onlinePlayers + ").", MUTED)
					: Component.empty());
			case EXTRA_LIFE -> Component.empty()
				.append(text("Dying uses up the ", MUTED))
				.append(text("extra life", GREEN))
				.append(text(", you respawn normally.", MUTED));
			case SAFE -> text("A death has no consequences right now (" + onlinePlayers + " players online).", MUTED);
			case EXEMPT -> text("In creative or spectator mode, you respawn normally.", MUTED);
		};
	}

	public static Component listHeader(int count) {
		return Component.empty()
			.append(deadHeart())
			.append(bold(" Dead players (" + count + "):", DARK_BLOOD));
	}

	/** One line of the list of dead players; operators also get a clickable revive button. */
	public static Component listEntry(DeadPlayerManager.DeadPlayer deadPlayer, boolean withReviveButton) {
		MutableComponent entry = Component.empty()
			.append(" ")
			.append(head(deadPlayer.uuid))
			.append(text(" " + deadPlayer.name, LIGHT))
			.append(text(" – ", MUTED))
			.append(text(TimeFormat.duration(deadPlayer.remainingMillis()), GOLD))
			.append(text(" left (until " + TimeFormat.date(deadPlayer.reviveAt) + ")", MUTED));
		return withReviveButton ? entry.append(" ").append(reviveButton(deadPlayer.name)) : entry;
	}

	private static Component reviveButton(String name) {
		Component tooltip = Component.empty()
			.append(totem())
			.append(text(" Revive " + name + " now", GREEN))
			.append("\n")
			.append(text("/revive " + name, MUTED));
		return bold("[Revive]", GREEN).withStyle(style -> style
			.withClickEvent(new ClickEvent.RunCommand("/revive " + name))
			.withHoverEvent(new HoverEvent.ShowText(tooltip)));
	}

	public static Component livesHeader(int count, boolean greenIcon) {
		return Component.empty()
			.append(appleIcon(greenIcon))
			.append(bold(" With extra life (" + count + "):", GREEN));
	}

	public static Component livesEntry(UUID uuid, String name) {
		return Component.empty().append(" ").append(head(uuid)).append(text(" " + name, LIGHT));
	}
}
