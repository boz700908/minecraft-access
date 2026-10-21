package org.mcaccess.minecraftaccess.features;

import net.blay09.mods.balm.client.platform.module.BalmClientModule;
import net.blay09.mods.balm.client.platform.util.SessionLocal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HayBlock;
import net.minecraft.world.level.block.ShelfMushroomBlock;
import net.minecraft.world.level.block.SlimeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import org.mcaccess.minecraftaccess.Config;
import org.mcaccess.minecraftaccess.MainClass;
import org.mcaccess.minecraftaccess.utils.events.ClientPlayingTick;

/**
 * Warns about a drop before it is stepped off: a fall deeper than the configured threshold, lying along the
 * direction the player is actually moving (their horizontal velocity, not where they are facing) and as far
 * ahead as that speed will carry them in the configured reaction time, so a sprint is warned about sooner than
 * a walk. Every fall past the threshold is announced, not only the ones that hurt, but a landing that will not
 * hurt - water, a slime block, a bed - says so, which turns a cave lake below into useful information rather
 * than a false alarm.
 *
 * <p>Only while standing on solid ground, so the warning comes once, ahead of the edge, rather than as a
 * running commentary on the way down; never in creative or spectator, while gliding, or with slow falling or
 * levitation, none of which can take fall damage. The lookahead line is sampled a block at a time and the
 * ground level carried forward, so a staircase that can be walked down is not mistaken for one long drop, and
 * each segment is swept rather than sampled, so a thin wall cannot slip between two samples.
 *
 * <p>Whether a landing hurts follows what the game itself does with it: water and a bouncing block take no
 * damage at all, hay reduces it to a fifth, and a bed halves the drop before the player's own safe-fall
 * distance comes off. Armour and feather falling deliberately do not enter into it, since they only scale
 * damage that has already been decided. Lava, magma, cactus, powder snow and a sweet berry bush are named on
 * their own, because a short drop into lava would otherwise be read as safe.
 */
public class FallWarning implements BalmClientModule {
    private static final String PREFIX = "minecraft_access.fall_warning.";
    private static final double FALL_POWER_EPSILON = 1.0e-6;
    private static final double MIN_HORIZONTAL_SPEED_SQR = 0.02 * 0.02;
    private static final double TICKS_PER_SECOND = 20.0;
    private static final double MIN_LOOKAHEAD = 3.0;
    private static final double MAX_LOOKAHEAD_PER_SECOND = 8.0;
    private static final double SAMPLE_STEP = 1.0;
    private static final int RE_WARN_COOLDOWN_TICKS = 40;

    private final SessionLocal<State> state = new SessionLocal<>(State::new);

    @Override
    public @NotNull Identifier getId() {
        return Identifier.fromNamespaceAndPath(MainClass.MOD_ID, "fall_warning");
    }

    @Override
    public void initialize() {
        ClientPlayingTick.AFTER.register(this::tick);
    }

    private void tick(Minecraft client, LocalPlayer player, ClientLevel level) {
        Config.FallWarning config = Config.getInstance().fallWarning;
        if (!config.enabled) return;

        State state = this.state.value;
        state.ticks++;

        if (!player.onGround() || player.isSpectator() || player.getAbilities().invulnerable
                || player.isFallFlying() || player.hasEffect(MobEffects.SLOW_FALLING) || player.hasEffect(MobEffects.LEVITATION)) {
            return;
        }

        double vx = player.getDeltaMovement().x;
        double vz = player.getDeltaMovement().z;
        double speedSqr = vx * vx + vz * vz;
        if (speedSqr < MIN_HORIZONTAL_SPEED_SQR) {
            // Standing still, so approaching the same edge again may warn again instead of being held back
            // by the cooldown below.
            state.lastWarnedColumn = null;
            return;
        }

        double lookaheadSeconds = config.lookaheadSeconds;
        double speed = Math.sqrt(speedSqr);
        double lookahead = Mth.clamp(speed * lookaheadSeconds * TICKS_PER_SECOND,
                MIN_LOOKAHEAD, MAX_LOOKAHEAD_PER_SECOND * lookaheadSeconds);
        double dirX = vx / speed;
        double dirZ = vz / speed;

        // Walked in steps rather than measured against the far endpoint: a staircase can be walked down,
        // and each tread advances the ground level instead of the whole flight counting as one drop.
        double referenceY = player.getY();
        double aheadX = player.getX();
        double aheadZ = player.getZ();
        double prevX = player.getX();
        double prevZ = player.getZ();
        BlockPos landing = null;
        double dropHeight = 0;
        boolean dangerous = false;

        for (double d = SAMPLE_STEP; d <= lookahead + 1.0e-9; d += SAMPLE_STEP) {
            double sampleDist = Math.min(d, lookahead);
            double x = player.getX() + dirX * sampleDist;
            double z = player.getZ() + dirZ * sampleDist;

            // Swept over the whole segment, since a wall narrower than one step could sit between two
            // samples and never be hit by either.
            if (!canOccupySwept(level, player, prevX, prevZ, x, z, referenceY)
                    || !canOccupySwept(level, player, prevX, prevZ, x, z, referenceY + 1.0)) {
                // A wall is reached before any fall could be, so there is nothing to warn about.
                return;
            }
            prevX = x;
            prevZ = z;

            landing = scanForLanding(level, Mth.floor(x), Mth.floor(referenceY), Mth.floor(z));
            // No floor to the bottom of the world is a fall out of it, which always kills: worth a
            // damaging warning rather than silence.
            dropHeight = landing != null ? referenceY - (landing.getY() + 1) : referenceY - level.getMinY();
            aheadX = x;
            aheadZ = z;

            if (dropHeight > config.threshold) {
                dangerous = true;
                break;
            }
            if (landing == null) break;

            referenceY = landing.getY() + 1;
            if (sampleDist >= lookahead) break;
        }

        if (!dangerous) return;

        BlockPos column = new BlockPos(Mth.floor(aheadX), 0, Mth.floor(aheadZ));
        if (column.equals(state.lastWarnedColumn) && state.ticks - state.lastWarnTick < RE_WARN_COOLDOWN_TICKS) {
            return;
        }
        state.lastWarnedColumn = column;
        state.lastWarnTick = state.ticks;

        boolean damaging = landing == null || wouldDamage(player, level.getBlockState(landing), dropHeight);
        String hazard = landing == null ? PREFIX + "hazard_void" : hazardOf(level.getBlockState(landing));
        // Played at the edge itself, not at the landing spot: for anything but a short drop that spot is far
        // enough away - or, falling out of the world, absent - that attenuation would silence it exactly
        // when it matters.
        warn(client, player, new Vec3(aheadX, referenceY, aheadZ), dropHeight, damaging, hazard);
    }

    /**
     * Whether the player's box would fit anywhere along the line from one point to the other at a given
     * height.
     *
     * @param level where to test
     * @param player whose box is swept
     * @param fromX start of the segment
     * @param fromZ start of the segment
     * @param toX end of the segment
     * @param toZ end of the segment
     * @param y the height to test at
     * @return true if nothing is in the way
     */
    private static boolean canOccupySwept(Level level, LocalPlayer player, double fromX, double fromZ,
                                          double toX, double toZ, double y) {
        AABB box = player.getBoundingBox().move(fromX - player.getX(), y - player.getY(), fromZ - player.getZ());
        return level.noCollision(player, box.expandTowards(toX - fromX, 0, toZ - fromZ));
    }

    /**
     * The first solid or fluid block straight down from a height.
     *
     * @param level where to look
     * @param x the column
     * @param startY the height to start from
     * @param z the column
     * @return where something would be landed on, or null if nothing is down to the bottom of the world
     */
    private static BlockPos scanForLanding(Level level, int x, int startY, int z) {
        int minY = level.getMinY();
        BlockPos pos = new BlockPos(x, startY, z);
        while (pos.getY() >= minY) {
            BlockState scanState = level.getBlockState(pos);
            if (!scanState.getFluidState().isEmpty() || !scanState.getCollisionShape(level, pos).isEmpty()) return pos;
            pos = pos.below();
        }
        return null;
    }

    /**
     * A landing that hurts whatever the height math says, so it can be named rather than folded into the
     * generic warning. Lava especially: the height math only weighs it as an ordinary fluid, so a short drop
     * into it would otherwise be read as a safe landing.
     *
     * @param landingState what the fall would land on
     * @return the translation key of the hazard's name, or null for an ordinary landing
     */
    private static String hazardOf(BlockState landingState) {
        if (landingState.getFluidState().is(FluidTags.LAVA)) return PREFIX + "hazard_lava";
        Block block = landingState.getBlock();
        if (block == Blocks.MAGMA_BLOCK) return PREFIX + "hazard_magma";
        if (block == Blocks.CACTUS) return PREFIX + "hazard_cactus";
        if (block == Blocks.POWDER_SNOW) return PREFIX + "hazard_powder_snow";
        if (block == Blocks.SWEET_BERRY_BUSH) return PREFIX + "hazard_sweet_berry_bush";
        return null;
    }

    /**
     * Whether this fall would hurt, following what the landing block itself does to it.
     *
     * @param player whose own safe-fall distance and fall-damage multiplier apply
     * @param landingState what the fall would land on
     * @param dropHeight how far the fall is, in blocks
     * @return true if landing there would cost health
     */
    private static boolean wouldDamage(LocalPlayer player, BlockState landingState, double dropHeight) {
        if (landingState.getFluidState().is(FluidTags.WATER)) return false;

        Block block = landingState.getBlock();
        // A bouncing block redirects the entity instead of letting it land, so it never takes fall damage;
        // there is no shared marker for that the way beds share a drop reduction.
        if (block instanceof SlimeBlock || block instanceof ShelfMushroomBlock) return false;

        // The same fraction the landing itself reduces the drop by, which covers every bed variant without
        // naming their types.
        double effectiveDrop = dropHeight * (1.0 - block.getFallDistanceReduction());
        double damageModifier = block instanceof HayBlock ? 0.2 : 1.0;

        double fallPower = effectiveDrop + FALL_POWER_EPSILON - player.getAttributeValue(Attributes.SAFE_FALL_DISTANCE);
        int damage = Mth.floor(fallPower * damageModifier * player.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER));
        return damage > 0;
    }

    private static void warn(Minecraft client, LocalPlayer player, Vec3 pos, double dropHeight,
                             boolean damaging, String hazard) {
        RandomSource random = player.getRandom();
        int blocks = (int) Math.round(dropHeight);
        float volume = Config.getInstance().fallWarning.volume / 100.0f;

        if (hazard != null) {
            // A low note block tone is its own kind of bad, and short enough not to grate when it repeats,
            // while staying in the same family as the other cues.
            client.getSoundManager().play(new SimpleSoundInstance(SoundEvents.NOTE_BLOCK_DIDGERIDOO.value(),
                    SoundSource.MASTER, volume, 0.7f, random, pos.x(), pos.y(), pos.z()));
            MainClass.narrate(I18n.get(PREFIX + "hazard", blocks, I18n.get(hazard)), true);
            return;
        }

        // An anvil reads as unmistakably bad, against the bright pling of a landing that will not hurt.
        if (damaging) {
            client.getSoundManager().play(new SimpleSoundInstance(SoundEvents.ANVIL_LAND, SoundSource.MASTER,
                    volume, 0.8f, random, pos.x(), pos.y(), pos.z()));
        } else {
            client.getSoundManager().play(new SimpleSoundInstance(SoundEvents.NOTE_BLOCK_PLING.value(),
                    SoundSource.MASTER, volume * 1.5f, 1.3f, random, pos.x(), pos.y(), pos.z()));
        }
        MainClass.narrate(I18n.get(PREFIX + (damaging ? "damaging" : "safe"), blocks), true);
    }

    /** Where the last warning was given, so the same edge is not announced twice in a row. */
    private static final class State {
        private int ticks;
        private BlockPos lastWarnedColumn;
        private int lastWarnTick = -RE_WARN_COOLDOWN_TICKS;
    }
}
