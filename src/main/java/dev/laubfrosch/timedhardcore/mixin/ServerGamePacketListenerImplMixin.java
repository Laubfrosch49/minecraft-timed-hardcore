package dev.laubfrosch.timedhardcore.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.laubfrosch.timedhardcore.TimedHardcore;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * In hardcore mode vanilla turns players into spectators after death.
 * Here they keep their game mode instead; the "penalty" is staying dead for a while.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class ServerGamePacketListenerImplMixin {

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
