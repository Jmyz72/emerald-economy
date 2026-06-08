package savage.commoneconomy.gui;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.AnvilInputGui;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import savage.commoneconomy.EconomyManager;

import java.math.BigDecimal;

/** Type how many emeralds to withdraw, then confirm. */
public class WithdrawShopGui extends AnvilInputGui {

    public WithdrawShopGui(ServerPlayerEntity player) {
        super(player, false);
        setTitle(Text.literal("Withdraw — type an amount"));
        // setDefaultInputValue seeds the input text AND configures slot 0 (the paper item).
        // Do not override slot 0 manually; getInput() is initialized to "" and never null.
        setDefaultInputValue("");
        setSlot(2, new GuiElementBuilder(Items.LIME_CONCRETE).setName(Text.literal("Confirm"))
                .setCallback((i, t, a, g) -> confirm()));
    }

    private void confirm() {
        ServerPlayerEntity p = getPlayer();
        String in = getInput();
        // getInput() is never null (initialized to "" by setDefaultInputValue in constructor),
        // but guard defensively.
        if (in == null) in = "";
        BigDecimal amount;
        try {
            amount = new BigDecimal(in.trim());
        } catch (NumberFormatException e) {
            p.sendMessage(Text.literal("Enter a number."), false);
            return;
        }
        EconomyManager.WithdrawResult wr = EconomyManager.getInstance().withdrawEmeralds(p, amount);
        switch (wr.status()) {
            case OK -> p.sendMessage(Text.literal("Withdrew " + wr.emeralds() + " emeralds."), false);
            case TOO_SMALL -> p.sendMessage(Text.literal("Withdraw at least 1."), false);
            case TOO_LARGE -> p.sendMessage(Text.literal("Too many at once."), false);
            case INSUFFICIENT_FUNDS -> p.sendMessage(Text.literal("Insufficient funds."), false);
        }
        new ShopHubGui(p).open();
    }
}
