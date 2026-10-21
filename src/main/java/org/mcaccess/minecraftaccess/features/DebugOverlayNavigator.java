package org.mcaccess.minecraftaccess.features;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.platform.InputConstants;
import net.blay09.mods.balm.client.platform.module.BalmClientModule;
import net.blay09.mods.balm.client.platform.util.SessionLocal;
import net.blay09.mods.kuma.api.InputBinding;
import net.blay09.mods.kuma.api.KeyModifier;
import net.blay09.mods.kuma.api.KeyModifiers;
import net.blay09.mods.kuma.api.Kuma;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.NotNull;

import org.mcaccess.minecraftaccess.Config;
import org.mcaccess.minecraftaccess.MainClass;
import org.mcaccess.minecraftaccess.utils.DebugScreenOverlayAccess;
import org.mcaccess.minecraftaccess.utils.KeyMappingCategories;
import org.mcaccess.minecraftaccess.utils.events.ClientPlayingTick;

/**
 * The F3 overlay, readable one line at a time. Vanilla flattens every debug entry's output into two lists of
 * strings before drawing, which loses which entry a line came from and leaves a blind player with no way into the
 * text at all. So the enabled entries are run again here, each tick, with a displayer that keeps their lines apart:
 * each entry or group becomes a section, and its lines are recomputed every tick so a line read is always current.
 *
 * <p>The cursor is a section id plus a line index rather than a copy of any text, so it survives the overlay's
 * content changing around it: sections appearing or disappearing, a section getting shorter. Nothing is
 * announced on its own; only opening and closing the overlay and the keys below say anything.
 */
public class DebugOverlayNavigator implements BalmClientModule {
    private static final String PREFIX = "minecraft_access.debug_overlay.";
    private static final String SECTION_NAME_PREFIX = "minecraft_access.debug_section.";

    private final SessionLocal<State> state = new SessionLocal<>(State::new);

    @Override
    public @NotNull Identifier getId() {
        return Identifier.fromNamespaceAndPath(MainClass.MOD_ID, "debug_overlay_navigator");
    }

    @Override
    public void initialize() {
        ClientPlayingTick.AFTER.register(this::tick);

        Kuma.createKeyMapping(Identifier.fromNamespaceAndPath(MainClass.MOD_ID, "debug_overlay.next_line"))
                .withDefault(InputBinding.key(InputConstants.KEY_RBRACKET))
                .overrideCategory(KeyMappingCategories.DEBUG_OVERLAY)
                .handleWorldInput(_ -> {
                    moveLine(1);
                    return true;
                })
                .build();
        Kuma.createKeyMapping(Identifier.fromNamespaceAndPath(MainClass.MOD_ID, "debug_overlay.previous_line"))
                .withDefault(InputBinding.key(InputConstants.KEY_LBRACKET))
                .overrideCategory(KeyMappingCategories.DEBUG_OVERLAY)
                .handleWorldInput(_ -> {
                    moveLine(-1);
                    return true;
                })
                .build();
        Kuma.createKeyMapping(Identifier.fromNamespaceAndPath(MainClass.MOD_ID, "debug_overlay.next_section"))
                .withDefault(InputBinding.key(InputConstants.KEY_RBRACKET, KeyModifiers.of(KeyModifier.SHIFT)))
                .overrideCategory(KeyMappingCategories.DEBUG_OVERLAY)
                .handleWorldInput(_ -> {
                    moveSection(1);
                    return true;
                })
                .build();
        Kuma.createKeyMapping(Identifier.fromNamespaceAndPath(MainClass.MOD_ID, "debug_overlay.previous_section"))
                .withDefault(InputBinding.key(InputConstants.KEY_LBRACKET, KeyModifiers.of(KeyModifier.SHIFT)))
                .overrideCategory(KeyMappingCategories.DEBUG_OVERLAY)
                .handleWorldInput(_ -> {
                    moveSection(-1);
                    return true;
                })
                .build();
    }

    private void tick(Minecraft client, LocalPlayer player, ClientLevel level) {
        State state = this.state.value;
        boolean shown = client.getDebugOverlay().showDebugScreen();
        // Kept current even while the feature is off, so turning it on needs no extra tick to be ready.
        if (shown) {
            rebuild(client, state);
        } else {
            state.sections = List.of();
        }

        Boolean before = state.wasShown;
        state.wasShown = shown;
        if (!Config.getInstance().debugOverlay.enabled || before == null || before == shown) return;

        if (!shown) {
            state.cursorSection = null;
            state.cursorLine = 0;
            narrate(PREFIX + "closed");
            return;
        }
        if (state.sections.isEmpty()) {
            narrate(PREFIX + "opened");
            return;
        }
        Section first = state.sections.getFirst();
        state.cursorSection = first.id();
        state.cursorLine = 0;
        narrate(PREFIX + "opened_with_line", sectionName(first.id()), cleanLine(first.lines().getFirst()));
    }

    /**
     * Runs every enabled debug entry again, keeping each one's lines apart.
     *
     * @param client where the enabled entries and the overlay's chunk lookups come from
     * @param state the sections to fill in
     */
    private void rebuild(Minecraft client, State state) {
        DebugScreenOverlayAccess access = (DebugScreenOverlayAccess) client.getDebugOverlay();
        if (access.getOverlayLevel() == null) return;

        LevelChunk clientChunk = access.getOverlayClientChunk();
        LevelChunk serverChunk = access.getOverlayServerChunk();
        Map<Identifier, List<String>> collected = new LinkedHashMap<>();
        Collector collector = new Collector(collected);
        for (Identifier id : client.debugEntries.getCurrentlyEnabled()) {
            DebugScreenEntry entry = DebugScreenEntries.getEntry(id);
            if (entry == null) continue;
            collector.current = id;
            entry.display(collector, access.getOverlayLevel(), clientChunk, serverChunk);
        }

        List<Section> built = new ArrayList<>(collected.size());
        collected.forEach((id, lines) -> built.add(new Section(id, List.copyOf(lines))));
        state.sections = List.copyOf(built);
    }

    private void moveSection(int delta) {
        State state = this.state.value;
        if (!isOpen()) return;
        if (state.sections.isEmpty()) {
            narrate(PREFIX + "empty");
            return;
        }

        int current = indexOfCursor(state);
        int target = current < 0 ? (delta > 0 ? 0 : state.sections.size() - 1) : current + delta;
        if (target < 0 || target >= state.sections.size()) {
            narrate(PREFIX + (delta > 0 ? "last_section" : "first_section"));
            return;
        }
        Section section = state.sections.get(target);
        state.cursorSection = section.id();
        state.cursorLine = 0;
        narrate(PREFIX + "section", sectionName(section.id()), cleanLine(section.lines().getFirst()));
    }

    private void moveLine(int delta) {
        State state = this.state.value;
        if (!isOpen()) return;
        if (state.sections.isEmpty()) {
            narrate(PREFIX + "empty");
            return;
        }

        int current = indexOfCursor(state);
        if (current < 0) {
            // No cursor yet, or its section is gone: start at the top.
            Section first = state.sections.getFirst();
            state.cursorSection = first.id();
            state.cursorLine = 0;
            narrate(PREFIX + "section", sectionName(first.id()), cleanLine(first.lines().getFirst()));
            return;
        }

        Section section = state.sections.get(current);
        int target = Math.min(state.cursorLine, section.lines().size() - 1) + delta;
        if (target < 0 || target >= section.lines().size()) {
            narrate(PREFIX + (delta > 0 ? "last_line" : "first_line"));
            return;
        }
        state.cursorLine = target;
        narrateText(cleanLine(section.lines().get(target)));
    }

    private static boolean isOpen() {
        return Config.getInstance().debugOverlay.enabled && Minecraft.getInstance().getDebugOverlay().showDebugScreen();
    }

    private static int indexOfCursor(State state) {
        if (state.cursorSection == null) return -1;
        for (int i = 0; i < state.sections.size(); i++) {
            if (state.sections.get(i).id().equals(state.cursorSection)) return i;
        }
        return -1;
    }

    private static String sectionName(Identifier id) {
        String key = SECTION_NAME_PREFIX + id.getPath();
        if ("minecraft".equals(id.getNamespace()) && Language.getInstance().has(key)) return I18n.get(key);
        // Other mods' entries have no name we ship, so the id's path is read as it stands.
        return id.getPath().replace('_', ' ').replace('/', ' ');
    }

    /**
     * Strips formatting codes and turns a bare biome id into the biome's translated name, since the overlay prints
     * ids that a reader would otherwise hear spelled out.
     *
     * @param line one line of the overlay
     * @return the line as it should be spoken
     */
    private static String cleanLine(String line) {
        String text = ChatFormatting.stripFormatting(line);
        if (text == null) return line;
        String prefix = "Biome: ";
        if (!text.startsWith(prefix)) return text;

        Identifier id = Identifier.tryParse(text.substring(prefix.length()).trim());
        if (id == null) return text;
        return prefix + Component.translatable(Util.makeDescriptionId("biome", id)).getString();
    }

    private static void narrate(String key, Object... arguments) {
        MainClass.narrate(I18n.get(key, arguments), true);
    }

    private static void narrateText(String text) {
        MainClass.narrate(text, true);
    }

    /** Where the reader is in the overlay, and whether it was showing as of the last tick. */
    private static final class State {
        private List<Section> sections = List.of();
        private Identifier cursorSection;
        private int cursorLine;
        // Null until a first tick in a world, so joining one never announces a change nobody made.
        private Boolean wasShown;
    }

    /** One debug entry's or group's lines. */
    private record Section(Identifier id, List<String> lines) {
    }

    /** Files plain lines under the entry being displayed, and grouped lines under their group. */
    private static final class Collector implements DebugScreenDisplayer {
        private final Map<Identifier, List<String>> out;
        private Identifier current;

        private Collector(Map<Identifier, List<String>> out) {
            this.out = out;
        }

        @Override
        public void addPriorityLine(String line) {
            add(current, line);
        }

        @Override
        public void addLine(String line) {
            add(current, line);
        }

        @Override
        public void addToGroup(Identifier group, Collection<String> lines) {
            for (String line : lines) {
                add(group, line);
            }
        }

        @Override
        public void addToGroup(Identifier group, String line) {
            add(group, line);
        }

        private void add(Identifier section, String line) {
            out.computeIfAbsent(section, key -> new ArrayList<>()).add(line);
        }
    }
}
