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
 * Messages to the client mod. Players without the mod never receive them;
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

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(RiskPayload.TYPE, RiskPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(AppleEatenPayload.TYPE, AppleEatenPayload.CODEC);
	}
}
