package savage.emeraldeconomy.economy;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import savage.emeraldeconomy.EconomyManager;
import savage.emeraldeconomy.util.TransactionLogger;

import java.math.BigDecimal;

/**
 * One transaction path for buying and selling, shared by the /buy and /sell
 * commands and the shop GUI. Always moves the balance first, then items, so a
 * storage failure never destroys items or currency.
 */
public final class TradeService {
    private TradeService() {}

    public enum Status { OK, NOT_TRADEABLE, INSUFFICIENT_FUNDS, NONE_HELD, IS_CURRENCY, UNKNOWN_ITEM, FAILED }

    public record Result(Status status, int amount, BigDecimal total) {
        public boolean ok() { return status == Status.OK; }
        public static Result of(Status s) { return new Result(s, 0, BigDecimal.ZERO); }
    }

    /** Buy {@code amount} of an item, debiting the player and delivering the items. */
    public static Result buy(ServerPlayerEntity player, String itemId, int amount) {
        EconomyManager eco = EconomyManager.getInstance();
        Identifier id = Identifier.tryParse(itemId);
        if (id == null || !Registries.ITEM.containsId(id)) return Result.of(Status.UNKNOWN_ITEM);
        if (eco.isCurrencyItem(itemId)) return Result.of(Status.IS_CURRENCY);
        if (!eco.isBuyable(itemId)) return Result.of(Status.NOT_TRADEABLE);

        BigDecimal unit = eco.getBuyPrice(itemId);
        BigDecimal total = unit.multiply(BigDecimal.valueOf(amount));
        if (!eco.removeBalance(player.getUuid(), total)) return new Result(Status.INSUFFICIENT_FUNDS, amount, total);

        Item item = Registries.ITEM.get(id);
        int remaining = amount;
        int maxStack = new ItemStack(item).getMaxCount();
        while (remaining > 0) {
            int give = Math.min(remaining, maxStack);
            player.getInventory().offerOrDrop(new ItemStack(item, give));
            remaining -= give;
        }
        TransactionLogger.log("COMMAND_BUY", player.getName().getString(), "Server", total,
                "Bought " + amount + "x " + itemId);
        return new Result(Status.OK, amount, total);
    }

    /**
     * Sell up to {@code maxAmount} of an item found anywhere in the player's
     * inventory. Credits the player and removes the items.
     */
    public static Result sell(ServerPlayerEntity player, String itemId, int maxAmount) {
        EconomyManager eco = EconomyManager.getInstance();
        Identifier id = Identifier.tryParse(itemId);
        if (id == null || !Registries.ITEM.containsId(id)) return Result.of(Status.UNKNOWN_ITEM);
        if (eco.isCurrencyItem(itemId)) return Result.of(Status.IS_CURRENCY);
        if (!eco.isSellable(itemId)) return Result.of(Status.NOT_TRADEABLE);

        Item item = Registries.ITEM.get(id);
        int held = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack s = player.getInventory().getStack(i);
            if (!s.isEmpty() && s.getItem() == item) held += s.getCount();
        }
        if (held <= 0) return Result.of(Status.NONE_HELD);

        int toSell = Math.min(maxAmount, held);
        BigDecimal unit = eco.getSellPrice(itemId);
        BigDecimal total = unit.multiply(BigDecimal.valueOf(toSell));
        if (!eco.addBalance(player.getUuid(), total)) return Result.of(Status.FAILED);

        int remaining = toSell;
        for (int i = 0; i < player.getInventory().size() && remaining > 0; i++) {
            ItemStack s = player.getInventory().getStack(i);
            if (!s.isEmpty() && s.getItem() == item) {
                int take = Math.min(remaining, s.getCount());
                s.decrement(take);
                remaining -= take;
            }
        }
        TransactionLogger.log("COMMAND_SELL", player.getName().getString(), "Server", total,
                "Sold " + toSell + "x " + itemId);
        return new Result(Status.OK, toSell, total);
    }

    /**
     * Sell every sellable stack in the player's whole inventory (across all item types).
     * Returns NONE_HELD if the inventory has nothing sellable; otherwise OK with the total
     * item count and total credited.
     */
    public static Result sellEverything(ServerPlayerEntity player) {
        EconomyManager eco = EconomyManager.getInstance();
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack s = player.getInventory().getStack(i);
            if (s.isEmpty()) continue;
            String id = Registries.ITEM.getId(s.getItem()).toString();
            if (!eco.isCurrencyItem(id) && eco.isSellable(id)) ids.add(id);
        }
        if (ids.isEmpty()) return Result.of(Status.NONE_HELD);
        BigDecimal grandTotal = BigDecimal.ZERO;
        int totalItems = 0;
        for (String id : ids) {
            Result r = sell(player, id, Integer.MAX_VALUE);
            if (r.ok()) { grandTotal = grandTotal.add(r.total()); totalItems += r.amount(); }
        }
        return new Result(Status.OK, totalItems, grandTotal);
    }
}
