package dev.laubfrosch.timedhardcore.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.laubfrosch.timedhardcore.Risk;
import dev.laubfrosch.timedhardcore.client.TimedHardcoreClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Death screen: "Game Over / Spectate" only if the player really stays dead,
 * otherwise the normal respawn button.
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerDeathScreenMixin {

	@ModifyExpressionValue(
		method = "handlePlayerCombatKill",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel$ClientLevelData;isHardcore()Z")
	)
	private boolean timedhardcore$hardcoreDeathScreen(boolean original) {
		Risk risk = TimedHardcoreClient.risk();
		return risk == null ? original : risk == Risk.TIMED_DEATH;
	}
}
