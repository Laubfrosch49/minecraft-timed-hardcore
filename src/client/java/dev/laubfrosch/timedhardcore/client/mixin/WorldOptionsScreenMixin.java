package dev.laubfrosch.timedhardcore.client.mixin;

import dev.laubfrosch.timedhardcore.client.ClientSettings;
import dev.laubfrosch.timedhardcore.client.ClientSettings.ClipConsent;
import dev.laubfrosch.timedhardcore.client.ClipNotice;
import dev.laubfrosch.timedhardcore.client.DeathClipRecorder;
import dev.laubfrosch.timedhardcore.client.TimedHardcoreClient;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.FocusableTextWidget;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.WorldOptionsScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * "Death clips" in the pause menu under "World Options", on servers with Timed Hardcore: the same choice as
 * under "Edit" in the server list, changeable while playing. Saved with "Apply Changes", like the other settings there.
 */
@Mixin(WorldOptionsScreen.class)
public abstract class WorldOptionsScreenMixin extends Screen {

	protected WorldOptionsScreenMixin(Component title) {
		super(title);
	}

	/** The server whose choice is shown, and the choice on the button until "Apply Changes" saves it. */
	@Unique
	private @Nullable String timedhardcore$server;
	@Unique
	private @Nullable ClipConsent timedhardcore$wantedConsent;

	@Shadow
	private FocusableTextWidget createCategoryHeader(Component title) {
		throw new AssertionError("Replaced by Mixin");
	}

	@Shadow
	private void updateApplyChangesActiveState() {
		throw new AssertionError("Replaced by Mixin");
	}

	@Inject(method = "generalOptions", at = @At("TAIL"))
	private void timedhardcore$addDeathClipSection(LinearLayout content, @Nullable IntegratedServer server, CallbackInfo ci) {
		String serverKey = ClipNotice.serverKey(minecraft);
		// Only on a server (not in singleplayer) that runs Timed Hardcore
		if (server != null || serverKey == null || TimedHardcoreClient.risk() == null) {
			return;
		}
		timedhardcore$server = serverKey;
		if (timedhardcore$wantedConsent == null) {
			timedhardcore$wantedConsent = ClientSettings.get().consent(serverKey);
		}
		GridLayout grid = content.addChild(new GridLayout().columnSpacing(8).rowSpacing(4));
		GridLayout.RowHelper rows = grid.createRowHelper(1);
		rows.defaultCellSetting().alignHorizontallyCenter();
		rows.addChild(createCategoryHeader(Component.literal("Death clips")));
		rows.addChild(new MultiLineTextWidget(Component.literal(serverStatus(DeathClipRecorder.requestedByServer())), font)
			.setMaxWidth(308).setCentered(true));
		rows.addChild(CycleButton.builder(ClipConsent::label, timedhardcore$wantedConsent)
			.withValues(ClipConsent.values())
			.withTooltip(consent -> Tooltip.create(consent.description()))
			.create(0, 0, 308, 20, Component.literal("Share my death clips"), (_, consent) -> {
				// Like the other settings here: only saved with "Apply Changes"
				timedhardcore$wantedConsent = consent;
				updateApplyChangesActiveState();
			}));
	}

	@Unique
	private boolean timedhardcore$consentChanged() {
		return timedhardcore$server != null && timedhardcore$wantedConsent != null
			&& timedhardcore$wantedConsent != ClientSettings.get().consent(timedhardcore$server);
	}

	@Inject(method = "hasChanges", at = @At("RETURN"), cancellable = true)
	private void timedhardcore$deathClipChoiceIsAChange(CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && timedhardcore$consentChanged()) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "applyChanges", at = @At("HEAD"))
	private void timedhardcore$saveDeathClipChoice(@Nullable IntegratedServer server, CallbackInfo ci) {
		if (timedhardcore$consentChanged()) {
			ClientSettings.get().setConsent(timedhardcore$server, timedhardcore$wantedConsent);
		}
	}

	private static String serverStatus(boolean requested) {
		return requested
			? "This server posts the last seconds before a death in its Discord. You decide what they show."
			: "This server does not record death clips at the moment.";
	}
}
