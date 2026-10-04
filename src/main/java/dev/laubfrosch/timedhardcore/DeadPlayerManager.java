package dev.laubfrosch.timedhardcore;

import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Keeps track of who is dead and until when.
 */
public final class DeadPlayerManager {

	public static class DeadPlayer {

		public transient UUID uuid;
		public String name;
		public long diedAt;
		public long reviveAt;
		/** Death message as text JSON, shown in the graveyard tab. May be missing. */
		public @Nullable JsonElement deathMessage;
		/** The player is alive again but has not joined since. Kept so the server list can tell them. */
		public boolean revived;

		public long remainingMillis() {
			return Math.max(0, reviveAt - System.currentTimeMillis());
		}

		/** Is the player still dead? */
		public boolean isActive() {
			return !revived && remainingMillis() > 0;
		}
	}

	private final JsonFile<Map<UUID, DeadPlayer>> file;
	private final Map<UUID, DeadPlayer> deadPlayers = new ConcurrentHashMap<>();

	public DeadPlayerManager(Path path) {
		this.file = new JsonFile<>(path, new TypeToken<>() {});
		try {
			load();
		} catch (IOException e) {
			file.moveAside(e.getMessage());
		}
	}

	public void markDead(UUID uuid, String name, long diedAt, long reviveAt) {
		markDead(uuid, name, diedAt, reviveAt, null);
	}

	public void markDead(UUID uuid, String name, long diedAt, long reviveAt, @Nullable JsonElement deathMessage) {
		DeadPlayer deadPlayer = new DeadPlayer();
		deadPlayer.uuid = uuid;
		deadPlayer.name = name;
		deadPlayer.diedAt = diedAt;
		deadPlayer.reviveAt = reviveAt;
		deadPlayer.deathMessage = deathMessage;
		deadPlayers.put(uuid, deadPlayer);
		save();
	}

	/**
	 * Returns the entry if the player is still dead. Players whose time is up stay in place until {@link #collectExpired()} picks them up.
	 */
	public Optional<DeadPlayer> getActive(UUID uuid) {
		return Optional.ofNullable(deadPlayers.get(uuid)).filter(DeadPlayer::isActive);
	}

	/** Marks the players whose time is up as revived and returns them; they have just come back to life. */
	public synchronized List<DeadPlayer> collectExpired() {
		List<DeadPlayer> expired = deadPlayers.values().stream().filter(deadPlayer -> !deadPlayer.revived && !deadPlayer.isActive()).toList();
		if (!expired.isEmpty()) {
			expired.forEach(deadPlayer -> deadPlayer.revived = true);
			save();
		}
		return expired;
	}

	/**
	 * Recalculates the revival of all dead players after the settings changed, each from their time of death.
	 * Players who are already due afterward are revived by the next {@link #collectExpired()}.
	 *
	 * @return number of players whose revival changed
	 */
	public synchronized int recalculate(TimedHardcoreConfig config) {
		int changed = 0;
		for (DeadPlayer deadPlayer : deadPlayers.values()) {
			if (!deadPlayer.isActive()) {
				continue;
			}
			long revivalTime = config.revivalTime(deadPlayer.diedAt);
			long reviveAt = revivalTime == TimedHardcoreConfig.NO_TIMED_DEATH ? deadPlayer.diedAt : revivalTime;
			if (reviveAt != deadPlayer.reviveAt) {
				deadPlayer.reviveAt = reviveAt;
				changed++;
			}
		}
		if (changed > 0) {
			save();
		}
		return changed;
	}

	/** Remembers the current name of a dead player; it may have changed since the death. */
	public void updateName(UUID uuid, String name) {
		DeadPlayer deadPlayer = deadPlayers.get(uuid);
		if (deadPlayer != null && !name.equals(deadPlayer.name)) {
			deadPlayer.name = name;
			save();
		}
	}

	/** All players who are still dead, sorted by remaining time. */
	public List<DeadPlayer> stillDead() {
		return deadPlayers.values().stream()
			.filter(DeadPlayer::isActive)
			.sorted(Comparator.comparingLong(deadPlayer -> deadPlayer.reviveAt))
			.toList();
	}

	/**
	 * Revives a player by name (case-insensitive).
	 *
	 * @return the revived player, or empty if the player was not dead
	 */
	public synchronized Optional<DeadPlayer> revive(String name) {
		Optional<DeadPlayer> found = deadPlayers.values().stream()
			.filter(deadPlayer -> !deadPlayer.revived && deadPlayer.name.equalsIgnoreCase(name))
			.findFirst();
		if (found.isEmpty()) {
			return found;
		}
		DeadPlayer deadPlayer = found.get();
		// If the time was already up, the player was not dead any more
		boolean wasDead = deadPlayer.isActive();
		deadPlayer.revived = true;
		save();
		return wasDead ? found : Optional.empty();
	}

	/** Players who are alive again but have not joined since. */
	public List<DeadPlayer> awaitingReturn() {
		return deadPlayers.values().stream().filter(deadPlayer -> deadPlayer.revived).toList();
	}

	/** The revived player has joined again, so their entry is no longer needed. */
	public synchronized void welcomeBack(UUID uuid) {
		DeadPlayer deadPlayer = deadPlayers.get(uuid);
		if (deadPlayer != null && deadPlayer.revived) {
			deadPlayers.remove(uuid);
			save();
		}
	}

	/**
	 * Reads the file again (e.g. after it was edited by hand).
	 *
	 * @throws IOException if the file is invalid; the current entries stay unchanged in that case
	 */
	public synchronized void load() throws IOException {
		Map<UUID, DeadPlayer> loaded = file.read();
		if (loaded == null) {
			loaded = Map.of();
		}
		for (Map.Entry<UUID, DeadPlayer> entry : loaded.entrySet()) {
			DeadPlayer deadPlayer = entry.getValue();
			if (entry.getKey() == null || deadPlayer == null || deadPlayer.name == null) {
				throw new IOException("Incomplete entry: " + entry.getKey());
			}
			deadPlayer.uuid = entry.getKey();
		}
		// Do not clear first, so that parallel login checks never see an empty list
		deadPlayers.keySet().retainAll(loaded.keySet());
		deadPlayers.putAll(loaded);
	}

	private synchronized void save() {
		file.write(deadPlayers);
	}
}
