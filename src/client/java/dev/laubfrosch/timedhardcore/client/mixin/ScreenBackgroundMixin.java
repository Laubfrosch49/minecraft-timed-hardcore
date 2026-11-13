package dev.laubfrosch.timedhardcore.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.laubfrosch.timedhardcore.client.DeathClipRecorder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Menus show the blurred world behind them while a world is loaded, otherwise the panorama. After a timed death
 * the kick screen appears while the world is still loaded, so for a moment it would show the world (and the chat)
 * between the upload screen and the panorama. Until the world is gone, menus act as if there were none.
 */
@Mixin(Screen.class)
public class ScreenBackgroundMixin {

	@ModifyExpressionValue(
		method = "extractBackground",
		at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;level:Lnet/minecraft/client/multiplayer/ClientLevel;")
	)
	private @Nullable ClientLevel timedhardcore$panoramaAfterTimedDeath(@Nullable ClientLevel level) {
		return DeathClipRecorder.hidesWorld() ? null : level;
	}
}
