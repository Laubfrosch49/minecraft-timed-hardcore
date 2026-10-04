package dev.laubfrosch.timedhardcore.mixin;

import dev.laubfrosch.timedhardcore.DeadPlayerManager;
import dev.laubfrosch.timedhardcore.Messages;
import dev.laubfrosch.timedhardcore.TimedHardcore;
import java.net.SocketAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Rejects dead players at login and shows the remaining time.
 */
@Mixin(PlayerList.class)
public class PlayerListMixin {

	@Inject(method = "canPlayerLogin", at = @At("HEAD"), cancellable = true)
	private void timedhardcore$denyDeadPlayers(SocketAddress address, NameAndId nameAndId, CallbackInfoReturnable<Component> cir) {
		DeadPlayerManager manager = TimedHardcore.manager();
		if (manager == null) {
			return;
		}
		manager.getActive(nameAndId.id()).ifPresent(deadPlayer -> {
			manager.updateName(nameAndId.id(), nameAndId.name());
			cir.setReturnValue(Messages.loginDenied(deadPlayer));
		});
	}
}
