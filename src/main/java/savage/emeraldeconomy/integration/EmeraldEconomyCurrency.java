package savage.emeraldeconomy.integration;

import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import eu.pb4.common.economy.api.EconomyCurrency;
import eu.pb4.common.economy.api.EconomyProvider;

public class EmeraldEconomyCurrency implements EconomyCurrency {
    private final EconomyProvider provider;

    public EmeraldEconomyCurrency(EconomyProvider provider) {
        this.provider = provider;
    }

    @Override
    public Identifier id() {
        return Identifier.of("emerald_economy", "dollar");
    }

    @Override
    public Text name() {
        return Text.of("Dollar");
    }

    @Override
    public EconomyProvider provider() {
        return provider;
    }

    @Override
    public long parseValue(String value) {
        try {
            if (value.startsWith("$")) {
                value = value.substring(1);
            }
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public String formatValue(long value, boolean precise) {
        return "$" + value;
    }
}
