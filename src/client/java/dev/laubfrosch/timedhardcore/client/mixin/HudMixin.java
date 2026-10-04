package dev.laubfrosch.timedhardcore.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.laubfrosch.timedhardcore.Risk;
import dev.laubfrosch.timedhardcore.client.TimedHardcoreClient;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Switches the heart style live: hardcore hearts only if a death would have consequences.
 */
@Mixin(Hud.class)
public class HudMixin {

	@ModifyExpressionValue(
		method = "extractHearts",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/LevelData;isHardcore()Z")
	)
	private boolean timedhardcore$hardcoreHearts(boolean original) {
		Risk risk = TimedHardcoreClient.risk();
		return risk == null ? original : risk.hasConsequences();
	}
}
