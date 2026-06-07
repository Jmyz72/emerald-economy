# Common Economy API: Correct Usage Guide

This guide documents the correct pattern for interacting with the **Common Economy API** to ensure compatibility with various providers (e.g., Savs Common Economy, Fuji, Impactor).

## The Lookup Pattern

When interacting with the API, you should follow a hierarchical lookup pattern: **Provider -> Currency -> Account**.

### 1. Get the Provider
**CRITICAL:** Do not use the full currency ID (e.g., `modid:currency`) to look up a provider. Use the **Namespace** (Mod ID) of the provider.

```java
// CORRECT: Look up by namespace
String providerId = "savs_common_economy"; // or "fuji", "impactor"
EconomyProvider provider = CommonEconomy.getProvider(providerId);

if (provider == null) {
    // Handle missing provider
    return;
}
```

### 2. Get the Currency
Once you have the provider, ask it for the specific currency using the **Path** (or full ID).

```java
String currencyPath = "dollar"; // or "gold", "coins"
EconomyCurrency currency = provider.getCurrency(server, currencyPath);

if (currency == null) {
    // Try full ID as fallback
    currency = provider.getCurrency(server, providerId + ":" + currencyPath);
}

if (currency == null) {
    // Handle missing currency
    return;
}
```

### 3. Get the Account
Finally, use the provider and currency to get the player's account.

**Note:** Some providers require you to get the "Default Account ID" first.

```java
// Get the default account ID for this player and currency
String accountId = provider.defaultAccount(server, player.getGameProfile(), currency);

if (accountId == null) {
    // Player might not have an account yet
    return;
}

// Retrieve the actual account object
EconomyAccount account = provider.getAccount(server, player.getGameProfile(), accountId);

if (account != null) {
    // Perform transactions
    account.increaseBalance(100);
}
```

## Why this matters?

Using `CommonEconomy.getProvider("modid:currency")` will often fail because providers are registered under their **Mod ID** (Namespace), not the full currency string.

By splitting the lookup into steps, you ensure:
1.  You find the correct provider.
2.  You get the valid currency object that belongs to that provider.
3.  You correctly resolve the player's account ID.

## Example: Configurable Integration

If you are making a mod that integrates with *any* economy, make the **Provider ID** and **Currency ID** configurable.

```java
// Config values
String configProvider = "fuji";
String configCurrency = "gold";

// 1. Provider
var provider = CommonEconomy.getProvider(configProvider);

// 2. Currency
var currency = provider.getCurrency(server, configCurrency);

// 3. Account
var accountId = provider.defaultAccount(server, profile, currency);
var account = provider.getAccount(server, profile, accountId);
```
