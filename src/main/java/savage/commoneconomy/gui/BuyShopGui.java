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

/** Creative-style buy screen: top row = category tabs (paged if many), grid below = buyable items, paged. */
public class BuyShopGui extends SimpleGui {
    private static final int TAB_SLOTS = 9;       // row 0
    private static final int TABS_PER_PAGE = 7;   // when categories overflow: slots 0-6 + prev(7)/next(8)
    private static final int GRID_START = 9;       // rows 1-4
    private static final int GRID_SIZE = 36;       // 4 rows * 9
    private static final int NAV_ROW = 45;         // row 5

    private final Map<String, Map<String, ItemPrice>> buyable;
    private final List<String> categories;
    private String category;
    private int page;      // item-grid page
    private int tabPage;   // tab-window page (only used when categories overflow)

    public BuyShopGui(ServerPlayerEntity player, String category) {
        super(ScreenHandlerType.GENERIC_9X6, player, false);
        this.buyable = EconomyManager.getInstance().getBuyableCategories();
        this.categories = new ArrayList<>(buyable.keySet());
        this.category = category != null ? category : (categories.isEmpty() ? null : categories.get(0));
        this.page = 0;
        this.tabPage = 0;
        render();
    }

    private void render() {
        setTitle(Text.literal("Buy" + (category != null ? " — " + category : "") + "  |  " + balanceStr()));
        renderTabs();
        List<Map.Entry<String, ItemPrice>> list = currentItems();
        renderGrid(list);
        renderNav(list.size());
    }

    private void renderTabs() {
        boolean overflow = categories.size() > TAB_SLOTS;
        int visible = overflow ? TABS_PER_PAGE : TAB_SLOTS;
        int start = overflow ? tabPage * TABS_PER_PAGE : 0;
        for (int i = 0; i < visible; i++) {
            int idx = start + i;
            if (idx < categories.size()) {
                setSlot(i, tabButton(categories.get(idx)));
            } else {
                setSlot(i, filler());
            }
        }
        if (overflow) {
            boolean hasPrev = tabPage > 0;
            boolean hasNext = (tabPage + 1) * TABS_PER_PAGE < categories.size();
            setSlot(7, new GuiElementBuilder(hasPrev ? Items.SPECTRAL_ARROW : Items.GRAY_STAINED_GLASS_PANE)
                    .setName(Text.literal(hasPrev ? "◀ More categories" : " "))
                    .setCallback((i, t, a, g) -> { if (hasPrev) { tabPage--; render(); } }));
            setSlot(8, new GuiElementBuilder(hasNext ? Items.SPECTRAL_ARROW : Items.GRAY_STAINED_GLASS_PANE)
                    .setName(Text.literal(hasNext ? "More categories ▶" : " "))
                    .setCallback((i, t, a, g) -> { if (hasNext) { tabPage++; render(); } }));
        }
    }

    private GuiElementBuilder tabButton(String cat) {
        boolean active = cat.equals(category);
        return new GuiElementBuilder(active ? Items.WRITABLE_BOOK : Items.BOOK)
                .setName(Text.literal((active ? "▶ " : "") + cat))
                .setCallback((i, t, a, g) -> { this.category = cat; this.page = 0; render(); });
    }

    private List<Map.Entry<String, ItemPrice>> currentItems() {
        Map<String, ItemPrice> items = category != null ? buyable.getOrDefault(category, Map.of()) : Map.of();
        return new ArrayList<>(items.entrySet());
    }

    private void renderGrid(List<Map.Entry<String, ItemPrice>> list) {
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
    }

    private void renderNav(int listSize) {
        setSlot(NAV_ROW, new GuiElementBuilder(Items.BARRIER).setName(Text.literal("Back"))
                .setCallback((i, t, a, g) -> new ShopHubGui(getPlayer()).open()));
        boolean hasPrev = page > 0;
        boolean hasNext = (page + 1) * GRID_SIZE < listSize;
        setSlot(NAV_ROW + 3, new GuiElementBuilder(hasPrev ? Items.ARROW : Items.GRAY_STAINED_GLASS_PANE)
                .setName(Text.literal(hasPrev ? "Prev Page" : " "))
                .setCallback((i, t, a, g) -> { if (page > 0) { page--; render(); } }));
        setSlot(NAV_ROW + 5, new GuiElementBuilder(hasNext ? Items.ARROW : Items.GRAY_STAINED_GLASS_PANE)
                .setName(Text.literal(hasNext ? "Next Page" : " "))
                .setCallback((i, t, a, g) -> { if ((page + 1) * GRID_SIZE < listSize) { page++; render(); } }));
    }

    private GuiElementBuilder buyButton(String itemId, ItemPrice price) {
        Item item = Registries.ITEM.get(Identifier.tryParse(itemId));
        return new GuiElementBuilder(item)
                .setName(item.getName())
                .addLoreLine(Text.literal(itemId))
                .addLoreLine(Text.literal("Buy: " + EconomyManager.getInstance().format(price.buy) + " each"))
                .addLoreLine(Text.literal("Click: 1   Shift-click: 64"))
                .setCallback((idx, type, action, gui) -> {
                    int amount = type.shift ? 64 : 1;
                    feedback(TradeService.buy(getPlayer(), itemId, amount), itemId);
                    render();
                });
    }

    private GuiElementBuilder filler() {
        return new GuiElementBuilder(Items.GRAY_STAINED_GLASS_PANE).setName(Text.literal(" "));
    }

    private String balanceStr() {
        EconomyManager eco = EconomyManager.getInstance();
        return eco.format(eco.getBalance(getPlayer().getUuid()));
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
}
