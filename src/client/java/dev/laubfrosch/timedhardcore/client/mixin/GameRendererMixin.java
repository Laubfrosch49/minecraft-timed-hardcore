package dev.laubfrosch.timedhardcore.client.mixin;

import dev.laubfrosch.timedhardcore.client.DeathClipRecorder;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Takes the frames for the death clip. Without HUD: once the world is completely drawn (including effects
 * like the spectator shaders), before chat and HUD are drawn on top, so private messages never end up in a clip.
 * With HUD: after everything the player sees is drawn.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

	@Inject(
		method = "render",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;applyPostEffects()V", shift = At.Shift.AFTER)
	)
	private void timedhardcore$captureWorld(CallbackInfo ci) {
		DeathClipRecorder.capture(((GameRenderer) (Object) this).mainRenderTarget(), false);
	}

	@Inject(
		method = "render",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V", shift = At.Shift.AFTER)
	)
	private void timedhardcore$captureWithHud(CallbackInfo ci) {
		DeathClipRecorder.capture(((GameRenderer) (Object) this).mainRenderTarget(), true);
	}
}
