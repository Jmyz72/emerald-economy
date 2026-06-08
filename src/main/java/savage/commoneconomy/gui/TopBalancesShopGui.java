package savage.commoneconomy.gui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import savage.commoneconomy.EconomyManager;

import java.util.List;

/** Read-only leaderboard of the top balances. */
public class TopBalancesShopGui extends SimpleGui {

    public TopBalancesShopGui(ServerPlayerEntity player) {
        super(ScreenHandlerType.GENERIC_9X6, player, false);
        setTitle(Text.literal("Top Balances"));
        List<EconomyManager.AccountData> top = EconomyManager.getInstance().getTopAccounts(45);
        for (int i = 0; i < 45; i++) {
            if (i < top.size()) {
                EconomyManager.AccountData a = top.get(i);
                setSlot(i, new GuiElementBuilder(Items.PLAYER_HEAD)
                        .setProfile(a.name)
                        .setName(Text.literal("#" + (i + 1) + " " + a.name))
                        .addLoreLine(Text.literal(EconomyManager.getInstance().format(a.balance))));
            } else {
                setSlot(i, new GuiElementBuilder(Items.AIR));
            }
        }
        setSlot(49, new GuiElementBuilder(Items.BARRIER).setName(Text.literal("Back"))
                .setCallback((i, t, a, g) -> new ShopHubGui(getPlayer()).open()));
    }
}
