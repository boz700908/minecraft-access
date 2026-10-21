package org.mcaccess.minecraftaccess.features;

import net.blay09.mods.balm.client.platform.module.BalmClientModule;
import net.blay09.mods.balm.client.platform.util.SessionLocal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import org.mcaccess.minecraftaccess.Config;
import org.mcaccess.minecraftaccess.MainClass;
import org.mcaccess.minecraftaccess.utils.events.ClientPlayingTick;

/**
 * Keeps the crosshair on a tree while it is being felled. Once the log under the crosshair breaks, the aim moves to a
 * log touching where it stood - straight up first, the way a trunk is normally chopped - so felling a tree does not
 * mean finding the next block by hand after every swing.
 *
 * <p>Silent while it is following a trunk, which would otherwise be a line of chatter through a fast chop; it speaks
 * only when the chain runs out, which is the moment to stop swinging.
 *
 * <p>It leaves the aim alone while a lock is held, since that already owns the view for its own purpose.
 */
public class TreeChoppingAssist implements BalmClientModule {
    private final SessionLocal<State> sessionState = new SessionLocal<>(State::new);

    @Override
    public @NotNull Identifier getId() {
        return Identifier.fromNamespaceAndPath(MainClass.MOD_ID, "tree_chopping_assist");
    }

    @Override
    public void initialize() {
        ClientPlayingTick.AFTER.register(this::tick);
    }

    private void tick(Minecraft client, LocalPlayer player, ClientLevel level) {
        Config.TreeChoppingAssist config = Config.getInstance().treeChoppingAssist;
        State state = this.sessionState.value;
        if (!config.enabled || client.gui.screen() != null) {
            state.trackedLog = null;
            return;
        }
        if (MainClass.poiManager.lockingHandler.isPlayerLocked()) {
            state.trackedLog = null;
            return;
        }

        if (state.trackedLog != null) {
            followTrunk(player, level, state);
            return;
        }

        // Not following anything yet: take up whatever log the crosshair is on.
        HitResult hit = client.hitResult;
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK
                && level.getBlockState(blockHit.getBlockPos()).is(BlockTags.LOGS)) {
            state.trackedLog = blockHit.getBlockPos().immutable();
        }
    }

    private void followTrunk(LocalPlayer player, ClientLevel level, State state) {
        BlockPos tracked = state.trackedLog;
        double reach = player.blockInteractionRange() + 1.0;
        if (player.getEyePosition().distanceTo(Vec3.atCenterOf(tracked)) > reach) {
            state.trackedLog = null;
            return;
        }
        if (level.getBlockState(tracked).is(BlockTags.LOGS)) return;

        BlockPos next = findAdjacentLog(level, tracked);
        if (next == null) {
            state.trackedLog = null;
            MainClass.narrate(I18n.get("minecraft_access.tree_chopping.done"), true);
            return;
        }
        player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(next));
        state.trackedLog = next;
    }

    private static BlockPos findAdjacentLog(Level level, BlockPos brokenPos) {
        BlockPos above = brokenPos.above();
        if (level.getBlockState(above).is(BlockTags.LOGS)) {
            return above;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos neighbour = brokenPos.offset(dx, dy, dz);
                    if (level.getBlockState(neighbour).is(BlockTags.LOGS)) {
                        return neighbour;
                    }
                }
            }
        }
        return null;
    }

    /** The log being followed, cleared whenever the chain ends or something else takes over the aim. */
    private static final class State {
        private BlockPos trackedLog;
    }
}
