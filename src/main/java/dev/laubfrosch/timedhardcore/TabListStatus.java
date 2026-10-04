package dev.laubfrosch.timedhardcore;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import org.jspecify.annotations.Nullable;

/**
 * Player list (TAB): a risk icon in front of every name (at risk / protected / safe) and the
 * viewer's own risk with an explanation in the footer. Works without the client mod; players
 * with the client mod see the green apple instead of the normal one as "protected" icon.
 */
public final class TabListStatus {

	/**
	 * Vanilla asks for the display name without a viewer ({@link #displayName}). To still give every
	 * viewer the apple icon that fits them, this holds who the packet is currently being built for.
	 */
	private static final ThreadLocal<Boolean> viewerHasClientMod = ThreadLocal.withInitial(() -> false);

	/** Last state sent to each viewer (only resent when it changes). */
	private static final Map<UUID, String> sentNames = new ConcurrentHashMap<>();
	private static final Map<UUID, String> sentFooters = new ConcurrentHashMap<>();

	private TabListStatus() {
	}

	/** Called by ServerPlayer.getTabListDisplayName (mixin). null = vanilla name. */
	public static @Nullable Component displayName(ServerPlayer player) {
		if (TimedHardcore.manager() == null) {
			return null;
		}
		Component name = PlayerTeam.formatNameForTeam(player.getTeam(), Component.literal(player.getGameProfile().name()));
		return Messages.tabListName(name, Risk.of(player), viewerHasClientMod.get());
	}

	/** Every second: updates names and footers wherever something changed. */
	public static void tick(MinecraftServer server) {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		if (players.isEmpty()) {
			return;
		}
		String allNames = players.stream().map(TabListStatus::nameSignature).collect(Collectors.joining(","));
		int online = players.size();
		int safePlayerCount = TimedHardcore.config().safePlayerCount;

		for (ServerPlayer viewer : players) {
			boolean greenIcon = ClientSync.hasClientMod(viewer);
			UUID id = viewer.getUUID();

			String nameSignature = allNames + "|" + greenIcon;
			if (!nameSignature.equals(sentNames.put(id, nameSignature))) {
				viewer.connection.send(displayNamePacket(players, greenIcon));
			}

			Risk risk = Risk.of(viewer);
			String footerSignature = risk + "|" + online + "|" + safePlayerCount + "|" + greenIcon;
			if (!footerSignature.equals(sentFooters.put(id, footerSignature))) {
				Component footer = Messages.tabListFooter(risk, online, safePlayerCount, greenIcon);
				viewer.connection.send(new ClientboundTabListPacket(Component.empty(), footer));
			}
		}
	}

	/** Everything that determines the displayed name of a player. */
	private static String nameSignature(ServerPlayer player) {
		String team = player.getTeam() == null ? "" : player.getTeam().getName();
		return player.getUUID() + ":" + Risk.of(player) + ":" + team;
	}

	private static ClientboundPlayerInfoUpdatePacket displayNamePacket(List<ServerPlayer> players, boolean greenIcon) {
		viewerHasClientMod.set(greenIcon);
		try {
			// The constructor calls displayName() for every player
			return new ClientboundPlayerInfoUpdatePacket(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME), players);
		} finally {
			viewerHasClientMod.remove();
		}
	}

	public static void forget(UUID player) {
		sentNames.remove(player);
		sentFooters.remove(player);
	}

	public static void reset() {
		sentNames.clear();
		sentFooters.clear();
	}
}
