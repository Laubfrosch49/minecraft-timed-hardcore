package dev.laubfrosch.timedhardcore.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.laubfrosch.timedhardcore.GreenApple;
import dev.laubfrosch.timedhardcore.client.TimedHardcoreClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * After eating the green apple the totem animation shows the apple instead of a totem.
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

	@WrapOperation(
		method = "handleEntityEvent",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;findTotem(Lnet/minecraft/world/entity/player/Player;)Lnet/minecraft/world/item/ItemStack;")
	)
	private ItemStack timedhardcore$showGreenApple(Player player, Operation<ItemStack> original) {
		if (TimedHardcoreClient.consumeAppleActivation()) {
			return GreenApple.create();
		}
		return original.call(player);
	}
}
