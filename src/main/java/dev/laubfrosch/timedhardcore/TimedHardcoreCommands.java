package dev.laubfrosch.timedhardcore;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.Prediction;

public final class TimedHardcoreCommands {

	private static final Predicate<CommandSourceStack> IS_OPERATOR = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);

	private static final SuggestionProvider<CommandSourceStack> DEAD_PLAYERS = (_, builder) -> {
		DeadPlayerManager manager = TimedHardcore.manager();
		if (manager == null) {
			return builder.buildFuture();
		}
		return SharedSuggestionProvider.suggest(manager.stillDead().stream().map(deadPlayer -> deadPlayer.name), builder);
	};

	private static final SuggestionProvider<CommandSourceStack> PLAYERS_WITH_LIFE = (_, builder) -> {
		ExtraLifeManager lives = TimedHardcore.lives();
		if (lives == null) {
			return builder.buildFuture();
		}
		return SharedSuggestionProvider.suggest(lives.all().keySet(), builder);
	};

	private TimedHardcoreCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(operatorOnly("revive").then(deadPlayerArgument()));

		dispatcher.register(Commands.literal("timedhardcore")
			// For everyone
			.then(Commands.literal("info").executes(ctx -> info(ctx.getSource())))
			.then(Commands.literal("dead").executes(ctx -> dead(ctx.getSource())))
			// For operators
			.then(operatorOnly("list").executes(ctx -> list(ctx.getSource())))
			.then(operatorOnly("reload").executes(ctx -> reload(ctx.getSource())))
			.then(operatorOnly("revive").then(deadPlayerArgument()))
			.then(operatorOnly("duration")
				.executes(ctx -> showRevival(ctx.getSource()))
				.then(Commands.argument("minutes", DoubleArgumentType.doubleArg(0))
					.executes(ctx -> setDuration(ctx.getSource(), DoubleArgumentType.getDouble(ctx, "minutes")))))
			.then(operatorOnly("times")
				.executes(ctx -> showRevival(ctx.getSource()))
				.then(Commands.argument("times", StringArgumentType.greedyString())
					.suggests((_, builder) -> SharedSuggestionProvider.suggest(List.of("0:00 12:00", "0:00", "0:00 20:00"), builder))
					.executes(ctx -> setTimes(ctx.getSource(), StringArgumentType.getString(ctx, "times")))))
			.then(operatorOnly("mode")
				.executes(ctx -> showRevival(ctx.getSource()))
				.then(Commands.literal("duration").executes(ctx -> setMode(ctx.getSource(), TimedHardcoreConfig.ReviveMode.DURATION)))
				.then(Commands.literal("times").executes(ctx -> setMode(ctx.getSource(), TimedHardcoreConfig.ReviveMode.FIXED_TIMES))))
			.then(operatorOnly("safe")
				.executes(ctx -> showSafe(ctx.getSource()))
				.then(Commands.argument("count", IntegerArgumentType.integer(0))
					.executes(ctx -> setSafe(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "count")))))
			.then(operatorOnly("life")
				.then(Commands.literal("give").then(Commands.argument("player", GameProfileArgument.gameProfile())
					.executes(ctx -> giveLife(ctx.getSource(), GameProfileArgument.getGameProfiles(ctx, "player")))))
				.then(Commands.literal("take").then(Commands.argument("player", StringArgumentType.word())
					.suggests(PLAYERS_WITH_LIFE)
					.executes(ctx -> takeLife(ctx.getSource(), StringArgumentType.getString(ctx, "player"))))))
			.then(operatorOnly("apple")
				.executes(ctx -> giveApple(ctx.getSource(), List.of(ctx.getSource().getPlayerOrException())))
				.then(Commands.argument("players", EntityArgument.players())
					.executes(ctx -> giveApple(ctx.getSource(), EntityArgument.getPlayers(ctx, "players")))))
			.then(operatorOnly("healer").executes(ctx -> spawnHealer(ctx.getSource()))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> operatorOnly(String name) {
		return Commands.literal(name).requires(IS_OPERATOR);
	}

	private static RequiredArgumentBuilder<CommandSourceStack, String> deadPlayerArgument() {
		return Commands.argument("player", StringArgumentType.word())
			.suggests(DEAD_PLAYERS)
			.executes(ctx -> revive(ctx.getSource(), StringArgumentType.getString(ctx, "player")));
	}

	private static Component success(String text) {
		return Component.literal(text).withStyle(ChatFormatting.GREEN);
	}

	private static String plural(int count, String singular, String plural) {
		return count + " " + (count == 1 ? singular : plural);
	}

	// --- Info for everyone ---

	private static int info(CommandSourceStack source) {
		DeadPlayerManager manager = TimedHardcore.manager();
		if (manager == null) {
			return 0;
		}
		ServerPlayer player = source.getPlayer();
		boolean greenIcon = ClientSync.hasClientMod(player);
		int online = source.getServer().getPlayerList().getPlayerCount();
		int dead = manager.stillDead().size();
		boolean keepInventory = source.getServer().getGameRules().get(TimedHardcoreGameRules.KEEP_INVENTORY_ON_TIMED_DEATH);
		source.sendSuccess(() -> Messages.info(TimedHardcore.config(), online, keepInventory, dead, greenIcon), false);
		if (player != null) {
			source.sendSuccess(() -> Messages.riskOnJoin(Risk.of(player), online, greenIcon), false);
		}
		return 1;
	}

	private static int dead(CommandSourceStack source) {
		DeadPlayerManager manager = TimedHardcore.manager();
		return manager == null ? 0 : sendDeadPlayers(source, manager);
	}

	/**
	 * Lists who is dead and for how long. Operators also get a revive button on every line.
	 *
	 * @return number of dead players
	 */
	private static int sendDeadPlayers(CommandSourceStack source, DeadPlayerManager manager) {
		boolean withReviveButton = IS_OPERATOR.test(source);
		List<DeadPlayerManager.DeadPlayer> deadPlayers = manager.stillDead();
		if (deadPlayers.isEmpty()) {
			source.sendSuccess(() -> success("Nobody is dead."), false);
		} else {
			source.sendSuccess(() -> Messages.listHeader(deadPlayers.size()), false);
			for (DeadPlayerManager.DeadPlayer deadPlayer : deadPlayers) {
				source.sendSuccess(() -> Messages.listEntry(deadPlayer, withReviveButton), false);
			}
		}
		return deadPlayers.size();
	}

	// --- Revive and list ---

	private static int revive(CommandSourceStack source, String name) {
		DeadPlayerManager manager = TimedHardcore.manager();
		if (manager == null) {
			return 0;
		}
		return manager.revive(name).map(deadPlayer -> {
			// To everyone in chat (which also puts it into the server log)
			source.getServer().getPlayerList().broadcastSystemMessage(Messages.revived(deadPlayer, source.getTextName()), false);
			return 1;
		}).orElseGet(() -> {
			source.sendFailure(Component.literal(name + " is not dead."));
			return 0;
		});
	}

	private static int list(CommandSourceStack source) {
		DeadPlayerManager manager = TimedHardcore.manager();
		ExtraLifeManager lives = TimedHardcore.lives();
		if (manager == null || lives == null) {
			return 0;
		}
		boolean greenIcon = ClientSync.hasClientMod(source.getPlayer());
		List<Messages.PlayerRisk> risks = source.getServer().getPlayerList().getPlayers().stream()
			.map(Messages.PlayerRisk::of)
			.toList();
		source.sendSuccess(() -> Messages.statusLine(risks, greenIcon), false);

		int dead = sendDeadPlayers(source, manager);

		Map<String, UUID> withLife = lives.all();
		if (!withLife.isEmpty()) {
			source.sendSuccess(() -> Messages.livesHeader(withLife.size(), greenIcon), false);
			withLife.forEach((name, uuid) -> source.sendSuccess(() -> Messages.livesEntry(uuid, name), false));
		}
		return dead;
	}

	// --- Reload ---

	private static int reload(CommandSourceStack source) {
		DeadPlayerManager manager = TimedHardcore.manager();
		ExtraLifeManager lives = TimedHardcore.lives();
		if (manager == null || lives == null) {
			return 0;
		}
		String revivalBefore = TimedHardcore.config().revivalSignature();
		boolean configLoaded = tryReload(source, "The config file is invalid, the old settings stay active", TimedHardcore::reloadConfig);
		boolean deadPlayersLoaded = tryReload(source, "The file with the dead players is invalid, the old entries stay active", manager::load);
		boolean livesLoaded = tryReload(source, "The extra life file of the world is invalid, the old extra lives stay active", lives::load);
		if (!configLoaded || !deadPlayersLoaded || !livesLoaded) {
			return 0;
		}
		// Only recalculate when the time settings changed; otherwise entries edited by hand would be lost
		int recalculated = revivalBefore.equals(TimedHardcore.config().revivalSignature())
			? 0
			: TimedHardcore.recalculateRevivals(source.getServer());
		TimedHardcore.announceRevivals(source.getServer());
		int dead = manager.stillDead().size();
		source.sendSuccess(() -> success("Timed Hardcore reloaded: " + plural(dead, "dead player", "dead players")
			+ ", revival " + TimedHardcore.config().describe() + "." + recalculatedNote(recalculated)), true);
		return 1;
	}

	@FunctionalInterface
	private interface Reloadable {
		void reload() throws Exception;
	}

	/** @return false on an error; it is reported to the sender and the current data stays active */
	private static boolean tryReload(CommandSourceStack source, String failure, Reloadable file) {
		try {
			file.reload();
			return true;
		} catch (Exception e) {
			TimedHardcore.LOGGER.error(failure, e);
			source.sendFailure(Component.literal(failure + ": " + e.getMessage()));
			return false;
		}
	}

	private static String recalculatedNote(int recalculated) {
		return recalculated == 0 ? "" : " " + plural(recalculated, "revival", "revivals") + " recalculated.";
	}

	// --- Revival settings ---

	private static int showRevival(CommandSourceStack source) {
		source.sendSuccess(() -> Component.literal("Revival: " + TimedHardcore.config().describe()), false);
		return 1;
	}

	private static int setDuration(CommandSourceStack source, double minutes) {
		TimedHardcoreConfig config = TimedHardcore.config();
		config.deathDuration = minutes;
		config.reviveMode = TimedHardcoreConfig.ReviveMode.DURATION;
		return saveRevival(source, config);
	}

	private static int setTimes(CommandSourceStack source, String input) {
		List<String> times;
		try {
			times = Arrays.stream(input.split("[,;\\s]+"))
				.filter(part -> !part.isBlank())
				.map(TimedHardcoreConfig::parseTime)
				.distinct()
				.sorted()
				.map(TimedHardcoreConfig::formatTime)
				.toList();
		} catch (IllegalArgumentException e) {
			source.sendFailure(Component.literal(e.getMessage()));
			return 0;
		}
		if (times.isEmpty()) {
			source.sendFailure(Component.literal("Please give at least one time, e.g. 0:00 20:00"));
			return 0;
		}
		TimedHardcoreConfig config = TimedHardcore.config();
		config.reviveTimes = new ArrayList<>(times);
		config.reviveMode = TimedHardcoreConfig.ReviveMode.FIXED_TIMES;
		return saveRevival(source, config);
	}

	private static int setMode(CommandSourceStack source, TimedHardcoreConfig.ReviveMode mode) {
		TimedHardcoreConfig config = TimedHardcore.config();
		if (mode == TimedHardcoreConfig.ReviveMode.FIXED_TIMES && config.reviveTimes.isEmpty()) {
			source.sendFailure(Component.literal("Set the times first: /timedhardcore times 0:00 20:00"));
			return 0;
		}
		config.reviveMode = mode;
		return saveRevival(source, config);
	}

	/** After the revival time changed: saves and recalculates all dead players from their time of death. */
	private static int saveRevival(CommandSourceStack source, TimedHardcoreConfig config) {
		config.save();
		int recalculated = TimedHardcore.recalculateRevivals(source.getServer());
		source.sendSuccess(() -> success("Revival is now " + config.describe() + "." + recalculatedNote(recalculated)), true);
		return 1;
	}

	// --- Safe rule ---

	private static String describeSafe(int count) {
		return count > 0
			? "Deaths have no consequences while at least " + plural(count, "player is", "players are") + " online."
			: "Safe rule off: every death counts.";
	}

	private static int showSafe(CommandSourceStack source) {
		source.sendSuccess(() -> Component.literal(describeSafe(TimedHardcore.config().safePlayerCount)), false);
		return 1;
	}

	private static int setSafe(CommandSourceStack source, int count) {
		TimedHardcoreConfig config = TimedHardcore.config();
		config.safePlayerCount = count;
		config.save();
		source.sendSuccess(() -> success(describeSafe(count)), true);
		return 1;
	}

	// --- Extra lives, apple and healer ---

	private static int giveLife(CommandSourceStack source, Collection<NameAndId> players) {
		ExtraLifeManager lives = TimedHardcore.lives();
		if (lives == null) {
			return 0;
		}
		boolean greenIcon = ClientSync.hasClientMod(source.getPlayer());
		int given = 0;
		for (NameAndId player : players) {
			if (lives.grant(player.id(), player.name())) {
				given++;
				source.sendSuccess(() -> Messages.extraLifeGained(new GameProfile(player.id(), player.name()), greenIcon), true);
			} else {
				source.sendFailure(Component.literal(player.name() + " already has an extra life."));
			}
		}
		return given;
	}

	private static int takeLife(CommandSourceStack source, String name) {
		ExtraLifeManager lives = TimedHardcore.lives();
		if (lives == null) {
			return 0;
		}
		return lives.removeByName(name).map(storedName -> {
			source.sendSuccess(() -> success(storedName + " no longer has an extra life."), true);
			return 1;
		}).orElseGet(() -> {
			source.sendFailure(Component.literal(name + " has no extra life."));
			return 0;
		});
	}

	private static int giveApple(CommandSourceStack source, Collection<ServerPlayer> players) {
		for (ServerPlayer player : players) {
			// Goes into the inventory or drops at the player's feet if it is full
			player.getInventory().placeItemBackInInventory(GreenApple.create(), Prediction.SERVER_ONLY);
		}
		source.sendSuccess(() -> success("Gave a green apple to " + plural(players.size(), "player", "players") + "."), true);
		return players.size();
	}

	private static int spawnHealer(CommandSourceStack source) {
		BlockPos pos = BlockPos.containing(source.getPosition());
		if (Healer.spawn(source.getLevel(), pos) == null) {
			source.sendFailure(Component.literal("The healer could not be spawned here."));
			return 0;
		}
		source.sendSuccess(() -> success("A Wandering Healer has appeared."), true);
		return 1;
	}
}
