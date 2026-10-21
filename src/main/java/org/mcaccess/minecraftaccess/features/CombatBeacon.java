package org.mcaccess.minecraftaccess.features;

import java.util.Comparator;
import java.util.List;

import net.blay09.mods.balm.client.platform.module.BalmClientModule;
import net.blay09.mods.balm.client.platform.util.SessionLocal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import org.mcaccess.minecraftaccess.Config;
import org.mcaccess.minecraftaccess.MainClass;
import org.mcaccess.minecraftaccess.utils.events.ClientPlayingTick;

/**
 * Turns the nearest threat into a sound the player turns toward - the audio counterpart of the camera lock in
 * {@link CombatLock}, with the tones themselves in {@link AimTone}.
 *
 * <p>The target is whatever the player is locked on to (the ordinary POI lock, which {@link CombatLock} also
 * drives), and failing that the nearest hostile mob in plain sight within the configured range - the same stand-in
 * for "something is after you" that {@link HostileRadar} uses, since a mob's AI target is never synced to clients.
 */
public class CombatBeacon implements BalmClientModule {
    private static final int SCAN_INTERVAL_TICKS = 5;
    /** Per session, so a target tracked in a previous world is not carried over. */
    private final SessionLocal<State> sessionState = new SessionLocal<>(State::new);

    @Override
    public @NotNull Identifier getId() {
        return Identifier.fromNamespaceAndPath(MainClass.MOD_ID, "combat_beacon");
    }

    @Override
    public void initialize() {
        ClientPlayingTick.AFTER.register(this::tick);
    }

    private void tick(Minecraft client, LocalPlayer player, ClientLevel level) {
        Config.CombatBeacon config = Config.getInstance().combatBeacon;
        State state = this.sessionState.value;
        if (!config.enabled || client.gui.screen() != null) {
            state.reset();
            return;
        }

        Entity target = target(player, state, config);
        if (target == null) {
            state.reset();
            return;
        }

        AABB box = target.getBoundingBox();
        Vec3 eye = player.getEyePosition();
        Vec3 aim = box.getCenter();
        AimTone.Guide guide = AimTone.Guide.of(player, eye, aim);
        state.onTarget = AimTone.withinTolerance(guide.yawError(), guide.pitchError(), box, eye.distanceTo(aim),
                state.onTarget);
        state.tone.play(client, player, guide, state.onTarget, config.volume / 100.0f);
    }

    /**
     * The entity the beacon guides toward, refreshed every few ticks so it can follow a mob that walks away and can
     * pick up a lock the player takes in the meantime.
     */
    private Entity target(LocalPlayer player, State state, Config.CombatBeacon config) {
        Entity lockedOn = MainClass.poiManager.lockingHandler.getLockedEntity();
        if (lockedOn != null && lockedOn.isAlive() && lockedOn.level() == player.level()) {
            state.target = lockedOn;
            return state.target;
        }

        if (--state.ticksUntilScan <= 0) {
            state.ticksUntilScan = SCAN_INTERVAL_TICKS;
            state.target = nearestHostile(player, config.range);
        }

        if (state.target != null && (!state.target.isAlive() || state.target.level() != player.level())) {
            state.target = null;
        }
        return state.target;
    }

    private Entity nearestHostile(LocalPlayer player, double range) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return null;

        List<Entity> candidates = level.getEntitiesOfClass(Entity.class, player.getBoundingBox().inflate(range),
                entity -> entity instanceof Enemy && entity.isAlive() && player.hasLineOfSight(entity));
        return candidates.stream().min(Comparator.comparingDouble(player::distanceTo)).orElse(null);
    }

    /**
     * Everything the beacon remembers between ticks of one session.
     */
    private static final class State {
        private final AimTone tone = new AimTone();
        private Entity target;
        private int ticksUntilScan;
        private boolean onTarget;

        private void reset() {
            tone.reset();
            target = null;
            ticksUntilScan = 0;
            onTarget = false;
        }
    }
}
