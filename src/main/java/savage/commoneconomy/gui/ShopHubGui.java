package savage.commoneconomy.gui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import savage.commoneconomy.EconomyManager;

/** Root shop menu. Each slot is an item-button linking to a sub-screen. */
public class ShopHubGui extends SimpleGui {

    public ShopHubGui(ServerPlayerEntity player) {
        super(ScreenHandlerType.GENERIC_9X3, player, false);
        setTitle(Text.literal("Shop"));
        build();
    }

    private void build() {
        ServerPlayerEntity p = getPlayer();
        setSlot(4, new GuiElementBuilder(Items.EMERALD)
                .setName(Text.literal("Balance: " + EconomyManager.getInstance().format(
                        EconomyManager.getInstance().getBalance(p.getUuid())))));

        setButton(10, Items.DIAMOND, "Buy", () -> new BuyShopGui(p, null).open());
        setButton(11, Items.HOPPER, "Sell", () -> new SellShopGui(p).open());
        setButton(13, Items.EMERALD_BLOCK, "Deposit", () -> new DepositShopGui(p).open());
        setButton(14, Items.GOLD_INGOT, "Withdraw", () -> new WithdrawShopGui(p).open());
        setButton(15, Items.PAPER, "Transfer", () -> new TransferShopGui(p).open());
        setButton(16, Items.PLAYER_HEAD, "Top Balances", () -> new TopBalancesShopGui(p).open());
    }

    private void setButton(int slot, Item icon, String name, Runnable onClick) {
        setSlot(slot, new GuiElementBuilder(icon)
                .setName(Text.literal(name))
                .setCallback((index, type, action, gui) -> onClick.run()));
    }
}
