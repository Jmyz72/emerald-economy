package savage.commoneconomy.gui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import savage.commoneconomy.EconomyManager;

import java.util.ArrayList;
import java.util.List;

/** Pick an online player to pay, shown as heads. */
public class TransferShopGui extends SimpleGui {
    private static final int PER_PAGE = 45;
    private int page = 0;

    public TransferShopGui(ServerPlayerEntity player) {
        super(ScreenHandlerType.GENERIC_9X6, player, false);
        render();
    }

    private void render() {
        setTitle(Text.literal("Transfer — pick a player"));
        List<ServerPlayerEntity> online = new ArrayList<>(
                EconomyManager.getInstance().getServer().getPlayerManager().getPlayerList());
        online.removeIf(t -> t.getUuid().equals(getPlayer().getUuid())); // not yourself
        int from = page * PER_PAGE;
        for (int slot = 0; slot < PER_PAGE; slot++) {
            int idx = from + slot;
            if (idx < online.size()) {
                ServerPlayerEntity target = online.get(idx);
                setSlot(slot, new GuiElementBuilder(Items.PLAYER_HEAD)
                        .setProfile(target.getGameProfile())
                        .setName(Text.literal(target.getName().getString()))
                        .setCallback((i, t, a, g) -> new TransferAmountGui(getPlayer(), target).open()));
            } else {
                setSlot(slot, new GuiElementBuilder(Items.AIR));
            }
        }
        int total = online.size();
        setSlot(49, new GuiElementBuilder(Items.BARRIER).setName(Text.literal("Back"))
                .setCallback((i, t, a, g) -> new ShopHubGui(getPlayer()).open()));
        boolean hasPrev = page > 0;
        boolean hasNext = (page + 1) * PER_PAGE < total;
        setSlot(48, new GuiElementBuilder(hasPrev ? Items.ARROW : Items.GRAY_STAINED_GLASS_PANE)
                .setName(Text.literal(hasPrev ? "Prev" : " "))
                .setCallback((i, t, a, g) -> { if (page > 0) { page--; render(); } }));
        setSlot(50, new GuiElementBuilder(hasNext ? Items.ARROW : Items.GRAY_STAINED_GLASS_PANE)
                .setName(Text.literal(hasNext ? "Next" : " "))
                .setCallback((i, t, a, g) -> { if ((page + 1) * PER_PAGE < total) { page++; render(); } }));
    }
}
