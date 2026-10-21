package org.mcaccess.minecraftaccess.features;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.blay09.mods.balm.client.platform.module.BalmClientModule;
import net.blay09.mods.balm.client.platform.util.SessionLocal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import org.mcaccess.minecraftaccess.Config;
import org.mcaccess.minecraftaccess.MainClass;
import org.mcaccess.minecraftaccess.utils.NarrationUtils;
import org.mcaccess.minecraftaccess.utils.events.ClientPlayingTick;

/**
 * Makes a thrown Eye of Ender usable without seeing it. An eye flies for about four seconds toward the nearest
 * stronghold and then either drops as an item or shatters, which is invisible to a player who cannot watch it, so
 * the flight is followed instead: the heading once it is clear, then how it ended.
 *
 * <p>The client never learns where the stronghold is, only what the eye does, so everything here is inferred from
 * its movement. Its horizontal heading over the first few blocks points at the stronghold. An eye far from it is
 * sent twelve blocks toward it and eight up, so it always travels the full twelve; one that is nearer goes
 * straight there instead, so clearly less travel means the stronghold is about that close. The server then
 * removes the eye with no client-visible reason, and either leaves an item where it was or shatters it, which is
 * told apart by whether an Eye of Ender item turns up beside its last position in the next few ticks.
 */
public class EyeOfEnderAwareness implements BalmClientModule {
    // A freshly thrown eye appears right at the thrower, which is what makes it the player's own.
    private static final double THROW_DISCOVERY_RANGE = 4.0;
    private static final int NEW_EYE_MAX_AGE = 5;
    private static final double HEADING_DISTANCE = 3.0;
    private static final double MIN_BEARING_DISTANCE = 1.0;
    private static final double CLOSE_TRAVEL_THRESHOLD = 10.0;
    // How long to wait for an eye's dropped item before calling it shattered.
    private static final int OUTCOME_WAIT_TICKS = 3;
    private static final double ITEM_SEARCH_RADIUS = 4.0;

    private final SessionLocal<State> state = new SessionLocal<>(State::new);

    @Override
    public @NotNull Identifier getId() {
        return Identifier.fromNamespaceAndPath(MainClass.MOD_ID, "eye_of_ender_awareness");
    }

    @Override
    public void initialize() {
        ClientPlayingTick.AFTER.register(this::tick);
    }

    private void tick(Minecraft client, LocalPlayer player, ClientLevel level) {
        Config.EyeOfEnderAwareness config = Config.getInstance().eyeOfEnderAwareness;
        if (!config.enabled) return;

        State state = this.state.value;
        AABB near = player.getBoundingBox().inflate(THROW_DISCOVERY_RANGE);
        for (Entity entity : level.getEntities(player, near,
                candidate -> candidate instanceof EyeOfEnder && candidate.tickCount <= NEW_EYE_MAX_AGE)) {
            state.watched.computeIfAbsent(entity.getId(), id -> new Tracked(entity.position()));
        }

        follow(client, player, level, state, config);
        resolve(client, player, level, state, config);
    }

    private void follow(Minecraft client, LocalPlayer player, ClientLevel level, State state,
                        Config.EyeOfEnderAwareness config) {
        Iterator<Map.Entry<Integer, Tracked>> iterator = state.watched.entrySet().iterator();
        while (iterator.hasNext()) {
            Tracked tracked = iterator.next().getValue();
            Entity entity = level.getEntity(tracked.id);
            if (entity == null) {
                state.pending.put(state.nextPendingId++, new Pending(tracked.start, tracked.lastPos));
                iterator.remove();
                continue;
            }

            tracked.lastPos = entity.position();
            if (!tracked.headingAnnounced && horizontalDistance(tracked.start, tracked.lastPos) >= HEADING_DISTANCE) {
                tracked.headingAnnounced = true;
                MainClass.narrate(I18n.get("minecraft_access.eye_of_ender.heading",
                        NarrationUtils.narrateCompassDirection(tracked.start, tracked.lastPos)), true);
                // Played at the eye itself, so the ping arrives from the direction it is flying.
                play(client, player, SoundEvents.NOTE_BLOCK_FLUTE.value(), tracked.lastPos, 1.2f, config);
            }
        }
    }

    private void resolve(Minecraft client, LocalPlayer player, ClientLevel level, State state,
                         Config.EyeOfEnderAwareness config) {
        Iterator<Map.Entry<Integer, Pending>> iterator = state.pending.entrySet().iterator();
        while (iterator.hasNext()) {
            Pending ended = iterator.next().getValue();
            boolean dropped = droppedItemNear(level, ended.lastPos);
            if (!dropped && --ended.ticksLeft > 0) continue;

            double travelled = horizontalDistance(ended.start, ended.lastPos);
            String message = I18n.get(dropped ? "minecraft_access.eye_of_ender.dropped"
                    : "minecraft_access.eye_of_ender.shattered");
            if (travelled < CLOSE_TRAVEL_THRESHOLD && travelled >= MIN_BEARING_DISTANCE) {
                message += " " + I18n.get("minecraft_access.eye_of_ender.close", (int) Math.round(travelled),
                        NarrationUtils.narrateCompassDirection(ended.start, ended.lastPos));
                play(client, player, SoundEvents.NOTE_BLOCK_COW_BELL.value(), player.position(), 2.0f, config);
            }
            MainClass.narrate(message, true);
            play(client, player, dropped ? SoundEvents.NOTE_BLOCK_IRON_XYLOPHONE.value() : SoundEvents.NOTE_BLOCK_SNARE.value(),
                    ended.lastPos, dropped ? 1.4f : 1.0f, config);
            iterator.remove();
        }
    }

    private static boolean droppedItemNear(ClientLevel level, Vec3 pos) {
        AABB box = new AABB(pos, pos).inflate(ITEM_SEARCH_RADIUS);
        return !level.getEntitiesOfClass(ItemEntity.class, box, item -> item.getItem().is(Items.ENDER_EYE)).isEmpty();
    }

    private static double horizontalDistance(Vec3 from, Vec3 to) {
        return Math.hypot(to.x() - from.x(), to.z() - from.z());
    }

    private static void play(Minecraft client, LocalPlayer player, SoundEvent sound, Vec3 pos, float pitch,
                             Config.EyeOfEnderAwareness config) {
        client.getSoundManager().play(new SimpleSoundInstance(sound, SoundSource.MASTER, config.volume / 100.0f,
                pitch, player.getRandom(), pos.x(), pos.y(), pos.z()));
    }

    /** The eyes of this session: those still flying, and those whose ending is not yet known. */
    private static final class State {
        private final Map<Integer, Tracked> watched = new HashMap<>();
        private final Map<Integer, Pending> pending = new HashMap<>();
        private int nextPendingId;
    }

    /** One eye in flight, from where it was thrown. */
    private static final class Tracked {
        private final int id;
        private final Vec3 start;
        private Vec3 lastPos;
        private boolean headingAnnounced;

        private Tracked(int id, Vec3 start) {
            this.id = id;
            this.start = start;
            this.lastPos = start;
        }
    }

    /** An eye that has gone, waiting to see whether it left an item behind. */
    private static final class Pending {
        private final Vec3 start;
        private final Vec3 lastPos;
        private int ticksLeft = OUTCOME_WAIT_TICKS;

        private Pending(Vec3 start, Vec3 lastPos) {
            this.start = start;
            this.lastPos = lastPos;
        }
    }
}
