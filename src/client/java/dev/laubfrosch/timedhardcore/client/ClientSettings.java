package dev.laubfrosch.timedhardcore.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.laubfrosch.timedhardcore.TimedHardcore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Settings of the client mod in config/timed-hardcore-client.json. They belong to the player,
 * no server can change them.
 */
public class ClientSettings {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve(TimedHardcore.MOD_ID + "-client.json");

	private static ClientSettings current = new ClientSettings();

	/** What a player allows a server to record for death clips; set when joining or under "Edit Server". */
	public enum ClipConsent {
		/** Show the notice when joining a server that records death clips. */
		ASK("Ask", "Ask when joining, if the server records death clips"),
		/** Clips of the game world only, without HUD and chat. */
		WORLD_ONLY("World only", "Share death clips of the game world only, without HUD and chat"),
		/** Clips of everything the player sees, including HUD, inventory and chat. */
		WITH_HUD("With HUD", "Share death clips of everything you see, including HUD, inventory and chat"),
		/** Never record anything for this server. */
		OFF("Off", "Never share death clips with this server");

		private final Component label;
		private final Component description;

		ClipConsent(String label, String description) {
			this.label = Component.literal(label);
			this.description = Component.literal(description);
		}

		public Component label() {
			return label;
		}

		public Component description() {
			return description;
		}
	}

	/**
	 * Master switch: false = never record death clips on any server, whatever is chosen per server.
	 * Those servers show a picture of your skin instead.
	 */
	public boolean shareDeathClips = true;

	/** Your choice per server, by the address as entered. Servers not listed: ASK. */
	public Map<String, ClipConsent> deathClips = new HashMap<>();

	public static ClientSettings get() {
		return current;
	}

	public ClipConsent consent(@Nullable String server) {
		return server == null ? ClipConsent.ASK : deathClips.getOrDefault(server, ClipConsent.ASK);
	}

	public void setConsent(String server, ClipConsent consent) {
		if (consent == ClipConsent.ASK) {
			deathClips.remove(server);
		} else {
			deathClips.put(server, consent);
		}
		save();
	}

	/**
	 * What is recorded on this server: WORLD_ONLY, WITH_HUD or OFF (nothing). Nothing unless the server asks
	 * for clips ({@code requested}) and the player chose what to share, so no server can record without asking.
	 */
	public ClipConsent recording(@Nullable String server, boolean requested) {
		if (!shareDeathClips || server == null || !requested) {
			return ClipConsent.OFF;
		}
		ClipConsent consent = consent(server);
		return consent == ClipConsent.ASK ? ClipConsent.OFF : consent;
	}

	/** Should the notice be shown when joining a server that records clips? Only while the choice is "Ask". */
	public boolean needsClipNotice(String server) {
		return shareDeathClips && consent(server) == ClipConsent.ASK;
	}

	/** Reads the file, or creates it with the default values; missing fields are written back. */
	public static void load() {
		try {
			if (Files.exists(FILE)) {
				ClientSettings loaded = GSON.fromJson(Files.readString(FILE), ClientSettings.class);
				if (loaded != null) {
					current = loaded;
				}
			}
			if (current.deathClips == null) {
				current.deathClips = new HashMap<>();
			}
			// Unknown values (typos in the file) become null and are dropped: those servers are asked again
			current.deathClips.values().removeIf(consent -> consent == null);
		} catch (IOException | RuntimeException e) {
			TimedHardcore.LOGGER.error("{} could not be read, using the default values", FILE, e);
		}
		current.save();
	}

	public void save() {
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, GSON.toJson(this));
		} catch (IOException e) {
			TimedHardcore.LOGGER.error("{} could not be saved", FILE, e);
		}
	}
}
