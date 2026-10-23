package org.mcaccess.minecraftaccess.features;

import java.util.Arrays;

import net.blay09.mods.balm.client.platform.module.BalmClientModule;
import net.blay09.mods.balm.client.platform.util.SessionLocal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import org.mcaccess.minecraftaccess.Config;
import org.mcaccess.minecraftaccess.MainClass;
import org.mcaccess.minecraftaccess.utils.events.ClientPlayingTick;

/**
 * Narrates worn armour and held items as they wear out, and again when one of them breaks.
 *
 * <p>Each slot speaks once when its stack falls below the warning threshold, once when it falls below the critical
 * one, and once when the stack is gone. A stack that is repaired, by Mending or on an anvil, is armed again on the
 * next tick that sees it above the threshold, so no hook on repairing is needed.
 */
public class DurabilityAwareness implements BalmClientModule {
    private static final int PERCENT = 100;
    private static final int SLOT_COUNT = 6;
    private static final int BOOTS = 0;
    private static final int LEGGINGS = 1;
    private static final int CHESTPLATE = 2;
    private static final int HELMET = 3;
    private static final int MAIN_HAND = 4;
    private static final int OFF_HAND = 5;
    private static final String[] SLOT_KEYS = {
            "minecraft_access.durability_slot.boots",
            "minecraft_access.durability_slot.leggings",
            "minecraft_access.durability_slot.chestplate",
            "minecraft_access.durability_slot.helmet",
            "minecraft_access.durability_slot.held_item",
            "minecraft_access.durability_slot.off_hand",
    };

    /** Per session, so joining another world starts with every slot armed. */
    private final SessionLocal<Tracker> tracker = new SessionLocal<>(Tracker::new);

    @Override
    public @NotNull Identifier getId() {
        return Identifier.fromNamespaceAndPath(MainClass.MOD_ID, "durability_awareness");
    }

    @Override
    public void initialize() {
        ClientPlayingTick.AFTER.register(this::tick);
    }

    private void tick(Minecraft client, LocalPlayer player, ClientLevel level) {
        Config.DurabilityAwareness config = Config.getInstance().durabilityAwareness;
        if (!config.enabled) return;

        Tracker state = tracker.value;
        check(state, BOOTS, player.getItemBySlot(EquipmentSlot.FEET), config);
        check(state, LEGGINGS, player.getItemBySlot(EquipmentSlot.LEGS), config);
        check(state, CHESTPLATE, player.getItemBySlot(EquipmentSlot.CHEST), config);
        check(state, HELMET, player.getItemBySlot(EquipmentSlot.HEAD), config);
        check(state, MAIN_HAND, player.getMainHandItem(), config);
        check(state, OFF_HAND, player.getOffhandItem(), config);
    }

    private void check(Tracker state, int slot, ItemStack current, Config.DurabilityAwareness config) {
        Snapshot previous = state.snapshots[slot];

        // A stack can go from its last point of damage to gone between two ticks, so the last state that is still
        // observable is announced once the slot is empty, and never when the item was merely switched away.
        boolean wasNearlyBroken = !previous.isEmpty()
                && previous.maxDamage() > 0
                && previous.damageValue() >= previous.maxDamage() - 1;
        if (wasNearlyBroken && current.isEmpty()) {
            MainClass.narrate(I18n.get("minecraft_access.durability.broken", previous.name()), true);
        }

        if (current.isEmpty() || !current.isDamageableItem() || current.getMaxDamage() <= 0) {
            state.rearm(slot);
            state.snapshots[slot] = current.isEmpty() ? Snapshot.EMPTY : Snapshot.of(current);
            return;
        }

        // Another item in the slot is a new window of its own.
        if (!previous.isEmpty() && previous.item() != current.getItem()) {
            state.rearm(slot);
        }

        int max = current.getMaxDamage();
        int remaining = max - current.getDamageValue();
        int percentRemaining = remaining * PERCENT / max;

        if (percentRemaining > config.warningThreshold) {
            state.rearm(slot);
        } else if (percentRemaining > config.criticalThreshold) {
            state.warnedCritical[slot] = false;
        }

        String slotName = I18n.get(SLOT_KEYS[slot]);
        String itemName = current.getHoverName().getString();
        if (!state.warnedCritical[slot] && percentRemaining <= config.criticalThreshold) {
            state.warnedCritical[slot] = true;
            state.warnedWarning[slot] = true;
            MainClass.narrate(I18n.get("minecraft_access.durability.critical", slotName, itemName, remaining, max), true);
        } else if (!state.warnedWarning[slot] && percentRemaining <= config.warningThreshold) {
            state.warnedWarning[slot] = true;
            MainClass.narrate(I18n.get("minecraft_access.durability.warning", slotName, itemName, remaining, max), true);
        }

        state.snapshots[slot] = Snapshot.of(current);
    }

    /** What one slot looked like on the previous tick. */
    private record Snapshot(Item item, int damageValue, int maxDamage, String name) {
        private static final Snapshot EMPTY = new Snapshot(null, 0, 0, "");

        private boolean isEmpty() {
            return item == null;
        }

        private static Snapshot of(ItemStack stack) {
            boolean damageable = stack.isDamageableItem() && stack.getMaxDamage() > 0;
            return new Snapshot(stack.getItem(),
                    damageable ? stack.getDamageValue() : 0,
                    damageable ? stack.getMaxDamage() : 0,
                    stack.getHoverName().getString());
        }
    }

    /** The warnings already spoken for each slot in this session. */
    private static final class Tracker {
        private final boolean[] warnedWarning = new boolean[SLOT_COUNT];
        private final boolean[] warnedCritical = new boolean[SLOT_COUNT];
        private final Snapshot[] snapshots = new Snapshot[SLOT_COUNT];

        private Tracker() {
            Arrays.fill(snapshots, Snapshot.EMPTY);
        }

        private void rearm(int slot) {
            warnedWarning[slot] = false;
            warnedCritical[slot] = false;
        }
    }
}
