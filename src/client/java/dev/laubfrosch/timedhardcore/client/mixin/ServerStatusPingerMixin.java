package dev.laubfrosch.timedhardcore.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.laubfrosch.timedhardcore.ServerListMotd;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Server list: if the server reports this player as dead or revived, the second MOTD line says so.
 * The target is the listener that the pinger creates for the status response.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ServerStatusPinger$1")
public class ServerStatusPingerMixin {

	@ModifyExpressionValue(
		method = "handleStatusResponse",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/status/ServerStatus;description()Lnet/minecraft/network/chat/Component;")
	)
	private Component timedhardcore$showOwnDeath(Component description) {
		User user = Minecraft.getInstance().getUser();
		// A server in offline mode knows the player by a UUID derived from the name, not by the account's UUID
		return ServerListMotd.personalize(description, List.of(user.getProfileId(), UUIDUtil.createOfflinePlayerUUID(user.getName())));
	}
}
