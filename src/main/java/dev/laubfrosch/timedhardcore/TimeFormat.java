package dev.laubfrosch.timedhardcore;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TimeFormat {

	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy 'at' h:mm a", Locale.ENGLISH);

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

	/** e.g. "28 Sep 2026 at 8:00 PM", in the server's time zone. */
	public static String date(long epochMillis) {
		return DATE_FORMAT.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMillis));
	}
}
