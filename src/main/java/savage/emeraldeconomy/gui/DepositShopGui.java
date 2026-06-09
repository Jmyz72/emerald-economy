package savage.emeraldeconomy.gui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import savage.emeraldeconomy.EconomyManager;

/**
 * Drop emeralds in and click "Deposit" to convert them to balance (minus fee); the screen
 * stays open. "Back" returns to the hub, handing back anything still in the grid.
 * Closing/Esc/disconnect always RETURNS the dropped items — converting only happens on an
 * explicit "Deposit" click, and items are never destroyed.
 */
public class DepositShopGui extends SimpleGui {
    private static final int DROP_SLOTS = 45;
    private final SimpleInventory inv = new SimpleInventory(DROP_SLOTS);
    private boolean returned = false; // guards the return-items pass (runs once)

    public DepositShopGui(ServerPlayerEntity player) {
        super(ScreenHandlerType.GENERIC_9X6, player, false);
        setTitle(Text.literal("Deposit — drop emeralds, then click Deposit"));
        for (int i = 0; i < DROP_SLOTS; i++) {
            setSlotRedirect(i, new Slot(inv, i, 0, 0));
        }
        setSlot(48, new GuiElementBuilder(Items.EMERALD_BLOCK).setName(Text.literal("Deposit dropped emeralds"))
                .setCallback((i, t, a, g) -> depositDropped()));
        setSlot(50, new GuiElementBuilder(Items.BARRIER).setName(Text.literal("Back"))
                .setCallback((i, t, a, g) -> { returnAll(); new ShopHubGui(getPlayer()).open(); }));
        // If the player disconnects with items in the grid, return them (onClose won't fire then).
        ShopDropGuis.register(player.getUuid(), this::returnAll);
    }

    @Override
    public void onClose() {
        returnAll(); // closing / Esc / disconnect returns dropped items, never deposits
        super.onClose();
    }

    /** Convert dropped emeralds to balance; immediately hand back any non-emeralds. Screen stays open. */
    private void depositDropped() {
        ServerPlayerEntity p = getPlayer();
        int emeralds = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            if (stack.getItem() == Items.EMERALD) {
                emeralds += stack.getCount();
            } else {
                p.getInventory().offerOrDrop(stack.copy()); // non-emeralds go straight back
            }
            inv.setStack(i, ItemStack.EMPTY);
        }
        if (emeralds == 0) {
            p.sendMessage(Text.literal("No emeralds in the grid to deposit."), false);
            return;
        }
        EconomyManager.DepositResult dr = EconomyManager.getInstance().depositEmeralds(p.getUuid(), emeralds);
        if (dr.ok()) {
            p.sendMessage(Text.literal("Deposited " + emeralds + " emeralds → "
                    + EconomyManager.getInstance().format(dr.net()) + " (fee " + dr.feePercent() + "%)"), false);
        } else {
            // credit failed: return the emeralds so nothing is lost
            p.getInventory().offerOrDrop(new ItemStack(Items.EMERALD, emeralds));
            p.sendMessage(Text.literal("Deposit failed; emeralds returned."), false);
        }
    }

    /** Return every dropped stack to the player (no deposit). Runs once. */
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
