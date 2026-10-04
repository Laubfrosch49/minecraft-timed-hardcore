package dev.laubfrosch.timedhardcore.client.mixin;

import dev.laubfrosch.timedhardcore.Healer;
import dev.laubfrosch.timedhardcore.client.HealerRenderState;
import dev.laubfrosch.timedhardcore.client.HealerTexture;
import net.minecraft.client.renderer.entity.WanderingTraderRenderer;
import net.minecraft.client.renderer.entity.state.VillagerRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Wandering Healer gets its own texture (recognised by the name the server syncs).
 */
@Mixin(WanderingTraderRenderer.class)
public class WanderingTraderRendererMixin {

	@Inject(
		method = "extractRenderState(Lnet/minecraft/world/entity/npc/wanderingtrader/WanderingTrader;Lnet/minecraft/client/renderer/entity/state/VillagerRenderState;F)V",
		at = @At("TAIL")
	)
	private void timedhardcore$detectHealer(WanderingTrader entity, VillagerRenderState state, float partialTicks, CallbackInfo ci) {
		Component name = entity.getCustomName();
		((HealerRenderState) state).timedhardcore$setHealer(name != null && Healer.NAME.equals(name.getString()));
	}

	@Inject(
		method = "getTextureLocation(Lnet/minecraft/client/renderer/entity/state/VillagerRenderState;)Lnet/minecraft/resources/Identifier;",
		at = @At("HEAD"),
		cancellable = true
	)
	private void timedhardcore$healerTexture(VillagerRenderState state, CallbackInfoReturnable<Identifier> cir) {
		if (((HealerRenderState) state).timedhardcore$isHealer()) {
			Identifier texture = HealerTexture.get();
			if (texture != null) {
				cir.setReturnValue(texture);
			}
		}
	}
}
