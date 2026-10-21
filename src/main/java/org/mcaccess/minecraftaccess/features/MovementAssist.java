package org.mcaccess.minecraftaccess.features;

import net.blay09.mods.balm.client.platform.module.BalmClientModule;
import net.blay09.mods.balm.client.platform.util.SessionLocal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;

import org.mcaccess.minecraftaccess.Config;
import org.mcaccess.minecraftaccess.MainClass;
import org.mcaccess.minecraftaccess.utils.events.ClientPlayingTick;

/**
 * Says why walking forward has stopped working. Nothing is said during ordinary movement: only after the
 * forward key has been held without the player actually moving for long enough that a stutter or a slab cannot
 * explain it, and then whatever is directly ahead is diagnosed - water or lava, a wall too tall to jump, a
 * step a jump would clear, or a drop-off - instead of leaving the player to guess why they stopped.
 *
 * <p>A wall or drop-off is also checked for a way around: if three blocks of walking to the left or the right
 * would continue, that side is named, since a single tree trunk almost always has clear ground beside it and
 * "go right" is more useful than "blocked".
 */
public class MovementAssist implements BalmClientModule {
    private static final String PREFIX = "minecraft_access.movement_assist.";
    private static final double STUCK_VELOCITY_THRESHOLD_SQR = 0.03 * 0.03;
    private static final int STUCK_TICKS_BEFORE_NARRATION = 15;
    private static final int MAX_DROP_SCAN = 5;
    private static final int SIDE_CHECK_DISTANCE = 3;

    private final SessionLocal<State> state = new SessionLocal<>(State::new);

    @Override
    public @NotNull Identifier getId() {
        return Identifier.fromNamespaceAndPath(MainClass.MOD_ID, "movement_assist");
    }

    @Override
    public void initialize() {
        ClientPlayingTick.AFTER.register(this::tick);
    }

    private void tick(Minecraft client, LocalPlayer player, ClientLevel level) {
        State state = this.state.value;
        if (!Config.getInstance().movementAssist.enabled) {
            state.stuckTicks = 0;
            return;
        }

        double dx = player.getDeltaMovement().x;
        double dz = player.getDeltaMovement().z;
        if (!player.input.hasForwardImpulse() || !player.onGround() || dx * dx + dz * dz > STUCK_VELOCITY_THRESHOLD_SQR) {
            state.stuckTicks = 0;
            return;
        }

        // Only climbs while the branch above keeps resetting on any movement, so it reaches the threshold
        // exactly once per stuck episode and needs no separate "already said this" flag.
        if (++state.stuckTicks == STUCK_TICKS_BEFORE_NARRATION) narrateObstacle(player);
    }

    private static void narrateObstacle(LocalPlayer player) {
        Level level = player.level();
        Direction facing = player.getDirection();
        BlockPos aheadPos = player.blockPosition().relative(facing);
        BlockState aheadState = level.getBlockState(aheadPos);

        if (!aheadState.getFluidState().isEmpty()) {
            narrate(aheadState.getFluidState().is(FluidTags.LAVA) ? PREFIX + "lava" : PREFIX + "water");
            return;
        }

        // Tested with the player's own box rather than the neighbouring block's state: a fence or a wall
        // reaches above its own block space to defeat a jump, and a state check alone would not see that.
        double aheadX = player.getX() + facing.getStepX();
        double aheadZ = player.getZ() + facing.getStepZ();
        boolean blocked = !canOccupy(level, player, aheadX, player.getY(), aheadZ);

        if (blocked) {
            if (canOccupy(level, player, aheadX, player.getY() + 1.0, aheadZ)) {
                narrate(PREFIX + "wall_jumpable");
                return;
            }
        } else if (!hasDropOff(level, aheadPos)) {
            // Not a wall and no drop to be found, so something else holds the player back: an odd shape or a
            // corner without enough room to walk through. Naming a side would be a guess here.
            narrate(PREFIX + "blocked_generic");
            return;
        }

        Direction side = clearSide(level, player, facing);
        if (side != null) {
            narrate(PREFIX + (side == facing.getClockWise() ? "route_right" : "route_left"));
            return;
        }
        narrate(PREFIX + (blocked ? "wall" : "dropoff"));
    }

    /**
     * Looks for a short stretch of walkable ground to either side of the obstacle.
     *
     * @param level where to look
     * @param player whose box has to fit
     * @param facing the way the player is trying to go
     * @return the side that can be walked, or null if neither can
     */
    private static Direction clearSide(Level level, LocalPlayer player, Direction facing) {
        for (Direction side : new Direction[]{facing.getClockWise(), facing.getCounterClockWise()}) {
            if (isClearAlongside(level, player, side)) return side;
        }
        return null;
    }

    private static boolean isClearAlongside(Level level, LocalPlayer player, Direction side) {
        BlockPos from = player.blockPosition();
        for (int step = 1; step <= SIDE_CHECK_DISTANCE; step++) {
            BlockPos pos = from.relative(side, step);
            if (!canOccupy(level, player, pos.getX() + 0.5, player.getY(), pos.getZ() + 0.5)) return false;
            if (!hasGround(level, pos)) return false;
        }
        return true;
    }

    private static boolean hasGround(Level level, BlockPos pos) {
        BlockPos below = pos.below();
        return !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
    }

    /** Whether the player's own box would fit at this position without colliding with anything. */
    private static boolean canOccupy(Level level, LocalPlayer player, double x, double y, double z) {
        AABB box = player.getBoundingBox().move(x - player.getX(), y - player.getY(), z - player.getZ());
        return level.noCollision(player, box);
    }

    private static boolean hasDropOff(Level level, BlockPos ahead) {
        BlockPos scan = ahead.below();
        for (int i = 0; i < MAX_DROP_SCAN; i++) {
            BlockState scanState = level.getBlockState(scan);
            if (!scanState.getCollisionShape(level, scan).isEmpty()) return false;
            scan = scan.below();
        }
        return true;
    }

    private static void narrate(String key) {
        MainClass.narrate(I18n.get(key), true);
    }

    /** How many ticks forward has been held without the player moving. */
    private static final class State {
        private int stuckTicks;
    }
}
