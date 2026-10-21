package org.mcaccess.minecraftaccess.addon.accessmenu;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.mcaccess.minecraftaccess.Config;
import org.mcaccess.minecraftaccess.MainClass;
import org.mcaccess.minecraftaccess.api.AccessMenuFunction;
import org.mcaccess.minecraftaccess.utils.NarrationUtils;
import org.mcaccess.minecraftaccess.utils.position.WaterExitFinder;

/**
 * Answers "how do I get out of here" while swimming: reports the distance and direction of the nearest shore a body
 * could actually climb out onto, along a swim the player can follow rather than one that merely looks close.
 *
 * <p>The route itself comes from {@link WaterExitFinder}, which floods outward from the player; this only turns its
 * answer into words. Nothing here moves the player, so asking costs a narration and never wrestles the controls away.
 */
public class FindWaterExit implements AccessMenuFunction {
    /** How far the exit may sit above or below the player before the direction alone would be misleading. */
    private static final double VERTICAL_DIRECTION_THRESHOLD = 3.0;

    @Override
    public void execute() {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) return;

        if (!player.isInWater()) {
            MainClass.narrate(I18n.get("minecraft_access.water_exit.not_in_water"), true);
            return;
        }

        int radius = Config.getInstance().accessMenu.waterExit.radius;
        BlockPos start = player.blockPosition();
        List<WaterExitFinder.Step> route = WaterExitFinder.findRoute(new WorldCells(level, player),
                new WaterExitFinder.Step(start.getX(), start.getY(), start.getZ()), radius);
        if (route.isEmpty()) {
            MainClass.narrate(I18n.get("minecraft_access.water_exit.not_found", radius), true);
            return;
        }

        WaterExitFinder.Step exit = route.getLast();
        Vec3 from = player.position();
        Vec3 to = Vec3.atCenterOf(new BlockPos(exit.x(), exit.y(), exit.z()));
        int distance = (int) Math.round(Math.sqrt(from.distanceToSqr(to)));
        String text = I18n.get("minecraft_access.water_exit.found", distance,
                NarrationUtils.narrateCompassDirection(from, to));

        // A compass bearing alone would have the player swim into a cliff face when the only way out is up a bank,
        // so the height is worth a word of its own - but only once it is far enough to actually matter.
        double difference = to.y() - from.y();
        if (difference > VERTICAL_DIRECTION_THRESHOLD) {
            text += I18n.get("minecraft_access.other.words_connection") + I18n.get("minecraft_access.direction.up");
        } else if (difference < -VERTICAL_DIRECTION_THRESHOLD) {
            text += I18n.get("minecraft_access.other.words_connection") + I18n.get("minecraft_access.direction.down");
        }
        MainClass.narrate(text, true);
    }

    /**
     * Reads the search's two questions straight out of the level. A chunk that has not loaded counts as neither
     * swimmable nor standable, so a route never runs off the edge of what has actually been seen.
     */
    private record WorldCells(ClientLevel level, LocalPlayer player) implements WaterExitFinder.Cells {
        @Override
        public boolean swimmable(int x, int y, int z) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.isLoaded(pos)) return false;
            BlockState state = level.getBlockState(pos);
            // Lava is deliberately not swimmable: this is a way out of water, not a way through everything.
            return state.isAir() || state.getFluidState().is(FluidTags.WATER);
        }

        @Override
        public boolean standable(int x, int y, int z) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.isLoaded(pos)) return false;
            if (!level.getBlockState(pos).isAir()) return false;

            BlockPos below = pos.below();
            if (!level.getBlockState(below).getFluidState().isEmpty()) return false;
            if (level.getBlockState(below).getCollisionShape(level, below).isEmpty()) return false;

            AABB box = player.getBoundingBox().move(x + 0.5 - player.getX(), y - player.getY(), z + 0.5 - player.getZ());
            return level.noCollision(player, box);
        }
    }
}
