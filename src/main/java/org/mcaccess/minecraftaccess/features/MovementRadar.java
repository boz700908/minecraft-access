package org.mcaccess.minecraftaccess.features;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.platform.InputConstants;
import net.blay09.mods.balm.client.platform.module.BalmClientModule;
import net.blay09.mods.balm.client.platform.util.SessionLocal;
import net.blay09.mods.kuma.api.InputBinding;
import net.blay09.mods.kuma.api.Kuma;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;

import org.mcaccess.minecraftaccess.Config;
import org.mcaccess.minecraftaccess.MainClass;
import org.mcaccess.minecraftaccess.utils.KeyMappingCategories;
import org.mcaccess.minecraftaccess.utils.events.ClientPlayingTick;

/**
 * An audio radar for what is straight ahead, to the right and to the left, made of three real raycasts so a
 * wall heard in a direction really is in the way: a hit is classified by how far its top rises above the
 * player's feet against their own step and jump height, so a low step needs no cue while a wall too tall to
 * jump does. A blocked or jumpable direction plays the hit block's own place sound, so stone and wood already
 * sound different without a table of materials, pitched higher for jumpable than for blocked; an open
 * direction plays a short chime.
 *
 * <p>State is kept per absolute bearing rather than per direction index, since the index-to-bearing mapping
 * turns with the player and would make all three look new on every quarter turn. Cues wait a few ticks apart
 * so several changes trickle out one at a time, and a bearing that flickers across a classification boundary
 * is held back for a moment instead of repeating itself.
 */
public class MovementRadar implements BalmClientModule {
    private static final String PREFIX = "minecraft_access.movement_radar.";
    private static final int[] DIRECTION_OFFSETS = {0, 90, 270};
    private static final double PROBE_HEIGHT = 0.1;
    private static final double JUMP_HEIGHT = 1.25;
    private static final int PLAY_INTERVAL_TICKS = 3;
    private static final int BEARING_COOLDOWN_TICKS = 10;
    private static final float JUMPABLE_PITCH = 2.0f;
    private static final float BLOCKED_PITCH = 1.5f;

    private final SessionLocal<State> state = new SessionLocal<>(State::new);

    @Override
    public @NotNull Identifier getId() {
        return Identifier.fromNamespaceAndPath(MainClass.MOD_ID, "movement_radar");
    }

    @Override
    public void initialize() {
        ClientPlayingTick.AFTER.register(this::tick);

        Kuma.createKeyMapping(Identifier.fromNamespaceAndPath(MainClass.MOD_ID, "movement_radar.toggle"))
                .withDefault(InputBinding.key(InputConstants.KEY_N))
                .overrideCategory(KeyMappingCategories.OTHER)
                .handleWorldInput(_ -> {
                    toggle();
                    return true;
                })
                .build();
    }

    private static void toggle() {
        Config.MovementRadar config = Config.getInstance().movementRadar;
        config.enabled = !config.enabled;
        MainClass.narrate(I18n.get(PREFIX + (config.enabled ? "enabled" : "disabled")), true);
    }

    private void tick(Minecraft client, LocalPlayer player, ClientLevel level) {
        if (!Config.getInstance().movementRadar.enabled) return;

        State state = this.state.value;
        state.ticks++;
        int range = Config.getInstance().movementRadar.range;
        int facing = Math.floorMod(Math.round(bearingOf(player) / 90.0f) * 90, 360);

        for (int offset : DIRECTION_OFFSETS) {
            int bearing = Math.floorMod(facing + offset, 360);
            Scan scan = probe(level, player, bearing, range);

            if (state.seen.get(bearing) == scan.state()) continue;
            state.seen.put(bearing, scan.state());

            Integer lastPlayedAt = state.lastPlayed.get(bearing);
            if (lastPlayedAt != null && state.ticks - lastPlayedAt < BEARING_COOLDOWN_TICKS) continue;
            state.pending.removeIf(cue -> cue.center() == bearing);
            state.pending.add(new PendingCue(bearing, scan));
        }

        if (state.pending.isEmpty() || state.ticks - state.lastPlayAt < PLAY_INTERVAL_TICKS) return;
        // Open directions go first: being told where you can walk is worth more than where you cannot.
        int index = 0;
        for (int i = 0; i < state.pending.size(); i++) {
            if (state.pending.get(i).scan().state() == Obstacle.OPEN) {
                index = i;
                break;
            }
        }
        PendingCue cue = state.pending.remove(index);
        playCue(client, player, cue.scan(), range);
        state.lastPlayed.put(cue.center(), state.ticks);
        state.lastPlayAt = state.ticks;
    }

    /** The compass bearing the player faces, where north is 0 and east is 90. */
    private static float bearingOf(LocalPlayer player) {
        return Math.floorMod(Math.round(player.getYRot()) + 180, 360);
    }

    /**
     * Raycasts one direction from just above the player's feet.
     *
     * @param level where to cast
     * @param player whose feet and step height classify the hit
     * @param bearingDeg the direction in compass degrees
     * @param range how far to look, in blocks
     * @return what is in the way, if anything
     */
    private static Scan probe(Level level, LocalPlayer player, double bearingDeg, int range) {
        double rad = Math.toRadians(bearingDeg);
        double dirX = Math.sin(rad);
        double dirZ = -Math.cos(rad);

        // Just above the feet, not at eye height, so a low fence is still found even though a person
        // could see clean over the top of it.
        double rayY = player.getY() + PROBE_HEIGHT;
        Vec3 from = new Vec3(player.getX(), rayY, player.getZ());
        Vec3 to = new Vec3(player.getX() + dirX * range, rayY, player.getZ() + dirZ * range);

        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.MISS) return new Scan(Obstacle.OPEN, bearingDeg, range, null);

        BlockPos hitPos = hit.getBlockPos();
        BlockState hitState = level.getBlockState(hitPos);
        VoxelShape shape = hitState.getCollisionShape(level, hitPos);
        double top = hitPos.getY() + (shape.isEmpty() ? 0.0 : shape.bounds().maxY);
        double rise = top - player.getY();

        Obstacle obstacle = rise <= player.maxUpStep()
                ? Obstacle.OPEN
                : rise <= JUMP_HEIGHT ? Obstacle.JUMPABLE : Obstacle.BLOCKED;
        return new Scan(obstacle, bearingDeg, from.distanceTo(hit.getLocation()), hitState);
    }

    private static void playCue(Minecraft client, LocalPlayer player, Scan scan, int range) {
        double rad = Math.toRadians(scan.bearing());
        double dirX = Math.sin(rad);
        double dirZ = -Math.cos(rad);
        RandomSource random = player.getRandom();
        float volume = Config.getInstance().movementRadar.volume / 100.0f;

        if (scan.state() == Obstacle.OPEN) {
            // A block ahead, so it reads as coming from that side rather than from inside the player's head.
            client.getSoundManager().play(new SimpleSoundInstance(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.MASTER,
                    0.6f * volume, 1.0f, random, player.getX() + dirX, player.getY(), player.getZ() + dirZ));
            return;
        }

        SoundEvent sound = scan.hitBlock().getSoundType().getPlaceSound();
        float pitch = scan.state() == Obstacle.JUMPABLE ? JUMPABLE_PITCH : BLOCKED_PITCH;
        double distance = Math.min(scan.distance(), range);
        client.getSoundManager().play(new SimpleSoundInstance(sound, SoundSource.MASTER, volume, pitch, random,
                player.getX() + dirX * distance, player.getY(), player.getZ() + dirZ * distance));
    }

    /** What the three directions last sounded like, and how recently each was allowed to speak. */
    private static final class State {
        private final Map<Integer, Obstacle> seen = new HashMap<>();
        private final Map<Integer, Integer> lastPlayed = new HashMap<>();
        private final List<PendingCue> pending = new ArrayList<>();
        private int ticks;
        private int lastPlayAt = -PLAY_INTERVAL_TICKS;
    }

    /** How passable a direction is. */
    private enum Obstacle {
        OPEN,
        JUMPABLE,
        BLOCKED
    }

    /**
     * One direction's reading.
     *
     * @param state how passable it is
     * @param bearing the direction, in compass degrees
     * @param distance how far away the hit is, or the full range when nothing was hit
     * @param hitBlock what was hit, or null for open
     */
    private record Scan(Obstacle state, double bearing, double distance, BlockState hitBlock) {
    }

    /**
     * A cue waiting its turn.
     *
     * @param center the bearing it belongs to
     * @param scan what to play
     */
    private record PendingCue(int center, Scan scan) {
    }
}
