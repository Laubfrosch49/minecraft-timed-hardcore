package dev.laubfrosch.timedhardcore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.jspecify.annotations.Nullable;

/**
 * The "Wandering Healer": a wandering trader with healing offers.
 * Every healer has 7 offers: 6 random ones from a pool (with varying prices and amounts)
 * and always the green apple at the bottom, once per healer and expensive.
 */
public final class Healer {

	private static final String TAG = "timed_hardcore_healer";
	/** The client mod also recognises the healer by this name (for its own texture). */
	public static final String NAME = "Wandering Healer";
	/** Number of random offers in addition to the green apple. */
	public static final int RANDOM_OFFERS = 6;
	/** Price of the green apple in emeralds (plus 1 enchanted golden apple). */
	public static final int GREEN_APPLE_PRICE_EMERALDS = 64;

	/** Possible offer: amount and price (emeralds) are picked at random within the range for each healer. */
	private record Trade(Supplier<ItemStack> item, int minCount, int maxCount, int minPrice, int maxPrice, int maxUses) {
	}

	private static final List<Trade> POOL = List.of(
		new Trade(() -> new ItemStack(Items.GLISTERING_MELON_SLICE), 2, 4, 1, 3, 8),
		new Trade(() -> new ItemStack(Items.GLOW_BERRIES), 4, 8, 1, 2, 8),
		new Trade(() -> new ItemStack(Items.HONEY_BOTTLE), 2, 4, 1, 3, 6),
		new Trade(() -> new ItemStack(Items.GOLDEN_CARROT), 4, 8, 2, 4, 8),
		new Trade(() -> new ItemStack(Items.MILK_BUCKET), 1, 1, 2, 4, 4),
		new Trade(potion(Items.POTION, Potions.STRONG_HEALING), 1, 1, 4, 7, 3),
		new Trade(potion(Items.POTION, Potions.LONG_REGENERATION), 1, 1, 5, 8, 3),
		new Trade(potion(Items.SPLASH_POTION, Potions.STRONG_HEALING), 1, 1, 6, 9, 3),
		new Trade(potion(Items.LINGERING_POTION, Potions.REGENERATION), 1, 1, 8, 12, 2),
		new Trade(potion(Items.POTION, Potions.LONG_FIRE_RESISTANCE), 1, 1, 5, 9, 2),
		new Trade(potion(Items.POTION, Potions.LONG_WATER_BREATHING), 1, 1, 4, 7, 2),
		new Trade(potion(Items.POTION, Potions.LONG_SLOW_FALLING), 1, 1, 4, 7, 2),
		new Trade(potion(Items.POTION, Potions.TURTLE_MASTER), 1, 1, 8, 12, 2),
		new Trade(() -> new ItemStack(Items.GOLDEN_APPLE), 1, 1, 9, 14, 2)
	);

	private Healer() {
	}

	public static boolean isHealer(WanderingTrader trader) {
		return trader.entityTags().contains(TAG);
	}

	/** Turns a freshly spawned wandering trader into a healer. */
	public static void convert(WanderingTrader trader) {
		trader.addTag(TAG);
		applyName(trader);
		// Offers are created on first access and saved with the trader afterwards
		MerchantOffers offers = trader.getOffers();
		offers.clear();
		offers.addAll(createOffers(trader.getRandom()));
	}

	/**
	 * Name without colour and without a permanent name tag. It stays set because it is the title of
	 * the trading screen and the client mod recognises the healer by it.
	 */
	private static void applyName(WanderingTrader trader) {
		trader.setCustomName(Component.literal(NAME));
		trader.setCustomNameVisible(false);
	}

	/** For /timedhardcore healer. */
	public static @Nullable WanderingTrader spawn(ServerLevel level, BlockPos pos) {
		WanderingTrader trader = EntityTypes.WANDERING_TRADER.spawn(level, pos, EntitySpawnReason.COMMAND);
		if (trader != null) {
			convert(trader);
			trader.setDespawnDelay(48000);
		}
		return trader;
	}

	private static MerchantOffers createOffers(RandomSource random) {
		// Draw 6 different offers from the pool, roll amount and price, sort by price
		List<Trade> pool = new ArrayList<>(POOL);
		List<MerchantOffer> picked = new ArrayList<>();
		for (int i = 0; i < RANDOM_OFFERS && !pool.isEmpty(); i++) {
			Trade trade = pool.remove(random.nextInt(pool.size()));
			ItemStack result = trade.item().get();
			result.setCount(between(random, trade.minCount(), trade.maxCount()));
			int price = between(random, trade.minPrice(), trade.maxPrice());
			picked.add(new MerchantOffer(new ItemCost(Items.EMERALD, price), result, trade.maxUses(), 1, 0.05F));
		}
		picked.sort(Comparator.comparingInt(offer -> offer.getBaseCostA().getCount()));

		MerchantOffers offers = new MerchantOffers();
		offers.addAll(picked);
		// The green apple always comes last and can be bought only once
		offers.add(new MerchantOffer(
			new ItemCost(Items.EMERALD, GREEN_APPLE_PRICE_EMERALDS),
			Optional.of(new ItemCost(Items.ENCHANTED_GOLDEN_APPLE, 1)),
			GreenApple.create(), 1, 1, 0.0F));
		return offers;
	}

	private static int between(RandomSource random, int min, int max) {
		return min + random.nextInt(max - min + 1);
	}

	private static Supplier<ItemStack> potion(Item item, Holder<Potion> potion) {
		return () -> PotionContents.createItemStack(item, potion);
	}
}
