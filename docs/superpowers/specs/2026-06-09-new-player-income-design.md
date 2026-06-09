# Emerald Economy — New-Player Income Design

**Date:** 2026-06-09
**Scope:** Add four configurable, restart-friendly income sources so new players earn
money without depending on the late-game emerald farm: `/daily`, mob-kill rewards,
session-based playtime income, and a bigger starting balance.

---

## 1. Context & constraints

- Currency is emeralds; balances are `BigDecimal`. All money movement goes through
  `EconomyManager.addBalance(UUID, BigDecimal)` and is recorded via
  `TransactionLogger.log(type, source, target, amount, details)`.
- The end-game economy already has a massive faucet (auto-trader + emerald farm,
  ~100k/day AFK — see `2026-06-09-worth-pricing-design.md`). This feature is **not**
  about that player. It is about the **first few hours**: a new player with no farm
  needs a trickle of income to afford early `/buy` purchases.
- The server **restarts every ~6 hours**. The design must survive restarts cleanly and
  must not depend on long-lived in-memory counters that a restart would corrupt.
- Everything is **live-tunable** via `/eco reload` — no numbers are compiled in beyond
  their defaults.

**Consequence:** income is **session-based and event-driven**, never a background
ticker. AFK is explicitly allowed (simplest, restart-safe). A hard crash may lose one
in-progress playtime session — accepted.

## 2. Configuration

A new nested `rewards` block is added to `EconomyConfig` (`config.json`). All fields are
live-tunable via `/eco reload` (see §4).

```jsonc
"rewards": {
  "dailyAmount": 10000,      // /daily payout
  "dailyCooldownHours": 12,  // rolling cooldown between claims (claimable twice a day)
  "mobKillHostile": 100,     // $ per hostile (SpawnGroup.MONSTER) kill, if not in mobKillRewards
  "mobKillPassive": 50,      // $ per non-hostile mob kill, if not in mobKillRewards
  "mobKillRewards": {        // optional per-entity-id overrides (the "tier list"); checked first
    "minecraft:ender_dragon": 50000,
    "minecraft:wither": 25000,
    "minecraft:warden": 10000,
    "minecraft:elder_guardian": 2000,
    "minecraft:blaze": 250
  },
  "playtimePerMinute": 50    // $ per whole minute of a session, paid on logout (= $3000/hr)
}
```

These defaults are deliberately **very generous** — new players start rich and earn fast.
They are the compiled defaults and the only levers; tune them down later via `/eco reload`
if the early game feels too loose.

Money fields are `BigDecimal` (decimals allowed for fine tuning); `dailyCooldownHours` is
an `int`; `mobKillRewards` is a `Map<String, BigDecimal>` keyed by entity id, seeded with
the boss/elite values above (admins add or remove entries freely). Defaults match the
block above.

**Backward compatibility:** existing `config.json` files have no `rewards` block. Gson
leaves the field at its default (`new RewardsConfig()`), so the feature works on upgrade
with the defaults above. `loadConfig()` only rewrites the file when it is *missing*, so an
existing file is **not** auto-populated with the new block — to tune the values an admin
either edits `config.json` (adding the `rewards` object) or deletes it to regenerate
defaults, then `/eco reload`. This is documented in §8.

**Bigger start:** the compiled `defaultBalance` default is raised to **25000**
(`EconomyConfig.defaultBalance`) so fresh servers start generous out of the box. Existing
servers already have `defaultBalance` written in their `config.json`, so this only affects
brand-new deploys; an admin can still set any value in `config.json` (it applies to
accounts created after the next reload/start).

## 3. The four income sources

### 3a. `/daily` — once-per-cooldown cash claim

- Command `/daily`, permission node `emeraldeconomy.command.daily` (default **allowed**,
  matching the player-command convention used by `/bal`, `/pay`).
- On claim: if off cooldown, pay `rewards.dailyAmount` via `addBalance`, record the claim
  timestamp, log a `DAILY` transaction, and confirm in chat.
- On cooldown: send an error — `"Come back in Xh Ym."` (derived from the remaining
  millis; `"Ym"` under an hour, `"<1m"` under a minute).
- Cooldown is a **rolling** `dailyCooldownHours` window from the last successful claim
  (not a calendar day), measured with `System.currentTimeMillis()`.
- The claim timestamp is only recorded **after** `addBalance` succeeds, so a failed
  payment never burns the cooldown.

**Persistence:** last-claim epoch-millis per player is stored in
`config/emerald-economy/rewards.json` as a flat `{ "<uuid>": <epochMillis> }` map.
Survives restarts. Loaded on `SERVER_STARTING`, written on each successful claim.

### 3b. Mob-kill rewards

- `ServerLivingEntityEvents.AFTER_DEATH` listener.
- Reward only when the killer is a `ServerPlayerEntity`
  (`damageSource.getAttacker()`), so projectile kills credit the shooter and environmental
  deaths pay nobody.
- **Never reward PvP**: if the dead entity is a `PlayerEntity`, pay nothing.
- Determine the reward in this precedence:
  1. **Per-mob override** — look up the entity id (`Registries.ENTITY_TYPE.getId(...)`) in
     `rewards.mobKillRewards`; if present, use that amount (this is the "tier list" — bosses
     and elites pay their listed jackpot regardless of spawn group).
  2. **Flat fallback** — otherwise `SpawnGroup.MONSTER` → `rewards.mobKillHostile`, else
     `rewards.mobKillPassive`.
- Skip payment entirely when the resolved amount is `null` or `<= 0` (so setting a rate or
  an override to `0` cleanly turns that pay off — no payment, no log).
- Feedback is an **action-bar** overlay (`player.sendMessage(text, true)`) — e.g. `+$100` —
  so it never spams chat even at the generous rates (mob kills are frequent). This is the
  one feedback channel that is *not* chat; `/daily` and the welcome-back message are chat
  because they fire at most once per claim/login. Each paid kill writes a `MOBKILL`
  transaction whose details are the entity-type id (the same id used for the override
  lookup).

### 3c. Session-based playtime income

- On `ServerPlayConnectionEvents.JOIN`: record `System.currentTimeMillis()` for the
  player's UUID in an in-memory map.
- On `ServerPlayConnectionEvents.DISCONNECT`: remove the start stamp, compute
  `floor(sessionMillis / 60000)` whole minutes, pay `minutes × playtimePerMinute`, log a
  `PLAYTIME` transaction, and `LOGGER.info` `"Paid $X playtime income to <name> for N min
  played"`.
- Counts **all** online time, AFK included (by design).
- Skip when minutes `<= 0` or the rate is `null`/`<= 0`.
- The scheduled ~6h restart disconnects every player first, firing `DISCONNECT` and paying
  the session, then `SERVER_STOPPING` saves. Because `addBalance` writes through to storage
  immediately (versioned `setBalance`), the credited balance is already persisted before
  shutdown. A hard crash (no graceful disconnect) loses the in-progress session — accepted.

**Welcome-back feedback (the player is offline when paid):** because payout happens on
disconnect, the player can't see it in-game at the time. So on a successful payout we also
record a per-player summary `{ amount, minutes }`, and on their **next join** we send
`"Welcome back! Last session you earned $X for N min played."` and clear the summary. The
summary is persisted in `config/emerald-economy/playtime.json` (flat
`{ "<uuid>": { "amount", "minutes" } }` map), loaded on `SERVER_STARTING`, so the message
survives the 6h restart between the paying disconnect and the next login. A player with no
pending summary simply gets no message.

### 3d. Bigger start

Config-only — see §2. No code.

## 4. `/eco reload` extension

Today `/eco reload` (`EcoCommands.reload`) only calls `reloadPrices()` (re-reads
`worth.json`). Reward tuning lives in `config.json`, which reload does **not** currently
re-read. So:

- Add a public `EconomyManager.reloadConfig()` that re-runs the existing `loadConfig()`
  (replaces the in-memory `EconomyConfig`). The legacy-dir migration inside `loadConfig()`
  no-ops on reload (the new dir already exists).
- `EcoCommands.reload` calls `reloadConfig()` **and** `reloadPrices()`, and its feedback
  becomes `"Reloaded config.json and worth.json (N priced items)."`.

All reward code reads `EconomyManager.getInstance().getConfig().rewards` **live** on each
event (never caches the config reference), so a reload takes effect immediately.
`storage.*` fields are only consumed at `initStorage()` time, so changing them via reload
has no live effect — acceptable and unchanged from today's behavior.

## 5. Architecture

New `rewards/` package plus one new command and small edits to existing files.

**Create:**
- `config/EconomyConfig.java` → add nested `RewardsConfig` (modify, not create).
- `rewards/DailyRewards.java` — singleton: `rewards.json` load/save + pure cooldown math
  (`remainingCooldownMillis`) + `markClaimed`.
- `rewards/MobKillRewards.java` — `register()` wires the `AFTER_DEATH` listener; resolves
  the reward via the `mobKillRewards` override map then the flat hostile/passive fallback;
  pure-ish `isHostile` helper.
- `rewards/PlaytimeIncome.java` — `register()` wires JOIN/DISCONNECT; pure `sessionMinutes`
  helper; in-memory session map; persisted last-session summaries (`playtime.json`) for the
  welcome-back message, with a `load()` called on `SERVER_STARTING`.
- `command/DailyCommand.java` — `/daily` (kept in `command/` with its siblings, matching
  the existing registration pattern; the reward *logic* it calls lives in `rewards/`). Pure
  `formatDuration` helper.

**Modify:**
- `EconomyManager.java` — add `public void reloadConfig()`.
- `command/EcoCommands.java` — reload also reloads config; updated feedback string.
- `EmeraldEconomy.java` — add `DailyCommand::register` to the command list; call
  `MobKillRewards.register()` and `PlaytimeIncome.register()`; load `DailyRewards` **and**
  `PlaytimeIncome` on `SERVER_STARTING`.

Every payout flows through `EconomyManager.addBalance` + `TransactionLogger.log`. New
transaction types: `DAILY`, `MOBKILL`, `PLAYTIME` (source `"Server"`).

**Note on the existing JOIN/DISCONNECT handlers:** `EmeraldEconomy` already registers a
JOIN (account creation) and a DISCONNECT (`ShopDropGuis.settleOnDisconnect`). Fabric events
support multiple listeners, so `PlaytimeIncome.register()` adds its own callbacks rather
than modifying the existing ones.

## 6. Testability

Following the project's harness (pure-logic JUnit like `PriceBookTest`; Minecraft-coupled
behavior verified by build + in-game smoke test):

**Unit-tested (pure, no Minecraft bootstrap):**
- `DailyRewards.remainingCooldownMillis(Long lastClaim, long now, int cooldownHours)` —
  null → 0; within window → positive; elapsed → 0.
- `DailyCommand.formatDuration(long millis)` — `Xh Ym` / `Ym` / `<1m`.
- `PlaytimeIncome.sessionMinutes(long start, long now)` — floors; never negative.

**Verified by build + smoke test (require a running server):** mob-kill classification &
payout, chat feedback, JOIN/DISCONNECT wiring, `/daily` end-to-end, `rewards.json`
persistence, `/eco reload` picking up edited config values.

## 7. Edge cases

- **Failed payment** (`addBalance` returns false after retries): `/daily` does not record
  the claim; mob/playtime simply skip the log. No money is fabricated or lost.
- **Passive/zero rate**: `mobKillPassive = 0` → no payment, no log, no message.
- **PvP / environmental death**: never pays (killer-is-player + not-a-player checks).
- **Disconnect without a recorded join** (e.g. listener added mid-session after a reload
  of code — not possible at runtime, but defensive): `sessionStart.remove` returns null →
  skip.
- **Corrupt/missing `rewards.json`**: logged, treated as empty (everyone can claim).
- **Corrupt/missing `playtime.json`**: logged, treated as empty (no pending welcome-back
  messages — never blocks join or payout).

## 8. Deployment notes

1. Build and deploy the jar.
2. On first server start the feature runs with the §2 defaults even if `config.json`
   predates this change.
3. To tune values or raise `defaultBalance`: edit `config/emerald-economy/config.json`
   (add the `rewards` block / change `defaultBalance`) **or** delete it to regenerate
   defaults, then run `/eco reload` (no restart needed; `defaultBalance` only affects
   accounts created after the reload).
4. If using LuckPerms / Fabric Permissions, grant `emeraldeconomy.command.daily` to the
   default group (it already defaults to allowed without the permissions API).

## 9. Out of scope

- Streaks / escalating daily rewards, kill streaks, first-join kits or item rewards.
- Anti-AFK detection (AFK income is intentional).
- Persisting an in-progress playtime session across a crash.
- A rigid named-tier system with automatic mob→tier classification. The `mobKillRewards`
  override map covers per-mob tiering directly; a bucket system is declined for its
  upkeep across modded mobs.

## 10. Success criteria

- `config.json` exposes the `rewards` block; every value (including the `mobKillRewards`
  map) is read live and change takes effect on `/eco reload` with no restart.
- `/daily` pays once per `dailyCooldownHours`, persists across restart via `rewards.json`,
  and reports remaining time when on cooldown.
- Killing a zombie pays `mobKillHostile` with a chat `+$N`; killing a cow pays
  `mobKillPassive` (on by default); killing a player pays nothing; killing a mob listed in
  `mobKillRewards` (e.g. a blaze) pays its override amount instead of the flat rate.
- A player who plays N whole minutes and logs out (or is dropped by the 6h restart) is
  credited `N × playtimePerMinute` and a `PLAYTIME` log line appears, and on their next
  login they see `"Welcome back! Last session you earned $X for N min played."` — even
  across a server restart.
- Every payout appears in the transaction log; no payout fabricates or destroys money on
  failure.
- `./gradlew build` is green (compiles + the three new pure unit tests pass).
