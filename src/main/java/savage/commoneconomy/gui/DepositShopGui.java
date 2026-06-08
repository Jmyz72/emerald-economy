package savage.commoneconomy.gui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import savage.commoneconomy.EconomyManager;

/** Drop emeralds in; on close they convert to balance (minus fee). Non-emeralds are returned. */
public class DepositShopGui extends SimpleGui {
    private static final int DROP_SLOTS = 45;
    private final SimpleInventory inv = new SimpleInventory(DROP_SLOTS);
    private boolean settled = false; // guards against onClose firing twice (Back button -> double close)

    public DepositShopGui(ServerPlayerEntity player) {
        super(ScreenHandlerType.GENERIC_9X6, player, false);
        setTitle(Text.literal("Deposit — drop emeralds, close to convert"));
        for (int i = 0; i < DROP_SLOTS; i++) {
            setSlotRedirect(i, new Slot(inv, i, 0, 0));
        }
        setSlot(49, new GuiElementBuilder(Items.BARRIER).setName(Text.literal("Back / Deposit & close"))
                .setCallback((i, t, a, g) -> close()));
        // Ensure dropped emeralds are settled even on an abrupt disconnect (sgui onClose won't fire then).
        ShopDropGuis.register(player.getUuid(), this::settle);
    }

    @Override
    public void onClose() {
        settle();
        super.onClose();
    }

    /** Convert dropped emeralds / return non-emeralds. Runs once (guards against double close and disconnect). */
    private void settle() {
        if (settled) return;
        settled = true;
        ServerPlayerEntity p = getPlayer();
        ShopDropGuis.unregister(p.getUuid());
        int emeralds = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            if (stack.getItem() == Items.EMERALD) {
                emeralds += stack.getCount();
            } else {
                p.getInventory().offerOrDrop(stack.copy()); // return non-emeralds
            }
        }
        inv.clear();
        if (emeralds > 0) {
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
    }
}
