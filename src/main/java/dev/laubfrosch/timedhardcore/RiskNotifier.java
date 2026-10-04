package dev.laubfrosch.timedhardcore;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import org.jspecify.annotations.Nullable;

/**
 * Action bar notice (with icon and sound): the current risk about 3 seconds after joining, then on
 * every change. Not while dead; in that case it follows after the respawn.
 */
public final class RiskNotifier {

	/** Wait this long after joining, otherwise the notice is lost behind the loading screen (3 seconds). */
	private static final int JOIN_DELAY_TICKS = 60;

	/** Last risk announced to each player. */
	private static final Map<UUID, Risk> lastRisks = new ConcurrentHashMap<>();

	private RiskNotifier() {
	}

	public static void tick(MinecraftServer server) {
		int online = server.getPlayerList().getPlayerCount();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			notifyIfChanged(player, Risk.of(player), online);
		}
	}

	private static void notifyIfChanged(ServerPlayer player, Risk risk, int online) {
		if (player.isDeadOrDying()) {
			return;
		}

		boolean firstTime = !lastRisks.containsKey(player.getUUID());
		if (firstTime && player.tickCount < JOIN_DELAY_TICKS) {
			return;
		}

		// Creative/spectator counts as "safe", so switching between the two triggers no notice
		Risk normalized = risk == Risk.EXEMPT ? Risk.SAFE : risk;
		Risk previous = lastRisks.put(player.getUUID(), normalized);
		if (previous == normalized) {
			return;
		}

		boolean greenIcon = ClientSync.hasClientMod(player);
		player.sendOverlayMessage(firstTime ? Messages.riskOnJoin(risk, online, greenIcon) : Messages.riskChanged(risk, online, greenIcon));
		switch (normalized) {
			case TIMED_DEATH -> playSound(player, SoundEvents.BELL_BLOCK, 0.7F);
			case EXTRA_LIFE -> playSound(player, SoundEvents.AMETHYST_BLOCK_CHIME, 1.2F);
			default -> playSound(player, SoundEvents.NOTE_BLOCK_PLING.value(), 1.6F);
		}
	}

	/** Sound for this one player only. */
	private static void playSound(ServerPlayer player, SoundEvent sound, float pitch) {
		player.connection.send(new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), SoundSource.MASTER,
			player.getX(), player.getY(), player.getZ(), 1.0F, pitch, player.getRandom().nextLong()));
	}

	/** Last announced risk (for tests). */
	public static @Nullable Risk lastRisk(UUID player) {
		return lastRisks.get(player);
	}

	public static void forget(UUID player) {
		lastRisks.remove(player);
	}

	public static void reset() {
		lastRisks.clear();
	}
}
