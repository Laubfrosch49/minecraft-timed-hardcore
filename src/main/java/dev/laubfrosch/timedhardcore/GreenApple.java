package dev.laubfrosch.timedhardcore;

import static dev.laubfrosch.timedhardcore.Colors.GREEN;
import static dev.laubfrosch.timedhardcore.Colors.MUTED;

import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemLore;

/**
 * The green apple grants an extra life and nothing else. Technically it is a normal (red) apple with a
 * marker, so players without the client mod can still join (they see a glinting apple with a green name).
 * With the client mod the item model picks the green texture based on custom_model_data.
 */
public final class GreenApple {

	/** Must match the "when" value in assets/minecraft/items/apple.json. */
	private static final String MODEL_ID = TimedHardcore.MOD_ID + ":green_apple";
	private static final String MARKER_KEY = TimedHardcore.MOD_ID;
	private static final String MARKER_VALUE = "green_apple";

	private GreenApple() {
	}

	public static ItemStack create() {
		ItemStack stack = new ItemStack(Items.APPLE);
		stack.set(DataComponents.ITEM_NAME, Component.literal("Green Apple").withColor(GREEN));
		stack.set(DataComponents.LORE, new ItemLore(List.of(
			lore("Grants you an extra life.", GREEN),
			lore("If you die, you do not stay dead;", MUTED),
			lore("the extra life is used up instead.", MUTED),
			lore("Only one extra life at a time.", MUTED)
		)));
		stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(), List.of(MODEL_ID), List.of()));
		stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		// Like a normal apple (no effects), but edible even when not hungry
		stack.set(DataComponents.FOOD, new FoodProperties(4, 2.4F, true));
		CompoundTag marker = new CompoundTag();
		marker.putString(MARKER_KEY, MARKER_VALUE);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(marker));
		return stack;
	}

	public static boolean is(ItemStack stack) {
		if (!stack.is(Items.APPLE)) {
			return false;
		}
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && MARKER_VALUE.equals(data.copyTag().getStringOr(MARKER_KEY, ""));
	}

	/** May the player eat the apple right now? Players who already have an extra life get a notice in the action bar. */
	public static boolean canEat(ServerPlayer player) {
		ExtraLifeManager lives = TimedHardcore.lives();
		if (lives == null || !TimedHardcore.config().extraLivesEnabled || !lives.has(player.getUUID())) {
			return true;
		}
		player.sendOverlayMessage(Messages.alreadyHasExtraLife());
		return false;
	}

	/** Apple eaten: grants the extra life, with totem animation, particles and a chat message to everyone. */
	public static void onEaten(ServerPlayer player) {
		ExtraLifeManager lives = TimedHardcore.lives();
		if (lives == null || !TimedHardcore.config().extraLivesEnabled || !lives.grant(player.getUUID(), player.getGameProfile().name())) {
			return;
		}
		ServerLevel level = player.level();
		ClientSync.announceAppleEaten(player);
		// Totem animation including sound and particles (vanilla, also for players without the client mod)
		level.broadcastEntityEvent(player, EntityEvent.PROTECTED_FROM_DEATH);
		level.sendParticles(ParticleTypes.HAPPY_VILLAGER, player.getX(), player.getY() + 1.0, player.getZ(), 40, 0.6, 0.9, 0.6, 0.1);
		level.sendParticles(ParticleTypes.COMPOSTER, player.getX(), player.getY() + 1.0, player.getZ(), 25, 0.5, 0.8, 0.5, 0.05);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 1.4F);
		ClientSync.broadcastWithAppleIcon(level.getServer(), green -> Messages.extraLifeGained(player.getGameProfile(), green));
		// Update hearts, player list and notice right away instead of at the next full second
		TimedHardcore.refreshStatus(level.getServer());
	}

	private static Component lore(String text, int color) {
		return Component.literal(text).withStyle(style -> style.withColor(color).withItalic(false));
	}
}
