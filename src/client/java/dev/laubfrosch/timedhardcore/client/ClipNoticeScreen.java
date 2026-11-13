package dev.laubfrosch.timedhardcore.client;

import dev.laubfrosch.timedhardcore.client.ClientSettings.ClipConsent;
import java.util.function.Consumer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ImageWidget;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The notice while joining a server that records death clips: info icon, explanation and the player's choice
 * of what their clips show. Built like Minecraft's own confirmation screens.
 */
public class ClipNoticeScreen extends Screen {

	private static final Identifier INFO_ICON = Identifier.withDefaultNamespace("icon/info");
	private static final Component MESSAGE = Component.literal("If you die and stay dead, the last 10 seconds before your death "
		+ "are sent to this server and posted in its Discord. Besides the game world, this server also records your HUD, "
		+ "inventory and chat. You can also share only the game world.\n\n"
		+ "You can change this any time under \"Edit\" in the server list.");

	private final Consumer<ClipConsent> onChoice;
	/** Made again in every {@link #init}, which also runs when the window is resized. */
	private LinearLayout layout = LinearLayout.vertical();

	public ClipNoticeScreen(Consumer<ClipConsent> onChoice) {
		super(Component.literal("Death clips on this server"));
		this.onChoice = onChoice;
	}

	@Override
	protected void init() {
		layout = LinearLayout.vertical().spacing(8);
		layout.defaultCellSetting().alignHorizontallyCenter();
		layout.addChild(ImageWidget.sprite(20, 20, INFO_ICON));
		layout.addChild(new StringWidget(title, font));
		layout.addChild(new MultiLineTextWidget(MESSAGE, font).setMaxWidth(width - 50).setMaxRows(15).setCentered(true));

		LinearLayout buttons = layout.addChild(LinearLayout.horizontal().spacing(4));
		buttons.defaultCellSetting().paddingTop(16);
		// From sharing nothing on the left to sharing everything on the right
		buttons.addChild(button("Don't share", ClipConsent.OFF));
		buttons.addChild(button("World only", ClipConsent.WORLD_ONLY));
		buttons.addChild(button("With HUD", ClipConsent.WITH_HUD));

		layout.visitWidgets(this::addRenderableWidget);
		repositionElements();
	}

	private Button button(String label, ClipConsent consent) {
		return Button.builder(Component.literal(label), _ -> onChoice.accept(consent))
			.width(120)
			.tooltip(Tooltip.create(consent.description()))
			.build();
	}

	@Override
	protected void repositionElements() {
		layout.arrangeElements();
		FrameLayout.centerInRectangle(layout, getRectangle());
	}

	@Override
	public boolean shouldCloseOnEsc() {
		// Escape would leave the client without a screen in the middle of joining
		return false;
	}
}
