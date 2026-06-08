package savage.commoneconomy.gui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import savage.commoneconomy.EconomyManager;

import java.math.BigDecimal;

/**
 * Drop-to-sell chest. The player places stacks into the open slots; on close,
 * each sellable stack is sold (credited) and the rest is returned. Items are
 * never destroyed: on every close path leftover stacks go back to the player.
 */
public class SellShopGui extends SimpleGui {
    private static final int DROP_SLOTS = 45; // rows 0-4
    private final SimpleInventory inv = new SimpleInventory(DROP_SLOTS);
    private boolean settled = false; // guards against onClose firing twice (Back button -> double close)

    public SellShopGui(ServerPlayerEntity player) {
        super(ScreenHandlerType.GENERIC_9X6, player, false);
        setTitle(Text.literal("Sell — drop items, close to sell"));
        for (int i = 0; i < DROP_SLOTS; i++) {
            setSlotRedirect(i, new Slot(inv, i, 0, 0));
        }
        setSlot(49, new GuiElementBuilder(Items.BARRIER).setName(Text.literal("Back / Sell & close"))
                .setCallback((i, t, a, g) -> close()));
        // Ensure dropped items are settled even on an abrupt disconnect (sgui onClose won't fire then).
        ShopDropGuis.register(player.getUuid(), this::settle);
    }

    @Override
    public void onClose() {
        settle();
        super.onClose();
    }

    /** Sell/return every dropped stack. Runs once (guards against double close and disconnect). */
    private void settle() {
        if (settled) return;
        settled = true;
        ServerPlayerEntity p = getPlayer();
        ShopDropGuis.unregister(p.getUuid());
        BigDecimal totalCredited = BigDecimal.ZERO;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            String id = Registries.ITEM.getId(stack.getItem()).toString();
            if (EconomyManager.getInstance().isSellable(id)) {
                BigDecimal unit = EconomyManager.getInstance().getSellPrice(id);
                BigDecimal total = unit.multiply(BigDecimal.valueOf(stack.getCount()));
                if (EconomyManager.getInstance().addBalance(p.getUuid(), total)) {
                    savage.commoneconomy.util.TransactionLogger.log("GUI_SELL", p.getName().getString(), "Server", total,
                            "Sold " + stack.getCount() + "x " + id);
                    totalCredited = totalCredited.add(total);
                    continue; // sold & credited -> consume
                }
            }
            // Not sellable, or credit failed -> return to player, never void.
            p.getInventory().offerOrDrop(stack.copy());
        }
        inv.clear();
        if (totalCredited.signum() > 0) {
            p.sendMessage(Text.literal("Sold for " + EconomyManager.getInstance().format(totalCredited)), false);
        }
    }
}
