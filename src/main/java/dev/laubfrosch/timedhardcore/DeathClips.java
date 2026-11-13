package dev.laubfrosch.timedhardcore;

import dev.laubfrosch.timedhardcore.network.TimedHardcoreNetwork;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ConfigurationTask;
import org.jspecify.annotations.Nullable;

/**
 * Death clips: the last seconds before a timed death as an animated GIF, for the Discord message
 * ({@link TimedHardcoreConfig.DeathMedia#CLIP}). Whether a clip shows only the game world or also the HUD,
 * each player decides on their client; the server takes the clip as it comes.
 *
 * <p>Only the client of the dying player can record them, so this needs the client mod. The server asks
 * for the clip right at death and waits for it, up to {@link #TIMEOUT}; the player is kicked only after that,
 * because a disconnected client cannot upload anything. Meanwhile the client shows the death screen for a moment
 * longer (it ends the clip), which then stays until the kick and says that the clip is being sent.
 */
public final class DeathClips {

	/**
	 * How long the server waits for a clip; after that the Discord message falls back to the picture. Clips usually
	 * arrive within a second or two, this is for slow connections; the player waits that long at most before the kick.
	 */
	public static final Duration TIMEOUT = Duration.ofSeconds(60);

	/** Clips the server is waiting for; completed with null if none comes. */
	private static final Map<UUID, CompletableFuture<byte @Nullable []>> pending = new ConcurrentHashMap<>();

	private DeathClips() {
	}

	/**
	 * Does the server want clips? Only with {@code discord.deathMedia: CLIP}, and never without a webhook or while
	 * the messages are disabled: clips are only for Discord.
	 */
	public static boolean wanted(TimedHardcoreConfig config) {
		return config.discord.active() && config.discord.deathMedia.isClip();
	}

	/**
	 * While a player with the client mod joins, before they are in the world: if the server records clips,
	 * their client shows a notice about it (once per server) and the join waits until it is confirmed.
	 */
	public static void registerJoinNotice() {
		ServerConfigurationConnectionEvents.CONFIGURE.register((handler, server) -> {
			if (wanted(TimedHardcore.config()) && !server.isSingleplayer()
				&& ServerConfigurationNetworking.canSend(handler, TimedHardcoreNetwork.ClipNoticePayload.TYPE)) {
				handler.addTask(new NoticeTask());
			}
		});
		ServerConfigurationNetworking.registerGlobalReceiver(TimedHardcoreNetwork.ClipNoticeAnswerPayload.TYPE,
			(_, context) -> context.packetListener().completeTask(NoticeTask.TYPE));
	}

	/** Join step that sends the notice and waits for the answer. */
	private record NoticeTask() implements ConfigurationTask {
		static final Type TYPE = new Type(TimedHardcore.id("clip_notice").toString());

		@Override
		public void start(Consumer<Packet<?>> sender) {
			sender.accept(ServerConfigurationNetworking.createClientboundPacket(TimedHardcoreNetwork.ClipNoticePayload.INSTANCE));
		}

		@Override
		public Type type() {
			return TYPE;
		}
	}

	/**
	 * Asks the client of a player who just died for their clip.
	 *
	 * @return the clip that will arrive (null inside if none comes), or null if no clip is asked for:
	 *     clips are off, or the player has no client mod (or an old one)
	 */
	public static @Nullable CompletableFuture<byte @Nullable []> request(ServerPlayer player) {
		if (!wanted(TimedHardcore.config()) || !ServerPlayNetworking.canSend(player, TimedHardcoreNetwork.ClipRequestPayload.TYPE)) {
			return null;
		}
		CompletableFuture<byte @Nullable []> clip = await(player.getUUID());
		ServerPlayNetworking.send(player, TimedHardcoreNetwork.ClipRequestPayload.INSTANCE);
		return clip;
	}

	/**
	 * Waits for a clip from this player without asking for one: their kick waits until it arrives or
	 * {@link #TIMEOUT} is over. Public for the game test, whose players have no client mod to ask.
	 */
	public static CompletableFuture<byte @Nullable []> await(UUID player) {
		CompletableFuture<byte @Nullable []> clip = new CompletableFuture<byte @Nullable []>()
			.completeOnTimeout(null, TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		CompletableFuture<byte @Nullable []> previous = pending.put(player, clip);
		if (previous != null) {
			previous.complete(null);
		}
		return clip;
	}

	/** Is the server still waiting for this player's clip? Their kick waits as long. */
	public static boolean isWaitingFor(UUID player) {
		CompletableFuture<byte @Nullable []> clip = pending.get(player);
		return clip != null && !clip.isDone();
	}

	/**
	 * A clip arrived. Clips nobody asked for are ignored, so a client cannot post to Discord on its own, and so
	 * is anything that does not have the shape of a recording (size of the picture, number of frames). What the
	 * pictures show cannot be checked: like a skin, it comes from the player.
	 */
	public static void receive(ServerPlayer player, byte[] gif) {
		CompletableFuture<byte @Nullable []> clip = pending.remove(player.getUUID());
		if (clip == null) {
			return;
		}
		int frames = gif.length <= TimedHardcoreNetwork.MAX_CLIP_BYTES
			? GifEncoder.countFrames(gif, TimedHardcoreNetwork.MAX_CLIP_WIDTH, TimedHardcoreNetwork.MAX_CLIP_HEIGHT) : -1;
		boolean valid = frames > 0 && frames <= TimedHardcoreNetwork.MAX_CLIP_FRAMES;
		if (gif.length > 0 && !valid) {
			TimedHardcore.LOGGER.warn("{} sent an invalid death clip, it is not used", player.getGameProfile().name());
		}
		clip.complete(valid ? gif : null);
	}

	/** The player left: no clip will come any more. */
	public static void forget(UUID player) {
		CompletableFuture<byte @Nullable []> clip = pending.remove(player);
		if (clip != null) {
			clip.complete(null);
		}
	}

	public static void reset() {
		pending.values().forEach(clip -> clip.complete(null));
		pending.clear();
	}
}
