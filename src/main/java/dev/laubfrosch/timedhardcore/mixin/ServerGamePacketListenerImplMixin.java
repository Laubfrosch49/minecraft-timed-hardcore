package dev.laubfrosch.timedhardcore.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.laubfrosch.timedhardcore.DeathClips;
import dev.laubfrosch.timedhardcore.TimedHardcore;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Respawning after a timed death.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class ServerGamePacketListenerImplMixin {

	@Shadow
	public ServerPlayer player;

	/**
	 * While the client uploads its death clip, the player stays on the death screen. Without this, a click on
	 * "Spectate world" would respawn them (as survival, see below) and they could play on until the kick.
	 * The server respawns and kicks them itself once the clip is there.
	 */
	@Inject(method = "handleClientCommand", at = @At("HEAD"), cancellable = true)
	private void timedhardcore$waitForDeathClip(ServerboundClientCommandPacket packet, CallbackInfo ci) {
		if (packet.getAction() == ServerboundClientCommandPacket.Action.PERFORM_RESPAWN && DeathClips.isWaitingFor(player.getUUID())) {
			ci.cancel();
		}
	}

	/**
	 * In hardcore mode vanilla turns players into spectators after death.
	 * Here they keep their game mode instead; the "penalty" is staying dead for a while.
	 */
	@WrapOperation(
		method = "handleClientCommand",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;setGameMode(Lnet/minecraft/world/level/GameType;)Z")
	)
	private boolean timedhardcore$skipHardcoreSpectator(ServerPlayer player, GameType mode, Operation<Boolean> original) {
		if (mode == GameType.SPECTATOR && TimedHardcore.manager() != null) {
			return false;
		}
		return original.call(player, mode);
	}
}
