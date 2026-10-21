package org.mcaccess.minecraftaccess.features;

import net.blay09.mods.balm.client.platform.module.BalmClientModule;
import net.blay09.mods.balm.client.platform.util.SessionLocal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import org.mcaccess.minecraftaccess.Config;
import org.mcaccess.minecraftaccess.MainClass;
import org.mcaccess.minecraftaccess.utils.events.ClientPlayingTick;

/**
 * Chimes the moment a mount's jump is charged. Holding jump on a horse, donkey, mule, llama or camel winds up a leap
 * and releasing it lets go - but how far the charge has got is shown only as a bar on screen, so a player who cannot
 * see it has no way to tell a full leap from a hop over a fence.
 *
 * <p>The charge reads as nothing at all unless a jump is actually being wound up, so it is safe to watch every tick
 * without looking at what is being ridden first. The chime sounds once per charge, on the tick it fills, rather than
 * for as long as it stays full.
 */
public class MountJumpCue implements BalmClientModule {
    private static final float CUE_VOLUME = 0.6f;
    private static final float CUE_PITCH = 1.6f;
    private final SessionLocal<State> sessionState = new SessionLocal<>(State::new);

    @Override
    public @NotNull Identifier getId() {
        return Identifier.fromNamespaceAndPath(MainClass.MOD_ID, "mount_jump_cue");
    }

    @Override
    public void initialize() {
        ClientPlayingTick.AFTER.register(this::tick);
    }

    private void tick(Minecraft client, LocalPlayer player, ClientLevel level) {
        Config.MountJumpCue config = Config.getInstance().mountJumpCue;
        State state = this.sessionState.value;
        if (!config.enabled || client.gui.screen() != null) {
            // Forget any charge rather than resuming mid-way with a stale "already full" state.
            state.wasReady = false;
            return;
        }

        boolean ready = player.getJumpRidingScale() >= 1.0f;
        if (ready && !state.wasReady) {
            playCue(client, player);
        }
        state.wasReady = ready;
    }

    private void playCue(Minecraft client, LocalPlayer player) {
        RandomSource random = player.getRandom();
        Vec3 position = player.position();
        client.getSoundManager().play(new SimpleSoundInstance(SoundEvents.NOTE_BLOCK_HARP.value(), SoundSource.MASTER,
                CUE_VOLUME, CUE_PITCH, random, position.x(), position.y(), position.z()));
    }

    /** Whether the charge was already full last tick, so the chime sounds on the fill and not every tick after it. */
    private static final class State {
        private boolean wasReady;
    }
}
