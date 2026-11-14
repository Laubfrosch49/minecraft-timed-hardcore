package dev.laubfrosch.timedhardcore.client.mixin;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The server being joined. Minecraft.getCurrentServer() only knows it once the player is in the world,
 * but the notice about death clips is shown before that, while joining.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public interface ClientCommonPacketListenerImplAccessor {

	@Accessor("serverData")
	@Nullable ServerData timedhardcore$serverData();
}
