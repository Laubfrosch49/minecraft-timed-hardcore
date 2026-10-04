package dev.laubfrosch.timedhardcore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.status.ServerStatus;

/**
 * Shows dead players in the server list how long they are still dead, and revived players that
 * they can join again.
 *
 * <p>The server list ping carries no player identity, so the server cannot tell who is asking. Instead
 * it attaches the list of these players invisibly to the MOTD ({@link #publish}). The client mod looks
 * for its own player in that list and rewrites the second line itself ({@link #personalize}).
 * Clients without the mod see the normal MOTD.
 */
public final class ServerListMotd {

	/** Marks the invisible part of the MOTD that carries the list. */
	private static final String DATA_PREFIX = TimedHardcore.MOD_ID + ":";
	private static final String ENTRY_SEPARATOR = ";";
	private static final String VALUE_SEPARATOR = "=";
	/** Sent instead of a remaining time for players who are alive again but have not joined since. */
	private static final long REVIVED = 0;

	private ServerListMotd() {
	}

	/** Server side: attaches the dead and the revived players to the MOTD, unless that is switched off in the config. */
	public static ServerStatus publish(ServerStatus status) {
		DeadPlayerManager manager = TimedHardcore.manager();
		if (manager == null || !TimedHardcore.config().showRemainingTimeInMotd) {
			return status;
		}
		List<DeadPlayerManager.DeadPlayer> players = new ArrayList<>(manager.stillDead());
		players.addAll(manager.awaitingReturn());
		if (players.isEmpty()) {
			return status;
		}
		Component description = Component.empty().append(status.description()).append(invisibleData(players));
		return new ServerStatus(description, status.players(), status.version(), status.favicon(), status.enforcesSecureChat());
	}

	/**
	 * An empty text, so nothing is drawn. The list sits in its "insertion" style, which only has an
	 * effect in chat. It holds the remaining time instead of the end time, so a wrong clock on the
	 * client does not matter.
	 */
	private static Component invisibleData(List<DeadPlayerManager.DeadPlayer> players) {
		String data = players.stream()
			.map(player -> player.uuid + VALUE_SEPARATOR + (player.revived ? REVIVED : player.remainingMillis()))
			.collect(Collectors.joining(ENTRY_SEPARATOR));
		return Component.literal("").withStyle(style -> style.withInsertion(DATA_PREFIX + data));
	}

	/**
	 * Client side: if this player is in the list, the second MOTD line shows their remaining time or
	 * that they were revived.
	 *
	 * @param ownIds the UUIDs this player may have on the server: the one of the account, and the one
	 *               a server in offline mode derives from the name
	 */
	public static Component personalize(Component description, Collection<UUID> ownIds) {
		OptionalLong found = remainingMillis(description, ownIds);
		if (found.isEmpty()) {
			return description;
		}
		long remainingMillis = found.getAsLong();
		String firstLine = description.getString().split("\n", 2)[0];
		return Component.empty()
			.append(Component.literal(firstLine))
			.append("\n")
			.append(remainingMillis == REVIVED ? Messages.motdRevived() : Messages.motdSecondLine(remainingMillis));
	}

	private static OptionalLong remainingMillis(Component description, Collection<UUID> ownIds) {
		String data = description.getStyle().getInsertion();
		if (data != null && data.startsWith(DATA_PREFIX)) {
			return parse(data.substring(DATA_PREFIX.length()), ownIds);
		}
		for (Component sibling : description.getSiblings()) {
			OptionalLong found = remainingMillis(sibling, ownIds);
			if (found.isPresent()) {
				return found;
			}
		}
		return OptionalLong.empty();
	}

	private static OptionalLong parse(String data, Collection<UUID> ownIds) {
		List<String> wanted = ownIds.stream().map(UUID::toString).toList();
		for (String entry : data.split(ENTRY_SEPARATOR)) {
			String[] parts = entry.split(VALUE_SEPARATOR, 2);
			if (parts.length == 2 && wanted.contains(parts[0])) {
				try {
					return OptionalLong.of(Long.parseLong(parts[1]));
				} catch (NumberFormatException e) {
					// The data comes from a server and may be anything; an unreadable entry is ignored
					return OptionalLong.empty();
				}
			}
		}
		return OptionalLong.empty();
	}
}
