package org.mcaccess.minecraftaccess.features;

import java.util.Comparator;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import net.blay09.mods.balm.client.platform.event.callback.ClientLifecycleCallback;
import net.blay09.mods.balm.client.platform.module.BalmClientModule;
import net.blay09.mods.kuma.api.InputBinding;
import net.blay09.mods.kuma.api.Kuma;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import org.jetbrains.annotations.NotNull;

import org.mcaccess.minecraftaccess.Config;
import org.mcaccess.minecraftaccess.MainClass;
import org.mcaccess.minecraftaccess.features.point_of_interest.LockingHandler;
import org.mcaccess.minecraftaccess.utils.KeyMappingCategories;
import org.mcaccess.minecraftaccess.utils.events.ClientPlayingTick;

/**
 * Keeps the aim on whichever hostile mob is nearest, rather than on one chosen target.
 *
 * <p>The ordinary lock-on holds one target until it dies or is released, which is the right thing when there is
 * a single thing to deal with. In an actual fight there is usually more than one, and reaching back to the
 * tracker to re-select between swings is not practical - so this one picks a target by itself and moves on to a
 * closer one the moment there is one. A newcomer has to be meaningfully nearer than the current target to take
 * the lock, otherwise two mobs at a similar distance would trade it back and forth every tick as they move.
 *
 * <p>It uses the same aim as the ordinary lock-on, so the two share the camera: turning this on takes over the
 * aim, releasing the lock with the ordinary unlock key turns this off rather than leaving it fighting for the
 * camera, and the aim itself is left alone while a screen is open.
 */
public class CombatLock implements BalmClientModule {
    /**
     * How much nearer than the current target a newcomer has to be to take the lock over.
     */
    private static final double SWITCH_MARGIN = 1.0;
    private static final int RESCAN_INTERVAL_TICKS = 10;

    private final Config.CombatLock config = Config.getInstance().combatLock;
    private Entity target = null;
    private boolean active = false;
    private boolean announcedEmpty = false;
    private int ticksSinceScan = 0;

    @Override
    public @NotNull Identifier getId() {
        return Identifier.fromNamespaceAndPath(MainClass.MOD_ID, "combat_lock");
    }

    @Override
    public void initialize() {
        ClientPlayingTick.AFTER.register(this::tick);
        ClientLifecycleCallback.ConnectedToServer.EVENT.register(_ -> stop());

        Kuma.createKeyMapping(Identifier.fromNamespaceAndPath(MainClass.MOD_ID, "combat_lock/toggle"))
                .withDefault(InputBinding.key(InputConstants.KEY_G))
                .overrideCategory(KeyMappingCategories.OTHER)
                .handleWorldInput(_ -> {
                    toggle();
                    return true;
                })
                .build();
    }

    private void toggle() {
        if (active) {
            stop();
            MainClass.narrate(I18n.get("minecraft_access.combat_lock.disabled"), true);
            return;
        }

        active = true;
        announcedEmpty = false;
        if (acquire()) return;

        stop();
        MainClass.narrate(I18n.get("minecraft_access.combat_lock.none"), true);
    }

    private void tick(Minecraft client, LocalPlayer player, ClientLevel level) {
        if (!active) return;
        if (client.gui.screen() != null) return;

        LockingHandler locking = MainClass.poiManager.lockingHandler;

        if (target != null && !target.isAlive()) {
            target = null;
            MainClass.narrate(I18n.get("minecraft_access.combat_lock.lost"), true);
        }

        if (target == null) {
            ticksSinceScan = 0;
            if (!acquire() && !announcedEmpty) {
                announcedEmpty = true;
                MainClass.narrate(I18n.get("minecraft_access.combat_lock.none"), true);
            }
            return;
        }

        // Released with the ordinary unlock key: the player asked for the aim back, so this stops instead of
        // taking the camera again on the next tick.
        if (!locking.isPlayerLocked()) {
            active = false;
            target = null;
            return;
        }

        if (++ticksSinceScan < RESCAN_INTERVAL_TICKS) return;
        ticksSinceScan = 0;

        Entity nearer = nearest(player);
        if (nearer != null && nearer != target && player.distanceTo(nearer) < player.distanceTo(target) - SWITCH_MARGIN) {
            target = nearer;
            locking.lockOnEntity(nearer);
        }
    }

    /**
     * Locks on to the nearest hostile mob in range, saying what it is as a side effect of the shared lock-on.
     *
     * @return whether there was anything to lock on to
     */
    private boolean acquire() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return false;

        Entity nearest = nearest(player);
        if (nearest == null) return false;

        target = nearest;
        announcedEmpty = false;
        MainClass.poiManager.lockingHandler.lockOnEntity(nearest);
        return true;
    }

    private Entity nearest(LocalPlayer player) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return null;

        List<Entity> candidates = level.getEntitiesOfClass(Entity.class,
                player.getBoundingBox().inflate(config.range),
                entity -> entity instanceof Enemy && entity.isAlive() && player.hasLineOfSight(entity));

        return candidates.stream().min(Comparator.comparingDouble(player::distanceTo)).orElse(null);
    }

    private void stop() {
        active = false;
        target = null;
        announcedEmpty = false;
        ticksSinceScan = 0;
        MainClass.poiManager.lockingHandler.releaseLock();
    }
}
