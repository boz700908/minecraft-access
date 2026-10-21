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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.NotNull;

import org.mcaccess.minecraftaccess.Config;
import org.mcaccess.minecraftaccess.MainClass;
import org.mcaccess.minecraftaccess.utils.events.ClientPlayingTick;

/**
 * Plays a short eating sound when feeding an animal would actually work - breeding an adult, or hurrying a baby
 * along. Vanilla leaves most species silent here, because {@code Animal#playEatingSound} is an empty default; the
 * heart and growth particles are sighted players' only clue, so without this a successful feed is indistinguishable
 * from a wasted one.
 *
 * <p>It predicts the same gate the server uses, and only as far as the client can see it: a baby's growth lock is
 * synced, so that half is exact, while an adult's breeding cooldown is a server-only timer and always reads as
 * available here. A cue on a cooldown feed is a small false positive; silence on every real one would not be.
 *
 * <p>Fired on the edge of a right-click over an animal rather than every tick, so holding the button down does not
 * repeat it.
 */
public class AnimalFeedingCue implements BalmClientModule {
    private final SessionLocal<State> sessionState = new SessionLocal<>(State::new);

    @Override
    public @NotNull Identifier getId() {
        return Identifier.fromNamespaceAndPath(MainClass.MOD_ID, "animal_feeding_cue");
    }

    @Override
    public void initialize() {
        ClientPlayingTick.AFTER.register(this::tick);
    }

    private void tick(Minecraft client, LocalPlayer player, ClientLevel level) {
        State state = this.sessionState.value;
        boolean useDown = client.options.keyUse.isDown();
        boolean justPressed = useDown && !state.useWasDown;
        state.useWasDown = useDown;

        if (!justPressed || !Config.getInstance().animalFeedingCue.enabled
                || client.gui.screen() != null || player.isSpectator()) {
            return;
        }

        HitResult hit = client.hitResult;
        if (!(hit instanceof EntityHitResult entityHit) || hit.getType() != HitResult.Type.ENTITY) {
            return;
        }
        Entity target = entityHit.getEntity();
        if (target instanceof Animal animal && willFeedSucceed(animal, player.getMainHandItem())) {
            client.getSoundManager().play(new SimpleSoundInstance(SoundEvents.GENERIC_EAT.value(), SoundSource.NEUTRAL,
                    1.0f, 1.0f, animal.getRandom(), animal.getX(), animal.getY(), animal.getZ()));
        }
    }

    private static boolean willFeedSucceed(Animal animal, ItemStack stack) {
        if (!animal.isFood(stack)) return false;
        return !animal.isBaby() || animal.canAgeUp();
    }

    /** Whether the use key was already held last tick, so a held button counts as a single feed. */
    private static final class State {
        private boolean useWasDown;
    }
}
