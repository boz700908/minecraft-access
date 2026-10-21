package org.mcaccess.minecraftaccess.screen_reader;

import lombok.extern.slf4j.Slf4j;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import org.jetbrains.annotations.Nullable;

import org.mcaccess.minecraftaccess.MainClass;
import org.mcaccess.minecraftaccess.screen_reader.android.AndroidPlatform;

/**
 * Gives the mod the screen reader it narrates with.
 *
 * <p>Everywhere but on Android this is Prism, which speaks through whichever screen reader the machine runs and
 * through the text to speech engine of the system when there is none. It brings the native library it needs along
 * with the mod, so nothing has to be installed or downloaded beside it, on any platform.
 *
 * <p>An Android launcher hands the speech engine of the device to Java rather than to native code, so Prism cannot
 * be reached from the game there and {@code screen_reader/android} speaks instead, on top of the same backends.
 */
@Slf4j
public final class ScreenReaderController {
    private ScreenReaderController() {
    }

    /**
     * Gets the screen reader of this session, starting it if it is not running yet.
     *
     * @return the screen reader, or {@code null} when this machine offers no way to speak
     */
    public static @Nullable ScreenReaderInterface getAvailable() {
        if (AndroidPlatform.isAndroid()) {
            ScreenReaderAndroid screenReaderAndroid = new ScreenReaderAndroid();
            screenReaderAndroid.initializeScreenReader();
            return screenReaderAndroid;
        }

        ScreenReaderPrism screenReaderPrism = new ScreenReaderPrism();
        screenReaderPrism.initializeScreenReader();

        if (!screenReaderPrism.isInitialized()) {
            log.error("No valid ScreenReader interface found");
            return null;
        }
        return screenReaderPrism;
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
