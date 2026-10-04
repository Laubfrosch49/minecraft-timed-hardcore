package dev.laubfrosch.timedhardcore.mixin;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import dev.laubfrosch.timedhardcore.DeadPlayerManager;
import dev.laubfrosch.timedhardcore.Graveyard;
import dev.laubfrosch.timedhardcore.TimedHardcore;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The first advancement packet (on join and after /reload) resets everything on the client,
 * so the graveyard tab is sent again right after it.
 */
@Mixin(PlayerAdvancements.class)
public class PlayerAdvancementsMixin {

	@Shadow
	private boolean isFirstPacket;

	@Inject(method = "flushDirty", at = @At("HEAD"))
	private void timedhardcore$rememberReset(ServerPlayer player, boolean showAdvancements, CallbackInfo ci, @Share("reset") LocalBooleanRef reset) {
		reset.set(this.isFirstPacket);
	}

	@Inject(method = "flushDirty", at = @At("TAIL"))
	private void timedhardcore$resendGraveyard(ServerPlayer player, boolean showAdvancements, CallbackInfo ci, @Share("reset") LocalBooleanRef reset) {
		DeadPlayerManager manager = TimedHardcore.manager();
		if (reset.get() && manager != null) {
			Graveyard.sendTo(player, manager);
		}
	}
}
