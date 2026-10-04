package dev.laubfrosch.timedhardcore.client;

import dev.laubfrosch.timedhardcore.Risk;
import dev.laubfrosch.timedhardcore.network.TimedHardcoreNetwork;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.jspecify.annotations.Nullable;

/**
 * Optional client part. Remembers what the server reports; the mixins use it for the hearts
 * (red = timed death, green = extra life, normal = safe), the death screen and the totem animation.
 */
public class TimedHardcoreClient implements ClientModInitializer {

	/** Risk reported by the server; null = server without Timed Hardcore, everything stays vanilla. */
	private static volatile @Nullable Risk risk;
	private static volatile boolean appleActivationPending;

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(TimedHardcoreNetwork.RiskPayload.TYPE,
			(payload, _) -> risk = payload.risk());
		ClientPlayNetworking.registerGlobalReceiver(TimedHardcoreNetwork.AppleEatenPayload.TYPE,
			(_, _) -> appleActivationPending = true);
		ClientPlayConnectionEvents.DISCONNECT.register((_, _) -> {
			risk = null;
			appleActivationPending = false;
		});
	}

	public static @Nullable Risk risk() {
		return risk;
	}

	/** Returns true once if the next totem animation should show the green apple. */
	public static boolean consumeAppleActivation() {
		boolean pending = appleActivationPending;
		appleActivationPending = false;
		return pending;
	}
}
