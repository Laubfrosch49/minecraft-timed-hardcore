package dev.laubfrosch.timedhardcore.client.mixin;

import dev.laubfrosch.timedhardcore.client.HealerRenderState;
import net.minecraft.client.renderer.entity.state.VillagerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(VillagerRenderState.class)
public class VillagerRenderStateMixin implements HealerRenderState {

	@Unique
	private boolean timedhardcore$healer;

	@Override
	public boolean timedhardcore$isHealer() {
		return this.timedhardcore$healer;
	}

	@Override
	public void timedhardcore$setHealer(boolean healer) {
		this.timedhardcore$healer = healer;
	}
}
