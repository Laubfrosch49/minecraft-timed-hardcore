package dev.laubfrosch.timedhardcore;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

/**
 * Posts timed deaths and revivals to a Discord channel through a webhook ({@link TimedHardcoreConfig#discordWebhookUrl}).
 *
 * <p>Each message is an embed with a picture of the player ({@link TimedHardcoreConfig#discordPlayerImage}):
 * black and white on death, in colour on revival. The pictures are downloaded here and uploaded together
 * with the message: Discord cannot change the colours of an image itself, and an uploaded picture stays
 * even if the render service is gone one day.
 *
 * <p>Everything that talks to the network runs on a single background thread: the server never waits
 * for Discord, and the messages arrive in the order in which they happened.
 */
public final class DiscordWebhook {

	/** Side stripe of a death: grey, like the picture. */
	public static final int DEATH_COLOR = 0x6C757D;
	public static final int REVIVAL_COLOR = Colors.GREEN;

	private static final String IMAGE_NAME = "player.png";
	private static final String HEAD_NAME = "head.png";
	/** Set by the render service NMSR when it sends a default skin because it could not load the real one. */
	private static final String FALLBACK_HEADER = "x-nmsr-fallback";
	private static final int PICTURE_ATTEMPTS = 3;
	private static final Duration PICTURE_RETRY_DELAY = Duration.ofSeconds(1);
	private static final String USER_AGENT = "Timed Hardcore (Minecraft server mod)";
	private static final Duration TIMEOUT = Duration.ofSeconds(15);
	private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
	/** Discord answers 429 when too many messages are sent at once; we wait at most this long before trying again. */
	private static final Duration MAX_RETRY_WAIT = Duration.ofSeconds(30);
	/** Waits before the second and third attempt, for network errors and Discord's own server errors (5xx). */
	private static final Duration[] RETRY_DELAYS = {Duration.ofSeconds(2), Duration.ofSeconds(10)};

	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "Timed Hardcore Discord webhook");
		// Must not keep the server process alive after it stopped
		thread.setDaemon(true);
		return thread;
	});

	private DiscordWebhook() {
	}

	// --- Events ---

	/** A player died and stays dead until {@code reviveAt}. */
	public static void announceDeath(MinecraftServer server, GameProfile profile, Component deathMessage, long diedAt, long reviveAt) {
		TimedHardcoreConfig config = TimedHardcore.config();
		URI webhook = webhookUri(config);
		if (webhook == null) {
			return;
		}
		// Collected here on the server thread, the background thread only gets plain values
		String imageUrl = pictureUrl(config.discordPlayerImage, server, profile.id(), profile.name());
		String headUrl = pictureUrl(config.discordPlayerHead, server, profile.id(), profile.name());
		JsonObject payload = deathPayload(profile.name(), deathMessage.getString(), diedAt, reviveAt);

		send(webhook, payload, imageUrl, headUrl, true, "death of " + profile.name());
	}

	/**
	 * A dead player is alive again.
	 *
	 * @param revivedBy name of the operator who revived them, or null if their time was up
	 */
	public static void announceRevival(MinecraftServer server, DeadPlayerManager.DeadPlayer deadPlayer, @Nullable String revivedBy) {
		TimedHardcoreConfig config = TimedHardcore.config();
		URI webhook = webhookUri(config);
		if (webhook == null) {
			return;
		}
		Component deathMessage = Graveyard.decodeDeathMessage(server.registryAccess(), deadPlayer.deathMessage);
		// After a server downtime the time may be up long ago; the player was dead until then, not until now
		long revivedAt = revivedBy == null ? Math.min(System.currentTimeMillis(), deadPlayer.reviveAt) : System.currentTimeMillis();
		JsonObject payload = revivalPayload(deadPlayer.name, deathMessage == null ? null : deathMessage.getString(),
			deadPlayer.diedAt, revivedAt, revivedBy);

		send(webhook, payload,
			pictureUrl(config.discordPlayerImage, server, deadPlayer.uuid, deadPlayer.name),
			pictureUrl(config.discordPlayerHead, server, deadPlayer.uuid, deadPlayer.name),
			false, "revival of " + deadPlayer.name);
	}

	/**
	 * Fetches the pictures and posts the message, on the background thread. An unexpected error is logged
	 * instead of vanishing with the task.
	 *
	 * @param grey turn the pictures black and white
	 * @param what for the log, e.g. "death of Steve"
	 */
	private static void send(URI webhook, JsonObject payload, @Nullable String imageUrl, @Nullable String headUrl, boolean grey, String what) {
		WORKER.execute(() -> {
			try {
				Map<String, byte[]> files = new LinkedHashMap<>();
				if (imageUrl != null) {
					setImage(payload, attach(files, IMAGE_NAME, imageUrl, grey));
				}
				if (headUrl != null) {
					setAuthorIcon(payload, attach(files, HEAD_NAME, headUrl, grey));
				}
				post(webhook, payload, files, what);
			} catch (RuntimeException e) {
				TimedHardcore.LOGGER.error("Discord message about the {} failed", what, e);
			}
		});
	}

	/**
	 * Downloads a picture and adds it to the files to upload. Uploading instead of linking makes the message
	 * independent of the render service: Discord would otherwise fetch the link itself, including whatever
	 * the service has cached, and the picture would disappear if the service ever does.
	 *
	 * @return the address for the message: the uploaded file, or the link itself if the picture could not be
	 *     downloaded or converted (a picture in colour, e.g. a WebP that cannot be turned grey, is better than none)
	 */
	private static String attach(Map<String, byte[]> files, String fileName, String url, boolean grey) {
		byte[] picture = download(url);
		if (grey) {
			picture = blackAndWhite(picture);
		}
		if (picture == null || !isPng(picture)) {
			return url;
		}
		files.put(fileName, picture);
		return "attachment://" + fileName;
	}

	private static boolean isPng(byte[] data) {
		return data.length > 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G';
	}

	// --- Messages ---

	/**
	 * The death message: who died and how, how long they stay dead and when they come back.
	 * The time of revival is a Discord timestamp, so everyone sees it in their own time zone and with a live countdown.
	 */
	public static JsonObject deathPayload(String name, String deathMessage, long diedAt, long reviveAt) {
		JsonObject embed = embed(name + " died", deathMessage, DEATH_COLOR, diedAt);

		JsonArray fields = new JsonArray();
		fields.add(field("Dead for", TimeFormat.duration(reviveAt - diedAt)));
		fields.add(field("Revival", timestamp(reviveAt, 'f') + " (" + timestamp(reviveAt, 'R') + ")"));
		embed.add("fields", fields);
		return payload(embed);
	}

	/** The revival message: who is back, who revived them and what killed them last time. */
	public static JsonObject revivalPayload(String name, @Nullable String deathMessage, long diedAt, long revivedAt, @Nullable String revivedBy) {
		JsonObject embed = embed(name + " is alive again", revivedBy == null
			? "Their time is up. They can join again."
			: "Revived by " + revivedBy + ". They can join again.", REVIVAL_COLOR, revivedAt);

		JsonArray fields = new JsonArray();
		fields.add(field("Was dead for", TimeFormat.duration(revivedAt - diedAt)));
		if (deathMessage != null) {
			fields.add(field("Cause of death", escape(deathMessage)));
		}
		embed.add("fields", fields);
		return payload(embed);
	}

	/**
	 * Head and headline at the top (as author, e.g. "Steve died"), below it the main sentence as a bold quote.
	 * No title: Discord does not need one when there is a description.
	 */
	private static JsonObject embed(String headline, String sentence, int color, long timestamp) {
		JsonObject embed = new JsonObject();
		JsonObject author = new JsonObject();
		// The author name is plain text in Discord, no Markdown: nothing to escape
		author.addProperty("name", headline);
		embed.add("author", author);
		embed.addProperty("description", "> **" + escape(sentence) + "**");
		embed.addProperty("color", color);
		embed.addProperty("timestamp", Instant.ofEpochMilli(timestamp).toString());
		JsonObject footer = new JsonObject();
		footer.addProperty("text", "Timed Hardcore");
		embed.add("footer", footer);
		return embed;
	}

	/** A field on its own line (not inline, so fields are listed below each other). */
	private static JsonObject field(String name, String value) {
		JsonObject field = new JsonObject();
		field.addProperty("name", name);
		field.addProperty("value", value);
		return field;
	}

	private static JsonObject payload(JsonObject embed) {
		JsonObject payload = new JsonObject();
		JsonArray embeds = new JsonArray();
		embeds.add(embed);
		payload.add("embeds", embeds);
		// Names and death messages come from players: "@everyone" in a name must not ping anybody
		JsonObject allowedMentions = new JsonObject();
		allowedMentions.add("parse", new JsonArray());
		payload.add("allowed_mentions", allowedMentions);
		return payload;
	}

	private static void setImage(JsonObject payload, String url) {
		JsonObject image = new JsonObject();
		image.addProperty("url", url);
		payload.getAsJsonArray("embeds").get(0).getAsJsonObject().add("image", image);
	}

	private static void setAuthorIcon(JsonObject payload, String url) {
		payload.getAsJsonArray("embeds").get(0).getAsJsonObject().getAsJsonObject("author").addProperty("icon_url", url);
	}

	/** Discord timestamp, shown in the time zone of the reader. Styles: f = date and time, R = relative ("in 5 hours"). */
	private static String timestamp(long epochMillis, char style) {
		return "<t:" + epochMillis / 1000 + ":" + style + ">";
	}

	/** Player names like "jeb_" would otherwise turn into italics. */
	public static String escape(String text) {
		return text.replaceAll("([\\\\*_~`|>\\[\\]])", "\\\\$1");
	}

	// --- Player picture ---

	/**
	 * Fills in a picture URL from the config. {player} is the UUID on servers in online mode and the name otherwise,
	 * because there the UUIDs are not the ones of the Minecraft accounts.
	 */
	private static @Nullable String pictureUrl(@Nullable String template, MinecraftServer server, UUID uuid, String name) {
		if (template == null || template.isBlank()) {
			return null;
		}
		String uuidText = uuid.toString().replace("-", "");
		String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8);
		return template.trim()
			.replace("{player}", server.usesAuthentication() ? uuidText : encodedName)
			.replace("{uuid}", uuidText)
			.replace("{name}", encodedName);
	}

	/**
	 * A new client for every message. Deaths are rare, so a kept connection would mostly sit idle,
	 * and a connection the other side has closed in the meantime can make the next request hang or fail.
	 */
	private static HttpClient newClient() {
		return HttpClient.newBuilder()
			.connectTimeout(TIMEOUT)
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	}

	/**
	 * Downloads a picture of a player.
	 *
	 * <p>When the render service cannot load a skin at that moment, it answers with a default skin instead
	 * and marks the answer with the header {@value #FALLBACK_HEADER}. Its CDN keeps that answer for hours
	 * under the same address, so a single hiccup would show the wrong skin for a long time. Such an answer
	 * is therefore asked for again with an extra query parameter, which skips the cache. If the stand-in
	 * comes back every time (a name without a Minecraft account, on servers in offline mode), it is used.
	 */
	private static byte @Nullable [] download(String url) {
		byte[] standIn = null;
		try (HttpClient http = newClient()) {
			for (int attempt = 0; attempt < PICTURE_ATTEMPTS; attempt++) {
				String address = url;
				if (attempt > 0) {
					Thread.sleep(PICTURE_RETRY_DELAY.toMillis());
					address += (url.contains("?") ? "&" : "?") + "retry=" + System.currentTimeMillis();
				}
				HttpRequest request = HttpRequest.newBuilder(URI.create(address))
					.timeout(TIMEOUT)
					.header("User-Agent", USER_AGENT)
					.header("Accept", "image/png")
					.GET()
					.build();
				HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
				if (response.statusCode() != 200 || response.body().length > MAX_IMAGE_BYTES) {
					TimedHardcore.LOGGER.warn("Player picture for Discord not available (HTTP {})", response.statusCode());
					return standIn;
				}
				if (!response.headers().firstValue(FALLBACK_HEADER).map("true"::equalsIgnoreCase).orElse(false)) {
					return response.body();
				}
				standIn = response.body();
			}
			return standIn;
		} catch (IOException | IllegalArgumentException e) {
			TimedHardcore.LOGGER.warn("Player picture for Discord could not be downloaded: {}", e.toString());
			return standIn;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	/**
	 * Turns a picture grey, keeping its transparency.
	 *
	 * @return the grey picture as PNG, or null if the picture could not be read (ImageIO reads PNG, JPEG, GIF and BMP)
	 */
	public static byte @Nullable [] blackAndWhite(byte @Nullable [] image) {
		if (image == null) {
			return null;
		}
		try {
			BufferedImage source = ImageIO.read(new ByteArrayInputStream(image));
			if (source == null) {
				return null;
			}
			BufferedImage grey = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
			for (int y = 0; y < source.getHeight(); y++) {
				for (int x = 0; x < source.getWidth(); x++) {
					int argb = source.getRGB(x, y);
					int red = argb >> 16 & 0xFF;
					int green = argb >> 8 & 0xFF;
					int blue = argb & 0xFF;
					// Perceived brightness (ITU-R BT.601)
					int luminance = (int) Math.round(0.299 * red + 0.587 * green + 0.114 * blue);
					grey.setRGB(x, y, argb & 0xFF000000 | luminance << 16 | luminance << 8 | luminance);
				}
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(grey, "png", out);
			return out.toByteArray();
		} catch (IOException | RuntimeException | LinkageError e) {
			// LinkageError: a Java runtime without the desktop module has no ImageIO
			TimedHardcore.LOGGER.warn("Player picture for Discord could not be turned grey: {}", e.toString());
			return null;
		}
	}

	// --- Sending ---

	/**
	 * Sends a message to the webhook and waits for the answer. With files the request is multipart,
	 * and the message can use each file as "attachment://" + its name.
	 *
	 * <p>Network errors, Discord's own server errors (5xx) and rate limits (429) are tried again, up to three attempts.
	 * The webhook URL is never logged: it contains the secret token.
	 *
	 * @param pngs PNG pictures to upload, by file name; may be empty
	 * @param what for the log, e.g. "death of Steve"
	 * @return the last HTTP status, or -1 if Discord could not be reached
	 */
	public static int post(URI webhook, JsonObject payload, Map<String, byte[]> pngs, String what) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(webhook)
			.timeout(TIMEOUT)
			.header("User-Agent", USER_AGENT);
		if (pngs.isEmpty()) {
			builder.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(payload.toString()));
		} else {
			String boundary = "TimedHardcore" + UUID.randomUUID().toString().replace("-", "");
			builder.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, payload, pngs)));
		}
		HttpRequest request = builder.build();

		String problem = "";
		int status = -1;
		try (HttpClient http = newClient()) {
			for (int attempt = 0; attempt <= RETRY_DELAYS.length; attempt++) {
				if (attempt > 0) {
					Thread.sleep(status == 429 ? 0 : RETRY_DELAYS[attempt - 1].toMillis());
				}
				try {
					HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
					status = response.statusCode();
					if (status / 100 == 2) {
						return status;
					}
					String body = response.body();
					problem = "HTTP " + status + ": " + (body.length() > 300 ? body.substring(0, 300) + "..." : body);
					if (status == 429) {
						Thread.sleep(retryAfter(response).toMillis());
					} else if (status < 500) {
						// Invalid message or webhook deleted: trying again would not help
						break;
					}
				} catch (IOException e) {
					status = -1;
					problem = e.toString();
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return -1;
		}
		TimedHardcore.LOGGER.warn("Discord message about the {} could not be sent ({})", what, problem);
		return status;
	}

	private static byte[] multipart(String boundary, JsonObject payload, Map<String, byte[]> pngs) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.writeBytes(("--" + boundary + "\r\n"
			+ "Content-Disposition: form-data; name=\"payload_json\"\r\n"
			+ "Content-Type: application/json\r\n\r\n"
			+ payload + "\r\n").getBytes(StandardCharsets.UTF_8));
		int index = 0;
		for (Map.Entry<String, byte[]> png : pngs.entrySet()) {
			body.writeBytes(("--" + boundary + "\r\n"
				+ "Content-Disposition: form-data; name=\"files[" + index++ + "]\"; filename=\"" + png.getKey() + "\"\r\n"
				+ "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
			body.writeBytes(png.getValue());
			body.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
		}
		body.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
		return body.toByteArray();
	}

	private static Duration retryAfter(HttpResponse<?> response) {
		try {
			double seconds = Double.parseDouble(response.headers().firstValue("Retry-After").orElse("5"));
			Duration wait = Duration.ofMillis((long) (seconds * 1000));
			return wait.compareTo(MAX_RETRY_WAIT) > 0 ? MAX_RETRY_WAIT : wait;
		} catch (NumberFormatException e) {
			return Duration.ofSeconds(5);
		}
	}

	private static @Nullable URI webhookUri(TimedHardcoreConfig config) {
		String url = config.discordWebhookUrl;
		return url == null || url.isBlank() ? null : URI.create(url.trim());
	}
}
