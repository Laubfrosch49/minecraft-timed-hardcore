package dev.laubfrosch.timedhardcore;

import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jspecify.annotations.Nullable;

/**
 * What happens when a player dies: depending on the {@link Risk} nothing, the extra life is used up,
 * or the player stays dead for a while (a "timed death"). Players who stay dead are respawned by the
 * server and kicked, so they wait outside until they are revived. Whether they keep
 * their inventory is decided by the game rule {@link TimedHardcoreGameRules#KEEP_INVENTORY_ON_TIMED_DEATH}.
 */
public final class DeathHandler {

	/** Tag on a dead player who keeps their inventory; it carries that decision over to the respawn. */
	public static final String KEEP_INVENTORY_TAG = "timed_hardcore_keep_inventory";

	/** Players to respawn (and kick, if they stay dead) at the end of the tick. */
	private static final Set<UUID> pendingRespawn = ConcurrentHashMap.newKeySet();

	/**
	 * Players who died a timed death and are kicked at the end of the tick, or once their death clip has arrived.
	 * Kept with the death message for the kick screen. A player in here is kicked even if their time ran out
	 * meanwhile: with a very short death time the upload of the clip can take longer than being dead, and without
	 * a clip they would have been kicked right away. Only a revival by command takes them out ({@link #cancelKick}).
	 */
	private static final Map<UUID, PendingKick> pendingKicks = new ConcurrentHashMap<>();

	private record PendingKick(DeadPlayerManager.DeadPlayer deadPlayer, Component deathMessage) {
	}

	private DeathHandler() {
	}

	public static void onDeath(ServerPlayer player, Component deathMessage) {
		DeadPlayerManager manager = TimedHardcore.manager();
		ExtraLifeManager lives = TimedHardcore.lives();

		if (manager == null || lives == null) {
			return;
		}

		MinecraftServer server = player.level().getServer();
		GameProfile profile = player.getGameProfile();

		switch (Risk.of(player)) {
			case EXEMPT -> {
			}
			case SAFE -> {
				int online = server.getPlayerList().getPlayerCount();
				if (TimedHardcore.config().isSafe(online)) {
					player.sendSystemMessage(Messages.safeDeath(online));
				}
			}
			case EXTRA_LIFE -> {
				lives.consume(player.getUUID());
				TimedHardcore.LOGGER.info("{} died and used up their extra life", profile.name());
				ClientSync.broadcastWithAppleIcon(server, green -> Messages.extraLifeUsed(profile, green));
			}
			case TIMED_DEATH -> startTimedDeath(manager, player, deathMessage);
		}
	}

	private static void startTimedDeath(DeadPlayerManager manager, ServerPlayer player, Component deathMessage) {
		GameProfile profile = player.getGameProfile();

		long now = System.currentTimeMillis();
		long revivalTime = TimedHardcore.config().revivalTime(now);
		long duration = revivalTime - now;

		manager.markDead(profile.id(), profile.name(), now, revivalTime,
			Graveyard.encodeDeathMessage(player.level().getServer().registryAccess(), deathMessage));
		pendingRespawn.add(player.getUUID());
		manager.getActive(profile.id()).ifPresent(deadPlayer -> pendingKicks.put(player.getUUID(), new PendingKick(deadPlayer, deathMessage)));
		// Right away, together with the death message; the kick can follow seconds later (death clip upload)
		player.level().getServer().getPlayerList().broadcastSystemMessage(Messages.deathBroadcast(profile, duration), false);
		TimedHardcore.LOGGER.info("{} died and stays dead for {}", profile.name(), TimeFormat.duration(duration));
		DiscordWebhook.announceDeath(player.level().getServer(), profile, deathMessage, now, revivalTime, DeathClips.request(player));
	}

	/**
	 * Asked on death, before items and XP drop. Players who stay dead keep everything if the game rule
	 * {@link TimedHardcoreGameRules#KEEP_INVENTORY_ON_TIMED_DEATH} is on. If it is off, and for all other deaths,
	 * the normal keepInventory rule applies.
	 *
	 * <p>Players who keep their inventory are tagged, so it is also carried over if the respawn only
	 * happens on the next join after a lost connection.
	 */
	public static boolean keepsInventoryOnDeath(ServerPlayer player) {
		if (player.entityTags().contains(KEEP_INVENTORY_TAG)) {
			return true;
		}
		if (TimedHardcore.manager() == null || !player.isDeadOrDying() || Risk.of(player) != Risk.TIMED_DEATH) {
			return false;
		}
		if (!player.level().getGameRules().get(TimedHardcoreGameRules.KEEP_INVENTORY_ON_TIMED_DEATH)) {
			return false;
		}
		player.addTag(KEEP_INVENTORY_TAG);
		return true;
	}

	/**
	 * For players who were saved while dead (e.g. connection lost right at death):
	 * they are respawned automatically when they join after their time is up.
	 */
	public static void respawnAtTickEnd(ServerPlayer player) {
		pendingRespawn.add(player.getUUID());
	}

	/** Called at the end of every server tick. */
	public static void onTickEnd(MinecraftServer server) {
		processPendingRespawns(server);
	}

	/**
	 * Respawns dead players on the server side (through the normal respawn flow), so they appear alive
	 * at their spawn point once their time is up, and kicks them afterwards if they are still dead.
	 * Runs at the end of the tick so players are not swapped in the middle of the entity tick.
	 */
	private static void processPendingRespawns(MinecraftServer server) {
		if (pendingRespawn.isEmpty()) {
			return;
		}
		List<UUID> todo = new ArrayList<>(pendingRespawn);
		pendingRespawn.clear();

		for (UUID uuid : todo) {
			ServerPlayer player = server.getPlayerList().getPlayer(uuid);
			if (player != null && DeathClips.isWaitingFor(uuid)) {
				// The client is still uploading its death clip; a kicked client could not finish
				pendingRespawn.add(uuid);
				continue;
			}
			PendingKick kick = pendingKicks.remove(uuid);
			if (player == null) {
				continue;
			}
			ServerGamePacketListenerImpl connection = player.connection;
			if (player.isDeadOrDying()) {
				connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
			}
			kickIfDead(connection, uuid, kick);
		}
	}

	/** Kicks a player who just died a timed death, or one who joined while still dead. */
	private static void kickIfDead(ServerGamePacketListenerImpl connection, UUID uuid, @Nullable PendingKick kick) {
		DeadPlayerManager manager = TimedHardcore.manager();
		if (manager == null) {
			return;
		}
		// After the respawn the connection holds a new player object
		GameProfile profile = connection.player.getGameProfile();
		DeadPlayerManager.DeadPlayer deadPlayer = kick != null ? kick.deadPlayer() : manager.getActive(uuid).orElse(null);
		if (deadPlayer == null) {
			return;
		}
		Component message = kick != null ? kick.deathMessage() : Component.literal(profile.name() + " died");
		connection.disconnect(Messages.deathKick(deadPlayer, profile, message));
	}

	/** The player was revived by command before the kick (while their death clip uploaded): they are not kicked. */
	public static void cancelKick(UUID uuid) {
		pendingKicks.remove(uuid);
	}
}
