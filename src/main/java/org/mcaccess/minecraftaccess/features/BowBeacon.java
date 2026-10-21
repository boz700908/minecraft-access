package org.mcaccess.minecraftaccess.features;

import java.util.Optional;

import net.blay09.mods.balm.client.platform.module.BalmClientModule;
import net.blay09.mods.balm.client.platform.util.SessionLocal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import org.mcaccess.minecraftaccess.Config;
import org.mcaccess.minecraftaccess.MainClass;
import org.mcaccess.minecraftaccess.utils.events.ClientPlayingTick;

/**
 * The ranged half of {@link CombatBeacon}: while a bow is being drawn, a crossbow is loaded or a trident is held
 * back, it points the aim along the arc the shot will actually take rather than straight at the target.
 *
 * <p>The arc is solved from the projectile's real launch speed - a bow's scaling with the draw, a crossbow's fixed
 * speed, a trident's throw - and the target is led by its own movement, so the cue stays honest while a skeleton or
 * a player strafes. The tone holds steady only when a tick-by-tick flight simulation says the shot lands, so the
 * player hears the difference between "pointed at it" and "would hit it". The same tones as {@link CombatBeacon} are
 * used, through {@link AimTone}.
 *
 * <p>It stays quiet whenever the aim is not a projectile's - a sword, a pickaxe, an unloaded crossbow - where the
 * plain guidance of {@link CombatBeacon} is the right one.
 */
public class BowBeacon implements BalmClientModule {
    private static final double SEARCH_RANGE = 64.0;
    private static final int MAX_FLIGHT_TICKS = 100;
    /** How far the simulated shot may pass from a target and still count as a hit, and what it takes to lose it. */
    private static final double HIT_MARGIN_BLOCKS = 0.25;
    private static final double HIT_RELEASE_MARGIN_BLOCKS = 0.6;
    /** Arrow flight, as the game applies it every tick. */
    private static final double PROJECTILE_GRAVITY = 0.05;
    private static final double PROJECTILE_DRAG = 0.99;
    private static final double VELOCITY_SMOOTHING = 0.3;
    private static final double MAX_LEAD_TICKS = 40.0;
    /** Launch speeds in blocks per tick: a trident is always thrown at this, a crossbow fires an arrow at this. */
    private static final double TRIDENT_SPEED = 2.5;
    private static final double CROSSBOW_ARROW_SPEED = 3.15;
    /** A bow's launch speed is its draw power times this. */
    private static final double BOW_SPEED_FACTOR = 3.0;
    /** Below the game's own firing threshold there is no launch speed to solve an arc from. */
    private static final double MIN_DRAW_POWER = 0.1;
    private static final int BISECTION_STEPS = 32;
    private static final int MAX_SIMULATION_TICKS = 200;
    private final SessionLocal<State> sessionState = new SessionLocal<>(State::new);

    @Override
    public @NotNull Identifier getId() {
        return Identifier.fromNamespaceAndPath(MainClass.MOD_ID, "bow_beacon");
    }

    @Override
    public void initialize() {
        ClientPlayingTick.AFTER.register(this::tick);
    }

    private void tick(Minecraft client, LocalPlayer player, ClientLevel level) {
        Config.BowBeacon config = Config.getInstance().bowBeacon;
        State state = this.sessionState.value;
        double speed = launchSpeed(player);
        if (!config.enabled || client.gui.screen() != null || speed <= 0.0 && !isDrawingBow(player)) {
            state.reset();
            return;
        }

        Entity target = pickTarget(player, state);
        if (target == null) {
            state.reset();
            return;
        }

        Vec3 eye = player.getEyePosition();
        Vec3 velocity = smoothVelocity(state, target);
        Vec3 aimPoint = leadPoint(eye, target.getBoundingBox().getCenter(), velocity, speed);
        // Below a bow's firing threshold no arc can be solved; a flat aim is the best guide until the draw gets
        // there, and no shot can land yet.
        Aim aim = speed > 0.0 ? ballisticAim(eye, aimPoint, speed) : flatAim(eye, aimPoint);

        AimTone.Guide guide = new AimTone.Guide(eye, aimPoint, Mth.wrapDegrees(aim.yaw() - player.getYRot()),
                aim.pitch() - player.getXRot());
        state.onTarget = speed > 0.0 && wouldHit(player, target, velocity, speed,
                state.onTarget ? HIT_RELEASE_MARGIN_BLOCKS : HIT_MARGIN_BLOCKS);
        state.tone.play(client, player, guide, state.onTarget, config.volume / 100.0f);
    }

    /**
     * The locked entity if it is still around, otherwise the visible hostile nearest the player's aim, so the beacon
     * follows what the player is already pointing at rather than what is merely closest.
     */
    private Entity pickTarget(LocalPlayer player, State state) {
        Entity lockedOn = MainClass.poiManager.lockingHandler.getLockedEntity();
        if (lockedOn != null && lockedOn.isAlive() && lockedOn.level() == player.level()) {
            return lockedOn;
        }

        if (state.target != null && (!state.target.isAlive() || state.target.level() != player.level())) {
            state.target = null;
        }
        if (--state.ticksUntilScan > 0 && state.target != null) return state.target;
        state.ticksUntilScan = 5;

        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        Entity best = null;
        double bestDot = -2.0;
        for (Entity entity : player.level().getEntities(player, player.getBoundingBox().inflate(SEARCH_RANGE),
                candidate -> candidate instanceof Enemy && candidate.isAlive())) {
            Vec3 center = entity.getBoundingBox().getCenter();
            Vec3 toward = center.subtract(eye);
            double distance = toward.length();
            if (distance > SEARCH_RANGE || distance < 1.0e-3) continue;
            double dot = toward.scale(1.0 / distance).dot(look);
            if (dot > bestDot && canSee(player.level(), player, eye, center)) {
                best = entity;
                bestDot = dot;
            }
        }
        state.target = best;
        return best;
    }

    private static boolean canSee(Level level, Entity viewer, Vec3 eye, Vec3 point) {
        return level.clip(new ClipContext(eye, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer))
                .getType() == HitResult.Type.MISS;
    }

    /** Bow being drawn (even before it can fire), loaded crossbow, or trident held back. */
    private static boolean isDrawingBow(LocalPlayer player) {
        return player.isUsingItem() && player.getUseItem().getItem() instanceof BowItem;
    }

    /**
     * How fast the projectile the player is about to release would leave, or 0 when there is nothing to solve an arc
     * for yet. A bow scales with its draw; a loaded crossbow fires an arrow at a fixed speed (a firework rocket flies
     * under its own rules, so it is left to plain aim); a trident is thrown at a fixed speed unless it is a Riptide
     * one, which launches the player instead.
     */
    private static double launchSpeed(LocalPlayer player) {
        float drawPower = drawingBowPower(player);
        if (drawPower >= MIN_DRAW_POWER) {
            return drawPower * BOW_SPEED_FACTOR;
        }
        if (player.isUsingItem() && player.getUseItem().getItem() instanceof TridentItem) {
            return EnchantmentHelper.getTridentSpinAttackStrength(player.getUseItem(), player) > 0.0f
                    ? 0.0
                    : TRIDENT_SPEED;
        }
        ItemStack crossbow = loadedCrossbow(player);
        if (crossbow != null) {
            ChargedProjectiles loaded = crossbow.get(DataComponents.CHARGED_PROJECTILES);
            return loaded != null && !loaded.contains(Items.FIREWORK_ROCKET) ? CROSSBOW_ARROW_SPEED : 0.0;
        }
        return 0.0;
    }

    /**
     * The loaded crossbow the player would fire, or null. The main hand's wins; an off-hand one only counts while the
     * main hand is empty, so a loaded crossbow carried in the off-hand doesn't bend the aim during a sword fight.
     */
    private static ItemStack loadedCrossbow(LocalPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (main.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(main)) {
            return main;
        }
        ItemStack off = player.getOffhandItem();
        if (main.isEmpty() && off.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(off)) {
            return off;
        }
        return null;
    }

    private static float drawingBowPower(LocalPlayer player) {
        if (!isDrawingBow(player)) return 0.0f;
        return BowItem.getPowerForTime(player.getTicksUsingItem());
    }

    /** The target's movement per tick, smoothed - zero for the first tick a target is seen. */
    private static Vec3 smoothVelocity(State state, Entity target) {
        Vec3 measured = target.position().subtract(target.oldPosition());
        if (target != state.velocityTarget) {
            state.velocityTarget = target;
            state.smoothedVelocity = Vec3.ZERO;
            return state.smoothedVelocity;
        }
        state.smoothedVelocity = state.smoothedVelocity.scale(1.0 - VELOCITY_SMOOTHING)
                .add(measured.scale(VELOCITY_SMOOTHING));
        return state.smoothedVelocity;
    }

    /** Where the target will be about when the shot gets there: its centre plus its velocity times a rough flight time. */
    private static Vec3 leadPoint(Vec3 eye, Vec3 center, Vec3 velocity, double speed) {
        if (speed <= 0.0) return center;
        double ticks = Math.min(center.distanceTo(eye) / speed, MAX_LEAD_TICKS);
        return center.add(velocity.scale(ticks));
    }

    /**
     * Flies the shot from the player's real look direction and says whether it reaches the target before a block
     * stops it. The target is moved along its velocity each tick alongside the arrow, and its box grown by
     * {@code margin}.
     */
    private static boolean wouldHit(LocalPlayer player, Entity target, Vec3 velocity, double speed, double margin) {
        Level level = player.level();
        Vec3 position = player.getEyePosition();
        Vec3 motion = player.getLookAngle().scale(speed);
        AABB box = target.getBoundingBox().inflate(margin);
        for (int tick = 0; tick < MAX_FLIGHT_TICKS; tick++) {
            Vec3 next = position.add(motion);
            AABB movedBox = box.move(velocity.scale(tick + 1));
            Optional<Vec3> entityHit = movedBox.clip(position, next);
            BlockHitResult blockHit = level.clip(
                    new ClipContext(position, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            boolean blocked = blockHit.getType() != HitResult.Type.MISS;
            if (entityHit.isPresent()) {
                // Hit the target unless a block is in the way first on this same step.
                return !blocked
                        || position.distanceToSqr(entityHit.get()) <= position.distanceToSqr(blockHit.getLocation());
            }
            if (blocked) return false;
            position = next;
            motion = new Vec3(motion.x * PROJECTILE_DRAG, motion.y * PROJECTILE_DRAG - PROJECTILE_GRAVITY,
                    motion.z * PROJECTILE_DRAG);
        }
        return false;
    }

    /** Where to point to land a projectile of the given speed on a point, and whether that arc really gets there. */
    private static Aim ballisticAim(Vec3 eye, Vec3 target, double speed) {
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        if (horizontalDistance <= 1.0e-3) {
            return new Aim(yaw, (float) Math.toDegrees(Math.atan2(-dy, horizontalDistance)));
        }
        return new Aim(yaw, solveBallisticPitch(horizontalDistance, dy, speed));
    }

    /** The straight-line aim at a point, for when no arc can be solved yet. */
    private static Aim flatAim(Vec3 eye, Vec3 target) {
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return new Aim((float) Math.toDegrees(Math.atan2(-dx, dz)),
                (float) Math.toDegrees(Math.atan2(-dy, horizontal)));
    }

    /**
     * Binary search for the launch pitch whose simulated arc passes through {@code heightDiff} at
     * {@code horizontalDistance}. A steeper (more upward) pitch always arrives higher at that distance than a flatter
     * one, so the simulated height is monotonic in pitch across a sane range, which is all bisection needs.
     */
    private static float solveBallisticPitch(double horizontalDistance, double heightDiff, double speed) {
        double low = -80.0;
        double high = 80.0;
        boolean reachable = false;
        for (int i = 0; i < BISECTION_STEPS; i++) {
            double mid = (low + high) / 2.0;
            double height = heightAtDistance(mid, speed, horizontalDistance);
            if (Double.isFinite(height)) {
                reachable = true;
                if (height > heightDiff) {
                    low = mid;
                } else {
                    high = mid;
                }
            } else {
                // Steep angle couldn't cover the horizontal distance; try flatter angles.
                high = mid;
            }
        }
        if (!reachable) {
            return (float) Math.toDegrees(Math.atan2(-heightDiff, horizontalDistance));
        }
        return (float) ((low + high) / 2.0);
    }

    /**
     * Simulates a projectile launched at {@code pitchDegrees}/{@code speed} and returns its height once it has first
     * travelled {@code horizontalDistance}, interpolated between the two surrounding ticks - or
     * {@link Double#NEGATIVE_INFINITY} if it never covers that much ground (an overly steep angle, or too little draw
     * speed).
     */
    private static double heightAtDistance(double pitchDegrees, double speed, double horizontalDistance) {
        double pitch = Math.toRadians(pitchDegrees);
        double vy = -Math.sin(pitch) * speed;
        double vxz = Math.cos(pitch) * speed;
        double x = 0.0;
        double y = 0.0;
        for (int tick = 0; tick < MAX_SIMULATION_TICKS; tick++) {
            double nextX = x + vxz;
            double nextY = y + vy;
            if (nextX >= horizontalDistance) {
                double fraction = nextX > x ? (horizontalDistance - x) / (nextX - x) : 1.0;
                return y + (nextY - y) * fraction;
            }
            x = nextX;
            y = nextY;
            vxz *= PROJECTILE_DRAG;
            vy = vy * PROJECTILE_DRAG - PROJECTILE_GRAVITY;
        }
        return Double.NEGATIVE_INFINITY;
    }

    /** Vanilla yaw and pitch, in degrees, that point the shot. */
    private record Aim(float yaw, float pitch) {
    }

    /**
     * Everything the beacon remembers between ticks of one session.
     */
    private static final class State {
        private final AimTone tone = new AimTone();
        private Entity target;
        private Entity velocityTarget;
        private Vec3 smoothedVelocity = Vec3.ZERO;
        private int ticksUntilScan;
        private boolean onTarget;

        private void reset() {
            tone.reset();
            target = null;
            velocityTarget = null;
            smoothedVelocity = Vec3.ZERO;
            ticksUntilScan = 0;
            onTarget = false;
        }
    }
}
