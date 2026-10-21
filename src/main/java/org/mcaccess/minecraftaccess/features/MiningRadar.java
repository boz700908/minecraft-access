package org.mcaccess.minecraftaccess.features;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

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
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.NotNull;

import org.mcaccess.minecraftaccess.Config;
import org.mcaccess.minecraftaccess.MainClass;
import org.mcaccess.minecraftaccess.utils.NarrationUtils;
import org.mcaccess.minecraftaccess.utils.events.ClientPlayingTick;

/**
 * Quietly chimes, and says what and where, when valuable ore comes into view while mining. Every few ticks it
 * looks at a small box around the player for ore that is genuinely visible, not merely adjacent to air: a face
 * on the far side of an unmined wall or in a sealed pocket is not something the player can see, so each of them
 * is put to the same raycast vanilla uses for block interaction. That keeps this an awareness aid rather than
 * x-ray.
 *
 * <p>Breaking into a vein can reveal several blocks at once, and narrating them all in one tick would cut each
 * sentence off with the next, so finds are queued and announced a few ticks apart. A block is announced once
 * per session, with the oldest of a few thousand remembered positions dropped rather than letting a long
 * strip-mining session accumulate one entry per ore ever seen.
 */
public class MiningRadar implements BalmClientModule {
    private static final int SCAN_INTERVAL_TICKS = 10;
    private static final int ALERT_INTERVAL_TICKS = 5;
    private static final int MAX_REMEMBERED_ALERTS = 4096;
    private static final float ALERT_VOLUME = 0.6f;
    private static final float ALERT_PITCH = 1.6f;

    private final SessionLocal<State> state = new SessionLocal<>(State::new);

    @Override
    public @NotNull Identifier getId() {
        return Identifier.fromNamespaceAndPath(MainClass.MOD_ID, "mining_radar");
    }

    @Override
    public void initialize() {
        ClientPlayingTick.AFTER.register(this::tick);
    }

    private void tick(Minecraft client, LocalPlayer player, ClientLevel level) {
        Config.MiningRadar config = Config.getInstance().miningRadar;
        State state = this.state.value;
        if (!config.enabled) {
            state.pending.clear();
            return;
        }

        if (state.ticksUntilScan <= 0) {
            state.ticksUntilScan = SCAN_INTERVAL_TICKS;
            scan(player, level, state, config);
        } else {
            state.ticksUntilScan--;
        }

        if (!state.pending.isEmpty() && state.ticksUntilNextAlert <= 0) {
            state.ticksUntilNextAlert = ALERT_INTERVAL_TICKS;
            alert(client, player, state.pending.poll(), config);
        } else if (state.ticksUntilNextAlert > 0) {
            state.ticksUntilNextAlert--;
        }
    }

    private void scan(LocalPlayer player, Level level, State state, Config.MiningRadar config) {
        BlockPos center = player.blockPosition();
        Vec3 eye = player.getEyePosition();
        int radius = config.range;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius),
                center.offset(radius, radius, radius))) {
            if (state.alerted.contains(pos)) continue;
            if (!isValuableOre(level.getBlockState(pos)) || !isVisible(level, pos, eye)) continue;

            BlockPos found = pos.immutable();
            state.alerted.add(found);
            state.pending.add(found);
            if (state.alerted.size() > MAX_REMEMBERED_ALERTS) {
                Iterator<BlockPos> oldest = state.alerted.iterator();
                oldest.next();
                oldest.remove();
            }
        }
    }

    private void alert(Minecraft client, LocalPlayer player, BlockPos pos, Config.MiningRadar config) {
        Level level = player.level();
        BlockState blockState = level.getBlockState(pos);
        if (!isValuableOre(blockState)) return; // Mined away before its turn in the queue came.

        Vec3 center = Vec3.atCenterOf(pos);
        RandomSource random = player.getRandom();
        client.getSoundManager().play(new SimpleSoundInstance(SoundEvents.PLAYER_LEVELUP, SoundSource.MASTER,
                config.volume / 100.0f * ALERT_VOLUME, ALERT_PITCH, random, center.x(), center.y(), center.z()));

        int distance = (int) Math.round(player.getEyePosition().distanceTo(center));
        MainClass.narrate(I18n.get("minecraft_access.mining_radar.alert", blockState.getBlock().getName().getString(),
                distance, NarrationUtils.narrateRelativeDirection(center)), true);
    }

    private static boolean isValuableOre(BlockState state) {
        return state.is(BlockTags.COAL_ORES) || state.is(BlockTags.IRON_ORES) || state.is(BlockTags.COPPER_ORES)
                || state.is(BlockTags.GOLD_ORES) || state.is(BlockTags.REDSTONE_ORES) || state.is(BlockTags.LAPIS_ORES)
                || state.is(BlockTags.DIAMOND_ORES) || state.is(BlockTags.EMERALD_ORES)
                || state.is(Blocks.NETHER_GOLD_ORE) || state.is(Blocks.NETHER_QUARTZ_ORE)
                || state.is(Blocks.ANCIENT_DEBRIS);
    }

    /**
     * Whether a raycast from the player's eye reaches the block itself. Ore behind stone, or alone in a sealed
     * pocket, stops the ray short, which is exactly what the player could and could not see.
     */
    private static boolean isVisible(Level level, BlockPos pos, Vec3 eye) {
        HitResult hit = level.clip(new ClipContext(eye, Vec3.atCenterOf(pos), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(pos);
    }

    /** What has been found and said so far this session, and when the next scan or announcement is due. */
    private static final class State {
        private final Set<BlockPos> alerted = new LinkedHashSet<>();
        private final Deque<BlockPos> pending = new ArrayDeque<>();
        private int ticksUntilScan;
        private int ticksUntilNextAlert;
    }
}
