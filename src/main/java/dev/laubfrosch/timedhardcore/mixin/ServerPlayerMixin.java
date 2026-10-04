package dev.laubfrosch.timedhardcore.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.laubfrosch.timedhardcore.TabListStatus;
import dev.laubfrosch.timedhardcore.DeathHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public class ServerPlayerMixin {

	/** On respawn after a timed death, carries the kept inventory (and XP) over to the new player. */
	@WrapOperation(
		method = "restoreFrom",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/gamerules/GameRules;get(Lnet/minecraft/world/level/gamerules/GameRule;)Ljava/lang/Object;")
	)
	private Object timedhardcore$restoreKeptInventory(GameRules rules, GameRule<?> gameRule, Operation<Object> original,
		@Local(argsOnly = true, name = "oldPlayer") ServerPlayer oldPlayer) {
		if (gameRule == GameRules.KEEP_INVENTORY && oldPlayer.entityTags().contains(DeathHandler.KEEP_INVENTORY_TAG)) {
			return true;
		}
		return original.call(rules, gameRule);
	}

	/** Name in the player list with a risk icon. */
	@Inject(method = "getTabListDisplayName", at = @At("HEAD"), cancellable = true)
	private void timedhardcore$tabListStatus(CallbackInfoReturnable<Component> cir) {
		Component name = TabListStatus.displayName((ServerPlayer) (Object) this);
		if (name != null) {
			cir.setReturnValue(name);
		}
	}
}
