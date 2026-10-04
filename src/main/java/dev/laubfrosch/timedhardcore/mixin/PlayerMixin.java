package dev.laubfrosch.timedhardcore.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.laubfrosch.timedhardcore.DeathHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Whether players who stay dead after dying keep inventory and XP is decided by the game rule
 * timed-hardcore:keep_inventory_on_timed_death. All other deaths follow the normal keepInventory rule.
 */
@Mixin(Player.class)
public class PlayerMixin {

	@WrapOperation(
		method = {"dropEquipment", "getBaseExperienceReward"},
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/gamerules/GameRules;get(Lnet/minecraft/world/level/gamerules/GameRule;)Ljava/lang/Object;")
	)
	// At runtime this code is part of Player, so "this" can be a ServerPlayer; the IDE cannot know that
	@SuppressWarnings("ConstantValue")
	private Object timedhardcore$keepInventoryOnTimedDeath(GameRules rules, GameRule<?> gameRule, Operation<Object> original) {
		if (gameRule == GameRules.KEEP_INVENTORY && (Object) this instanceof ServerPlayer player && DeathHandler.keepsInventoryOnDeath(player)) {
			return true;
		}
		return original.call(rules, gameRule);
	}
}
