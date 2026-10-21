package org.mcaccess.minecraftaccess.features;

import java.util.Comparator;
import java.util.List;

import net.blay09.mods.balm.client.platform.module.BalmClientModule;
import net.blay09.mods.balm.client.platform.util.SessionLocal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
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
 * {@link CombatLock}.
 *
 * <p>While a mob is tracked a ping is placed a fixed short distance from the player along the real bearing to it,
 * so Minecraft's own panning says which side it is on, and it repeats faster the closer the player's facing is to
 * that bearing - slow when facing away, fastest when nearly on it - which tells ahead from behind with no extra
 * cue. The ping's pitch carries the vertical: neutral when level with the mob, lower when the player has to look
 * up, higher when down. Once the aim is on the mob in both axes the pings give way to a steady rapid tone that
 * holds until the aim drifts off again, with hysteresis so it does not stutter at the edge.
 *
 * <p>The target is whatever the player is locked on to (the ordinary POI lock, which {@link CombatLock} also
 * drives), and failing that the nearest hostile mob in plain sight within the configured range - the same stand-in
 * for "something is after you" that {@link HostileRadar} uses, since a mob's AI target is never synced to clients.
 */
public class CombatBeacon implements BalmClientModule {
    /** How far from the player the ping is placed, near enough that its bearing is what the panning reports. */
    private static final double PING_OFFSET_BLOCKS = 2.0;
    private static final int PING_INTERVAL_MIN_TICKS = 2;
    private static final int PING_INTERVAL_MAX_TICKS = 20;
    /** Vertical error that moves the ping one octave. */
    private static final double DEGREES_PER_OCTAVE = 60.0;
    private static final double LOCK_TOLERANCE_MIN_DEGREES = 3.0;
    private static final double LOCK_TOLERANCE_MAX_DEGREES = 12.0;
    /** Leaving the lock takes this much more error than entering it, so the tone doesn't stutter at the edge. */
    private static final double LOCK_RELEASE_FACTOR = 1.5;
    private static final int LOCK_TONE_INTERVAL_TICKS = 2;
    private static final int SCAN_INTERVAL_TICKS = 5;
    private static final float PING_VOLUME = 0.8f;
    private static final float LOCK_VOLUME = 0.35f;
    private static final double BEARING_EPSILON_SQUARED = 0.01;
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

        Guidance guidance = guidance(player, target);
        state.locked = isLocked(state.locked, Math.abs(guidance.yawError()), Math.abs(guidance.pitchError()),
                guidance.yawTolerance(), guidance.pitchTolerance());
        guide(client, player, guidance, state, config);
    }

    /**
     * Where the target is seen from the player's eyes, how far off the aim still is on each axis, and how far off it
     * may be and still count as on target - all of it read once per tick so nothing is measured twice.
     */
    private static Guidance guidance(LocalPlayer player, Entity target) {
        Vec3 eye = player.getEyePosition();
        AABB box = target.getBoundingBox();
        Vec3 aim = box.getCenter();
        double dx = aim.x - eye.x;
        double dy = aim.y - eye.y;
        double dz = aim.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double distance = Math.sqrt(horizontal * horizontal + dy * dy);
        return new Guidance(eye, aim, yawErrorDegrees(dx, dz, player.getYRot()),
                pitchErrorDegrees(dy, horizontal, player.getXRot()), toleranceDegrees(box.getXsize() / 2.0, distance),
                toleranceDegrees(box.getYsize() / 2.0, distance));
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
     * Plays one tick of guidance: the steady tone while the aim is on the target, otherwise a ping on the cadence the
     * horizontal error sets.
     */
    private void guide(Minecraft client, LocalPlayer player, Guidance guidance, State state,
            Config.CombatBeacon config) {
        if (state.locked) {
            state.ticksUntilPing = 0;
            if (--state.ticksUntilLockTone <= 0) {
                state.ticksUntilLockTone = LOCK_TONE_INTERVAL_TICKS;
                playLockTone(client, player, config);
            }
            return;
        }
        state.ticksUntilLockTone = 0;

        if (--state.ticksUntilPing > 0) return;
        state.ticksUntilPing = pingIntervalTicks(Math.abs(guidance.yawError()));
        playPing(client, player, guidance, config);
    }

    private void playPing(Minecraft client, LocalPlayer player, Guidance guidance, Config.CombatBeacon config) {
        double dx = guidance.aim().x - guidance.eye().x;
        double dz = guidance.aim().z - guidance.eye().z;
        double length = Math.sqrt(dx * dx + dz * dz);
        double ux;
        double uz;
        if (length < 0.1) {
            // Straight above or below: no horizontal bearing, so put it dead ahead.
            double yawRad = Math.toRadians(player.getYRot());
            ux = -Math.sin(yawRad);
            uz = Math.cos(yawRad);
        } else {
            ux = dx / length;
            uz = dz / length;
        }

        Vec3 eye = guidance.eye();
        RandomSource random = player.getRandom();
        client.getSoundManager().play(new SimpleSoundInstance(SoundEvents.NOTE_BLOCK_COW_BELL.value(), SoundSource.MASTER,
                config.volume / 100.0f * PING_VOLUME, pingPitch(guidance.pitchError()), random,
                eye.x + ux * PING_OFFSET_BLOCKS, eye.y, eye.z + uz * PING_OFFSET_BLOCKS));
    }

    private void playLockTone(Minecraft client, LocalPlayer player, Config.CombatBeacon config) {
        Vec3 pos = player.position();
        client.getSoundManager().play(new SimpleSoundInstance(SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.MASTER,
                config.volume / 100.0f * LOCK_VOLUME, 1.0f, player.getRandom(), pos.x(), pos.y(), pos.z()));
    }

    /**
     * Signed horizontal angle from where the player faces to the target, in degrees, in (-180, 180]. Minecraft's yaw
     * is 0 = south and increases toward the west, so the direction to a target at offset ({@code dx}, {@code dz}) has
     * yaw {@code atan2(-dx, dz)}. A target straight above or below has no bearing and counts as dead ahead.
     */
    private static double yawErrorDegrees(double dx, double dz, float playerYaw) {
        if (dx * dx + dz * dz < BEARING_EPSILON_SQUARED) return 0.0;
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        return Mth.wrapDegrees(targetYaw - playerYaw);
    }

    /**
     * Signed vertical angle the player still has to turn, in degrees: negative means look up, positive means look
     * down, matching Minecraft's pitch.
     */
    private static double pitchErrorDegrees(double dy, double horizontal, float playerPitch) {
        double targetPitch = -Math.toDegrees(Math.atan2(dy, Math.max(horizontal, 1.0e-6)));
        return targetPitch - playerPitch;
    }

    /**
     * Ticks between pings: the minimum when on the bearing, the maximum when facing directly away.
     */
    private static int pingIntervalTicks(double absoluteYawError) {
        double fraction = Math.min(Math.max(absoluteYawError / 180.0, 0.0), 1.0);
        return PING_INTERVAL_MIN_TICKS
                + (int) Math.round((PING_INTERVAL_MAX_TICKS - PING_INTERVAL_MIN_TICKS) * fraction);
    }

    /**
     * Sound pitch for a vertical error: 1.0 when level, an octave lower per 60 degrees of looking up, higher when
     * looking down, clamped to what the sound engine accepts.
     */
    private static float pingPitch(double pitchError) {
        double pitch = Math.pow(2.0, pitchError / DEGREES_PER_OCTAVE);
        return (float) Math.min(Math.max(pitch, 0.5), 2.0);
    }

    /**
     * How far off, in degrees, the aim can be and still count as on a target of the given half-size at the given
     * distance.
     */
    private static double toleranceDegrees(double halfExtent, double distance) {
        double degrees = Math.toDegrees(Math.atan2(halfExtent, Math.max(distance, 1.0)));
        return Math.min(Math.max(degrees, LOCK_TOLERANCE_MIN_DEGREES), LOCK_TOLERANCE_MAX_DEGREES);
    }

    /**
     * Enters the lock inside the tolerance on both axes, and only leaves it once outside {@link #LOCK_RELEASE_FACTOR}
     * times that.
     */
    private static boolean isLocked(boolean wasLocked, double absoluteYawError, double absolutePitchError,
            double yawTolerance, double pitchTolerance) {
        double factor = wasLocked ? LOCK_RELEASE_FACTOR : 1.0;
        return absoluteYawError <= yawTolerance * factor && absolutePitchError <= pitchTolerance * factor;
    }

    /**
     * One tick's worth of geometry: where the target is from the player's eyes, and how far off the aim is on each axis
     * against the tolerance that counts as on target.
     */
    private record Guidance(Vec3 eye, Vec3 aim, double yawError, double pitchError, double yawTolerance,
            double pitchTolerance) {
    }

    /**
     * Everything the beacon remembers between ticks of one session.
     */
    private static final class State {
        private Entity target;
        private int ticksUntilScan;
        private int ticksUntilPing;
        private int ticksUntilLockTone;
        private boolean locked;

        private void reset() {
            target = null;
            ticksUntilScan = 0;
            ticksUntilPing = 0;
            ticksUntilLockTone = 0;
            locked = false;
        }
    }
}
