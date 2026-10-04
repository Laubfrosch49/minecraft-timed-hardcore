package dev.laubfrosch.timedhardcore;

import net.minecraft.server.level.ServerPlayer;

/**
 * What happens if a player dies right now? The one place where this is decided, for the death
 * itself as well as for hearts, player list and notices.
 */
public enum Risk {

	/** "At risk": the player stays dead until revived; red hardcore hearts. */
	TIMED_DEATH,
	/** "Protected": death uses up the extra life; green hearts. */
	EXTRA_LIFE,
	/** "Safe": death has no consequences (enough players online or death duration 0); normal hearts. */
	SAFE,
	/** Creative or spectator mode: never stays dead, and no extra life is used up either. */
	EXEMPT;

	private static final Risk[] VALUES = values();

	/** For the transfer to the client mod. */
	public static Risk byId(int id) {
		return id >= 0 && id < VALUES.length ? VALUES[id] : TIMED_DEATH;
	}

	public static Risk of(ServerPlayer player) {
		TimedHardcoreConfig config = TimedHardcore.config();
		if (player.isCreative() || player.isSpectator()) {
			return EXEMPT;
		}
		if (config.isSafe(player.level().getServer().getPlayerList().getPlayerCount())) {
			return SAFE;
		}
		ExtraLifeManager lives = TimedHardcore.lives();
		if (config.extraLivesEnabled && lives != null && lives.has(player.getUUID())) {
			return EXTRA_LIFE;
		}
		long now = System.currentTimeMillis();
		return config.revivalTime(now) > now ? TIMED_DEATH : SAFE;
	}

	/** Does the client show hardcore hearts? */
	public boolean hasConsequences() {
		return this == TIMED_DEATH || this == EXTRA_LIFE;
	}
}
