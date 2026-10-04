package dev.laubfrosch.timedhardcore.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.laubfrosch.timedhardcore.TimedHardcore;
import java.io.IOException;
import java.io.InputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.ARGB;
import org.jspecify.annotations.Nullable;

/**
 * Texture of the Wandering Healer. Created on first use by recolouring the vanilla wandering trader
 * texture, so the mod does not have to ship any Mojang textures:
 * blue robe -> cream white, red trim -> green, dark green shirt -> linen, green cross on the chest.
 */
public final class HealerTexture {

	private static final Identifier VANILLA = Identifier.withDefaultNamespace("textures/entity/wandering_trader/wandering_trader.png");
	private static final Identifier ID = TimedHardcore.id("textures/entity/wandering_healer.png");

	private static boolean created;
	private static boolean failed;

	private HealerTexture() {
	}

	/** @return the healer texture, or null if it could not be created (the vanilla texture is used then) */
	public static @Nullable Identifier get() {
		if (!created && !failed) {
			create();
		}
		return created ? ID : null;
	}

	private static void create() {
		Minecraft minecraft = Minecraft.getInstance();
		try {
			Resource resource = minecraft.getResourceManager().getResource(VANILLA)
				.orElseThrow(() -> new IOException("Texture missing: " + VANILLA));
			NativeImage image;
			try (InputStream in = resource.open()) {
				image = NativeImage.read(in);
			}
			recolor(image);
			// Dynamic textures survive a resource reload
			minecraft.getTextureManager().register(ID, new DynamicTexture(ID::toString, image));
			created = true;
		} catch (Exception e) {
			failed = true;
			TimedHardcore.LOGGER.error("Could not create the healer texture, using the vanilla texture", e);
		}
	}

	private static void recolor(NativeImage image) {
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				int argb = image.getPixel(x, y);
				int alpha = ARGB.alpha(argb);
				if (alpha == 0) {
					continue;
				}
				int rgb = recolorPixel(ARGB.red(argb), ARGB.green(argb), ARGB.blue(argb));
				if (rgb != -1) {
					image.setPixel(x, y, ARGB.color(alpha, rgb));
				}
			}
		}
		if (image.getWidth() < 64 || image.getHeight() < 64) {
			return;
		}
		// Green pharmacy cross on the chest (front of the body: x 22-29, y 26-37)
		int cross = ARGB.color(255, 46, 190, 80);
		int crossDark = ARGB.color(255, 24, 120, 50);
		for (int y = 26; y <= 30; y++) {
			image.setPixel(25, y, cross);
			image.setPixel(26, y, cross);
		}
		for (int x = 23; x <= 28; x++) {
			image.setPixel(x, 27, cross);
			image.setPixel(x, 28, cross);
		}
		image.setPixel(25, 30, crossDark);
		image.setPixel(26, 30, crossDark);
		image.setPixel(23, 28, crossDark);
		image.setPixel(28, 28, crossDark);
	}

	/** @return new colour as RGB, or -1 if the pixel stays as it is (skin, gold, leather …) */
	private static int recolorPixel(int r, int g, int b) {
		int max = Math.max(r, Math.max(g, b));
		int min = Math.min(r, Math.min(g, b));
		float value = max / 255f;
		float saturation = max == 0 ? 0 : (max - min) / (float) max;
		float hue = hue(r, g, b, max, min);

		if (hue >= 190 && hue <= 250 && saturation > 0.2f) {
			// Blue (robe, hood, sleeves) -> cream white, brightness is kept as shading
			return scaled(226, 220, 200, 0.55f + 0.55f * Math.min(1f, value / 0.62f));
		}
		if ((hue <= 12 || hue >= 345) && saturation > 0.6f && value < 0.75f) {
			// Strong red (trim) -> green
			return scaled(40, 150, 60, value / 0.55f);
		}
		if (hue >= 70 && hue <= 150 && saturation > 0.15f && value < 0.5f) {
			// Dark green shirt -> light linen
			return scaled(170, 176, 160, 0.75f + 0.5f * (value / 0.45f));
		}
		return -1;
	}

	private static float hue(int r, int g, int b, int max, int min) {
		if (max == min) {
			return 0;
		}
		float delta = max - min;
		float h;
		if (max == r) {
			h = ((g - b) / delta) % 6;
		} else if (max == g) {
			h = (b - r) / delta + 2;
		} else {
			h = (r - g) / delta + 4;
		}
		h *= 60;
		return h < 0 ? h + 360 : h;
	}

	private static int scaled(int r, int g, int b, float factor) {
		return (clamp(r * factor) << 16) | (clamp(g * factor) << 8) | clamp(b * factor);
	}

	private static int clamp(float value) {
		return Math.clamp((int) value, 0, 255);
	}
}
