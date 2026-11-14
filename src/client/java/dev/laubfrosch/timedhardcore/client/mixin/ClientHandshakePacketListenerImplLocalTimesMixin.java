package dev.laubfrosch.timedhardcore.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.laubfrosch.timedhardcore.client.LocalTimes;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** "You are still dead" when joining shows the time of revival in the player's own time zone. */
@Mixin(ClientHandshakePacketListenerImpl.class)
public class ClientHandshakePacketListenerImplLocalTimesMixin {

	@ModifyExpressionValue(
		method = "handleDisconnect",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/login/ClientboundLoginDisconnectPacket;reason()Lnet/minecraft/network/chat/Component;")
	)
	private Component timedhardcore$localTimes(Component reason) {
		return LocalTimes.localize(reason);
	}
}
