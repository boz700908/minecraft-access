package org.mcaccess.minecraftaccess.addon.accessmenu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.mcaccess.minecraftaccess.Config;
import org.mcaccess.minecraftaccess.MainClass;
import org.mcaccess.minecraftaccess.api.AccessMenuFunction;
import org.mcaccess.minecraftaccess.utils.NarrationUtils;
import org.mcaccess.minecraftaccess.utils.position.FlatAreaFinder;

/**
 * Answers "where can I put this down": walks the columns around the player once, marks every height with solid ground
 * and two clear blocks above it, and hands those grids to {@link FlatAreaFinder} for the nearest patch big enough to
 * build on.
 *
 * <p>Only loaded chunks are read, and only within the configured range and height band, so the search reaches exactly
 * as far as the world does and never guesses about ground nobody has seen.
 */
public class FindFlatArea implements AccessMenuFunction {
    @Override
    public void execute() {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) return;

        Config.AccessMenu.FlatAreaFinder config = Config.getInstance().accessMenu.flatAreaFinder;
        BlockPos origin = player.blockPosition();
        int span = config.range * 2 + 1;
        int minX = origin.getX() - config.range;
        int minZ = origin.getZ() - config.range;
        int minY = Math.max(level.getMinY(), origin.getY() - config.verticalRange);
        int maxY = origin.getY() + config.verticalRange;

        Map<Integer, boolean[]> floors = new HashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = minX; x < minX + span; x++) {
            for (int z = minZ; z < minZ + span; z++) {
                if (!level.isLoaded(pos.set(x, minY, z))) continue;
                // standY is the block a body would occupy, set once ground has been seen directly under it;
                // clearRun then counts the clear blocks since, two being a body's worth.
                int standY = Integer.MIN_VALUE;
                int clearRun = 0;
                for (int y = minY; y <= maxY; y++) {
                    BlockState state = level.getBlockState(pos.set(x, y, z));
                    boolean fluid = !state.getFluidState().isEmpty();
                    if (!fluid && (state.isAir() || state.getCollisionShape(level, pos).isEmpty())) {
                        if (standY != Integer.MIN_VALUE && ++clearRun == 2) {
                            int cell = (z - minZ) * span + (x - minX);
                            floors.computeIfAbsent(standY, key -> new boolean[span * span])[cell] = true;
                            standY = Integer.MIN_VALUE;
                        }
                    } else if (fluid || state.is(BlockTags.LEAVES) || state.is(Blocks.MAGMA_BLOCK)) {
                        // Water is no floor to stand on, and leaves and magma carry a collision shape without being
                        // one either.
                        standY = Integer.MIN_VALUE;
                        clearRun = 0;
                    } else {
                        standY = y + 1;
                        clearRun = 0;
                    }
                }
            }
        }

        List<FlatAreaFinder.Area> areas =
                new ArrayList<>(FlatAreaFinder.find(floors, span, span, config.width, config.depth, config.maxResults));
        Vec3 eye = player.getEyePosition();
        areas.sort(Comparator.comparingDouble(area -> eye.distanceToSqr(centerOf(area, minX, minZ))));
        if (areas.isEmpty()) {
            MainClass.narrate(I18n.get("minecraft_access.flat_area.not_found", config.width, config.depth), true);
            return;
        }

        FlatAreaFinder.Area area = areas.getFirst();
        Vec3 center = centerOf(area, minX, minZ);
        int blocks = (int) Math.round(Math.sqrt(eye.distanceTo(center)));
        MainClass.narrate(I18n.get("minecraft_access.flat_area.found",
                Math.max(area.width(), area.depth()), Math.min(area.width(), area.depth()),
                surfaceOf(level, area, minX, minZ, pos),
                NarrationUtils.narrateRelativeDirection(center), blocks), true);
    }

    /** The middle block of the area's floor - for an even side there is no single one, so the lower of the two. */
    private static Vec3 centerOf(FlatAreaFinder.Area area, int minX, int minZ) {
        return Vec3.atCenterOf(new BlockPos(minX + area.x() + (area.width() - 1) / 2, area.y() - 1,
                minZ + area.z() + (area.depth() - 1) / 2));
    }

    /** What most of the area's floor is made of, ties broken by name so a repeat scan words it the same way. */
    private static String surfaceOf(ClientLevel level, FlatAreaFinder.Area area, int minX, int minZ, BlockPos.MutableBlockPos pos) {
        int originX = minX + area.x();
        int originZ = minZ + area.z();
        int groundY = area.y() - 1;
        Map<Block, Integer> counts = new HashMap<>();
        for (int x = originX; x < originX + area.width(); x++) {
            for (int z = originZ; z < originZ + area.depth(); z++) {
                counts.merge(level.getBlockState(pos.set(x, groundY, z)).getBlock(), 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .sorted(Comparator.<Map.Entry<Block, Integer>>comparingInt(entry -> -entry.getValue())
                        .thenComparing(entry -> entry.getKey().getName().getString()))
                .map(entry -> entry.getKey().getName().getString())
                .findFirst()
                .orElse(I18n.get("minecraft_access.other.not_found"));
    }
}
