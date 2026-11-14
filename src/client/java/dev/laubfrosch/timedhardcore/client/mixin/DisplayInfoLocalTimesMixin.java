package dev.laubfrosch.timedhardcore.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.laubfrosch.timedhardcore.client.LocalTimes;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The graveyard (an advancement tab) shows the times of death and revival in the player's own time zone. */
@Mixin(DisplayInfo.class)
public class DisplayInfoLocalTimesMixin {

	@ModifyReturnValue(method = "description", at = @At("RETURN"))
	private Component timedhardcore$localTimes(Component description) {
		return LocalTimes.localize(description);
	}
}
