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

	/** Death message for the kick screen, kept until the player is kicked at the end of the tick. */
	private static final Map<UUID, Component> pendingDeathMessages = new ConcurrentHashMap<>();

	/** Chat announcements of timed deaths, held back until the player has left the game. */
	private static final Map<UUID, Component> pendingAnnouncements = new ConcurrentHashMap<>();

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

		manager.markDead(profile.id(), profile.name(), now, revivalTime);
		pendingRespawn.add(player.getUUID());
		pendingDeathMessages.put(player.getUUID(), deathMessage);
		pendingAnnouncements.put(player.getUUID(), Messages.deathBroadcast(profile, duration));
		TimedHardcore.LOGGER.info("{} died and stays dead for {}", profile.name(), TimeFormat.duration(duration));
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
		announceTimedDeaths(server);
	}

	/**
	 * Tells everyone in chat who stays dead and for how long. The message waits until the player is
	 * gone, so it appears after "... left the game"; the kick can take a few ticks to complete.
	 */
	private static void announceTimedDeaths(MinecraftServer server) {
		DeadPlayerManager manager = TimedHardcore.manager();
		pendingAnnouncements.entrySet().removeIf(entry -> {
			UUID uuid = entry.getKey();
			boolean online = server.getPlayerList().getPlayer(uuid) != null;
			boolean kickPending = manager != null && manager.getActive(uuid).isPresent();
			if (online && kickPending) {
				return false;
			}
			server.getPlayerList().broadcastSystemMessage(entry.getValue(), false);
			return true;
		});
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
			Component deathMessage = pendingDeathMessages.remove(uuid);
			if (player == null) {
				continue;
			}
			ServerGamePacketListenerImpl connection = player.connection;
			if (player.isDeadOrDying()) {
				connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
			}
			kickIfDead(connection, uuid, deathMessage);
		}
	}

	private static void kickIfDead(ServerGamePacketListenerImpl connection, UUID uuid, @Nullable Component deathMessage) {
		DeadPlayerManager manager = TimedHardcore.manager();
		if (manager == null) {
			return;
		}
		// After the respawn the connection holds a new player object
		GameProfile profile = connection.player.getGameProfile();
		Component message = deathMessage != null ? deathMessage : Component.literal(profile.name() + " died");
		manager.getActive(uuid).ifPresent(deadPlayer -> connection.disconnect(Messages.deathKick(deadPlayer, profile, message)));
	}
}
