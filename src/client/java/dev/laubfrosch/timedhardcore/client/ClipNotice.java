package dev.laubfrosch.timedhardcore.client;

import dev.laubfrosch.timedhardcore.client.mixin.ClientCommonPacketListenerImplAccessor;
import dev.laubfrosch.timedhardcore.network.TimedHardcoreNetwork;
import java.util.Locale;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The notice while joining a server that records death clips, before the player is in the world. It appears
 * as long as the player's choice for that server is "Ask", like server resource packs set to "Prompt"; the
 * answer becomes the "Death clips" setting under "Edit" in the server list, where it can be changed later.
 */
public final class ClipNotice {

	private ClipNotice() {
	}

	public static void register() {
		ClientConfigurationNetworking.registerGlobalReceiver(TimedHardcoreNetwork.ClipNoticePayload.TYPE, (_, context) -> {
			Minecraft client = context.client();
			PacketSender sender = context.responseSender();
			// While joining, Minecraft.getCurrentServer() is still empty; the connection knows the server already
			String server = serverKey(((ClientCommonPacketListenerImplAccessor) context.packetListener()).timedhardcore$serverData());
			client.execute(() -> show(client, server, () -> sender.sendPacket(TimedHardcoreNetwork.ClipNoticeAnswerPayload.INSTANCE)));
		});
	}

	/** The server the client is in, as it was entered; clip choices are remembered per server. */
	public static @Nullable String serverKey(Minecraft client) {
		return serverKey(client.getCurrentServer());
	}

	public static @Nullable String serverKey(@Nullable ServerData server) {
		if (server == null || server.ip == null || server.ip.isBlank()) {
			return null;
		}
		return server.ip.trim().toLowerCase(Locale.ROOT);
	}

	/**
	 * Shows the notice if needed; the join continues once {@code answer} has run. Without a known server
	 * nothing is shown, and nothing will be recorded either.
	 */
	private static void show(Minecraft client, @Nullable String server, Runnable answer) {
		ClientSettings settings = ClientSettings.get();
		if (server == null || !settings.needsClipNotice(server)) {
			answer.run();
			return;
		}
		Screen current = client.gui.screen();
		Screen joining = current != null ? current : new GenericMessageScreen(Component.translatable("connect.joining"));
		client.gui.setScreen(new ClipNoticeScreen(consent -> {
			settings.setConsent(server, consent);
			client.gui.setScreen(joining);
			answer.run();
		}));
	}
}
