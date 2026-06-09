package savage.emeraldeconomy.integration;

import net.minecraft.text.Text;
import net.minecraft.server.MinecraftServer;
import com.mojang.authlib.GameProfile;
import eu.pb4.common.economy.api.EconomyCurrency;
import eu.pb4.common.economy.api.EconomyAccount;
import eu.pb4.common.economy.api.EconomyProvider;
import java.util.Collections;
import java.util.Collection;
import java.util.List;
import java.util.ArrayList;

public class EmeraldEconomyProvider implements EconomyProvider {
    public static final EmeraldEconomyProvider INSTANCE = new EmeraldEconomyProvider();
    private final EmeraldEconomyCurrency currency = new EmeraldEconomyCurrency(this);

    private EmeraldEconomyProvider() {}

    @Override
    public Text name() {
        return Text.of("Emerald Economy");
    }

    @Override
    public String defaultAccount(MinecraftServer server, com.mojang.authlib.GameProfile profile, EconomyCurrency currency) {
        if (currency == this.currency) {
            return profile.id().toString();
        }
        return null;
    }

    @Override
    public Collection<EconomyCurrency> getCurrencies(MinecraftServer server) {
        return Collections.singletonList(currency);
    }

    @Override
    public EconomyCurrency getCurrency(MinecraftServer server, String id) {
        if (id.equals("dollar") || id.equals("emerald_economy:dollar")) {
            return currency;
        }
        return null;
    }

    @Override
    public Collection<EconomyAccount> getAccounts(MinecraftServer server, GameProfile profile) {
        return Collections.singletonList(new EmeraldEconomyAccount(profile, currency, this));
    }

    @Override
    public EconomyAccount getAccount(MinecraftServer server, GameProfile profile, String id) {
        if (id.equals(profile.id().toString())) {
            return new EmeraldEconomyAccount(profile, currency, this);
        }
        return null;
    }
}
