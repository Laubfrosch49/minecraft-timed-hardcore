package dev.laubfrosch.timedhardcore.client;

import dev.laubfrosch.timedhardcore.TimeFormat;
import java.time.ZoneId;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * Shows the points in time in the server's texts (revival, death) in the player's own time zone. The server
 * writes them in its zone with the zone's name, and marks each with the exact moment ({@link TimeFormat#date});
 * here every marked piece is written again in the zone of this computer, without the name.
 *
 * <p>Used on chat messages, the kick and login screens and the graveyard.
 */
public final class LocalTimes {

	private LocalTimes() {
	}

	/** The text with all marked times in the local time zone; the same object if there are none. */
	public static Component localize(Component text) {
		return hasMarkedTime(text) ? rewrite(text, ZoneId.systemDefault()) : text;
	}

	private static boolean hasMarkedTime(Component text) {
		Style style = text.getStyle();
		if (TimeFormat.isMarked(style.getInsertion())) {
			return true;
		}
		if (style.getHoverEvent() instanceof HoverEvent.ShowText(Component hover) && hasMarkedTime(hover)) {
			return true;
		}
		for (Component sibling : text.getSiblings()) {
			if (hasMarkedTime(sibling)) {
				return true;
			}
		}
		return false;
	}

	private static MutableComponent rewrite(Component text, ZoneId zone) {
		Style style = text.getStyle();
		String local = TimeFormat.localize(style.getInsertion(), zone);
		MutableComponent copy;
		if (local != null) {
			copy = Component.literal(local);
			// The mark has done its job; shift-clicking the time in chat should not insert it
			style = style.withInsertion(null);
		} else {
			copy = MutableComponent.create(text.getContents());
		}
		if (style.getHoverEvent() instanceof HoverEvent.ShowText(Component hover)) {
			style = style.withHoverEvent(new HoverEvent.ShowText(localize(hover)));
		}
		copy.setStyle(style);
		for (Component sibling : text.getSiblings()) {
			copy.append(localize(sibling));
		}
		return copy;
	}
}
