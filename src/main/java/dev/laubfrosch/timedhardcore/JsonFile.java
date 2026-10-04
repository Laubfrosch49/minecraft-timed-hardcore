package dev.laubfrosch.timedhardcore;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.jspecify.annotations.Nullable;

/**
 * A JSON file in the world folder. It is never written halfway, and a file broken by manual editing is not lost.
 */
final class JsonFile<T> {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final Path path;
	private final Type type;

	JsonFile(Path path, TypeToken<T> type) {
		this.path = path;
		this.type = type.getType();
	}

	/**
	 * @return content of the file, or null if it is missing or empty
	 * @throws IOException if the file cannot be read or is not valid JSON
	 */
	@Nullable T read() throws IOException {
		if (!Files.exists(path)) {
			return null;
		}
		try {
			return GSON.fromJson(Files.readString(path), type);
		} catch (RuntimeException e) {
			throw new IOException(e.getMessage(), e);
		}
	}

	/** Writes to a temporary file first and then swaps it in, so a crash never leaves half a file. */
	void write(T content) {
		try {
			Files.createDirectories(path.getParent());
			Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
			Files.writeString(tmp, GSON.toJson(content, type));
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			TimedHardcore.LOGGER.error("{} could not be saved", path, e);
		}
	}

	/** Moves a broken file aside so the next save does not overwrite it. */
	void moveAside(String reason) {
		Path backup = path.resolveSibling(path.getFileName() + ".broken");
		try {
			Files.move(path, backup, StandardCopyOption.REPLACE_EXISTING);
			TimedHardcore.LOGGER.error("{} is invalid ({}), moved to {}", path, reason, backup);
		} catch (IOException e) {
			TimedHardcore.LOGGER.error("Broken file {} could not be moved aside", path, e);
		}
	}

	@Override
	public String toString() {
		return path.getFileName().toString();
	}
}
