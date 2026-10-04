package dev.laubfrosch.timedhardcore;

import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who has an extra life? Stored in &lt;world&gt;/timed-hardcore-lives.json * as { "uuid": "name" }.
 */
public final class ExtraLifeManager {

	private final JsonFile<Map<UUID, String>> file;
	private final Map<UUID, String> lives = new ConcurrentHashMap<>();

	public ExtraLifeManager(Path path) {
		this.file = new JsonFile<>(path, new TypeToken<>() {});
		try {
			load();
		} catch (IOException e) {
			file.moveAside(e.getMessage());
		}
	}

	public boolean has(UUID uuid) {
		return lives.containsKey(uuid);
	}

	/** @return false if the player already has an extra life */
	public boolean grant(UUID uuid, String name) {
		if (lives.putIfAbsent(uuid, name) != null) {
			return false;
		}
		save();
		return true;
	}

	/** Uses up the extra life if the player has one. */
	public void consume(UUID uuid) {
		if (lives.remove(uuid) != null) {
			save();
		}
	}

	/** @return the player's name as it was stored, or empty if they had no extra life */
	public synchronized Optional<String> removeByName(String name) {
		Optional<Map.Entry<UUID, String>> found = lives.entrySet().stream()
			.filter(entry -> entry.getValue().equalsIgnoreCase(name))
			.findFirst();
		found.ifPresent(entry -> {
			lives.remove(entry.getKey());
			save();
		});
		return found.map(Map.Entry::getValue);
	}

	/** All players with an extra life, sorted by name. */
	public Map<String, UUID> all() {
		Map<String, UUID> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		lives.forEach((uuid, name) -> result.put(name, uuid));
		return result;
	}

	/** @throws IOException if the file is invalid; the current data stays unchanged in that case */
	public synchronized void load() throws IOException {
		Map<UUID, String> loaded = file.read();
		if (loaded == null) {
			loaded = Map.of();
		}
		for (Map.Entry<UUID, String> entry : loaded.entrySet()) {
			if (entry.getKey() == null || entry.getValue() == null) {
				throw new IOException("Incomplete entry: " + entry.getKey());
			}
		}
		lives.keySet().retainAll(loaded.keySet());
		lives.putAll(loaded);
	}

	private synchronized void save() {
		file.write(new TreeMap<>(lives));
	}
}
