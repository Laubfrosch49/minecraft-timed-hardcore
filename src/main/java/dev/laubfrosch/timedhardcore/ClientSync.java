package dev.laubfrosch.timedhardcore;

import dev.laubfrosch.timedhardcore.network.TimedHardcoreNetwork;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Everything the server tells the optional client mod. Players without the mod receive none of it.
 */
public final class ClientSync {

	/** Last risk sent to each player. */
	private static final Map<UUID, Risk> sentRisks = new ConcurrentHashMap<>();

	private ClientSync() {
	}

	/** Does the player have the client mod? */
	public static boolean hasClientMod(@Nullable ServerPlayer player) {
		return player != null && ServerPlayNetworking.canSend(player, TimedHardcoreNetwork.RiskPayload.TYPE);
	}

	/** Sends clients with the mod their risk, which decides how hearts and death screen look. */
	public static void tick(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!hasClientMod(player)) {
				continue;
			}
			Risk risk = Risk.of(player);
			if (sentRisks.put(player.getUUID(), risk) != risk) {
				ServerPlayNetworking.send(player, new TimedHardcoreNetwork.RiskPayload(risk));
			}
		}
	}

	/** Sent right before the totem animation so the client shows the green apple instead of a totem. */
	public static void announceAppleEaten(ServerPlayer player) {
		if (ServerPlayNetworking.canSend(player, TimedHardcoreNetwork.AppleEatenPayload.TYPE)) {
			ServerPlayNetworking.send(player, TimedHardcoreNetwork.AppleEatenPayload.INSTANCE);
		}
	}

	/**
	 * Message to everyone: players with the client mod see the green apple as an icon, the others the normal one.
	 *
	 * @param message builds the message; its argument says whether the green icon may be used
	 */
	public static void broadcastWithAppleIcon(MinecraftServer server, Function<Boolean, Component> message) {
		server.getPlayerList().broadcastSystemMessage(message.apply(false), player -> message.apply(hasClientMod(player)), false);
	}

	public static void forget(UUID player) {
		sentRisks.remove(player);
	}

	public static void reset() {
		sentRisks.clear();
	}
}
