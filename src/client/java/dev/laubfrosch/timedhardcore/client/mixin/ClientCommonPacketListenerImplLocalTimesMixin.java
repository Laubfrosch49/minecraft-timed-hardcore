package dev.laubfrosch.timedhardcore.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.laubfrosch.timedhardcore.client.LocalTimes;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The kick screen after a timed death shows the time of revival in the player's own time zone. */
@Mixin(ClientCommonPacketListenerImpl.class)
public class ClientCommonPacketListenerImplLocalTimesMixin {

	@ModifyExpressionValue(
		method = "handleDisconnect",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/common/ClientboundDisconnectPacket;reason()Lnet/minecraft/network/chat/Component;")
	)
	private Component timedhardcore$localTimes(Component reason) {
		return LocalTimes.localize(reason);
	}
}
