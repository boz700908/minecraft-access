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
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import org.mcaccess.minecraftaccess.Config;
import org.mcaccess.minecraftaccess.MainClass;
import org.mcaccess.minecraftaccess.utils.events.ClientPlayingTick;

/**
 * Keeps the crosshair on a vein while it is being mined out. Once the ore under the crosshair breaks, the aim moves
 * to the nearest ore of the vein that is both close by and genuinely in sight - the same raycast test the mining
 * radar uses, so an ore sealed behind an unmined wall never attracts the aim just for being nearby.
 *
 * <p>It is the ore counterpart of {@link TreeChoppingAssist}: a one-shot re-aim rather than a lock, so the player is
 * free to look elsewhere afterwards. Silent while it follows a vein, which would otherwise be a line of chatter
 * through a fast dig; it speaks only when nothing more is in reach and in sight, which is the moment to stop mining.
 *
 * <p>It leaves the aim alone while a lock is held, since that already owns the view for its own purpose.
 */
public class OreMiningAssist implements BalmClientModule {
    /** How far from the ore that just broke the next one may be, in blocks, measured in a straight line. */
    private static final int SEARCH_RADIUS = 3;

    private final SessionLocal<State> sessionState = new SessionLocal<>(State::new);

    @Override
    public @NotNull Identifier getId() {
        return Identifier.fromNamespaceAndPath(MainClass.MOD_ID, "ore_mining_assist");
    }

    @Override
    public void initialize() {
        ClientPlayingTick.AFTER.register(this::tick);
    }

    private void tick(Minecraft client, LocalPlayer player, ClientLevel level) {
        Config.OreMiningAssist config = Config.getInstance().oreMiningAssist;
        State state = this.sessionState.value;
        if (!config.enabled || client.gui.screen() != null) {
            state.trackedOre = null;
            return;
        }
        if (MainClass.poiManager.lockingHandler.isPlayerLocked()) {
            state.trackedOre = null;
            return;
        }

        if (state.trackedOre != null) {
            followVein(player, level, state);
            return;
        }

        // Not following anything yet: take up whatever ore the crosshair is on.
        HitResult hit = client.hitResult;
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = blockHit.getBlockPos();
            if (MiningRadar.isValuableOre(level.getBlockState(pos))) {
                state.trackedOre = pos.immutable();
            }
        }
    }

    private void followVein(LocalPlayer player, Level level, State state) {
        BlockPos tracked = state.trackedOre;
        Vec3 eye = player.getEyePosition();
        double reach = player.blockInteractionRange() + 1.0;
        if (eye.distanceTo(Vec3.atCenterOf(tracked)) > reach) {
            state.trackedOre = null;
            return;
        }
        if (MiningRadar.isValuableOre(level.getBlockState(tracked))) return;

        BlockPos next = findNearbyVisibleOre(level, tracked, eye);
        if (next == null) {
            state.trackedOre = null;
            MainClass.narrate(I18n.get("minecraft_access.ore_mining.done"), true);
            return;
        }
        player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(next));
        state.trackedOre = next;
    }

    /**
     * The nearest ore still standing within {@link #SEARCH_RADIUS} blocks of {@code brokenPos} that {@code eye} has
     * genuine line of sight to, or {@code null} when the vein has run out.
     */
    private static BlockPos findNearbyVisibleOre(Level level, BlockPos brokenPos, Vec3 eye) {
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        double maxDistSq = (double) SEARCH_RADIUS * SEARCH_RADIUS;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dy = -SEARCH_RADIUS; dy <= SEARCH_RADIUS; dy++) {
                for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    double distSq = (double) (dx * dx + dy * dy + dz * dz);
                    if (distSq > maxDistSq || distSq >= bestDistSq) continue;
                    BlockPos candidate = brokenPos.offset(dx, dy, dz);
                    if (MiningRadar.isValuableOre(level.getBlockState(candidate))
                            && MiningRadar.isVisible(level, candidate, eye)) {
                        best = candidate;
                        bestDistSq = distSq;
                    }
                }
            }
        }
        return best;
    }

    /** The ore being followed, cleared whenever the vein runs out or something else takes over the aim. */
    private static final class State {
        private BlockPos trackedOre;
    }
}
