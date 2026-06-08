package savage.commoneconomy.gui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import savage.commoneconomy.EconomyManager;
import savage.commoneconomy.config.ItemPrice;
import savage.commoneconomy.economy.TradeService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Creative-style buy screen: top row = category tabs, grid below = buyable items, paged. */
public class BuyShopGui extends SimpleGui {
    private static final int TABS = 9;        // row 0
    private static final int GRID_START = 9;  // rows 1-4
    private static final int GRID_SIZE = 36;  // 4 rows * 9
    private static final int NAV_ROW = 45;    // row 5

    private final List<String> categories;
    private String category;
    private int page;

    public BuyShopGui(ServerPlayerEntity player, String category) {
        super(ScreenHandlerType.GENERIC_9X6, player, false);
        this.categories = new ArrayList<>(EconomyManager.getInstance().getBuyableCategories().keySet());
        this.category = category != null ? category : (categories.isEmpty() ? null : categories.get(0));
        this.page = 0;
        render();
    }

    private void render() {
        setTitle(Text.literal("Buy" + (category != null ? " — " + category : "")));
        for (int i = 0; i < TABS; i++) {
            if (i < categories.size()) {
                String cat = categories.get(i);
                boolean active = cat.equals(category);
                setSlot(i, new GuiElementBuilder(active ? Items.WRITABLE_BOOK : Items.BOOK)
                        .setName(Text.literal((active ? "▶ " : "") + cat))
                        .setCallback((idx, t, a, g) -> { this.category = cat; this.page = 0; render(); }));
            } else {
                setSlot(i, new GuiElementBuilder(Items.GRAY_STAINED_GLASS_PANE).setName(Text.literal(" ")));
            }
        }

        Map<String, ItemPrice> items = category != null
                ? EconomyManager.getInstance().getBuyableCategories().getOrDefault(category, Map.of())
                : Map.of();
        List<Map.Entry<String, ItemPrice>> list = new ArrayList<>(items.entrySet());
        int listSize = list.size();
        int from = page * GRID_SIZE;
        for (int slot = 0; slot < GRID_SIZE; slot++) {
            int idx = from + slot;
            if (idx < list.size()) {
                Map.Entry<String, ItemPrice> e = list.get(idx);
                setSlot(GRID_START + slot, buyButton(e.getKey(), e.getValue()));
            } else {
                setSlot(GRID_START + slot, new GuiElementBuilder(Items.AIR));
            }
        }

        setSlot(NAV_ROW, new GuiElementBuilder(Items.BARRIER).setName(Text.literal("Back"))
                .setCallback((i, t, a, g) -> new ShopHubGui(getPlayer()).open()));
        setSlot(NAV_ROW + 3, new GuiElementBuilder(Items.ARROW).setName(Text.literal("Prev Page"))
                .setCallback((i, t, a, g) -> { if (page > 0) { page--; render(); } }));
        setSlot(NAV_ROW + 5, new GuiElementBuilder(Items.ARROW).setName(Text.literal("Next Page"))
                .setCallback((i, t, a, g) -> { if ((page + 1) * GRID_SIZE < listSize) { page++; render(); } }));
    }

    private GuiElementBuilder buyButton(String itemId, ItemPrice price) {
        Item item = Registries.ITEM.get(Identifier.tryParse(itemId));
        return new GuiElementBuilder(item)
                .setName(Text.literal(itemId))
                .addLoreLine(Text.literal("Buy: " + EconomyManager.getInstance().format(price.buy) + " each"))
                .addLoreLine(Text.literal("Click: 1   Shift-click: 64"))
                .setCallback((idx, type, action, gui) -> {
                    int amount = type.shift ? 64 : 1;
                    TradeService.Result r = TradeService.buy(getPlayer(), itemId, amount);
                    feedback(r, itemId);
                    refreshBalanceTitle();
                });
    }

    private void feedback(TradeService.Result r, String itemId) {
        ServerPlayerEntity p = getPlayer();
        switch (r.status()) {
            case OK -> p.sendMessage(Text.literal("Bought " + r.amount() + "x " + itemId
                    + " for " + EconomyManager.getInstance().format(r.total())), true);
            case INSUFFICIENT_FUNDS -> p.sendMessage(Text.literal("Insufficient funds."), true);
            case NOT_TRADEABLE -> p.sendMessage(Text.literal("Not available."), true);
            default -> p.sendMessage(Text.literal("Transaction failed."), true);
        }
    }

    private void refreshBalanceTitle() {
        setTitle(Text.literal("Buy — " + category + "  |  "
                + EconomyManager.getInstance().format(EconomyManager.getInstance().getBalance(getPlayer().getUuid()))));
    }
}
