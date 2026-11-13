package dev.laubfrosch.timedhardcore.client.mixin;

import dev.laubfrosch.timedhardcore.client.ClipUploadScreen;
import dev.laubfrosch.timedhardcore.client.DeathClipRecorder;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the death clip is created and sent, the death screen shows a camera and a short message
 * where its buttons were (they are hidden: the player is kicked anyway).
 */
@Mixin(DeathScreen.class)
public abstract class DeathScreenMixin extends Screen {

	protected DeathScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void timedhardcore$clipUploadNotice(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (!DeathClipRecorder.sendingClip()) {
			return;
		}
		// Where the first button was
		int top = height / 4 + 72;
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, ClipUploadScreen.CAMERA_ICON, width / 2 - 10, top, 20, 20);
		graphics.centeredText(font, ClipUploadScreen.MESSAGE, width / 2, top + 28, 0xFFFFFFFF);
	}
}
