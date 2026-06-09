package savage.emeraldeconomy.gui;

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
import savage.emeraldeconomy.EconomyManager;

import java.math.BigDecimal;

/**
 * Drop-to-sell chest. The player drops stacks into the open slots and clicks "Sell"
 * to sell the sellable ones (the screen stays open). "Back" returns to the hub and
 * hands back anything still in the grid. Closing/Esc/disconnect always RETURNS the
 * dropped items — selling only happens on an explicit "Sell" click, and items are
 * never destroyed.
 */
public class SellShopGui extends SimpleGui {
    private static final int DROP_SLOTS = 45; // rows 0-4
    private final SimpleInventory inv = new SimpleInventory(DROP_SLOTS);
    private boolean returned = false; // guards the return-items pass (runs once)

    public SellShopGui(ServerPlayerEntity player) {
        super(ScreenHandlerType.GENERIC_9X6, player, false);
        setTitle(Text.literal("Sell — drop items, then click Sell"));
        for (int i = 0; i < DROP_SLOTS; i++) {
            setSlotRedirect(i, new Slot(inv, i, 0, 0));
        }
        setSlot(48, new GuiElementBuilder(Items.EMERALD).setName(Text.literal("Sell dropped items"))
                .setCallback((i, t, a, g) -> sellDropped()));
        setSlot(50, new GuiElementBuilder(Items.BARRIER).setName(Text.literal("Back"))
                .setCallback((i, t, a, g) -> { returnAll(); new ShopHubGui(getPlayer()).open(); }));
        // If the player disconnects with items in the grid, return them (onClose won't fire then).
        ShopDropGuis.register(player.getUuid(), this::returnAll);
    }

    @Override
    public void onClose() {
        returnAll(); // closing / Esc / disconnect returns dropped items, never sells
        super.onClose();
    }

    /** Sell every sellable dropped stack; leave non-sellable stacks in the grid. Screen stays open. */
    private void sellDropped() {
        ServerPlayerEntity p = getPlayer();
        EconomyManager eco = EconomyManager.getInstance();
        BigDecimal credited = BigDecimal.ZERO;
        int sold = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            String id = Registries.ITEM.getId(stack.getItem()).toString();
            if (!eco.isSellable(id)) continue; // leave non-sellable items where they are
            BigDecimal total = eco.getSellPrice(id).multiply(BigDecimal.valueOf(stack.getCount()));
            if (eco.addBalance(p.getUuid(), total)) {
                savage.emeraldeconomy.util.TransactionLogger.log("GUI_SELL", p.getName().getString(), "Server", total,
                        "Sold " + stack.getCount() + "x " + id);
                credited = credited.add(total);
                sold += stack.getCount();
                inv.setStack(i, ItemStack.EMPTY);
            }
        }
        if (sold > 0) {
            p.sendMessage(Text.literal("Sold " + sold + " item(s) for " + eco.format(credited)), false);
        } else {
            p.sendMessage(Text.literal("Nothing sellable in the grid. Use Back to take your items."), false);
        }
    }

    /** Return every dropped stack to the player (no selling). Runs once. */
    private void returnAll() {
        if (returned) return;
        returned = true;
        ServerPlayerEntity p = getPlayer();
        ShopDropGuis.unregister(p.getUuid());
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty()) p.getInventory().offerOrDrop(stack.copy());
        }
        inv.clear();
    }
}
