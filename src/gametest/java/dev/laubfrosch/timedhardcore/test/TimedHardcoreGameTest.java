package dev.laubfrosch.timedhardcore.test;

import com.google.gson.JsonElement;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.ParseResults;
import com.mojang.serialization.JsonOps;
import dev.laubfrosch.timedhardcore.DeadPlayerManager;
import dev.laubfrosch.timedhardcore.DeathHandler;
import dev.laubfrosch.timedhardcore.ExtraLifeManager;
import dev.laubfrosch.timedhardcore.GreenApple;
import dev.laubfrosch.timedhardcore.Healer;
import dev.laubfrosch.timedhardcore.Messages;
import dev.laubfrosch.timedhardcore.Risk;
import dev.laubfrosch.timedhardcore.RiskNotifier;
import dev.laubfrosch.timedhardcore.ServerListMotd;
import dev.laubfrosch.timedhardcore.TimedHardcore;
import dev.laubfrosch.timedhardcore.TimedHardcoreConfig;
import dev.laubfrosch.timedhardcore.TimedHardcoreGameRules;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
			manager.welcomeBack(id);
			helper.assertTrue(ServerListMotd.publish(status) == status, "Nothing is attached to the MOTD once the player has joined again");

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
		helper.assertTrue(config.describe().equals("daily at 00:00, 20:00"), "Description: " + config.describe());

		run(server, "timedhardcore times 25:00");
		helper.assertTrue(config.reviveTimes.equals(List.of("00:00", "20:00")), "An invalid time must not change anything");

		run(server, "timedhardcore mode duration");
		helper.assertTrue(config.reviveMode == TimedHardcoreConfig.ReviveMode.DURATION, "mode duration should switch back");

		run(server, "timedhardcore duration 1440");
		helper.assertTrue(config.reviveMode == TimedHardcoreConfig.ReviveMode.DURATION && config.deathDuration == ONE_DAY_IN_MINUTES, "duration should set the minutes");
		helper.assertTrue(config.describe().equals("1 day after death"), "Description: " + config.describe());
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
