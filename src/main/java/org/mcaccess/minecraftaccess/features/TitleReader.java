package org.mcaccess.minecraftaccess.features;

import net.blay09.mods.balm.client.platform.module.BalmClientModule;
import net.blay09.mods.balm.client.platform.util.SessionLocal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.NotNull;

import org.mcaccess.minecraftaccess.Config;
import org.mcaccess.minecraftaccess.MainClass;
import org.mcaccess.minecraftaccess.mixin.HudTitleAccessor;
import org.mcaccess.minecraftaccess.utils.events.ClientPlayingTick;

/**
 * Reads out the title and subtitle a server puts in the middle of the screen. Nothing says them aloud otherwise.
 *
 * <p>Read once a tick from what the HUD is actually showing, rather than from the packets that set it. A subtitle
 * arrives as its own packet, usually just before its title, so reacting to each packet would say the title's text
 * twice - by the end of the tick both are in place and are read together. The same text is not read again while it
 * stays up either, so a title a server keeps re-sending is heard once, while a countdown ("3", "2", "1") still comes
 * through in full as each one replaces the last.
 *
 * <p>Queued, not interrupting: a title tends to arrive alongside chat or other narration and should be heard in order.
 */
public class TitleReader implements BalmClientModule {
    private final SessionLocal<State> sessionState = new SessionLocal<>(State::new);

    @Override
    public @NotNull Identifier getId() {
        return Identifier.fromNamespaceAndPath(MainClass.MOD_ID, "title_reader");
    }

    @Override
    public void initialize() {
        ClientPlayingTick.AFTER.register(this::tick);
    }

    private void tick(Minecraft client, LocalPlayer player, ClientLevel level) {
        Config.TitleNarration config = Config.getInstance().titleNarration;
        State state = this.sessionState.value;
        HudTitleAccessor hud = (HudTitleAccessor) client.gui.hud;
        String message = state.read(hud.getHudTitle(), hud.getHudSubtitle(), config.enabled);
        if (message != null) {
            MainClass.narrate(message, false);
        }
    }

    /**
     * What the HUD shows, remembered from last tick so only new text is read - and what to say now, or null for
     * nothing.
     */
    private static final class State {
        private String lastTitle;
        private String lastSubtitle;

        private String read(Component title, Component subtitle, boolean enabled) {
            if (title == null) {
                // Faded out or cleared, so the same text showing later is a new title.
                lastTitle = null;
                lastSubtitle = null;
                return null;
            }
            String titleText = title.getString().strip();
            String subtitleText = subtitle == null ? "" : subtitle.getString().strip();
            boolean titleChanged = !titleText.equals(lastTitle);
            boolean subtitleChanged = !subtitleText.equals(lastSubtitle);
            lastTitle = titleText;
            lastSubtitle = subtitleText;
            if (!enabled || !titleChanged && !subtitleChanged) return null;

            if (titleChanged) {
                // A title with a blank subtitle is read alone; a blank title with a subtitle is how the
                // subtitle line is often shown on its own.
                if (titleText.isEmpty() && subtitleText.isEmpty()) return null;
                if (titleText.isEmpty()) return subtitleText;
                return subtitleText.isEmpty() ? titleText : titleText + ". " + subtitleText;
            }
            // Only the subtitle changed under a title already read, so just the new part.
            return subtitleText.isEmpty() ? null : subtitleText;
        }
    }
}
