package dev.laubfrosch.timedhardcore.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.laubfrosch.timedhardcore.Healer;
import dev.laubfrosch.timedhardcore.TimedHardcore;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTraderSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Turns a share of the naturally spawned wandering traders into "Wandering Healers".
 */
@Mixin(WanderingTraderSpawner.class)
public class WanderingTraderSpawnerMixin {

	@WrapOperation(
		method = "spawn",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/EntityType;spawn(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/EntitySpawnReason;)Lnet/minecraft/world/entity/Entity;")
	)
	private Entity timedhardcore$maybeHealer(EntityType<?> type, ServerLevel level, BlockPos spawnPos, EntitySpawnReason spawnReason, Operation<Entity> original) {
		Entity entity = original.call(type, level, spawnPos, spawnReason);
		if (entity instanceof WanderingTrader trader
			&& TimedHardcore.manager() != null
			&& level.getRandom().nextDouble() < TimedHardcore.config().healerSpawnChance) {
			Healer.convert(trader);
		}
		return entity;
	}
}
