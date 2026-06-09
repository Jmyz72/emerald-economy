package savage.emeraldeconomy.rewards;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import savage.emeraldeconomy.EconomyManager;
import savage.emeraldeconomy.config.EconomyConfig;

import java.math.BigDecimal;

/**
 * Pays a configurable reward to a player who kills a mob. A mob listed in
 * rewards.mobKillRewards pays its override amount (the "tier list"); otherwise hostile
 * mobs (SpawnGroup.MONSTER) pay rewards.mobKillHostile and everything else pays
 * rewards.mobKillPassive. PvP and environmental deaths pay nothing. Feedback is an
 * action-bar overlay so it never spams chat (mob kills are frequent).
 */
public final class MobKillRewards {

    private MobKillRewards() {
    }

    public static void register() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            // Killer must be a player (projectiles credit the shooter; null attacker -> false).
            if (!(damageSource.getAttacker() instanceof ServerPlayerEntity killer)) {
                return;
            }
            // Never reward PvP.
            if (entity instanceof PlayerEntity) {
                return;
            }

            EconomyConfig.RewardsConfig rewards = EconomyManager.getInstance().getConfig().rewards;
            String entityId = Registries.ENTITY_TYPE.getId(entity.getType()).toString();

            // Per-mob override (the "tier list") wins; otherwise fall back to the flat rate.
            BigDecimal override = rewards.mobKillRewards != null ? rewards.mobKillRewards.get(entityId) : null;
            BigDecimal reward = override != null
                    ? override
                    : (isHostile(entity) ? rewards.mobKillHostile : rewards.mobKillPassive);
            if (reward == null || reward.signum() <= 0) {
                return;
            }

            // Synchronous balance write on the server thread; only player-dealt kills reach here
            // (environmental/AFK-farm kills bail at the attacker guard above), so this is cheap.
            if (EconomyManager.getInstance().addBalance(killer.getUuid(), reward)) {
                killer.sendMessage(Text.literal("+" + EconomyManager.getInstance().format(reward)), true);
                savage.emeraldeconomy.util.TransactionLogger.log("MOBKILL", "Server",
                        killer.getName().getString(), reward, entityId);
            }
        });
    }

    /**
     * Hostile = the MONSTER spawn group. This covers most hostiles but not every aggressive mob
     * (some use other spawn groups); any that should pay the hostile rate can be listed explicitly
     * in rewards.mobKillRewards, which takes precedence over this classification.
     */
    static boolean isHostile(LivingEntity entity) {
        return entity.getType().getSpawnGroup() == SpawnGroup.MONSTER;
    }
}
