package dev.laubfrosch.timedhardcore.mixin;

import dev.laubfrosch.timedhardcore.GreenApple;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Green apple: only allow eating without an extra life yet, and grant the extra life once it is eaten.
 */
@Mixin(Consumable.class)
public class ConsumableMixin {

	@Inject(method = "startConsuming", at = @At("HEAD"), cancellable = true)
	private void timedhardcore$blockSecondExtraLife(LivingEntity user, ItemStack stack, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
		if (user instanceof ServerPlayer player && GreenApple.is(stack) && !GreenApple.canEat(player)) {
			cir.setReturnValue(InteractionResult.FAIL);
		}
	}

	@Inject(method = "onConsume", at = @At("HEAD"))
	private void timedhardcore$grantExtraLife(Level level, LivingEntity user, ItemStack stack, CallbackInfoReturnable<ItemStack> cir) {
		if (user instanceof ServerPlayer player && GreenApple.is(stack)) {
			GreenApple.onEaten(player);
		}
	}
}
