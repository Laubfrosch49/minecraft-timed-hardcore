package dev.laubfrosch.timedhardcore;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Settings stored in config/timed-hardcore.json.
 */
public class TimedHardcoreConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve(TimedHardcore.MOD_ID + ".json");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("H:mm");

    /** Returned by {@link #revivalTime(long)} when the player does not stay dead. */
    public static final long NO_TIMED_DEATH = -1;

    public enum ReviveMode {
        /** Revival after {@link #deathDuration} minutes. */
        DURATION,
        /** Revival at the next time of day listed in {@link #reviveTimes}. */
        FIXED_TIMES
    }

    /** DURATION = after a fixed duration, FIXED_TIMES = at fixed times of day. */
    public ReviveMode reviveMode = ReviveMode.DURATION;

    /** Mode DURATION: how long a player stays dead, in minutes (decimals allowed). 0 = deaths have no consequences. */
    public double deathDuration = 1440.0;

    /** Mode FIXED_TIMES: times of day (server time zone) at which all dead players are revived. */
    public List<String> reviveTimes = new ArrayList<>(List.of("00:00", "12:00"));

    /** With at least this many players online (including the dying one) a death has no consequences. 0 = rule off. */
    public int safePlayerCount = 0;

    /** Enables the green apple and extra lives. */
    public boolean extraLivesEnabled = true;

    /** Chance (0–1) that a naturally spawned wandering trader is a healer. */
    public double healerSpawnChance = 0.2;

    /**
     * Shows dead players their remaining time in the second MOTD line (needs the client mod).
     * For this the server list response carries the UUID and remaining time of every dead player.
     */
    public boolean showRemainingTimeInMotd = true;

    /** Messages in a Discord channel for every timed death and revival. */
    public Discord discord = new Discord();

    /** The settings of the Discord messages, as their own object {@code "discord"} in the file. */
    public static class Discord {

        /**
         * Discord webhook that receives a message for every timed death and revival
         * (Discord: Server Settings → Integrations → Webhooks → Copy Webhook URL). Empty = off.
         */
        public String webhookUrl = "";

        /**
         * false sends nothing, without losing the webhook (/timedhardcore discord disable). Deaths and revivals
         * meanwhile are not sent later, and no death clips are recorded.
         */
        public boolean enabled = true;

        /** What the message shows of a death: PICTURE or CLIP. */
        public DeathMedia deathMedia = DeathMedia.PICTURE;

        /**
         * Picture of the player in the messages: black and white on death, in colour on revival.
         * {player} becomes the UUID on servers in online mode and the name otherwise; {uuid} and {name} also work.
         * Empty = no picture.
         */
        public String playerImage = "https://nmsr.nickac.dev/fullbody/{player}";

        /** Head of the player next to their name at the top of the messages. Same placeholders, empty = no head. */
        public String playerHead = "https://nmsr.nickac.dev/face/{player}";

        /** Is a webhook set? */
        public boolean configured() {
            return webhookUrl != null && !webhookUrl.isBlank();
        }

        /** Are messages sent right now: webhook set and enabled? */
        public boolean active() {
            return configured() && enabled;
        }
    }

    public enum DeathMedia {
        /** Full-body picture of the player in black and white ({@link Discord#playerImage}). */
        PICTURE,
        /**
         * The last seconds before the death as an animated GIF, recorded by the player's client mod.
         * Every player decides whether that is only the game world or also their HUD and chat, or nothing;
         * players without the client mod, or who share nothing, get the picture instead.
         */
        CLIP;

        public boolean isClip() {
            return this != PICTURE;
        }
    }

    /** Are enough players online that a death has no consequences? */
    public boolean isSafe(int onlinePlayers) {
        return safePlayerCount > 0 && onlinePlayers >= safePlayerCount;
    }

    /** The configured revive times, parsed and sorted from earliest to latest. */
    public List<LocalTime> parsedReviveTimes() {
        return reviveTimes.stream().map(TimedHardcoreConfig::parseTime).sorted().toList();
    }

    public static LocalTime parseTime(String text) {
        String trimmed = text.trim();
        try {
            if (trimmed.matches("\\d{1,2}")) {
                return LocalTime.of(Integer.parseInt(trimmed), 0);
            }
            return LocalTime.parse(trimmed, TIME_FORMAT);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Invalid time: \"" + text + "\" (expected e.g. 20:00)");
        }
    }

    /**
     * Calculates when a player who died at the given moment is revived.
     *
     * @param deathMillis time of death in milliseconds since 1970
     * @return time of revival in milliseconds since 1970, or {@link #NO_TIMED_DEATH}
     */
    public long revivalTime(long deathMillis) {
        return switch (reviveMode) {
            case DURATION -> {
                long duration = Math.round(deathDuration * 60 * 1000);
                yield duration > 0 ? deathMillis + duration : NO_TIMED_DEATH;
            }
            case FIXED_TIMES -> nextFixedTime(deathMillis);
        };
    }

    private long nextFixedTime(long deathMillis) {
        List<LocalTime> times = parsedReviveTimes();
        ZoneId zone = ZoneId.systemDefault();
        LocalDate day = Instant.ofEpochMilli(deathMillis).atZone(zone).toLocalDate();
        // The next time is always today or tomorrow; the third day covers daylight saving changes
        for (int i = 0; i < 3; i++, day = day.plusDays(1)) {
            for (LocalTime time : times) {
                long candidate = ZonedDateTime.of(day, time, zone).toInstant().toEpochMilli();
                if (candidate > deathMillis) {
                    return candidate;
                }
            }
        }
        return NO_TIMED_DEATH;
    }

    public static String formatTime(LocalTime time) {
        return time.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    /** Short description for commands, e.g. "1 day after death" or "daily at 00:00, 12:00". */
    public String describe() {
        return switch (reviveMode) {
            case DURATION -> TimeFormat.duration(Math.round(deathDuration * 60 * 1000)) + " after death";
            case FIXED_TIMES -> "daily at " + String.join(", ", parsedReviveTimes().stream().map(TimedHardcoreConfig::formatTime).toList());
        };
    }

    /** All values that influence the time of revival (used to detect changes). */
    public String revivalSignature() {
        return reviveMode + "|" + deathDuration + "|" + reviveTimes;
    }

    private void validate() {
        if (reviveMode == null) {
            throw new IllegalArgumentException("reviveMode must be DURATION or FIXED_TIMES");
        }
        if (deathDuration < 0) {
            throw new IllegalArgumentException("deathDuration must not be negative");
        }
        if (reviveTimes == null) {
            reviveTimes = new ArrayList<>();
        }
        if (reviveMode == ReviveMode.FIXED_TIMES && reviveTimes.isEmpty()) {
            throw new IllegalArgumentException("reviveTimes must not be empty in mode FIXED_TIMES");
        }
        // Throws if one of the times cannot be parsed
        parsedReviveTimes();
        if (safePlayerCount < 0) {
            throw new IllegalArgumentException("safePlayerCount must not be negative");
        }
        if (healerSpawnChance < 0 || healerSpawnChance > 1) {
            throw new IllegalArgumentException("healerSpawnChance must be between 0 and 1");
        }
        if (discord == null) {
            discord = new Discord();
        }
        validateUrl("discord.webhookUrl", discord.webhookUrl);
        if (discord.deathMedia == null) {
            throw new IllegalArgumentException("discord.deathMedia must be PICTURE or CLIP");
        }
        validateUrl("discord.playerImage", withoutPlaceholders(discord.playerImage));
        validateUrl("discord.playerHead", withoutPlaceholders(discord.playerHead));
    }

    /** Settings of older versions that moved into {@code "discord"}: old name → new name there. */
    private static final Map<String, String> MOVED_TO_DISCORD = Map.of(
        "discordWebhookUrl", "webhookUrl",
        "discordPlayerImage", "playerImage",
        "discordPlayerHead", "playerHead",
        "discordDeathMedia", "deathMedia");

    /**
     * Reads the settings from the text of the file. Files of older versions are converted: the Discord settings
     * (up to 1.1 at the top level, e.g. {@code discordWebhookUrl}) move into {@code "discord"}.
     *
     * @throws Exception if the text is invalid
     */
    public static TimedHardcoreConfig parse(String json) throws Exception {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject()) {
            return new TimedHardcoreConfig();
        }
        JsonObject object = root.getAsJsonObject();
        for (Map.Entry<String, String> moved : MOVED_TO_DISCORD.entrySet()) {
            JsonElement value = object.remove(moved.getKey());
            if (value == null) {
                continue;
            }
            if (!object.has("discord") || !object.get("discord").isJsonObject()) {
                object.add("discord", new JsonObject());
            }
            JsonObject discord = object.getAsJsonObject("discord");
            // A value already in the new place wins
            if (!discord.has(moved.getValue())) {
                discord.add(moved.getValue(), value);
            }
            TimedHardcore.LOGGER.info("Config: moved {} to discord.{}", moved.getKey(), moved.getValue());
        }
        TimedHardcoreConfig config = GSON.fromJson(object, TimedHardcoreConfig.class);
        config.validate();
        return config;
    }

    /** The settings as they are written to the file. */
    public String toJson() {
        return GSON.toJson(this);
    }

    private static @Nullable String withoutPlaceholders(@Nullable String template) {
        return template == null ? null : template.replace("{player}", "x").replace("{uuid}", "x").replace("{name}", "x");
    }

    /** Empty is allowed (= off), anything else must be an http(s) address. */
    private static void validateUrl(String field, @Nullable String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            URI uri = new URI(url.trim());
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null) {
                throw new URISyntaxException(url, "not an http(s) address");
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(field + " is not a valid address (expected https://...)");
        }
    }

    /**
     * Reads the config file. If it does not exist, it is created with default values.
     *
     * @throws Exception if the file is invalid (it is left untouched in that case)
     */
    public static TimedHardcoreConfig load() throws Exception {
        TimedHardcoreConfig config = Files.exists(FILE) ? parse(Files.readString(FILE)) : new TimedHardcoreConfig();
        // Writes missing fields back with their default values, and files of older versions in the new form
        config.save();
        return config;
    }

    public void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, toJson());
        } catch (IOException e) {
            TimedHardcore.LOGGER.error("{} could not be saved", FILE, e);
        }
    }

}
