package dev.laubfrosch.timedhardcore.network;

import dev.laubfrosch.timedhardcore.Risk;
import dev.laubfrosch.timedhardcore.TimedHardcore;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.jspecify.annotations.NonNull;

/**
 * Messages between the server and the client mod. Players without the mod never receive them;
 * the server checks with ServerPlayNetworking.canSend first.
 */
public final class TimedHardcoreNetwork {

	private TimedHardcoreNetwork() {
	}

	/** What happens if the player dies right now; decides hearts and death screen on the client. */
	public record RiskPayload(Risk risk) implements CustomPacketPayload {
		public static final Type<RiskPayload> TYPE = new Type<>(TimedHardcore.id("risk"));
		public static final StreamCodec<ByteBuf, RiskPayload> CODEC = ByteBufCodecs.VAR_INT
			.map(id -> new RiskPayload(Risk.byId(id)), payload -> payload.risk().ordinal());

		@Override
		public @NonNull Type<RiskPayload> type() {
			return TYPE;
		}
	}

	/** Arrives right before the totem animation, so the client shows the green apple instead of a totem. */
	public record AppleEatenPayload() implements CustomPacketPayload {
		public static final AppleEatenPayload INSTANCE = new AppleEatenPayload();
		public static final Type<AppleEatenPayload> TYPE = new Type<>(TimedHardcore.id("apple_eaten"));
		public static final StreamCodec<ByteBuf, AppleEatenPayload> CODEC = StreamCodec.unit(INSTANCE);

		@Override
		public @NonNull Type<AppleEatenPayload> type() {
			return TYPE;
		}
	}

	/** Whether the server wants death clips; the client only records while it does (and the player agreed). */
	public record ClipSettingsPayload(boolean clips) implements CustomPacketPayload {
		public static final Type<ClipSettingsPayload> TYPE = new Type<>(TimedHardcore.id("clip_settings"));
		public static final StreamCodec<ByteBuf, ClipSettingsPayload> CODEC = ByteBufCodecs.BOOL
			.map(ClipSettingsPayload::new, ClipSettingsPayload::clips);

		@Override
		public @NonNull Type<ClipSettingsPayload> type() {
			return TYPE;
		}
	}

	/**
	 * Sent while joining, before the player is in the world: this server records death clips. The client
	 * shows a notice about it once and answers with {@link ClipNoticeAnswerPayload}; the join waits for that.
	 */
	public record ClipNoticePayload() implements CustomPacketPayload {
		public static final ClipNoticePayload INSTANCE = new ClipNoticePayload();
		public static final Type<ClipNoticePayload> TYPE = new Type<>(TimedHardcore.id("clip_notice"));
		public static final StreamCodec<ByteBuf, ClipNoticePayload> CODEC = StreamCodec.unit(INSTANCE);

		@Override
		public @NonNull Type<ClipNoticePayload> type() {
			return TYPE;
		}
	}

	/** The player has seen the notice (or saw it before); the join continues. */
	public record ClipNoticeAnswerPayload() implements CustomPacketPayload {
		public static final ClipNoticeAnswerPayload INSTANCE = new ClipNoticeAnswerPayload();
		public static final Type<ClipNoticeAnswerPayload> TYPE = new Type<>(TimedHardcore.id("clip_notice_answer"));
		public static final StreamCodec<ByteBuf, ClipNoticeAnswerPayload> CODEC = StreamCodec.unit(INSTANCE);

		@Override
		public @NonNull Type<ClipNoticeAnswerPayload> type() {
			return TYPE;
		}
	}

	/** The player just died and stays dead: the server asks for the clip of the last seconds. */
	public record ClipRequestPayload() implements CustomPacketPayload {
		public static final ClipRequestPayload INSTANCE = new ClipRequestPayload();
		public static final Type<ClipRequestPayload> TYPE = new Type<>(TimedHardcore.id("clip_request"));
		public static final StreamCodec<ByteBuf, ClipRequestPayload> CODEC = StreamCodec.unit(INSTANCE);

		@Override
		public @NonNull Type<ClipRequestPayload> type() {
			return TYPE;
		}
	}

	/**
	 * The answer to {@link ClipRequestPayload}: the clip as an animated GIF, or empty if the player
	 * switched clips off or nothing was recorded. The only message from the client to the server.
	 */
	public record ClipPayload(byte[] gif) implements CustomPacketPayload {
		public static final ClipPayload NONE = new ClipPayload(new byte[0]);
		public static final Type<ClipPayload> TYPE = new Type<>(TimedHardcore.id("clip"));
		public static final StreamCodec<ByteBuf, ClipPayload> CODEC = ByteBufCodecs.byteArray(MAX_CLIP_BYTES)
			.map(ClipPayload::new, ClipPayload::gif);

		@Override
		public @NonNull Type<ClipPayload> type() {
			return TYPE;
		}
	}

	/** Largest clip the server accepts; Discord takes up to 10 MB per message, the head picture also needs a little. */
	public static final int MAX_CLIP_BYTES = 8 * 1024 * 1024;
	/** Size the client records in; the server accepts nothing larger. */
	public static final int MAX_CLIP_WIDTH = 480;
	public static final int MAX_CLIP_HEIGHT = 270;
	/** A clip has about 115 frames (11.5 seconds, 10 per second); more than this is not a recording of the mod. */
	public static final int MAX_CLIP_FRAMES = 150;

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(RiskPayload.TYPE, RiskPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(AppleEatenPayload.TYPE, AppleEatenPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ClipSettingsPayload.TYPE, ClipSettingsPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ClipRequestPayload.TYPE, ClipRequestPayload.CODEC);
		PayloadTypeRegistry.clientboundConfiguration().register(ClipNoticePayload.TYPE, ClipNoticePayload.CODEC);
		PayloadTypeRegistry.serverboundConfiguration().register(ClipNoticeAnswerPayload.TYPE, ClipNoticeAnswerPayload.CODEC);
		// A clip is far larger than a normal packet may be (32 KB); Fabric API splits it up and puts it back together
		PayloadTypeRegistry.serverboundPlay().registerLarge(ClipPayload.TYPE, ClipPayload.CODEC, MAX_CLIP_BYTES + 16);
	}
}
