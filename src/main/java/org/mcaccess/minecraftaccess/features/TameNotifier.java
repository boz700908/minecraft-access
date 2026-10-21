package org.mcaccess.minecraftaccess.features;

import java.util.WeakHashMap;

import net.blay09.mods.balm.client.platform.module.BalmClientModule;
import net.blay09.mods.balm.client.platform.util.SessionLocal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import org.jetbrains.annotations.NotNull;

import org.mcaccess.minecraftaccess.Config;
import org.mcaccess.minecraftaccess.MainClass;
import org.mcaccess.minecraftaccess.utils.events.ClientPlayingTick;

/**
 * Says when an animal the player owns finishes taming. Vanilla's only feedback is heart particles and a silent
 * server-side flag, and the success roll itself never reaches the client, so it cannot be predicted - only noticed
 * once it happens.
 *
 * <p>Noticed by watching the tamed flag of every tameable animal nearby and speaking only on a false-to-true change,
 * never on first sight, so pets already owned do not announce themselves when they happen to come into range.
 *
 * <p>A tamed horse's owner is never synced to the client, so being its rider at the moment it flips is the only
 * client-visible sign that it was this player's doing; that is exactly where vanilla leaves the player when the
 * bucking phase finally succeeds.
 */
public class TameNotifier implements BalmClientModule {
    private static final double RANGE = 16.0;
    private final SessionLocal<State> sessionState = new SessionLocal<>(State::new);

    @Override
    public @NotNull Identifier getId() {
        return Identifier.fromNamespaceAndPath(MainClass.MOD_ID, "tame_notifier");
    }

    @Override
    public void initialize() {
        ClientPlayingTick.AFTER.register(this::tick);
    }

    private void tick(Minecraft client, LocalPlayer player, ClientLevel level) {
        Config.TameNotification config = Config.getInstance().tameNotification;
        State state = this.sessionState.value;
        if (!config.enabled || client.gui.screen() != null) {
            state.forget();
            return;
        }

        for (Entity entity : player.level().getEntities(player, player.getBoundingBox().inflate(RANGE),
                TameNotifier::isTameable)) {
            boolean tamedNow = isTamed(entity);
            Boolean tamedBefore = state.lastTamed.put(entity, tamedNow);
            if (tamedNow && Boolean.FALSE.equals(tamedBefore) && isOwnedByPlayer(entity, player)) {
                MainClass.narrate(I18n.get("minecraft_access.taming.tamed", entity.getName().getString()), false);
            }
        }
    }

    private static boolean isTameable(Entity entity) {
        return entity instanceof TamableAnimal || entity instanceof AbstractHorse;
    }

    private static boolean isTamed(Entity entity) {
        if (entity instanceof TamableAnimal tamable) {
            return tamable.isTame();
        }
        if (entity instanceof AbstractHorse horse) {
            return horse.isTamed();
        }
        return false;
    }

    /**
     * Whether the animal is the player's: the owner for anything that syncs one, and - for a horse, whose owner never
     * is - being its rider at the moment it turns tamed.
     */
    private static boolean isOwnedByPlayer(Entity entity, LocalPlayer player) {
        if (entity instanceof AbstractHorse) {
            return player.getVehicle() == entity;
        }
        return entity instanceof OwnableEntity ownable && player.equals(ownable.getOwner());
    }

    /**
     * The last known tamed flag of every animal seen, keyed by entity so unloading forgets them rather than leaking.
     */
    private static final class State {
        private final WeakHashMap<Entity, Boolean> lastTamed = new WeakHashMap<>();

        private void forget() {
            lastTamed.clear();
        }
    }
}
