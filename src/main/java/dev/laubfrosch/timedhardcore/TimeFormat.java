package dev.laubfrosch.timedhardcore;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;

/**
 * Durations and points in time as text.
 *
 * <p>Points in time are written in the server's time zone, with its name ("8:00 PM UTC"), so players without
 * the client mod know which zone is meant. Each one carries the exact moment in its (otherwise unused in these
 * texts) insertion, so the client mod can show it in the player's own time zone instead ({@link #localize}).
 */
public final class TimeFormat {

	/** Start of the insertion that marks a point in time; followed by the kind and the time in milliseconds. */
	private static final String MARKER = TimedHardcore.MOD_ID + ":time:";
	private static final String DATE = "date:";
	private static final String CLOCK = "clock:";

	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy 'at' h:mm a", Locale.ENGLISH);
	private static final DateTimeFormatter CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
	private static final DateTimeFormatter ZONE_FORMAT = DateTimeFormatter.ofPattern("zzz", Locale.ENGLISH);

	private TimeFormat() {
	}

	/** e.g. "3 h 12 min" or "4 min 30 s" */
	public static String duration(long millis) {

		long totalSeconds = (millis + 999) / 1000;
		long days = totalSeconds / 86400;
		long hours = totalSeconds % 86400 / 3600;
		long minutes = totalSeconds % 3600 / 60;
		long seconds = totalSeconds % 60;

		List<String> parts = new ArrayList<>();
		if (days > 0) {
			parts.add(days + (days == 1 ? " day" : " days"));
		}
		if (hours > 0) {
			parts.add(hours + (hours == 1 ? " hour" : " hours"));
		}
		if (minutes > 0) {
			parts.add(minutes + " min");
		}
		// Seconds only matter when less than an hour is left
		if (days == 0 && hours == 0 && (seconds > 0 || parts.isEmpty())) {
			parts.add(seconds + " sec");
		}
		return String.join(" ", parts);
	}

	/** e.g. "28 Sep 2026 at 8:00 PM UTC"; the client mod shows it in the player's time zone, without the name. */
	public static MutableComponent date(long epochMillis) {
		ZoneId zone = ZoneId.systemDefault();
		return marked(date(epochMillis, zone) + " " + zoneName(epochMillis, zone), DATE, epochMillis);
	}

	/**
	 * Time of day, e.g. "20:00", or "20:00 UTC" with {@code withZone} (for the last of several times in a row).
	 * The client mod shows it in the player's time zone, without the name.
	 */
	public static MutableComponent clock(long epochMillis, boolean withZone) {
		ZoneId zone = ZoneId.systemDefault();
		String text = clock(epochMillis, zone) + (withZone ? " " + zoneName(epochMillis, zone) : "");
		return marked(text, CLOCK, epochMillis);
	}

	private static MutableComponent marked(String text, String kind, long epochMillis) {
		return Component.literal(text).withStyle(style -> style.withInsertion(MARKER + kind + epochMillis));
	}

	public static String date(long epochMillis, ZoneId zone) {
		return DATE_FORMAT.withZone(zone).format(Instant.ofEpochMilli(epochMillis));
	}

	public static String clock(long epochMillis, ZoneId zone) {
		return CLOCK_FORMAT.withZone(zone).format(Instant.ofEpochMilli(epochMillis));
	}

	/** Short name of the zone at that moment, e.g. "UTC", "CET" or "CEST". */
	private static String zoneName(long epochMillis, ZoneId zone) {
		return ZONE_FORMAT.withZone(zone).format(Instant.ofEpochMilli(epochMillis));
	}

	/**
	 * For the client mod: the text of a point in time marked by {@link #date} or {@link #clock}, in the given
	 * time zone and without its name (it is the player's own).
	 *
	 * @param insertion the insertion of a piece of text
	 * @return the text, or null if the insertion is no such mark
	 */
	public static @Nullable String localize(@Nullable String insertion, ZoneId zone) {
		if (insertion == null || !insertion.startsWith(MARKER)) {
			return null;
		}
		String mark = insertion.substring(MARKER.length());
		try {
			if (mark.startsWith(DATE)) {
				return date(Long.parseLong(mark.substring(DATE.length())), zone);
			}
			if (mark.startsWith(CLOCK)) {
				return clock(Long.parseLong(mark.substring(CLOCK.length())), zone);
			}
		} catch (NumberFormatException e) {
			return null;
		}
		return null;
	}

	/** Is this the insertion of a marked point in time? */
	public static boolean isMarked(@Nullable String insertion) {
		return insertion != null && insertion.startsWith(MARKER);
	}
}
