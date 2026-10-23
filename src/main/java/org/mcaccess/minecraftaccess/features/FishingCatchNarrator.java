package org.mcaccess.minecraftaccess.features;

import net.blay09.mods.balm.client.platform.module.BalmClientModule;
import net.blay09.mods.balm.client.platform.util.SessionLocal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;

import org.mcaccess.minecraftaccess.Config;
import org.mcaccess.minecraftaccess.MainClass;
import org.mcaccess.minecraftaccess.utils.events.ClientPlayingTick;

/**
 * Says what a fishing rod just reeled in. Vanilla never tells anyone, sighted or otherwise: the only feedback is a
 * splash particle, the retrieve sound and whatever briefly flies out of the water.
 *
 * <p>There is no "you caught X" message to read - loot is resolved server-side, so the client never learns why the
 * hook came back, only that it did. So the catch is inferred the same way {@link ArrowHitAwareness} infers a hit: the
 * local player's own hook is watched, and the tick it goes away a real catch has just thrown a brand new item at the
 * player, which is looked for once. Reeling in on nothing, or hooking a mob instead of a fish, throws no item, and
 * nothing is said.
 *
 * <p>The search is centred on the player rather than the hook, because the thrown item leaves the hook fast enough
 * to be outside a tight radius around it within a tick or two at ordinary fishing range.
 */
public class FishingCatchNarrator implements BalmClientModule {
    /** Wide enough for a full-power cast, narrow enough not to pick up unrelated drops nearby. */
    private static final double CATCH_RADIUS = 32.0;
    /** How new an item must be to be this catch rather than something already lying there. */
    private static final int MAX_CATCH_AGE_TICKS = 2;
    private final SessionLocal<State> sessionState = new SessionLocal<>(State::new);

    @Override
    public @NotNull Identifier getId() {
        return Identifier.fromNamespaceAndPath(MainClass.MOD_ID, "fishing_catch_narrator");
    }

    @Override
    public void initialize() {
        ClientPlayingTick.AFTER.register(this::tick);
    }

    private void tick(Minecraft client, LocalPlayer player, ClientLevel level) {
        Config.FishingCatch config = Config.getInstance().fishingCatch;
        State state = this.sessionState.value;
        if (!config.enabled || client.gui.screen() != null) {
            state.reset();
            return;
        }

        boolean fishing = player.fishing != null;
        if (fishing) {
            state.wasFishing = true;
            return;
        }
        if (state.wasFishing) {
            state.wasFishing = false;
            announceCatch(client, player);
        }
    }

    /**
     * Announces only the nearest candidate, since anything else lying nearby is not what this cast reeled in, and
     * says it without interrupting - a catch lands on its own time and should not cut off what is being read.
     */
    private void announceCatch(Minecraft client, LocalPlayer player) {
        AABB box = player.getBoundingBox().inflate(CATCH_RADIUS);
        ItemEntity nearest = null;
        double nearestDistanceSqr = Double.MAX_VALUE;
        for (Entity entity : player.level().getEntities((Entity) null, box,
                candidate -> candidate instanceof ItemEntity item && item.tickCount <= MAX_CATCH_AGE_TICKS)) {
            double distanceSqr = entity.distanceToSqr(player);
            if (distanceSqr < nearestDistanceSqr) {
                nearestDistanceSqr = distanceSqr;
                nearest = (ItemEntity) entity;
            }
        }
        if (nearest == null) return;

        MainClass.narrate(I18n.get("minecraft_access.fishing.caught",
                nearest.getItem().getHoverName().getString()), false);
    }

    /**
     * Whether a cast was in progress, kept per session so a hook from a previous world is not mistaken for one that
     * just came back.
     */
    private static final class State {
        private boolean wasFishing;

        private void reset() {
            wasFishing = false;
        }
    }
}
