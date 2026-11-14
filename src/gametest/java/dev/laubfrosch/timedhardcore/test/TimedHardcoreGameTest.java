package dev.laubfrosch.timedhardcore.test;

import com.google.gson.JsonElement;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.ParseResults;
import com.mojang.serialization.JsonOps;
import dev.laubfrosch.timedhardcore.DeadPlayerManager;
import dev.laubfrosch.timedhardcore.DeathClips;
import dev.laubfrosch.timedhardcore.DeathHandler;
import dev.laubfrosch.timedhardcore.ExtraLifeManager;
import dev.laubfrosch.timedhardcore.GifEncoder;
import dev.laubfrosch.timedhardcore.Graveyard;
import dev.laubfrosch.timedhardcore.GreenApple;
import dev.laubfrosch.timedhardcore.Healer;
import dev.laubfrosch.timedhardcore.Messages;
import dev.laubfrosch.timedhardcore.Risk;
import dev.laubfrosch.timedhardcore.RiskNotifier;
import dev.laubfrosch.timedhardcore.ServerListMotd;
import dev.laubfrosch.timedhardcore.TimedHardcore;
import dev.laubfrosch.timedhardcore.TimeFormat;
import dev.laubfrosch.timedhardcore.TimedHardcoreConfig;
import dev.laubfrosch.timedhardcore.TimedHardcoreGameRules;
import com.sun.net.httpserver.HttpServer;
import dev.laubfrosch.timedhardcore.DiscordWebhook;
import io.netty.channel.embedded.EmbeddedChannel;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket.PositionedAdvancement;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerStatusPacketListenerImpl;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

// The methods marked with @GameTest are called by Fabric's game test framework, not by our own code
@SuppressWarnings("unused")
public class TimedHardcoreGameTest {
	private static final InetSocketAddress LOCAL = new InetSocketAddress("127.0.0.1", 50000);
	private static final double ONE_DAY_IN_MINUTES = 1440;

	private static final Map<UUID, ServerPlayer> respawnedPlayers = new HashMap<>();

	static {
		ServerPlayerEvents.AFTER_RESPAWN.register((_, newPlayer, _) -> respawnedPlayers.put(newPlayer.getUUID(), newPlayer));
	}

	private static ServerPlayer joinSurvivalPlayer(GameTestHelper helper, String name) {
		GameProfile profile = new GameProfile(UUID.randomUUID(), name);
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
		ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), profile, cookie.clientInformation());
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		new EmbeddedChannel(connection);
		helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
		player.setGameMode(GameType.SURVIVAL);
		// A real client reports "loaded"; without it the player is invulnerable
		player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
		return player;
	}

	private static boolean isRejectedAsDead(PlayerList playerList, NameAndId nameAndId) {
		Component result = playerList.canPlayerLogin(LOCAL, nameAndId);
		return result != null && result.getString().contains("YOU ARE STILL DEAD");
	}

	private static void run(MinecraftServer server, String command) {
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
	}

	/** The config of the test server persists between runs, so every run starts from known values. */
	private static void resetConfig() {
		TimedHardcoreConfig config = TimedHardcore.config();
		config.reviveMode = TimedHardcoreConfig.ReviveMode.DURATION;
		config.deathDuration = ONE_DAY_IN_MINUTES;
		config.safePlayerCount = 2;
		config.extraLivesEnabled = true;
		config.showRemainingTimeInMotd = true;
		config.discord.deathWebhookUrl = "";
		config.discord.revivalWebhookUrl = "";
		// Saved as well, because /timedhardcore reload reads the file again
		config.save();
	}

	@GameTest(maxTicks = 40)
	public void timedDeathRespawnsAndKicks(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		PlayerList playerList = server.getPlayerList();
		DeadPlayerManager manager = Objects.requireNonNull(TimedHardcore.manager(), "Manager not initialised");
		resetConfig();
		// Remove dead players left over from earlier test runs
		manager.stillDead().forEach(deadPlayer -> manager.revive(deadPlayer.name));
		manager.awaitingReturn().forEach(deadPlayer -> manager.welcomeBack(deadPlayer.uuid));

		ServerPlayer player = joinSurvivalPlayer(helper, "Victim");
		UUID id = player.getUUID();
		NameAndId nameAndId = new NameAndId(id, "Victim");

		helper.assertFalse(isRejectedAsDead(playerList, nameAndId), "A living player must not be rejected: " + playerList.canPlayerLogin(LOCAL, nameAndId));

		helper.assertFalse(helper.getLevel().getGameRules().get(GameRules.KEEP_INVENTORY), "The test requires keepInventory=false");
		player.getInventory().add(new ItemStack(Items.DIAMOND, 5));
		player.giveExperienceLevels(10);

		player.kill(helper.getLevel());
		helper.assertTrue(player.isDeadOrDying(), "Player should be dead");
		helper.assertTrue(manager.getActive(id).isPresent(), "The player should stay dead");

		// Login is rejected with the remaining time
		helper.assertTrue(isRejectedAsDead(playerList, nameAndId), "Login should be rejected: " + playerList.canPlayerLogin(LOCAL, nameAndId));

		ServerStatus status = new ServerStatus(Component.literal("§aMy Server\nSecond line"), Optional.empty(), Optional.empty(), Optional.empty(), false);
		checkServerList(helper, status, id);

		// Loads the class, which applies the status mixin
		new ServerStatusPacketListenerImpl(status, new Connection(PacketFlow.SERVERBOUND));

		helper.runAfterDelay(2, () -> {
			ServerPlayer respawned = Objects.requireNonNull(respawnedPlayers.get(id), "Player should have been respawned by the server");
			helper.assertTrue(respawned != player, "The respawn should create a new player object");
			helper.assertFalse(respawned.isDeadOrDying(), "Respawned player should be alive");
			helper.assertTrue(respawned.gameMode() == GameType.SURVIVAL, "Player should stay in survival mode, is " + respawned.gameMode());
			helper.assertTrue(playerList.getPlayer(id) == null, "Dead player should have been kicked");
			// Timed death: inventory and XP are kept despite keepInventory=false
			helper.assertTrue(respawned.getInventory().countItem(Items.DIAMOND) == 5,
				"Dead player should keep their diamonds, has " + respawned.getInventory().countItem(Items.DIAMOND));
			helper.assertTrue(respawned.experienceLevel == 10, "Dead player should keep their levels, has " + respawned.experienceLevel);
			helper.assertFalse(respawned.entityTags().contains(DeathHandler.KEEP_INVENTORY_TAG), "Tag should be gone after the respawn");

			// Graveyard tab: root + 4 rules + the dead player, with the stored death message
			// Only this test's player: other tests run at the same time and may have dead players of their own
			List<PositionedAdvancement> graveyard = Graveyard.build(server,
				manager.stillDead().stream().filter(deadPlayer -> deadPlayer.uuid.equals(id)).toList());
			helper.assertTrue(graveyard.size() == 6, "The graveyard should have a root, 4 rules and 1 dead player, has " + graveyard.size());
			String grave = graveyard.getLast().advancement().value().display().orElseThrow().description().getString();
			helper.assertTrue(grave.contains("Victim") && grave.contains("Died: ") && grave.contains("Revival: "), "Graveyard entry: " + grave);
			String rules = graveyard.subList(1, 5).stream()
				.map(rule -> rule.advancement().value().display().orElseThrow().description().getString())
				.reduce("", (a, b) -> a + "\n" + b);
			// Only the facts are checked, so the wording and the line breaks can change freely
			helper.assertTrue(rules.contains("1 day after death") && rules.contains("2 or more players")
				&& rules.contains("Wandering Healer") && !rules.contains("emerald") && rules.contains("inventory and XP") && rules.contains("keepInventory is off"),
				"Rules in the graveyard: " + rules);

			// /timedhardcore list contains a clickable revive button
			Component entry = Messages.listEntry(manager.getActive(id).orElseThrow(), true);
			boolean hasButton = entry.toFlatList().stream().anyMatch(part ->
				part.getStyle().getClickEvent() instanceof ClickEvent.RunCommand(String command) && command.equals("/revive Victim"));
			helper.assertTrue(hasButton, "List entry should have a button running /revive Victim");

			// Revive by command (case-insensitive)
			run(server, "revive victim");
			helper.assertTrue(manager.getActive(id).isEmpty(), "Player should be revived");
			helper.assertFalse(isRejectedAsDead(playerList, nameAndId), "Revived player should be allowed to join");
			// Server list: revived, but not joined since
			String revivedMotd = ServerListMotd.personalize(ServerListMotd.publish(status).description(), List.of(id)).getString();
			helper.assertTrue(revivedMotd.startsWith("§aMy Server\n") && revivedMotd.contains("revived"), "MOTD after the revival: " + revivedMotd);
			helper.assertTrue(Graveyard.build(server, manager.stillDead().stream().filter(deadPlayer -> deadPlayer.uuid.equals(id)).toList()).size() == 5,
				"Without dead players the graveyard has a root and 4 rules");
			manager.welcomeBack(id);
			// Other tests may have dead players at the same time, so only this player's entry is checked
			String joinedMotd = ServerListMotd.personalize(ServerListMotd.publish(status).description(), List.of(id)).getString();
			helper.assertTrue(joinedMotd.equals(status.description().getString()),
				"Nothing is attached to the MOTD for the player once they have joined again: " + joinedMotd);

			checkReload(helper, manager);
			checkTimeCommands(helper);
			checkRecalculation(helper, manager);
			checkExtraLifeAndSafeRule(helper);
			checkPublicCommands(helper);
			checkKeepInventoryRule(helper, manager);
			helper.succeed();
		});
	}

	/** Server list: the server attaches the dead players invisibly, the client mod finds its own player among them. */
	private static void checkServerList(GameTestHelper helper, ServerStatus status, UUID dead) {
		ServerStatus published = ServerListMotd.publish(status);
		helper.assertTrue(published.description().getString().equals("§aMy Server\nSecond line"),
			"Clients without the mod should see the unchanged MOTD: " + published.description().getString());

		// The way it travels to the client: as JSON
		JsonElement json = ServerStatus.CODEC.encodeStart(JsonOps.INSTANCE, published).getOrThrow();
		Component received = ServerStatus.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().description();

		String motd = ServerListMotd.personalize(received, List.of(dead)).getString();
		// Only the first line and the facts are checked, so the wording can change freely
		helper.assertTrue(motd.startsWith("§aMy Server\n") && motd.contains("dead") && (motd.contains("23 h 59 min") || motd.contains("1 day")),
			"Unexpected MOTD for the dead player: " + motd);
		// The client tries the UUID of the account and the one an offline-mode server derives from the name
		helper.assertTrue(ServerListMotd.personalize(received, List.of(UUID.randomUUID(), dead)) != received, "One matching UUID should be enough");
		helper.assertTrue(ServerListMotd.personalize(received, List.of(UUID.randomUUID())) == received, "Other players should see the normal MOTD");

		TimedHardcore.config().showRemainingTimeInMotd = false;
		helper.assertTrue(ServerListMotd.publish(status) == status, "Switched off in the config: nothing is attached");
		TimedHardcore.config().showRemainingTimeInMotd = true;
	}

	/** Editing the file by hand + /timedhardcore reload, including a broken file. */
	private static void checkReload(GameTestHelper helper, DeadPlayerManager manager) {
		MinecraftServer server = helper.getLevel().getServer();
		Path file = server.getWorldPath(LevelResource.ROOT).resolve("timed-hardcore.json");
		UUID handEdited = UUID.randomUUID();
		long until = System.currentTimeMillis() + 2 * 60 * 60 * 1000;
		write(file, """
			{
			  "%s": { "name": "ByHand", "diedAt": 0, "reviveAt": %d }
			}
			""".formatted(handEdited, until));
		helper.assertTrue(manager.getActive(handEdited).isEmpty(), "The change should not be active before the reload");
		run(server, "timedhardcore reload");
		helper.assertTrue(manager.getActive(handEdited).isPresent(), "ByHand should be dead after the reload");

		// Broken file: the reload fails and the current entries stay
		write(file, "{ broken");
		boolean rejected = false;
		try {
			manager.load();
		} catch (IOException e) {
			rejected = true;
		}
		helper.assertTrue(rejected, "A broken file should be reported as an error");
		helper.assertTrue(manager.getActive(handEdited).isPresent(), "Entries should survive a failed reload");

		manager.revive("ByHand");
	}

	private static void write(Path file, String content) {
		try {
			Files.writeString(file, content);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/** /timedhardcore times | mode | duration change the config correctly. */
	private static void checkTimeCommands(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		TimedHardcoreConfig config = TimedHardcore.config();

		run(server, "timedhardcore times 20, 0:00 20:00");
		helper.assertTrue(config.reviveMode == TimedHardcoreConfig.ReviveMode.FIXED_TIMES, "times should switch to the fixed times mode");
		helper.assertTrue(config.reviveTimes.equals(List.of("00:00", "20:00")), "Times sorted and without duplicates: " + config.reviveTimes);
		String fixedTimes = config.describe().getString();
		// The server names its time zone after the last time, e.g. "daily at 00:00, 20:00 UTC"
		helper.assertTrue(fixedTimes.startsWith("daily at 00:00, 20:00 ") && fixedTimes.length() > "daily at 00:00, 20:00 ".length(),
			"Description with the server's time zone: " + fixedTimes);

		run(server, "timedhardcore times 25:00");
		helper.assertTrue(config.reviveTimes.equals(List.of("00:00", "20:00")), "An invalid time must not change anything");

		run(server, "timedhardcore mode duration");
		helper.assertTrue(config.reviveMode == TimedHardcoreConfig.ReviveMode.DURATION, "mode duration should switch back");

		run(server, "timedhardcore duration 1440");
		helper.assertTrue(config.reviveMode == TimedHardcoreConfig.ReviveMode.DURATION && config.deathDuration == ONE_DAY_IN_MINUTES, "duration should set the minutes");
		helper.assertTrue(config.describe().getString().equals("1 day after death"), "Description: " + config.describe().getString());
	}

	/** Changing the settings recalculates all revivals from the time of death (shorter: revived at once, longer: extended). */
	private static void checkRecalculation(GameTestHelper helper, DeadPlayerManager manager) {
		MinecraftServer server = helper.getLevel().getServer();
		long now = System.currentTimeMillis();
		long hour = 3600_000L;
		long tenMinutes = 600_000L;
		UUID old = UUID.randomUUID();
		UUID fresh = UUID.randomUUID();
		// Died 2 hours and 10 minutes ago, both dead for 24 hours
		manager.markDead(old, "LongDead", now - 2 * hour, now + 22 * hour);
		manager.markDead(fresh, "FreshDead", now - tenMinutes, now - tenMinutes + 24 * hour);

		run(server, "timedhardcore duration 60");
		helper.assertTrue(manager.getActive(old).isEmpty(), "After shortening to 1 hour LongDead should be alive at once");
		long freshUntil = manager.getActive(fresh).orElseThrow().reviveAt;
		helper.assertTrue(Math.abs(freshUntil - (now - tenMinutes + hour)) < 1000, "FreshDead should be dead for 1 hour from death");

		run(server, "timedhardcore duration 1440");
		freshUntil = manager.getActive(fresh).orElseThrow().reviveAt;
		helper.assertTrue(Math.abs(freshUntil - (now - tenMinutes + 24 * hour)) < 1000, "Extending should recalculate from death");

		manager.revive("FreshDead");
	}

	/** Green apple -> extra life -> death without consequences; the same with 2 players online. */
	private static void checkExtraLifeAndSafeRule(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		PlayerList playerList = server.getPlayerList();
		DeadPlayerManager manager = Objects.requireNonNull(TimedHardcore.manager());
		ExtraLifeManager lives = Objects.requireNonNull(TimedHardcore.lives());
		helper.assertTrue(playerList.getPlayerCount() == 0, "Nobody should be online before: " + playerList.getPlayerCount());

		ServerPlayer lucky = joinSurvivalPlayer(helper, "Lucky");
		lives.consume(lucky.getUUID());
		helper.assertTrue(Risk.of(lucky) == Risk.TIMED_DEATH, "Alone without an extra life: at risk");
		String statusLine = Messages.statusLine(List.of(Messages.PlayerRisk.of(lucky)), false).getString();
		helper.assertTrue(statusLine.contains("Lucky") && statusLine.contains("at risk"), "Status line: " + statusLine);
		// Player list (TAB): name with icon (through the mixin), footer with the player's own risk
		Component tabName = lucky.getTabListDisplayName();
		helper.assertTrue(tabName != null && tabName.getString().endsWith(" Lucky"), "Tab name with the risk icon in front of the name: " + tabName);
		String footer = Messages.tabListFooter(Risk.TIMED_DEATH, 1, 2, false).getString();
		helper.assertTrue(footer.contains("You are at risk") && footer.contains("currently 1 online"), "Tab footer: " + footer);
		helper.assertTrue(footer.startsWith("\n"), "The footer should start with an empty line below the player names");
		TimedHardcore.refreshStatus(server);
		helper.assertTrue(RiskNotifier.lastRisk(lucky.getUUID()) == null, "No notice right after joining (loading screen)");
		lucky.tickCount = 100; // about 5 seconds online
		TimedHardcore.refreshStatus(server);
		helper.assertTrue(RiskNotifier.lastRisk(lucky.getUUID()) == Risk.TIMED_DEATH, "The risk should be announced after joining");
		String joinInfo = Messages.riskOnJoin(Risk.TIMED_DEATH, 1, false).getString();
		helper.assertTrue(joinInfo.contains("You are at risk"), "Join notice: " + joinInfo);

		ItemStack apple = GreenApple.create();
		helper.assertTrue(GreenApple.is(apple), "The green apple should be recognised");
		helper.assertFalse(GreenApple.is(new ItemStack(Items.APPLE)), "A normal apple is not a green apple");
		lucky.setItemInHand(InteractionHand.MAIN_HAND, apple);
		// Edible with a full hunger bar (unlike a normal apple)
		Consumable consumable = Objects.requireNonNull(apple.get(DataComponents.CONSUMABLE), "The green apple should be consumable");
		helper.assertTrue(consumable.startConsuming(lucky, apple, InteractionHand.MAIN_HAND) != InteractionResult.FAIL,
			"The green apple should be edible when not hungry");
		apple.finishUsingItem(helper.getLevel(), lucky);
		helper.assertTrue(lives.has(lucky.getUUID()), "The green apple should grant an extra life");
		helper.assertFalse(lucky.hasEffect(MobEffects.ABSORPTION) || lucky.hasEffect(MobEffects.REGENERATION),
			"The green apple must not give absorption or regeneration");
		helper.assertTrue(Risk.of(lucky) == Risk.EXTRA_LIFE, "With an extra life: protected");
		// The change from at risk to protected was noticed (action bar + sound)
		helper.assertTrue(RiskNotifier.lastRisk(lucky.getUUID()) == Risk.EXTRA_LIFE, "The risk change should be noticed");
		String warning = Messages.riskChanged(Risk.TIMED_DEATH, 1, false).getString();
		helper.assertTrue(warning.contains("at risk") && warning.contains("stay dead"), "Warning: " + warning);

		ItemStack second = GreenApple.create();
		InteractionResult result = consumable.startConsuming(lucky, second, InteractionHand.MAIN_HAND);
		helper.assertTrue(result == InteractionResult.FAIL, "A second extra life must not be possible, was " + result);

		lucky.getInventory().add(new ItemStack(Items.DIAMOND, 3));
		lucky.kill(helper.getLevel());
		helper.assertTrue(manager.getActive(lucky.getUUID()).isEmpty(), "No timed death with an extra life");
		helper.assertFalse(lives.has(lucky.getUUID()), "The extra life should be used up");
		helper.assertTrue(lucky.getInventory().countItem(Items.DIAMOND) == 0, "Without a timed death keepInventory=false applies: items drop");
		helper.assertFalse(lucky.entityTags().contains(DeathHandler.KEEP_INVENTORY_TAG), "No tag without a timed death");
		playerList.remove(lucky);

		ServerPlayer a = joinSurvivalPlayer(helper, "SafeA");
		ServerPlayer b = joinSurvivalPlayer(helper, "SafeB");
		helper.assertTrue(Risk.of(a) == Risk.SAFE, "Two players online: safe");
		a.setGameMode(GameType.CREATIVE);
		helper.assertTrue(Risk.of(a) == Risk.EXEMPT, "Creative mode: exempt");
		a.setGameMode(GameType.SURVIVAL);
		// Status line by command (must not crash, shows both players)
		run(server, "timedhardcore list");
		a.getInventory().add(new ItemStack(Items.DIAMOND, 3));
		a.kill(helper.getLevel());
		helper.assertTrue(manager.getActive(a.getUUID()).isEmpty(), "No timed death with two players online");
		helper.assertTrue(a.getInventory().countItem(Items.DIAMOND) == 0, "With two players keepInventory=false applies: items drop");

		run(server, "timedhardcore life give SafeB");
		helper.assertTrue(lives.has(b.getUUID()), "life give should grant an extra life");
		run(server, "timedhardcore life take safeb");
		helper.assertFalse(lives.has(b.getUUID()), "life take should remove the extra life");

		run(server, "timedhardcore safe 0");
		helper.assertTrue(Risk.of(b) == Risk.TIMED_DEATH, "Safe rule off: at risk even with two players online");
		run(server, "timedhardcore safe 2");

		playerList.remove(a);
		playerList.remove(b);
	}

	/** info and dead are open to everyone, everything else needs operator rights. */
	private static void checkPublicCommands(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerPlayer viewer = joinSurvivalPlayer(helper, "Viewer");
		CommandSourceStack source = viewer.createCommandSourceStack();
		helper.assertTrue(mayUse(server, source, "timedhardcore info"), "Everyone should be allowed to use info");
		helper.assertTrue(mayUse(server, source, "timedhardcore dead"), "Everyone should be allowed to use dead");
		helper.assertFalse(mayUse(server, source, "timedhardcore list"), "list should need operator rights");
		helper.assertFalse(mayUse(server, source, "timedhardcore duration 1"), "duration should need operator rights");
		helper.assertFalse(mayUse(server, source, "revive Viewer"), "revive should need operator rights");
		// Must not crash, neither for a player nor for the console
		server.getCommands().performPrefixedCommand(source, "timedhardcore info");
		server.getCommands().performPrefixedCommand(source, "timedhardcore dead");
		run(server, "timedhardcore info");

		String info = Messages.info(TimedHardcore.config(), 1, true, 0, false).getString();
		helper.assertTrue(info.contains("Revival: 1 day after death") && info.contains("Safe rule: no consequences with 2 or more players online")
			&& info.contains("Inventory: kept while you are dead") && info.contains("Dead right now: 0"), "Info: " + info);
		server.getPlayerList().remove(viewer);
	}

	private static boolean mayUse(MinecraftServer server, CommandSourceStack source, String command) {
		ParseResults<CommandSourceStack> parsed = server.getCommands().getDispatcher().parse(command, source);
		return !parsed.getReader().canRead() && parsed.getExceptions().isEmpty();
	}

	/** Game rule keep_inventory_on_timed_death off: dead players lose their items like everyone else. */
	private static void checkKeepInventoryRule(GameTestHelper helper, DeadPlayerManager manager) {
		MinecraftServer server = helper.getLevel().getServer();
		helper.assertTrue(helper.getLevel().getGameRules().get(TimedHardcoreGameRules.KEEP_INVENTORY_ON_TIMED_DEATH), "The rule should be on by default");
		run(server, "gamerule timed-hardcore:keep_inventory_on_timed_death false");
		helper.assertFalse(helper.getLevel().getGameRules().get(TimedHardcoreGameRules.KEEP_INVENTORY_ON_TIMED_DEATH), "The command should switch the rule off");

		ServerPlayer player = joinSurvivalPlayer(helper, "Dropper");
		player.getInventory().add(new ItemStack(Items.DIAMOND, 3));
		player.kill(helper.getLevel());
		helper.assertTrue(manager.getActive(player.getUUID()).isPresent(), "Alone without an extra life: stays dead");
		helper.assertTrue(player.getInventory().countItem(Items.DIAMOND) == 0, "Rule off: a dead player drops their items");
		helper.assertFalse(player.entityTags().contains(DeathHandler.KEEP_INVENTORY_TAG), "Rule off: no tag");

		run(server, "gamerule timed-hardcore:keep_inventory_on_timed_death true");
		manager.revive("Dropper");
		server.getPlayerList().remove(player);
	}

	@GameTest
	public void healerSellsGreenApple(GameTestHelper helper) {
		WanderingTrader healer = Objects.requireNonNull(Healer.spawn(helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 1))), "The healer should spawn");
		helper.assertTrue(Healer.isHealer(healer), "The spawned trader should be a healer");
		Component name = Objects.requireNonNull(healer.getCustomName(), "The healer should have a name");
		helper.assertTrue(name.getString().equals("Wandering Healer"), "Name of the healer: " + name.getString());
		helper.assertTrue(name.getStyle().getColor() == null, "The name should have no colour");
		helper.assertFalse(healer.isCustomNameVisible(), "No permanent name tag");
		var offers = healer.getOffers();
		helper.assertTrue(offers.size() == 7, "The healer should have 7 offers, has " + offers.size());
		var last = offers.getLast();
		helper.assertTrue(GreenApple.is(last.getResult()) && last.getMaxUses() == 1, "The green apple should be last, exactly once");
		helper.assertTrue(last.getBaseCostA().is(Items.EMERALD) && last.getBaseCostA().getCount() == Healer.GREEN_APPLE_PRICE_EMERALDS, "The apple costs the fixed emerald price");
		helper.assertTrue(last.getCostB().is(Items.ENCHANTED_GOLDEN_APPLE), "The apple also costs an enchanted golden apple");
		helper.assertTrue(offers.stream().filter(offer -> GreenApple.is(offer.getResult())).count() == 1, "Only one apple offer");
		for (int i = 0; i < Healer.RANDOM_OFFERS; i++) {
			if (i > 0) {
				helper.assertTrue(offers.get(i - 1).getBaseCostA().getCount() <= offers.get(i).getBaseCostA().getCount(), "Offers sorted by price");
			}
			for (int j = i + 1; j < Healer.RANDOM_OFFERS; j++) {
				helper.assertFalse(ItemStack.isSameItemSameComponents(offers.get(i).getResult(), offers.get(j).getResult()),
					"No duplicate offers: " + offers.get(i).getResult());
			}
		}
		healer.discard();
		helper.succeed();
	}

	/** A 4x4 picture, transparent except for one pixel in the given colour at the top left. */
	private static byte[] png(int argb) throws IOException {
		BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
		image.setRGB(0, 0, argb);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	/** The first PNG inside a multipart request (the reader stops at the end of the picture). */
	private static BufferedImage firstAttachedPicture(byte[] multipart) throws IOException {
		int start = new String(multipart, StandardCharsets.ISO_8859_1).indexOf("\u0089PNG");
		if (start < 0) {
			throw new IOException("No PNG attached");
		}
		return ImageIO.read(new ByteArrayInputStream(multipart, start, multipart.length - start));
	}

	/**
	 * Sends a death and a revival to a fake Discord on this machine, which also serves the player pictures.
	 * Both must arrive as multipart with the real skin attached: grey for the death, in colour for the revival.
	 */
	@GameTest
	public void discordWebhook(GameTestHelper helper) throws Exception {
		MinecraftServer server = helper.getLevel().getServer();
		TimedHardcoreConfig config = TimedHardcore.config();
		BlockingQueue<Map.Entry<String, byte[]>> posts = new LinkedBlockingQueue<>();
		// Deaths and revivals go to different webhooks (channels)
		BlockingQueue<String> postPaths = new LinkedBlockingQueue<>();
		Set<String> requestedPictures = ConcurrentHashMap.newKeySet();
		AtomicInteger webhookCalls = new AtomicInteger();

		// The real skin is red, the stand-in of the render service is blue
		byte[] redPng = png(0xFFFF0000);
		byte[] bluePng = png(0xFF0000FF);

		HttpServer discord = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		discord.createContext("/render/", exchange -> {
			requestedPictures.add(exchange.getRequestURI().getPath());
			// Like a render service whose cache holds a stand-in: only a request that skips the cache gets the real skin
			boolean cached = exchange.getRequestURI().getQuery() == null;
			if (cached) {
				exchange.getResponseHeaders().add("x-nmsr-fallback", "True");
			}
			byte[] picture = cached ? bluePng : redPng;
			exchange.sendResponseHeaders(200, picture.length);
			exchange.getResponseBody().write(picture);
			exchange.close();
		});
		discord.createContext("/webhook", exchange -> {
			byte[] body = exchange.getRequestBody().readAllBytes();
			// The first request fails like a Discord hiccup: the message must be sent again, not lost
			if (webhookCalls.getAndIncrement() == 0) {
				exchange.sendResponseHeaders(503, -1);
			} else {
				postPaths.add(exchange.getRequestURI().getPath());
				posts.add(Map.entry(exchange.getRequestHeaders().getFirst("Content-Type"), body));
				exchange.sendResponseHeaders(204, -1);
			}
			exchange.close();
		});
		discord.start();
		String base = "http://127.0.0.1:" + discord.getAddress().getPort();
		String previousImage = config.discord.playerImage;
		String previousHead = config.discord.playerHead;
		try {
			config.discord.deathWebhookUrl = base + "/webhook/deaths";
			config.discord.revivalWebhookUrl = base + "/webhook/revivals";
			config.discord.playerImage = base + "/render/{name}";
			config.discord.playerHead = base + "/render/head/{name}";

			GameProfile profile = new GameProfile(UUID.randomUUID(), "jeb_");
			long diedAt = System.currentTimeMillis() - 1000;
			long reviveAt = diedAt + 3_600_000;
			DiscordWebhook.announceDeath(server, profile, Component.literal("jeb_ was slain by @everyone"), diedAt, reviveAt, null);
			DeadPlayerManager.DeadPlayer deadPlayer = new DeadPlayerManager.DeadPlayer();
			deadPlayer.uuid = profile.id();
			deadPlayer.name = profile.name();
			deadPlayer.diedAt = diedAt;
			deadPlayer.reviveAt = reviveAt;
			DiscordWebhook.announceRevival(server, deadPlayer, "Admin");
			// A death clip that arrives a moment later, like one the client is still uploading
			byte[] clipGif = GifEncoder.encode(List.of(
				new GifEncoder.Frame(2, 2, new int[]{0xFF0000, 0xFF0000, 0xFF0000, 0xFF0000}, 0),
				new GifEncoder.Frame(2, 2, new int[]{0x0000FF, 0x0000FF, 0x0000FF, 0x0000FF}, 100)));
			CompletableFuture<byte @Nullable []> clip = new CompletableFuture<>();
			DiscordWebhook.announceDeath(server, profile, Component.literal("jeb_ fell from a high place"), diedAt, reviveAt, clip);
			CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS).execute(() -> clip.complete(clipGif));

			// The worker thread does not need the server thread, so waiting here cannot block it
			Map.Entry<String, byte[]> death = Objects.requireNonNull(posts.poll(15, TimeUnit.SECONDS), "The death should be posted (after one retry)");
			Map.Entry<String, byte[]> revival = Objects.requireNonNull(posts.poll(10, TimeUnit.SECONDS), "The revival should be posted");
			Map.Entry<String, byte[]> clipDeath = Objects.requireNonNull(posts.poll(10, TimeUnit.SECONDS), "The death with clip should be posted");
			helper.assertTrue(List.copyOf(postPaths).equals(List.of("/webhook/deaths", "/webhook/revivals", "/webhook/deaths")),
				"Deaths and revivals go to their own webhooks: " + postPaths);

			String clipBody = new String(clipDeath.getValue(), StandardCharsets.ISO_8859_1);
			helper.assertTrue(clipBody.contains("\"image\":{\"url\":\"attachment://death.gif\"}") && clipBody.contains("Content-Type: image/gif"),
				"The clip replaces the full-body picture: " + clipBody.substring(0, Math.min(600, clipBody.length())));
			helper.assertTrue(clipBody.contains("\"icon_url\":\"attachment://head.png\"") && !clipBody.contains("player.png"), "Head stays, no full-body picture");
			helper.assertTrue(clipBody.contains(new String(clipGif, StandardCharsets.ISO_8859_1)), "The clip is attached unchanged");

			helper.assertTrue(requestedPictures.equals(Set.of("/render/jeb_", "/render/head/jeb_")), "Pictures requested by name: " + requestedPictures);
			helper.assertTrue(death.getKey().startsWith("multipart/form-data"), "The death carries the grey pictures: " + death.getKey());
			String deathBody = new String(death.getValue(), StandardCharsets.ISO_8859_1);
			helper.assertTrue(deathBody.contains("\"author\":{\"name\":\"jeb_ died\",\"icon_url\":\"attachment://head.png\"}"),
				"Name (plain text) and grey head at the top: " + deathBody);
			helper.assertTrue(deathBody.contains("> **jeb\\\\_ was slain"), "Death message as a bold quote, name escaped: " + deathBody);
			helper.assertFalse(deathBody.contains("\"title\""), "No title");
			helper.assertTrue(deathBody.contains("\"image\":{\"url\":\"attachment://player.png\"}") && deathBody.contains("filename=\"head.png\""),
				"Both pictures are attached");
			helper.assertTrue(deathBody.contains("\"parse\":[]"), "Nobody gets pinged");
			helper.assertTrue(deathBody.contains("<t:" + reviveAt / 1000 + ":R>"), "The revival is a Discord timestamp");
			helper.assertTrue(deathBody.contains("\"color\":" + DiscordWebhook.DEATH_COLOR), "Grey stripe on death");

			BufferedImage grey = firstAttachedPicture(death.getValue());
			// 0x4c is the brightness of red; the blue stand-in would be 0x1d
			helper.assertTrue(grey.getRGB(0, 0) == 0xFF4C4C4C, "The real (red) skin, turned grey: " + Integer.toHexString(grey.getRGB(0, 0)));
			helper.assertTrue(grey.getRGB(1, 1) >>> 24 == 0, "Transparency stays");

			helper.assertTrue(revival.getKey().startsWith("multipart/form-data"), "The revival carries its pictures too: " + revival.getKey());
			String revivalBody = new String(revival.getValue(), StandardCharsets.ISO_8859_1);
			helper.assertTrue(revivalBody.contains("Admin") && revivalBody.contains("\"color\":" + DiscordWebhook.REVIVAL_COLOR), "Revival: " + revivalBody);
			helper.assertTrue(revivalBody.contains("\"image\":{\"url\":\"attachment://player.png\"}") && revivalBody.contains("\"icon_url\":\"attachment://head.png\""),
				"The revival shows the uploaded picture and head: " + revivalBody);
			int colour = firstAttachedPicture(revival.getValue()).getRGB(0, 0);
			helper.assertTrue(colour == 0xFFFF0000, "The real (red) skin in colour: " + Integer.toHexString(colour));
		} finally {
			config.discord.deathWebhookUrl = "";
			config.discord.revivalWebhookUrl = "";
			config.discord.playerImage = previousImage;
			config.discord.playerHead = previousHead;
			discord.stop(0);
		}
		helper.succeed();
	}

	/** Death clips: frames become a looping GIF with the right delays, and an oversized clip drops frames from the start. */
	@GameTest
	public void gifEncoder(GameTestHelper helper) throws Exception {
		List<GifEncoder.Frame> frames = new ArrayList<>();
		java.util.Random random = new java.util.Random(49);
		for (int i = 0; i < 20; i++) {
			int[] noise = new int[64 * 36];
			for (int p = 0; p < noise.length; p++) {
				noise[p] = random.nextInt(0x1000000);
			}
			frames.add(new GifEncoder.Frame(64, 36, noise, i * 100L));
		}
		// A frame from before the window was resized is dropped
		frames.addFirst(new GifEncoder.Frame(8, 8, new int[64], -100));

		byte[] gif = Objects.requireNonNull(GifEncoder.encode(frames, Integer.MAX_VALUE), "The clip should be encoded");
		helper.assertTrue(GifEncoder.isGif(gif), "Starts like a GIF");
		// What the server checks before it posts a clip from a client
		helper.assertTrue(GifEncoder.countFrames(gif, 64, 36) == 20, "20 frames counted: " + GifEncoder.countFrames(gif, 64, 36));
		helper.assertTrue(GifEncoder.countFrames(gif, 63, 36) == -1 && GifEncoder.countFrames(gif, 64, 35) == -1, "Too large is rejected");
		helper.assertTrue(GifEncoder.countFrames(java.util.Arrays.copyOf(gif, gif.length / 2), 64, 36) == -1, "A cut-off GIF is rejected");
		helper.assertTrue(GifEncoder.countFrames("GIF89a and then nonsense".getBytes(StandardCharsets.ISO_8859_1), 64, 36) == -1, "Only a GIF header is rejected");
		var reader = ImageIO.getImageReadersByFormatName("gif").next();
		reader.setInput(ImageIO.createImageInputStream(new ByteArrayInputStream(gif)));
		helper.assertTrue(reader.getNumImages(true) == 20, "20 frames of the same size: " + reader.getNumImages(true));
		helper.assertTrue(reader.getWidth(0) == 64 && reader.getHeight(0) == 36, "Size kept");
		String metadata = metadataText(reader.getImageMetadata(0).getAsTree("javax_imageio_gif_image_1.0"));
		helper.assertTrue(metadata.contains("delayTime=10") && metadata.contains("NETSCAPE"), "100 ms per frame, loops: " + metadata);
		helper.assertTrue(metadataText(reader.getImageMetadata(19).getAsTree("javax_imageio_gif_image_1.0")).contains("delayTime=150"),
			"The moment of death stays a little longer");

		// Too large for every frame: only every second (or third) frame is kept, always including the last one
		byte[] smaller = Objects.requireNonNull(GifEncoder.encode(frames, gif.length * 2 / 3), "A smaller clip should fit");
		reader.setInput(ImageIO.createImageInputStream(new ByteArrayInputStream(smaller)));
		int count = reader.getNumImages(true);
		helper.assertTrue(count < 20 && count >= 7, "Fewer frames: " + count);
		helper.assertTrue(GifEncoder.encode(frames, 100) == null, "Nothing fits into 100 bytes");
		helper.assertTrue(GifEncoder.encode(List.of(), Integer.MAX_VALUE) == null, "No frames, no clip");
		helper.succeed();
	}

	private static String metadataText(org.w3c.dom.Node node) {
		StringBuilder text = new StringBuilder(node.getNodeName());
		if (node.getAttributes() != null) {
			for (int i = 0; i < node.getAttributes().getLength(); i++) {
				text.append(' ').append(node.getAttributes().item(i).getNodeName()).append('=').append(node.getAttributes().item(i).getNodeValue());
			}
		}
		for (org.w3c.dom.Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
			text.append(" (").append(metadataText(child)).append(')');
		}
		return text.toString();
	}

	/**
	 * Death clips on the server: while a clip is on its way, the kick waits and "Spectate world" is ignored;
	 * once it arrives, the player is kicked. Clips that are no recording are refused.
	 *
	 * <p>Runs in an environment of its own, so not at the same time as {@link #timedDeathRespawnsAndKicks}:
	 * a second player online would make that test's death safe, and it revives every dead player when it starts.
	 */
	@GameTest(maxTicks = 40, environment = "timed-hardcore-test:alone")
	public void timedDeathWaitsForClip(GameTestHelper helper) throws IOException {
		PlayerList playerList = helper.getLevel().getServer().getPlayerList();
		DeadPlayerManager manager = Objects.requireNonNull(TimedHardcore.manager(), "Manager not initialised");
		TimedHardcoreConfig config = TimedHardcore.config();
		ServerPlayer player = joinSurvivalPlayer(helper, "ClipVictim");
		UUID id = player.getUUID();

		// Other tests may have a player online at the same time, which would make this death safe
		int safePlayerCount = config.safePlayerCount;
		config.safePlayerCount = 0;
		player.kill(helper.getLevel());
		config.safePlayerCount = safePlayerCount;
		helper.assertTrue(manager.getActive(id).isPresent(), "The player should stay dead");

		// Test players have no client mod that could be asked, so the server only waits as if it had asked
		CompletableFuture<byte @Nullable []> clip = DeathClips.await(id);
		helper.assertTrue(DeathClips.isWaitingFor(id), "The server waits for the clip");
		player.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
		helper.assertTrue(player.isDeadOrDying() && !respawnedPlayers.containsKey(id), "\"Spectate world\" is ignored while the clip uploads");

		byte[] gif = GifEncoder.encode(List.of(new GifEncoder.Frame(2, 2, new int[]{0xFF0000, 0, 0, 0xFF0000}, 0)));
		helper.runAfterDelay(3, () -> {
			helper.assertTrue(playerList.getPlayer(id) == player, "No kick while the clip uploads");
			helper.assertFalse(respawnedPlayers.containsKey(id), "No respawn while the clip uploads");

			// A very short death time ends while the clip uploads: the player is still kicked, as without a clip
			manager.getActive(id).orElseThrow().reviveAt = System.currentTimeMillis();
			helper.assertTrue(manager.getActive(id).isEmpty(), "The death time is over");

			DeathClips.receive(player, gif);
			helper.assertTrue(clip.isDone() && clip.join() == gif, "The clip arrived");
			helper.assertFalse(DeathClips.isWaitingFor(id), "Nothing to wait for any more");

			helper.runAfterDelay(2, () -> {
				helper.assertTrue(playerList.getPlayer(id) == null, "Kicked once the clip is there");
				helper.assertTrue(respawnedPlayers.containsKey(id), "Respawned by the server before the kick");

				// Only the shape of a recording is accepted
				CompletableFuture<byte @Nullable []> fake = DeathClips.await(id);
				DeathClips.receive(player, "GIF89a and then nonsense".getBytes(StandardCharsets.ISO_8859_1));
				helper.assertTrue(fake.isDone() && fake.join() == null, "A clip that is no recording is refused");

				manager.revive("ClipVictim");
				helper.succeed();
			});
		});
	}

	/** Config files of version 1.1 had the Discord settings at the top level; they move into "discord". */
	@GameTest
	public void configMigration(GameTestHelper helper) throws Exception {
		TimedHardcoreConfig old = TimedHardcoreConfig.parse("""
			{
			  "deathDuration": 30.0,
			  "discordWebhookUrl": "https://discord.example/api/webhooks/1/token",
			  "discordPlayerImage": "",
			  "discordPlayerHead": "https://render.example/head/{name}"
			}
			""");
		helper.assertTrue(old.deathDuration == 30.0, "Other settings stay as they are");
		helper.assertTrue(old.discord.deathWebhookUrl.equals("https://discord.example/api/webhooks/1/token")
			&& old.discord.revivalWebhookUrl.equals(old.discord.deathWebhookUrl), "The one webhook becomes both: " + old.discord.revivalWebhookUrl);
		helper.assertTrue(old.discord.playerImage.isEmpty(), "An empty picture (= no picture) stays empty");
		helper.assertTrue(old.discord.playerHead.equals("https://render.example/head/{name}"), "Head moved: " + old.discord.playerHead);
		helper.assertTrue(old.discord.deathMedia == TimedHardcoreConfig.DeathMedia.PICTURE && old.discord.enabled, "New settings get their defaults");
		String saved = old.toJson();
		helper.assertTrue(saved.contains("\"discord\": {") && !saved.contains("discordWebhookUrl"), "Saved in the new form: " + saved);

		TimedHardcoreConfig both = TimedHardcoreConfig.parse("""
			{"discordWebhookUrl": "https://old.example/hook", "discord": {"revivalWebhookUrl": "https://new.example/hook"}}
			""");
		helper.assertTrue(both.discord.revivalWebhookUrl.equals("https://new.example/hook") && both.discord.deathWebhookUrl.equals("https://old.example/hook"),
			"A value already in the new place wins");
		TimedHardcoreConfig testBuild = TimedHardcoreConfig.parse("""
			{"discord": {"webhookUrl": "https://test.example/hook"}}
			""");
		helper.assertTrue(testBuild.discord.deathWebhookUrl.equals("https://test.example/hook")
			&& testBuild.discord.revivalWebhookUrl.equals("https://test.example/hook"), "Test builds of 1.2 had one webhookUrl in discord");

		// Only deaths, only revivals
		TimedHardcoreConfig deathsOnly = TimedHardcoreConfig.parse("""
			{"discord": {"deathWebhookUrl": "https://deaths.example/hook"}}
			""");
		helper.assertTrue(deathsOnly.discord.postsDeaths() && !deathsOnly.discord.postsRevivals(), "Only deaths are posted");

		// Disabled: no messages and no clips, but the webhook stays
		old.discord.deathMedia = TimedHardcoreConfig.DeathMedia.CLIP;
		helper.assertTrue(old.discord.postsDeaths() && old.discord.postsRevivals() && DeathClips.wanted(old), "Webhooks set: messages and clips on");
		old.discord.enabled = false;
		helper.assertTrue(!old.discord.postsDeaths() && !old.discord.postsRevivals() && !DeathClips.wanted(old) && old.discord.configured(),
			"Disabled: nothing is sent");
		old.discord.enabled = true;
		old.discord.deathWebhookUrl = "";
		helper.assertTrue(!DeathClips.wanted(old) && old.discord.postsRevivals(), "Without a death webhook no clips are recorded");
		helper.succeed();
	}

	/**
	 * Points in time are written in the server's time zone with its name, and carry the exact moment,
	 * so the client mod can show them in the player's own time zone.
	 */
	@GameTest
	public void localTimes(GameTestHelper helper) {
		long moment = ZonedDateTime.of(2026, 9, 28, 18, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli();
		ZoneId server = ZoneId.systemDefault();
		Component date = TimeFormat.date(moment);
		helper.assertTrue(date.getString().startsWith(TimeFormat.date(moment, server) + " "), "Server time with the zone: " + date.getString());

		// Berlin is UTC+2 in September, New York UTC-4
		ZoneId berlin = ZoneId.of("Europe/Berlin");
		String local = TimeFormat.localize(date.getStyle().getInsertion(), berlin);
		helper.assertTrue("28 Sep 2026 at 8:00 PM".equals(local), "Date in the player's zone, without its name: " + local);
		Component clock = TimeFormat.clock(moment, true);
		String localClock = TimeFormat.localize(clock.getStyle().getInsertion(), ZoneId.of("America/New_York"));
		helper.assertTrue("14:00".equals(localClock), "Time of day in the player's zone: " + localClock);

		helper.assertTrue(TimeFormat.localize(null, berlin) == null && TimeFormat.localize("something else", berlin) == null,
			"Other text stays as it is");
		helper.succeed();
	}

	@GameTest
	public void revivalTimes(GameTestHelper helper) {
		TimedHardcoreConfig config = new TimedHardcoreConfig();
		config.reviveMode = TimedHardcoreConfig.ReviveMode.FIXED_TIMES;
		config.reviveTimes = new ArrayList<>(List.of("20", "0:00"));

		expectRevival(helper, config, "2026-09-28T19:30", "2026-09-28T20:00");
		expectRevival(helper, config, "2026-09-28T20:00", "2026-09-29T00:00");
		expectRevival(helper, config, "2026-09-28T23:59", "2026-09-29T00:00");
		expectRevival(helper, config, "2026-09-29T00:00", "2026-09-29T20:00");
		// Died shortly before the time: still revived at that time
		expectRevival(helper, config, "2026-09-28T19:59", "2026-09-28T20:00");

		config.reviveMode = TimedHardcoreConfig.ReviveMode.DURATION;
		config.deathDuration = 90;
		expectRevival(helper, config, "2026-09-28T19:30", "2026-09-28T21:00");
		config.deathDuration = 0;
		helper.assertTrue(config.revivalTime(0) == TimedHardcoreConfig.NO_TIMED_DEATH, "Duration 0 should mean no timed death");

		boolean rejected = false;
		try {
			TimedHardcoreConfig.parseTime("25:00");
		} catch (IllegalArgumentException e) {
			rejected = true;
		}
		helper.assertTrue(rejected, "25:00 should be invalid");
		helper.succeed();
	}

	/** Times are given in the server's time zone, like the revive times in the config. */
	private static void expectRevival(GameTestHelper helper, TimedHardcoreConfig config, String death, String expected) {
		ZoneId zone = ZoneId.systemDefault();
		long deathMillis = LocalDateTime.parse(death).atZone(zone).toInstant().toEpochMilli();
		long expectedMillis = LocalDateTime.parse(expected).atZone(zone).toInstant().toEpochMilli();
		long actual = config.revivalTime(deathMillis);
		helper.assertTrue(actual == expectedMillis, "Death " + death + ": expected " + expected + ", was "
			+ Instant.ofEpochMilli(actual).atZone(zone).toLocalDateTime());
	}
}
