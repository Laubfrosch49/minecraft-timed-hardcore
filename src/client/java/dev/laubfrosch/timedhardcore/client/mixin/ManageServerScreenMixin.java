package dev.laubfrosch.timedhardcore.client.mixin;

import dev.laubfrosch.timedhardcore.client.ClientSettings;
import dev.laubfrosch.timedhardcore.client.ClientSettings.ClipConsent;
import dev.laubfrosch.timedhardcore.client.ClipNotice;
import java.util.List;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ManageServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * "Death clips" under "Edit" (and "Add Server") in the server list, right below "Server Resource Packs":
 * Ask, World only, With HUD or Off. Saved together with the server when "Done" is pressed.
 */
@Mixin(ManageServerScreen.class)
public abstract class ManageServerScreenMixin extends Screen {

	/**
	 * How far Done and Cancel move down. Vanilla leaves 22 pixels between "Server Resource Packs" and Done; with the
	 * death clip button in between, 14 of them stay. A full row more (24) would cut off Cancel on the smallest
	 * screen (240 high); with 16, Cancel still ends 6 pixels above the bottom there.
	 */
	@Unique
	private static final int TIMEDHARDCORE$SHIFT = 16;

	@Shadow
	@Final
	private ServerData serverData;

	/** The choice on the button; kept when the window is resized and the screen is built again. */
	@Unique
	private ClipConsent timedhardcore$clipConsent;

	protected ManageServerScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void timedhardcore$addDeathClipButton(CallbackInfo ci) {
		if (timedhardcore$clipConsent == null) {
			timedhardcore$clipConsent = ClientSettings.get().consent(ClipNotice.serverKey(serverData));
		}
		// Right below the resource pack button; Done and Cancel move down a little to make room
		int y = height / 4 + 96;
		List<AbstractWidget> below = children().stream()
			.filter(child -> child instanceof AbstractWidget widget && widget.getY() >= y)
			.map(child -> (AbstractWidget) child)
			.toList();
		below.forEach(widget -> widget.setY(widget.getY() + TIMEDHARDCORE$SHIFT));

		addRenderableWidget(CycleButton.builder(ClipConsent::label, timedhardcore$clipConsent)
			.withValues(ClipConsent.values())
			.withTooltip(consent -> Tooltip.create(consent.description()))
			.create(width / 2 - 100, y, 200, 20, Component.literal("Death clips"), (_, consent) -> timedhardcore$clipConsent = consent));
	}

	@Inject(method = "onAdd", at = @At("TAIL"))
	private void timedhardcore$saveDeathClipChoice(CallbackInfo ci) {
		// The address may just have been changed in this screen; the choice belongs to the new one
		String server = ClipNotice.serverKey(serverData);
		if (server != null && timedhardcore$clipConsent != null) {
			ClientSettings.get().setConsent(server, timedhardcore$clipConsent);
		}
	}
}
