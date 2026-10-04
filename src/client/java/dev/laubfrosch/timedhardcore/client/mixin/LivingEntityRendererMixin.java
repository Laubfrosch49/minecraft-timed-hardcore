package dev.laubfrosch.timedhardcore.client.mixin;

import dev.laubfrosch.timedhardcore.Healer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No name tag above the Wandering Healer, not even when looking straight at it.
 * The name stays set anyway (title of the trading screen).
 */
@Mixin(LivingEntityRenderer.class)
public class LivingEntityRendererMixin {

	@Inject(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z", at = @At("HEAD"), cancellable = true)
	private void timedhardcore$hideHealerName(LivingEntity entity, double distanceToCameraSq, CallbackInfoReturnable<Boolean> cir) {
		if (entity instanceof WanderingTrader) {
			Component name = entity.getCustomName();
			if (name != null && Healer.NAME.equals(name.getString())) {
				cir.setReturnValue(false);
			}
		}
	}
}
