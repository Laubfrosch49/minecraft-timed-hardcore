package dev.laubfrosch.timedhardcore.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.laubfrosch.timedhardcore.Risk;
import dev.laubfrosch.timedhardcore.TimedHardcore;
import dev.laubfrosch.timedhardcore.client.TimedHardcoreClient;
import java.util.Set;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Green hearts with an extra life: the normal heart sprites are replaced by the mod's green ones
 * (assets/timed-hardcore/textures/gui/sprites/hud/heart/extra_life_*.png).
 */
@Mixin(targets = "net.minecraft.client.gui.Hud$HeartType")
public class HeartTypeMixin {

	@Unique
    private static final Set<String> NORMAL_HEARTS = Set.of(
		"hud/heart/full", "hud/heart/full_blinking", "hud/heart/half", "hud/heart/half_blinking",
		"hud/heart/hardcore_full", "hud/heart/hardcore_full_blinking", "hud/heart/hardcore_half", "hud/heart/hardcore_half_blinking"
	);

	@ModifyReturnValue(method = "getSprite", at = @At("RETURN"))
	private Identifier timedhardcore$greenHearts(Identifier sprite) {
		if (TimedHardcoreClient.risk() == Risk.EXTRA_LIFE
			&& sprite.getNamespace().equals("minecraft")
			&& NORMAL_HEARTS.contains(sprite.getPath())) {
			return TimedHardcore.id(sprite.getPath().replace("hud/heart/", "hud/heart/extra_life_"));
		}
		return sprite;
	}
}
