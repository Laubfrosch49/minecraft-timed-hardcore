package dev.laubfrosch.timedhardcore.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.laubfrosch.timedhardcore.client.DeathClipRecorder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Before the kick after a timed death, the server respawns the player, so they are alive at their spawn point
 * when they come back. On the client the kick screen only follows a few frames later. Until then a respawn would
 * close the death screen, open the "Loading terrain" screen and move the camera to the spawn point, all of which
 * would flash up between the death screen and the kick screen. While the kick is expected, the death screen and
 * the camera stay where they are; the new player and the world still load as usual. If the player is revived
 * instead of kicked, {@link DeathClipRecorder#tick} brings them back into the game.
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerRespawnMixin {

	@WrapOperation(
		method = "startWaitingForNewLevel",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;setScreenAndShow(Lnet/minecraft/client/gui/screens/Screen;)V")
	)
	private void timedhardcore$noLoadingScreen(Minecraft client, Screen screen, Operation<Void> original) {
		if (!DeathClipRecorder.awaitingKick()) {
			original.call(client, screen);
		}
	}

	@WrapOperation(
		method = "handleRespawn",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V")
	)
	private void timedhardcore$keepDeathScreen(Gui gui, @Nullable Screen screen, Operation<Void> original) {
		if (!DeathClipRecorder.awaitingKick()) {
			original.call(gui, screen);
		}
	}

	@WrapOperation(
		method = "handleRespawn",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;setCameraEntity(Lnet/minecraft/world/entity/Entity;)V")
	)
	private void timedhardcore$keepCamera(Minecraft client, @Nullable Entity entity, Operation<Void> original) {
		Entity camera = client.getCameraEntity();
		// Only within the same world: after a respawn in another dimension the old camera has no world to show
		if (!DeathClipRecorder.awaitingKick() || camera == null || camera.level() != client.level) {
			original.call(client, entity);
		}
	}
}
