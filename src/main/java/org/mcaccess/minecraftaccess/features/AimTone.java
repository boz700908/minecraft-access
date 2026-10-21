package org.mcaccess.minecraftaccess.features;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The tones every aim-by-ear feature shares ({@link CombatBeacon}, {@link BowBeacon}), and the angle maths behind
 * them.
 *
 * <p>A ping is placed a fixed short distance from the player along the real bearing to the aim point, so Minecraft's
 * own panning says which side it is on, and it repeats faster the closer the player's facing is to that bearing -
 * slow when facing away, fastest when nearly on it - which tells ahead from behind with no extra cue. The ping's
 * pitch carries the vertical error: neutral when level, lower when the player has to look up, higher when down. Once
 * the aim counts as on target the pings give way to a steady rapid tone, until it drifts off again.
 */
final class AimTone {
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
    private static final float PING_VOLUME = 0.8f;
    private static final float LOCK_VOLUME = 0.35f;
    private static final double BEARING_EPSILON_SQUARED = 0.01;
    private int ticksUntilPing;
    private int ticksUntilLockTone;

    /**
     * One tick of guidance: the steady tone while {@code onTarget}, otherwise a ping on the cadence the horizontal
     * error sets.
     *
     * @param volume 0 to 1, already scaled by the feature's own setting
     */
    void play(Minecraft client, LocalPlayer player, Guide guide, boolean onTarget, float volume) {
        if (onTarget) {
            ticksUntilPing = 0;
            if (--ticksUntilLockTone <= 0) {
                ticksUntilLockTone = LOCK_TONE_INTERVAL_TICKS;
                playLockTone(client, player, volume);
            }
            return;
        }
        ticksUntilLockTone = 0;

        if (--ticksUntilPing > 0) return;
        ticksUntilPing = pingIntervalTicks(Math.abs(guide.yawError()));
        playPing(client, player, guide, volume);
    }

    void reset() {
        ticksUntilPing = 0;
        ticksUntilLockTone = 0;
    }

    private void playPing(Minecraft client, LocalPlayer player, Guide guide, float volume) {
        Vec3 eye = guide.eye();
        double dx = guide.aim().x - eye.x;
        double dz = guide.aim().z - eye.z;
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

        RandomSource random = player.getRandom();
        client.getSoundManager().play(new SimpleSoundInstance(SoundEvents.NOTE_BLOCK_COW_BELL.value(), SoundSource.MASTER,
                volume * PING_VOLUME, pingPitch(guide.pitchError()), random,
                eye.x + ux * PING_OFFSET_BLOCKS, eye.y, eye.z + uz * PING_OFFSET_BLOCKS));
    }

    private void playLockTone(Minecraft client, LocalPlayer player, float volume) {
        Vec3 pos = player.position();
        client.getSoundManager().play(new SimpleSoundInstance(SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.MASTER,
                volume * LOCK_VOLUME, 1.0f, player.getRandom(), pos.x(), pos.y(), pos.z()));
    }

    /**
     * Whether the aim counts as on a target of the given box at the given distance, entering inside the tolerance and
     * leaving only outside {@link #LOCK_RELEASE_FACTOR} times that.
     */
    static boolean withinTolerance(double yawError, double pitchError, AABB box, double distance,
            boolean wasOnTarget) {
        double factor = wasOnTarget ? LOCK_RELEASE_FACTOR : 1.0;
        double yawTolerance = toleranceDegrees(box.getXsize() / 2.0, distance) * factor;
        double pitchTolerance = toleranceDegrees(box.getYsize() / 2.0, distance) * factor;
        return Math.abs(yawError) <= yawTolerance && Math.abs(pitchError) <= pitchTolerance;
    }

    /**
     * Signed horizontal angle from where the player faces to the aim point, in degrees, in (-180, 180]. Minecraft's
     * yaw is 0 = south and increases toward the west, so the direction to a point at offset ({@code dx}, {@code dz})
     * has yaw {@code atan2(-dx, dz)}. A point straight above or below has no bearing and counts as dead ahead.
     */
    static double yawErrorDegrees(Vec3 eye, Vec3 aim, float playerYaw) {
        double dx = aim.x - eye.x;
        double dz = aim.z - eye.z;
        if (dx * dx + dz * dz < BEARING_EPSILON_SQUARED) return 0.0;
        return Mth.wrapDegrees(Math.toDegrees(Math.atan2(-dx, dz)) - playerYaw);
    }

    /**
     * Signed vertical angle the player still has to turn, in degrees: negative means look up, positive means look
     * down, matching Minecraft's pitch.
     */
    static double pitchErrorDegrees(Vec3 eye, Vec3 aim, float playerPitch) {
        double dy = aim.y - eye.y;
        double horizontal = Math.sqrt((aim.x - eye.x) * (aim.x - eye.x) + (aim.z - eye.z) * (aim.z - eye.z));
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
     * One tick's aim geometry: where the eye is, the point being aimed at, and how far off the aim still is on each
     * axis.
     */
    record Guide(Vec3 eye, Vec3 aim, double yawError, double pitchError) {
        /**
         * The geometry of looking straight at {@code aim} from {@code eye} - for features whose guidance follows the
         * target itself rather than an arc.
         */
        static Guide of(LocalPlayer player, Vec3 eye, Vec3 aim) {
            return new Guide(eye, aim, yawErrorDegrees(eye, aim, player.getYRot()),
                    pitchErrorDegrees(eye, aim, player.getXRot()));
        }
    }
}
