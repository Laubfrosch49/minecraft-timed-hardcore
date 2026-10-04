package dev.laubfrosch.timedhardcore;

import dev.laubfrosch.timedhardcore.network.TimedHardcoreNetwork;
import java.nio.file.Path;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point: loads the settings, holds the state of the running server and connects the Fabric
 * events to the classes that do the actual work.
 */
public class TimedHardcore implements ModInitializer {

	public static final String MOD_ID = "timed-hardcore";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static TimedHardcoreConfig config;
	/** null while no server is running, and in singleplayer, where the mod is inactive. */
	private static @Nullable DeadPlayerManager manager;
	private static @Nullable ExtraLifeManager lives;

	@Override
	public void onInitialize() {
		try {
			config = TimedHardcoreConfig.load();
		} catch (Exception e) {
			LOGGER.error("Invalid config/timed-hardcore.json, using default values (file remains unchanged)", e);
			config = new TimedHardcoreConfig();
		}

		TimedHardcoreGameRules.register();
		TimedHardcoreNetwork.register();
		CommandRegistrationCallback.EVENT.register((dispatcher, _, _) ->
			TimedHardcoreCommands.register(dispatcher));

		ServerLifecycleEvents.SERVER_STARTING.register(TimedHardcore::onServerStarting);
		ServerLifecycleEvents.SERVER_STOPPED.register(_ -> onServerStopped());
		ServerTickEvents.END_SERVER_TICK.register(TimedHardcore::onTickEnd);

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
			if (entity instanceof ServerPlayer player) {
				DeathHandler.onDeath(player, damageSource.getLocalizedDeathMessage(player));
			}
		});
		// The tag was copied to the new player object on respawn and is no longer needed
		ServerPlayerEvents.AFTER_RESPAWN.register((_, newPlayer, _) -> newPlayer.removeTag(DeathHandler.KEEP_INVENTORY_TAG));

		ServerPlayConnectionEvents.JOIN.register((handler, _, _) -> {
			if (manager == null) {
				return;
			}
			manager.welcomeBack(handler.player.getUUID());
			if (handler.player.isDeadOrDying()) {
				DeathHandler.respawnAtTickEnd(handler.player);
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, _) -> forgetPlayer(handler.player.getUUID()));
	}

	private static void onServerStarting(MinecraftServer server) {
		// Singleplayer (including LAN) stays vanilla; the mod is meant for servers
		if (server.isSingleplayer()) {
			return;
		}
		Path world = server.getWorldPath(LevelResource.ROOT);
		manager = new DeadPlayerManager(world.resolve(MOD_ID + ".json"));
		lives = new ExtraLifeManager(world.resolve(MOD_ID + "-lives.json"));

		if (!server.isHardcore()) {
			// The mod does not depend on it; without the client mod, the hearts simply follow the world's setting
			LOGGER.warn("This world is not in hardcore mode. Timed Hardcore works anyway, but players without the client mod see normal hearts instead of hardcore hearts.");
			LOGGER.warn("For the intended look, create the world with hardcore=true in server.properties (the setting only applies to new worlds).");
		}
	}

	private static void onServerStopped() {
		manager = null;
		lives = null;
		ClientSync.reset();
		Graveyard.reset();
		TabListStatus.reset();
		RiskNotifier.reset();
	}

	private static void onTickEnd(MinecraftServer server) {
		DeathHandler.onTickEnd(server);
		if (manager != null && server.getTickCount() % SharedConstants.TICKS_PER_SECOND == 0) {
			announceRevivals(server);
			Graveyard.tick(server, manager);
			refreshStatus(server);
		}
	}

	private static void forgetPlayer(UUID player) {
		ClientSync.forget(player);
		TabListStatus.forget(player);
		RiskNotifier.forget(player);
	}

	/** Shows all players their current risk: hearts (client mod), player list and a notice on change. */
	public static void refreshStatus(MinecraftServer server) {
		ClientSync.tick(server);
		TabListStatus.tick(server);
		RiskNotifier.tick(server);
	}

	public static TimedHardcoreConfig config() {
		return config;
	}

	/**
	 * Reads the settings again.
	 *
	 * @throws Exception if the file is invalid; the current settings stay active in that case
	 */
	public static void reloadConfig() throws Exception {
		config = TimedHardcoreConfig.load();
	}

	public static @Nullable DeadPlayerManager manager() {
		return manager;
	}

	public static @Nullable ExtraLifeManager lives() {
		return lives;
	}

	/** Picks up the players whose time is up and tells everyone in chat who is alive again. */
	public static void announceRevivals(MinecraftServer server) {
		if (manager == null) {
			return;
		}
		for (DeadPlayerManager.DeadPlayer deadPlayer : manager.collectExpired()) {
			LOGGER.info("{} is alive again (time is up)", deadPlayer.name);
			server.getPlayerList().broadcastSystemMessage(Messages.revivedByTime(deadPlayer), false);
			// A player who is online at that moment needs no hint in the server list
			if (server.getPlayerList().getPlayer(deadPlayer.uuid) != null) {
				manager.welcomeBack(deadPlayer.uuid);
			}
		}
	}

	/**
	 * After the revival settings changed: recalculates all revivals from the time of death and revives
	 * players that are already due (with a chat message).
	 *
	 * @return number of players whose revival changed
	 */
	public static int recalculateRevivals(MinecraftServer server) {
		if (manager == null) {
			return 0;
		}
		int changed = manager.recalculate(config);
		announceRevivals(server);
		return changed;
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
