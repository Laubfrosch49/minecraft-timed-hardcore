package dev.laubfrosch.timedhardcore.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.laubfrosch.timedhardcore.GifEncoder;
import dev.laubfrosch.timedhardcore.TimedHardcore;
import dev.laubfrosch.timedhardcore.client.ClientSettings.ClipConsent;
import dev.laubfrosch.timedhardcore.network.TimedHardcoreNetwork;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * Records the last seconds of the game for the death clip, while a server asks for it
 * and the player allows it ({@link ClientSettings#recording}).
 *
 * <p>Ten times a second the finished picture of the world (for "World only" before chat and HUD are drawn on top,
 * for "With HUD" after) is scaled down on the graphics card and copied into memory; only the last {@link #CLIP_MILLIS}
 * are kept, nothing is written to disk. When the server reports a timed death, recording goes on for
 * {@link #AFTER_DEATH_MILLIS} so the clip ends with the death screen and its death message; then the frames become
 * an animated GIF in the background and are sent to the server, which posts them to Discord.
 *
 * <p>Everything here runs on the render thread, which in Minecraft is also the thread network messages
 * are handled on, so the frames need no locking.
 */
public final class DeathClipRecorder {

	private static final int MAX_WIDTH = TimedHardcoreNetwork.MAX_CLIP_WIDTH;
	private static final int MAX_HEIGHT = TimedHardcoreNetwork.MAX_CLIP_HEIGHT;
	private static final long FRAME_INTERVAL_MILLIS = 100;
	private static final long CLIP_MILLIS = 10_000;
	/** Recorded after the death as well: the camera tipping over and the death screen, which ends the clip. */
	private static final long AFTER_DEATH_MILLIS = 1_500;

	private static final Deque<GifEncoder.Frame> frames = new ArrayDeque<>();
	/** Pixel arrays of frames that dropped out of the clip, used again for the next frames instead of new ones. */
	private static final Deque<int[]> spareArrays = new ArrayDeque<>();
	private static final int MAX_SPARE_ARRAYS = 4;
	/** Whether the server wants clips. */
	private static boolean requested;
	/** The server the client is connected to, for the player's per-server choice. */
	private static @Nullable String server;

	/** Small picture on the graphics card the world is scaled into, and the memory it is copied to. */
	private static @Nullable GpuTexture texture;
	private static @Nullable GpuTextureView textureView;
	private static @Nullable GpuBuffer buffer;
	private static int width;
	private static int height;
	/**
	 * Pictures of half the size each, between the window and the small picture. Scaling down in one go would take
	 * only a few of the window's pixels for each pixel of the clip, which flickers and looks jagged; halving
	 * step by step averages all of them.
	 */
	private static final List<GpuTexture> halves = new ArrayList<>();
	private static final List<GpuTextureView> halfViews = new ArrayList<>();
	/** Size of the window the pictures above were made for. */
	private static int sourceWidth;
	private static int sourceHeight;
	/** A copy is on its way from the graphics card; until it arrives, no new frame is taken. */
	private static boolean copyPending;
	private static long lastCapture;
	/** What the frames in the buffer show; if the player changes their choice, the old frames are dropped. */
	private static ClipConsent bufferedConsent = ClipConsent.OFF;

	/** After a timed death: when the recording ends (-1 = not dying). */
	private static long finishAt = -1;
	/**
	 * After a timed death the HUD is hidden (like F1) until the kick: the clip ends with the bare death screen,
	 * and no chat shows through while waiting. Only set if the HUD was visible before, so it is shown again afterwards.
	 */
	private static boolean hudHiddenByUs;
	/**
	 * Between a timed death and the kick. If the player is alive again instead (revived while the clip was
	 * uploading), the kick never comes; after {@link #REVIVED_TICKS} alive ticks the death screen is closed.
	 */
	private static boolean awaitingKick;
	/** The clip is being created and sent; the death screen says so. */
	private static boolean sendingClip;
	/** The connection ended while the kick was expected; reset on the next join. */
	private static boolean kicked;
	private static int aliveTicks;
	/** A normal kick follows the server's respawn within the same moment; half a second tells the two apart. */
	private static final int REVIVED_TICKS = 10;

	private DeathClipRecorder() {
	}

	/** The server says whether it wants clips; without that nothing is recorded at all. */
	public static void setRequestedByServer(boolean clips) {
		if (!clips) {
			frames.clear();
		}
		requested = clips;
	}

	/** Whether the server asks for clips. */
	public static boolean requestedByServer() {
		return requested;
	}

	public static void onJoin(@Nullable String currentServer) {
		server = currentServer;
		kicked = false;
		restoreHud(Minecraft.getInstance());
	}

	public static void onDisconnect() {
		kicked = awaitingKick;
		finishAt = -1;
		awaitingKick = false;
		sendingClip = false;
		aliveTicks = 0;
		// The HUD comes back only once the world is gone (tick): the kick screen can follow a few frames later,
		// and until then the chat would show through the death screen
		setRequestedByServer(false);
		spareArrays.clear();
		server = null;
	}

	/** Between a timed death and the kick (or the revival, if it comes first). */
	public static boolean awaitingKick() {
		return awaitingKick;
	}

	/** Is the clip being created and sent right now? Then the death screen shows a camera and a message. */
	public static boolean sendingClip() {
		return sendingClip;
	}

	/**
	 * Should menus show the panorama instead of the world behind them? From the timed death until the world is
	 * gone after the kick: the kick screen appears while the world is still loaded, and would show it (with chat)
	 * for a moment between the upload screen and the panorama.
	 */
	public static boolean hidesWorld() {
		return awaitingKick || kicked;
	}

	private static boolean recording() {
		return recordedConsent() != ClipConsent.OFF;
	}

	/** What is recorded: the player's choice for this server (WORLD_ONLY or WITH_HUD), if the server wants clips; else OFF. */
	private static ClipConsent recordedConsent() {
		return ClientSettings.get().recording(server, requested);
	}

	/**
	 * Called every frame twice: once the world is drawn ({@code withHud} false) and once the HUD is drawn on top
	 * ({@code withHud} true). Only the call that matches the player's choice takes a frame, every {@link #FRAME_INTERVAL_MILLIS}.
	 * After the death always the second one, because only that one contains the death screen with the death message;
	 * the HUD is hidden then, so it shows nothing but the world and the death screen.
	 */
	public static void capture(RenderTarget source, boolean withHud) {
		ClipConsent consent = recordedConsent();
		if (consent != bufferedConsent) {
			// Frames with and without HUD must not end up in the same clip, and none from before a "no"
			frames.clear();
			bufferedConsent = consent;
		}
		boolean dying = finishAt >= 0;
		if (consent == ClipConsent.OFF || awaitingKick && !dying || copyPending || source.getColorTexture() == null) {
			// Nothing to record, or the clip is finished and only the kick is still to come
			return;
		}
		if (withHud != (dying || consent == ClipConsent.WITH_HUD)) {
			return;
		}
		if (dying && consent == ClipConsent.WORLD_ONLY && !Minecraft.getInstance().gui.hud.isHidden()) {
			// The player showed the HUD again (F1): it would bring chat and inventory into a clip without them
			return;
		}
		long now = Util.getMillis();
		if (now - lastCapture < FRAME_INTERVAL_MILLIS) {
			return;
		}
		lastCapture = now;
		prepare(source.width, source.height);
		GpuTexture target = texture;
		GpuTextureView targetView = textureView;
		GpuBuffer pixels = buffer;
		if (target == null || targetView == null || pixels == null) {
			return;
		}

		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		GpuTextureView from = source.getColorTextureView();
		for (GpuTextureView half : halfViews) {
			blit(encoder, from, half);
			from = half;
		}
		blit(encoder, from, targetView);
		copyPending = true;
		int frameWidth = width;
		int frameHeight = height;
		encoder.copyTextureToBuffer(target, pixels, 0, () -> {
			copyPending = false;
			store(read(pixels, frameWidth, frameHeight, now));
		}, 0);
	}

	/**
	 * Draws one picture scaled into another. With linear filtering, a picture of exactly half the size gets the
	 * average of 2 x 2 pixels each. The same drawing Minecraft uses for its profiler pictures.
	 */
	private static void blit(CommandEncoder encoder, GpuTextureView from, GpuTextureView to) {
		try (RenderPass pass = encoder.createRenderPass(() -> "Timed Hardcore death clip", to, Optional.empty())) {
			RenderSystem.bindDefaultUniforms(pass);
			pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT));
			pass.setUniform("InSampler", from, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			pass.draw(3, 1, 0, 0);
		}
	}

	/** Creates the small picture, the halves on the way there and the memory, again whenever the window size changes. */
	private static void prepare(int newSourceWidth, int newSourceHeight) {
		if (texture != null && newSourceWidth == sourceWidth && newSourceHeight == sourceHeight) {
			return;
		}
		release();
		sourceWidth = newSourceWidth;
		sourceHeight = newSourceHeight;
		double scale = Math.min(1, Math.min((double) MAX_WIDTH / sourceWidth, (double) MAX_HEIGHT / sourceHeight));
		width = Math.max(4, (int) (sourceWidth * scale) / 4 * 4);
		height = Math.max(4, (int) (sourceHeight * scale) / 4 * 4);
		GpuDevice device = RenderSystem.getDevice();

		// Halve while the picture is more than twice as large as the clip; the last step (at most halving) is the clip itself
		int halfWidth = sourceWidth;
		int halfHeight = sourceHeight;
		while (halfWidth > width * 2 && halfHeight > height * 2) {
			halfWidth /= 2;
			halfHeight /= 2;
			GpuTexture half = device.createTexture("Timed Hardcore death clip", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
				GpuFormat.RGBA8_UNORM, halfWidth, halfHeight, 1, 1);
			halves.add(half);
			halfViews.add(device.createTextureView(half));
		}

		texture = device.createTexture("Timed Hardcore death clip", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
			GpuFormat.RGBA8_UNORM, width, height, 1, 1);
		textureView = device.createTextureView(texture);
		buffer = device.createBuffer(() -> "Timed Hardcore death clip", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) width * height * 4);
		// Frames of the old size would not fit into the same GIF
		frames.clear();
		spareArrays.clear();
	}

	private static void release() {
		halfViews.forEach(GpuTextureView::close);
		halves.forEach(GpuTexture::close);
		halfViews.clear();
		halves.clear();
		if (textureView != null) {
			textureView.close();
		}
		if (texture != null) {
			texture.close();
		}
		if (buffer != null) {
			buffer.close();
		}
		textureView = null;
		texture = null;
		buffer = null;
	}

	/** The copied pixels as a frame. The graphics card stores the rows from the bottom up. */
	private static GifEncoder.Frame read(GpuBuffer pixels, int frameWidth, int frameHeight, long time) {
		int[] rgb = spareArrays.pollFirst();
		if (rgb == null || rgb.length != frameWidth * frameHeight) {
			rgb = new int[frameWidth * frameHeight];
		}
		try (GpuBufferSlice.MappedView view = pixels.map(true, false)) {
			ByteBuffer data = view.data();
			for (int y = 0; y < frameHeight; y++) {
				int row = (frameHeight - 1 - y) * frameWidth;
				for (int x = 0; x < frameWidth; x++) {
					int offset = (y * frameWidth + x) * 4;
					// RGBA, one byte each
					rgb[row + x] = (data.get(offset) & 0xFF) << 16 | (data.get(offset + 1) & 0xFF) << 8 | data.get(offset + 2) & 0xFF;
				}
			}
		}
		return new GifEncoder.Frame(frameWidth, frameHeight, rgb, time);
	}

	private static void store(GifEncoder.Frame frame) {
		if (!recording() || frame.width() != width || frame.height() != height) {
			return;
		}
		frames.addLast(frame);
		// After the death the clip grows by the last seconds, it keeps its 10 seconds before the death
		while (finishAt < 0 && !frames.isEmpty() && frames.getFirst().timeMillis() < frame.timeMillis() - CLIP_MILLIS) {
			// Nothing else holds a frame that is still in the buffer, so its array is free again
			int[] dropped = frames.removeFirst().rgb();
			if (spareArrays.size() < MAX_SPARE_ARRAYS) {
				spareArrays.addLast(dropped);
			}
		}
	}

	/**
	 * The server reports a timed death. Recording goes on for {@link #AFTER_DEATH_MILLIS} with the HUD hidden and
	 * without the buttons of the death screen, so the clip shows the death itself and ends with the death screen.
	 * Then the death screen stays until the kick and says that the clip is sent ({@link #tick}).
	 */
	public static void onTimedDeath() {
		Minecraft client = Minecraft.getInstance();
		awaitingKick = true;
		aliveTicks = 0;
		hideDeathScreenButtons(client);
		hideHud(client);
		if (!recording()) {
			frames.clear();
			send(TimedHardcoreNetwork.ClipPayload.NONE);
			waitForKick(client, false);
			return;
		}
		finishAt = Util.getMillis() + AFTER_DEATH_MILLIS;
	}

	/** Every client tick: ends the recording after the death, also if the window is minimized and nothing is drawn. */
	public static void tick(Minecraft client) {
		if (client.level == null && !awaitingKick) {
			// Back in the menus after the kick
			restoreHud(client);
		}
		if (awaitingKick) {
			aliveTicks = client.player != null && client.player.isAlive() ? aliveTicks + 1 : 0;
			if (aliveTicks >= REVIVED_TICKS) {
				// Revived instead of kicked: back into the game
				awaitingKick = false;
				sendingClip = false;
				aliveTicks = 0;
				finishAt = -1;
				restoreHud(client);
				// The respawn left the death screen and the camera as they were, in case the kick came
				if (client.gui.screen() instanceof ClipUploadScreen || client.gui.screen() instanceof DeathScreen) {
					client.gui.setScreen(null);
				}
				if (client.player != null && client.getCameraEntity() != client.player) {
					client.setCameraEntity(client.player);
				}
				return;
			}
		}
		long now = Util.getMillis();
		if (awaitingKick) {
			// Resizing the window builds the death screen again, with its buttons
			hideDeathScreenButtons(client);
		}
		// A copy still on its way is waited for a moment, so the last frame is not lost
		if (finishAt < 0 || now < finishAt || copyPending && now < finishAt + 500) {
			return;
		}
		finishAt = -1;
		waitForKick(client, sendClip());
	}

	/**
	 * Until the kick the death screen stays, with a camera and a message while a clip is sent (DeathScreenMixin).
	 * Without a death screen (e.g. closed by another mod) a screen of its own shows them instead.
	 */
	private static void waitForKick(Minecraft client, boolean sending) {
		sendingClip = sending;
		if (!(client.gui.screen() instanceof DeathScreen)) {
			client.gui.setScreen(new ClipUploadScreen(sending));
		}
	}

	private static void hideHud(Minecraft client) {
		if (!client.gui.hud.isHidden()) {
			client.gui.hud.toggle();
			hudHiddenByUs = true;
		}
	}

	/** After the kick: the HUD is back as the player had it. */
	private static void restoreHud(Minecraft client) {
		if (hudHiddenByUs && client.gui.hud.isHidden()) {
			client.gui.hud.toggle();
		}
		hudHiddenByUs = false;
	}

	/** The buttons of the death screen would be no use: the player is kicked anyway. */
	private static void hideDeathScreenButtons(Minecraft client) {
		if (client.gui.screen() instanceof DeathScreen deathScreen) {
			deathScreen.children().forEach(child -> {
				if (child instanceof AbstractWidget widget) {
					widget.visible = false;
				}
			});
		}
	}

	/**
	 * Turns the recorded frames into a GIF in the background and sends it; an empty answer if there are none.
	 *
	 * @return whether a clip is on its way (and not just the empty answer)
	 */
	private static boolean sendClip() {
		List<GifEncoder.Frame> clip = List.copyOf(frames);
		frames.clear();
		if (!recording() || clip.isEmpty()) {
			send(TimedHardcoreNetwork.ClipPayload.NONE);
			return false;
		}
		Util.backgroundExecutor().execute(() -> {
			byte[] gif = null;
			try {
				gif = GifEncoder.encode(clip, TimedHardcoreNetwork.MAX_CLIP_BYTES);
			} catch (IOException | RuntimeException e) {
				TimedHardcore.LOGGER.warn("Death clip could not be created", e);
			}
			if (gif != null) {
				TimedHardcore.LOGGER.info("Sending death clip: {} frames, {} KB", clip.size(), gif.length / 1024);
			}
			TimedHardcoreNetwork.ClipPayload payload = gif == null ? TimedHardcoreNetwork.ClipPayload.NONE : new TimedHardcoreNetwork.ClipPayload(gif);
			Minecraft.getInstance().execute(() -> send(payload));
		});
		return true;
	}

	private static void send(TimedHardcoreNetwork.ClipPayload payload) {
		// The connection may be gone by now
		if (ClientPlayNetworking.canSend(TimedHardcoreNetwork.ClipPayload.TYPE)) {
			ClientPlayNetworking.send(payload);
		}
	}
}
