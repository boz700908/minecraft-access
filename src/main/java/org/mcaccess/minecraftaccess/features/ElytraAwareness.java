package org.mcaccess.minecraftaccess.features;

import net.blay09.mods.balm.client.platform.module.BalmClientModule;
import net.blay09.mods.balm.client.platform.util.SessionLocal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import org.mcaccess.minecraftaccess.Config;
import org.mcaccess.minecraftaccess.MainClass;
import org.mcaccess.minecraftaccess.utils.events.ClientPlayingTick;

/**
 * Flight awareness while gliding: a sighted pilot sees the wall, the lava or the ground coming, and this says
 * so.
 *
 * <p>The rest of the flight is predicted every tick, assuming the player keeps looking where they are
 * looking, by stepping the same velocity update the game itself applies to a glider and testing each step
 * against the real blocks. How the path ends decides the cue: a wall, a hard landing, harmful ground or lava
 * is a warning, spoken as it comes within four seconds and then beeped faster as it nears, with another
 * spoken command in the last moments; only an impact that would actually hurt counts, so a graze along a wall
 * or a gentle skim over the ground is not worth an alarm. A gentle landing, or water, is good news instead: a
 * chime and a word, then the height above the ground counted down.
 *
 * <p>Damage is estimated the way the game does it: hitting a wall costs ten times the horizontal speed lost
 * against it, less three, and a landing hurts once the accumulated fall distance - which gliding keeps capped
 * at a block unless sinking faster than half a block a tick - passes the player's own safe-fall distance.
 * Armour and protection are not counted, so a predicted hit is the worst case.
 */
public class ElytraAwareness implements BalmClientModule {
    private static final String PREFIX = "minecraft_access.elytra_aware.";
    private static final double GRAVITY = 0.08;
    private static final int HORIZON_TICKS = 160;
    private static final int CAUTION_TICKS = 80;
    private static final int WARNING_TICKS = 55;
    private static final int CRITICAL_TICKS = 28;
    // Fall damage becomes possible once sinking a half block a tick or faster at touchdown.
    private static final double CAUTION_SINK = 0.45;
    private static final double MIN_DAMAGE = 1.0;
    private static final double PLAYER_CENTER_HEIGHT = 0.3;
    private static final int[] CALLOUT_HEIGHTS = {5, 10, 20, 30, 50};
    private static final double MAX_HEIGHT_PROBE = 120.0;
    private static final int REPEAT_CRITICAL_TICKS = 30;
    private static final int[] BEEP_INTERVAL_TICKS = {0, 0, 9, 4};

    private final SessionLocal<State> state = new SessionLocal<>(State::new);

    @Override
    public @NotNull Identifier getId() {
        return Identifier.fromNamespaceAndPath(MainClass.MOD_ID, "elytra_awareness");
    }

    @Override
    public void initialize() {
        ClientPlayingTick.AFTER.register(this::tick);
    }

    private void tick(Minecraft client, LocalPlayer player, ClientLevel level) {
        if (!Config.getInstance().elytraAwareness.enabled) return;

        State state = this.state.value;
        boolean gliding = player.isFallFlying() && !player.isSpectator() && !player.getAbilities().invulnerable;
        if (!gliding) {
            if (state.wasGliding && state.safeWhenLastGliding && player.onGround()) {
                MainClass.narrate(I18n.get(PREFIX + "landed"), true);
            }
            state.reset();
            return;
        }
        state.wasGliding = true;
        state.sinceBeep++;
        state.sinceSpeech++;

        Impact impact = predict(player);
        boolean danger = impact != null && impact.kind().dangerous;
        state.safeWhenLastGliding = impact != null && !danger;

        if (danger) {
            state.landingAnnounced = false;
            state.lastCallout = Integer.MAX_VALUE;
            warn(client, player, impact, state);
            return;
        }
        state.level = 0;
        state.dangerKind = null;

        if (impact != null) {
            announceLanding(client, player, impact, state);
        } else {
            state.landingAnnounced = false;
            state.lastCallout = Integer.MAX_VALUE;
        }
    }

    private static void warn(Minecraft client, LocalPlayer player, Impact impact, State state) {
        int urgency = impact.ticks() <= CRITICAL_TICKS ? 3
                : impact.ticks() <= WARNING_TICKS ? 2
                : impact.ticks() <= CAUTION_TICKS ? 1 : 0;
        if (urgency == 0) {
            state.level = 0;
            state.dangerKind = null;
            return;
        }
        boolean worse = urgency > state.level || impact.kind() != state.dangerKind;
        boolean repeat = urgency == 3 && state.sinceSpeech >= REPEAT_CRITICAL_TICKS;
        boolean fatal = impact.damage() >= player.getHealth() + player.getAbsorptionAmount();
        if (worse || repeat) {
            MainClass.narrate(I18n.get(PREFIX + impact.kind().name().toLowerCase(java.util.Locale.ROOT) + "_" + urgency,
                    (int) Math.round(impact.damage() / 2.0)), true);
            state.sinceSpeech = 0;
        }
        int interval = BEEP_INTERVAL_TICKS[urgency];
        if (interval > 0 && state.sinceBeep >= interval) {
            play(client, player, SoundEvents.NOTE_BLOCK_BIT.value(), fatal ? 1.0f : 0.8f,
                    (urgency == 3 ? 1.8f : 1.3f) + (fatal ? 0.2f : 0.0f));
            state.sinceBeep = 0;
        }
        state.level = urgency;
        state.dangerKind = impact.kind();
    }

    private static void announceLanding(Minecraft client, LocalPlayer player, Impact impact, State state) {
        double height = heightAboveGround(player);
        if (!state.landingAnnounced) {
            state.landingAnnounced = true;
            state.lastCallout = armCallouts(height);
            play(client, player, SoundEvents.NOTE_BLOCK_BANJO.value(), 0.8f, 1.5f);
            MainClass.narrate(I18n.get(PREFIX + (impact.kind() == Kind.WATER ? "water" : "safe")), true);
            return;
        }
        // Climbing back above a callout already given re-arms the ones below it.
        if (state.lastCallout != Integer.MAX_VALUE && height > state.lastCallout + 8) {
            state.lastCallout = armCallouts(height);
        }
        int crossed = -1;
        for (int callout : CALLOUT_HEIGHTS) {
            if (height <= callout && callout < state.lastCallout) {
                crossed = callout;
                break;
            }
        }
        if (crossed > 0) {
            state.lastCallout = crossed;
            MainClass.narrate(I18n.get(PREFIX + "height", crossed), true);
        }
    }

    private static int armCallouts(double height) {
        for (int callout : CALLOUT_HEIGHTS) {
            if (callout >= height) return callout;
        }
        return Integer.MAX_VALUE;
    }

    /**
     * How far the ground is straight below the player.
     *
     * @param player whose position is measured
     * @return the distance, or the probe length if nothing is below
     */
    private static double heightAboveGround(LocalPlayer player) {
        Vec3 from = player.position();
        Vec3 to = from.add(0.0, -MAX_HEIGHT_PROBE, 0.0);
        BlockHitResult hit = player.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, player));
        return hit.getType() == HitResult.Type.MISS ? MAX_HEIGHT_PROBE : from.y - hit.getLocation().y;
    }

    /**
     * Flies the rest of the glide on the current look and reports how it ends.
     *
     * @param player whose flight is predicted
     * @return the impact within the horizon, or null if nothing hurtful or notable comes up
     */
    private static Impact predict(LocalPlayer player) {
        Level level = player.level();
        Vec3 velocity = player.getDeltaMovement();
        double vx = velocity.x();
        double vy = velocity.y();
        double vz = velocity.z();
        double x = player.getX();
        double y = player.getY() + PLAYER_CENTER_HEIGHT;
        double z = player.getZ();
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        double fall = player.fallDistance;
        double safeFall = player.getAttributeValue(Attributes.SAFE_FALL_DISTANCE);
        double fallMultiplier = player.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER);

        for (int t = 1; t <= HORIZON_TICKS; t++) {
            double[] v = stepVelocity(vx, vy, vz, yaw, pitch);
            vx = v[0];
            vy = v[1];
            vz = v[2];
            // The game's own check, then the move's own accumulation.
            if (vy > -0.5 && fall > 1.0) fall = 1.0;
            if (vy < 0.0) fall -= vy;

            BlockHitResult hit = level.clip(new ClipContext(new Vec3(x, y, z), new Vec3(x + vx, y + vy, z + vz),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, player));
            if (hit.getType() == HitResult.Type.BLOCK) {
                return classify(level, hit, t, vx, vy, vz, fall, safeFall, fallMultiplier);
            }
            x += vx;
            y += vy;
            z += vz;
        }
        return null;
    }

    /**
     * One tick of a glider's velocity, the same update the game applies. Any change the game makes to elytra
     * flight has to be mirrored here, or the predictions drift.
     *
     * @param vx current velocity along x
     * @param vy current velocity along y
     * @param vz current velocity along z
     * @param yawDegrees where the player is looking, horizontally
     * @param pitchDegrees where the player is looking, vertically
     * @return the velocity after one tick
     */
    private static double[] stepVelocity(double velocityX, double velocityY, double velocityZ,
                                         float yawDegrees, float pitchDegrees) {
        double vx = velocityX;
        double vy = velocityY;
        double vz = velocityZ;
        double yaw = Math.toRadians(yawDegrees);
        float leanAngle = pitchDegrees * (float) (Math.PI / 180.0);
        double cosPitch = Math.cos(Math.toRadians(pitchDegrees));
        double lookX = -Math.sin(yaw) * cosPitch;
        double lookZ = Math.cos(yaw) * cosPitch;
        double lookHorLength = Math.sqrt(lookX * lookX + lookZ * lookZ);
        double moveHorLength = Math.sqrt(vx * vx + vz * vz);
        double liftForce = Math.cos(leanAngle) * Math.cos(leanAngle);

        vy += GRAVITY * (-1.0 + liftForce * 0.75);
        if (vy < 0.0 && lookHorLength > 0.0) {
            double convert = vy * -0.1 * liftForce;
            vx += lookX * convert / lookHorLength;
            vy += convert;
            vz += lookZ * convert / lookHorLength;
        }
        if (leanAngle < 0.0F && lookHorLength > 0.0) {
            double convert = moveHorLength * -Math.sin(leanAngle) * 0.04;
            vx += -lookX * convert / lookHorLength;
            vy += convert * 3.2;
            vz += -lookZ * convert / lookHorLength;
        }
        if (lookHorLength > 0.0) {
            vx += (lookX / lookHorLength * moveHorLength - vx) * 0.1;
            vz += (lookZ / lookHorLength * moveHorLength - vz) * 0.1;
        }
        return new double[] {vx * 0.99F, vy * 0.98F, vz * 0.99F};
    }

    private static Impact classify(Level level, BlockHitResult hit, int ticks, double vx, double vy, double vz,
                                   double fall, double safeFall, double fallMultiplier) {
        BlockPos pos = hit.getBlockPos();
        BlockState blockState = level.getBlockState(pos);
        if (!level.getFluidState(pos).isEmpty() && blockState.getBlock() instanceof LiquidBlock) {
            return new Impact(level.getFluidState(pos).is(FluidTags.LAVA) ? Kind.LAVA : Kind.WATER, ticks, 0.0);
        }
        Direction face = hit.getDirection();
        if (face == Direction.UP) {
            if (blockState.is(Blocks.CACTUS) || blockState.is(Blocks.MAGMA_BLOCK) || blockState.is(BlockTags.CAMPFIRES)
                    || blockState.is(Blocks.FIRE) || blockState.is(Blocks.SOUL_FIRE) || blockState.is(Blocks.SWEET_BERRY_BUSH)) {
                return new Impact(Kind.HAZARD, ticks, 0.0);
            }
            double damage = Math.max(0.0, Mth.floor((fall + 1.0e-6 - safeFall) * fallMultiplier));
            if (damage >= MIN_DAMAGE || -vy > CAUTION_SINK) return new Impact(Kind.HARD_LANDING, ticks, damage);
            return new Impact(Kind.SAFE_LANDING, ticks, 0.0);
        }
        // A ceiling stops the climb without the kinetic damage a wall does.
        if (face == Direction.DOWN) return null;
        double damage = Math.abs(vx * face.getStepX() + vz * face.getStepZ()) * 10.0 - 3.0;
        return damage >= MIN_DAMAGE ? new Impact(Kind.WALL, ticks, damage) : null;
    }

    private static void play(Minecraft client, LocalPlayer player, SoundEvent sound, float volume, float pitch) {
        client.getSoundManager().play(new SimpleSoundInstance(sound, SoundSource.MASTER,
                volume * Config.getInstance().elytraAwareness.volume / 100.0f, pitch,
                player.getRandom(), player.getX(), player.getY(), player.getZ()));
    }

    /** How a predicted flight ends, and what getting there costs. */
    private enum Kind {
        WALL(true),
        HARD_LANDING(true),
        HAZARD(true),
        LAVA(true),
        SAFE_LANDING(false),
        WATER(false);

        private final boolean dangerous;

        Kind(boolean dangerous) {
            this.dangerous = dangerous;
        }
    }

    /**
     * Where the flight is predicted to end.
     *
     * @param kind how it ends
     * @param ticks how far ahead that is
     * @param damage the estimated damage, zero for the safe kinds
     */
    private record Impact(Kind kind, int ticks, double damage) {
    }

    /** What has already been said about the current glide. */
    private static final class State {
        private int level;
        private Kind dangerKind;
        private int sinceBeep;
        private int sinceSpeech;
        private boolean landingAnnounced;
        private int lastCallout = Integer.MAX_VALUE;
        private boolean wasGliding;
        private boolean safeWhenLastGliding;

        private void reset() {
            level = 0;
            dangerKind = null;
            sinceBeep = 0;
            sinceSpeech = 0;
            landingAnnounced = false;
            lastCallout = Integer.MAX_VALUE;
            wasGliding = false;
        }
    }
}
