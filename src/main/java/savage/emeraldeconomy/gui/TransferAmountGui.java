package savage.emeraldeconomy.gui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.AnvilInputGui;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import savage.emeraldeconomy.EconomyManager;

import java.math.BigDecimal;

/** Type the amount to send to the chosen recipient, then confirm. */
public class TransferAmountGui extends AnvilInputGui {
    private final ServerPlayerEntity target;

    public TransferAmountGui(ServerPlayerEntity sender, ServerPlayerEntity target) {
        super(sender, false);
        this.target = target;
        setTitle(Text.literal("Pay " + target.getName().getString()));
        // Do NOT setSlot(0): AnvilInputGui.setDefaultInputValue owns slot 0.
        setDefaultInputValue("");
        setSlot(1, new GuiElementBuilder(Items.BARRIER).setName(Text.literal("Back"))
                .setCallback((i, t, a, g) -> new TransferShopGui(getPlayer()).open()));
        setSlot(2, new GuiElementBuilder(Items.LIME_CONCRETE).setName(Text.literal("Confirm"))
                .setCallback((i, t, a, g) -> confirm()));
    }

    private void confirm() {
        ServerPlayerEntity sender = getPlayer();
        String in = getInput();
        if (in == null) in = "";
        BigDecimal amount;
        try {
            amount = new BigDecimal(in.trim());
        } catch (NumberFormatException e) {
            sender.sendMessage(Text.literal("Enter a number."), false);
            return; // keep the anvil open so they can retype
        }
        if (amount.signum() <= 0) {
            sender.sendMessage(Text.literal("Enter a positive amount."), false);
            return;
        }
        EconomyManager eco = EconomyManager.getInstance();
        EconomyManager.TransferStatus ts = eco.transfer(sender.getUuid(), target.getUuid(), amount);
        switch (ts) {
            case OK -> {
                sender.sendMessage(Text.literal("Paid " + eco.format(amount) + " to " + target.getName().getString()), false);
                target.sendMessage(Text.literal("Received " + eco.format(amount) + " from " + sender.getName().getString()), false);
                savage.emeraldeconomy.util.TransactionLogger.log("PAY", sender.getName().getString(),
                        target.getName().getString(), amount, "GUI payment");
            }
            case SELF -> sender.sendMessage(Text.literal("You cannot pay yourself."), false);
            case INSUFFICIENT_FUNDS -> sender.sendMessage(Text.literal("Insufficient funds."), false);
            case FAILED -> sender.sendMessage(Text.literal("Payment failed, please try again."), false);
        }
        new ShopHubGui(sender).open();
    }
}
