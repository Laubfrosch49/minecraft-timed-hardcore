package dev.laubfrosch.timedhardcore.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.laubfrosch.timedhardcore.ServerListMotd;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.network.ServerStatusPacketListenerImpl;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Attaches the dead players to the server list response, so the client mod can show its player
 * how long they are still dead.
 */
@Mixin(ServerStatusPacketListenerImpl.class)
public class ServerStatusPacketListenerImplMixin {

	@ModifyExpressionValue(
		method = "handleStatusRequest",
		at = @At(value = "FIELD", target = "Lnet/minecraft/server/network/ServerStatusPacketListenerImpl;status:Lnet/minecraft/network/protocol/status/ServerStatus;", opcode = Opcodes.GETFIELD)
	)
	private ServerStatus timedhardcore$publishDeadPlayers(ServerStatus original) {
		return ServerListMotd.publish(original);
	}
}
