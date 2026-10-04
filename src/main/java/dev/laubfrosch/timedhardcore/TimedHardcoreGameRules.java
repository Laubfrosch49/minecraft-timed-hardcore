package dev.laubfrosch.timedhardcore;

import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;

/**
 * Game rules added by the mod. They are changed with the normal command, e.g.
 * /gamerule timed-hardcore:keep_inventory_on_timed_death false
 */
public final class TimedHardcoreGameRules {

	/**
	 * true: players who stay dead after dying keep inventory and XP, regardless of keepInventory.
	 * false: the normal keepInventory rule applies to them as well.
	 */
	public static final GameRule<Boolean> KEEP_INVENTORY_ON_TIMED_DEATH = GameRuleBuilder.forBoolean(true)
		.category(GameRuleCategory.PLAYER)
		.buildAndRegister(TimedHardcore.id("keep_inventory_on_timed_death"));

	private TimedHardcoreGameRules() {
	}

	/** Loads this class, which registers the rules. Must be called while the mod initialises. */
	public static void register() {
	}
}
