package org.mcaccess.minecraftaccess.features;

import java.util.Locale;

import com.mojang.blaze3d.platform.InputConstants;
import net.blay09.mods.balm.client.platform.event.callback.ClientLifecycleCallback;
import net.blay09.mods.balm.client.platform.module.BalmClientModule;
import net.blay09.mods.kuma.api.InputBinding;
import net.blay09.mods.kuma.api.KeyModifier;
import net.blay09.mods.kuma.api.KeyModifiers;
import net.blay09.mods.kuma.api.Kuma;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import org.mcaccess.minecraftaccess.MainClass;
import org.mcaccess.minecraftaccess.utils.KeyMappingCategories;
import org.mcaccess.minecraftaccess.utils.events.ClientPlayingTick;

/**
 * Tilts the crosshair onto the next block of a repetitive digging or building job, so only the yaw and the
 * attack or use key have to be looked after.
 *
 * <p>Only the pitch is ever moved; the yaw stays the player's own, so it has to be squared to a compass direction
 * for the aim to line up. Every tick the mode works out which block comes next and tilts onto it. The target is
 * always the first block of the pattern still standing, so a fast pickaxe that breaks a block the moment it is
 * hit simply moves the crosshair on to the next one in the pattern rather than to something beyond it.
 *
 * <ul>
 * <li><b>Tunnel</b>: a two high tunnel straight ahead, taking the head height block and then the foot height
 * block at each distance, nearest first.
 * <li><b>Staircase</b>: one down per one forward. Each step clears three blocks in its column - one above and one
 * at foot height of the position being stepped from, plus the one stepped down onto - because the player has to
 * walk horizontally into that column first.
 * <li><b>Bridge</b>: aims at the side of the block being bridged out from. That face points toward the void, so it
 * can only be seen from beyond the edge looking back at it: face the block and back up until over the edge.
 * </ul>
 */
public class AngleAssist implements BalmClientModule {
    private static final int TUNNEL_DEPTH = 6;
    private static final int STAIR_STEPS = 4;
    // A target closer than this along the line of sight cannot be aimed at sensibly.
    private static final double MIN_AIM_DISTANCE = 0.05;
    private static final double BRIDGE_FACE_HEIGHT = 0.8;
    private static final int STAIR_ANCHOR_DRIFT = 2;
    // A target this far behind the eye along the line of sight is still straight below the player.
    private static final double STRAIGHT_DOWN_REACH = 0.45;
    private static final float PITCH_LIMIT = 90.0f;

    private Mode mode = Mode.OFF;
    private BridgeState lastBridgeState = BridgeState.NONE;
    // Where the current staircase step began and which way it faces.
    private BlockPos stairAnchor;
    private Direction stairFacing;
    // The block being worked on, kept until it is gone, so that walking forward does not move the crosshair off a
    // block that is part way through breaking.
    private BlockPos latchedTarget;
    private Direction latchedFacing;

    @Override
    public @NotNull Identifier getId() {
        return Identifier.fromNamespaceAndPath(MainClass.MOD_ID, "angle_assist");
    }

    @Override
    public void initialize() {
        ClientPlayingTick.AFTER.register(this::tick);
        ClientLifecycleCallback.ConnectedToServer.EVENT.register(_ -> reset());

        Kuma.createKeyMapping(Identifier.fromNamespaceAndPath(MainClass.MOD_ID, "angle_assist/cycle"))
                .withDefault(InputBinding.key(InputConstants.KEY_G, KeyModifiers.of(KeyModifier.ALT)))
                .overrideCategory(KeyMappingCategories.OTHER)
                .handleWorldInput(_ -> {
                    cycle();
                    return true;
                })
                .build();
    }

    private void reset() {
        mode = Mode.OFF;
        lastBridgeState = BridgeState.NONE;
        stairAnchor = null;
        latchedTarget = null;
    }

    private void cycle() {
        Mode[] modes = Mode.values();
        mode = modes[(mode.ordinal() + 1) % modes.length];
        lastBridgeState = BridgeState.NONE;
        stairAnchor = null;
        latchedTarget = null;
        MainClass.narrate(I18n.get("minecraft_access.angle_assist.mode_" + mode.name().toLowerCase(Locale.ROOT)), true);
    }

    private void tick(Minecraft client, LocalPlayer player, ClientLevel level) {
        if (mode == Mode.OFF) return;
        if (client.gui.screen() != null) return;
        // Modes that take the whole aim over keep it.
        if (MainClass.poiManager.lockingHandler.isPlayerLocked()) return;

        switch (mode) {
            case TUNNEL -> aimAtPattern(client, player, BlockPos.containing(player.position()), TUNNEL_DEPTH, true);
            case STAIRS -> aimAtPattern(client, player, stairBase(player), STAIR_STEPS, false);
            case BRIDGE -> aimAtBridge(client, player);
            default -> { }
        }
    }

    /**
     * Height offsets from the player's feet cell to clear at a given distance ahead, in the order they should be
     * broken.
     */
    private static int[] offsets(Mode mode, int distance) {
        return mode == Mode.TUNNEL ? new int[] {1, 0} : new int[] {2 - distance, 1 - distance, -distance};
    }

    /**
     * The cell a staircase's step pattern is measured from. Measuring from the player's current cell every tick
     * breaks down mid step: walking forward after the two upper blocks are dug but before the lower one is puts
     * that undug block directly underfoot where the pattern never looks. So the pattern stays anchored where the
     * step began until the player has dropped below it, wandered off, or turned around.
     */
    private BlockPos stairBase(LocalPlayer player) {
        BlockPos feet = BlockPos.containing(player.position());
        Direction facing = Direction.fromYRot(player.getYRot());
        boolean drifted = stairAnchor == null
                || facing != stairFacing
                || feet.getY() != stairAnchor.getY()
                || Math.abs(feet.getX() - stairAnchor.getX()) > STAIR_ANCHOR_DRIFT
                || Math.abs(feet.getZ() - stairAnchor.getZ()) > STAIR_ANCHOR_DRIFT;

        if (drifted) {
            stairAnchor = feet;
            stairFacing = facing;
        }
        return stairAnchor;
    }

    private void aimAtPattern(Minecraft client, LocalPlayer player, BlockPos feet, int depth, boolean tunnel) {
        Level level = player.level();
        Direction facing = Direction.fromYRot(player.getYRot());
        Mode pattern = tunnel ? Mode.TUNNEL : Mode.STAIRS;

        BlockPos target = null;
        if (latchedTarget != null
                && latchedFacing == facing
                && needsBreaking(level, latchedTarget)
                && player.getEyePosition().distanceTo(Vec3.atCenterOf(latchedTarget)) <= player.blockInteractionRange()) {
            target = latchedTarget;
        }
        if (target == null) {
            target = nextInPattern(level, feet, facing, depth, pattern);
        }
        latchedTarget = target;
        latchedFacing = facing;
        if (target == null) return;

        if (client.hitResult instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(target)) {
            return;
        }
        if (!aimPitchAt(player, Vec3.atCenterOf(target), true)) {
            latchedTarget = null;
        }
    }

    private static BlockPos nextInPattern(Level level, BlockPos feet, Direction facing, int depth, Mode pattern) {
        for (int distance = 1; distance <= depth; distance++) {
            for (int offset : offsets(pattern, distance)) {
                BlockPos cell = feet.relative(facing, distance).above(offset);
                if (needsBreaking(level, cell)) {
                    return cell;
                }
            }
        }
        return null;
    }

    private static boolean needsBreaking(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        // Fluids cannot be broken, and an unbreakable block would pin the aim there for good.
        return !state.isAir() && state.getFluidState().isEmpty() && state.getDestroySpeed(level, pos) >= 0.0f;
    }

    private void aimAtBridge(Minecraft client, LocalPlayer player) {
        Level level = player.level();
        Direction facing = Direction.fromYRot(player.getYRot());
        BlockPos below = BlockPos.containing(player.position()).below();

        BlockPos support = null;
        for (int i = 0; i <= 2; i++) {
            BlockPos candidate = below.relative(facing, i);
            BlockState state = level.getBlockState(candidate);
            if (!state.isAir() && !state.canBeReplaced()) {
                support = candidate;
                break;
            }
        }
        if (support == null) {
            announceBridge(BridgeState.NO_SUPPORT);
            return;
        }

        // The side of the support facing the player, that is the side the next block goes against.
        Vec3 face = Vec3.atCenterOf(support)
                .add(-facing.getStepX() * 0.5, 0.0, -facing.getStepZ() * 0.5)
                .with(Direction.Axis.Y, support.getY() + BRIDGE_FACE_HEIGHT);
        if (!aimPitchAt(player, face, false)) {
            announceBridge(BridgeState.TOO_FAR);
            return;
        }
        announceBridge(BridgeState.READY);
    }

    private void announceBridge(BridgeState state) {
        if (state == lastBridgeState) return;
        lastBridgeState = state;
        MainClass.narrate(I18n.get(switch (state) {
            case NO_SUPPORT -> "minecraft_access.angle_assist.bridge_no_support";
            case TOO_FAR -> "minecraft_access.angle_assist.bridge_back_up";
            default -> "minecraft_access.angle_assist.bridge_ready";
        }), true);
    }

    /**
     * Sets only the pitch that makes the crosshair pass through the target, measured along the player's current
     * yaw.
     *
     * @return false when the target is not ahead of the player along that line and nothing was changed
     */
    private static boolean aimPitchAt(LocalPlayer player, Vec3 target, boolean allowStraightDown) {
        Vec3 eye = player.getEyePosition();
        double yawRadians = Math.toRadians(player.getYRot());
        double forwardX = -Math.sin(yawRadians);
        double forwardZ = Math.cos(yawRadians);
        double ahead = (target.x() - eye.x()) * forwardX + (target.z() - eye.z()) * forwardZ;

        if (ahead < MIN_AIM_DISTANCE) {
            if (allowStraightDown && ahead > -STRAIGHT_DOWN_REACH && target.y() < eye.y()) {
                player.setXRot(PITCH_LIMIT);
                return true;
            }
            return false;
        }

        float pitch = (float) Math.toDegrees(Math.atan2(-(target.y() - eye.y()), ahead));
        player.setXRot(Math.clamp(pitch, -PITCH_LIMIT, PITCH_LIMIT));
        return true;
    }

    private enum Mode {
        OFF, BRIDGE, TUNNEL, STAIRS
    }

    private enum BridgeState {
        NONE, NO_SUPPORT, TOO_FAR, READY
    }
}
