package org.mcaccess.minecraftaccess.screen_reader;

import lombok.extern.slf4j.Slf4j;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import org.jetbrains.annotations.Nullable;

import org.mcaccess.minecraftaccess.MainClass;

/**
 * Gives the mod the screen reader it narrates with.
 *
 * <p>There is a single implementation, Prism, which speaks through whichever screen reader the machine runs and
 * through the text to speech engine of the system when there is none. It brings the native library it needs along
 * with the mod, so nothing has to be installed or downloaded beside it, on any platform.
 */
@Slf4j
public final class ScreenReaderController {
    private ScreenReaderController() {
    }

    /**
     * Gets the screen reader of this session, starting it if it is not running yet.
     *
     * @return the screen reader, or {@code null} when Prism found no way to speak on this machine
     */
    public static @Nullable ScreenReaderInterface getAvailable() {
        ScreenReaderPrism screenReader = new ScreenReaderPrism();
        screenReader.initializeScreenReader();

        if (!screenReader.isInitialized()) {
            log.error("No valid ScreenReader interface found");
            return null;
        }
        return screenReader;
    }

    public static void refreshScreenReader() {
        refreshScreenReader(false);
    }

    public static void refreshScreenReader(boolean closeOpenedScreen) {
        log.info("Refreshing screen reader");
        MainClass.setScreenReader(getAvailable());

        if (!closeOpenedScreen) return;
        if (Minecraft.getInstance().player == null) return;
        Minecraft.getInstance().player.clientSideCloseContainer();
        MainClass.narrate(I18n.get("minecraft_access.access_menu.screen_reader_refreshed"), true);
    }
}
