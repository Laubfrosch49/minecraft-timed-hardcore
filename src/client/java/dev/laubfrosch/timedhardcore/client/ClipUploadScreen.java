package dev.laubfrosch.timedhardcore.client;

import dev.laubfrosch.timedhardcore.TimedHardcore;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ImageWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Shown while the death clip uploads if there is no death screen to show it on (normally the death screen stays
 * and shows the camera and the message itself, see DeathScreenMixin): the background of the kick screen
 * (Minecraft's panorama) with a camera icon and a short message, no buttons. Without a clip only the background.
 */
public class ClipUploadScreen extends Screen {

	public static final Identifier CAMERA_ICON = TimedHardcore.id("icon/death_clip");
	public static final Component MESSAGE = Component.literal("Creating and sending death clip...");

	/** Whether a clip is on its way; without one there is nothing to explain. */
	private final boolean sendingClip;
	/** Made again in every {@link #init}, which also runs when the window is resized. */
	private LinearLayout layout = LinearLayout.vertical();

	public ClipUploadScreen(boolean sendingClip) {
		super(CommonComponents.EMPTY);
		this.sendingClip = sendingClip;
	}

	@Override
	protected void init() {
		if (!sendingClip) {
			return;
		}
		layout = LinearLayout.vertical().spacing(8);
		layout.defaultCellSetting().alignHorizontallyCenter();
		layout.addChild(ImageWidget.sprite(20, 20, CAMERA_ICON));
		layout.addChild(new StringWidget(MESSAGE, font));
		layout.visitWidgets(this::addRenderableWidget);
		repositionElements();
	}

	@Override
	protected void repositionElements() {
		layout.arrangeElements();
		FrameLayout.centerInRectangle(layout, getRectangle());
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		extractPanorama(graphics, partialTick);
		extractBlurredBackground(graphics);
		extractMenuBackground(graphics);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		// Still connected: without this screen the player would be back in the world until the kick
		return false;
	}
}
