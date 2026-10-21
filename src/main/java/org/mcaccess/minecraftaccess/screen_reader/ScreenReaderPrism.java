package org.mcaccess.minecraftaccess.screen_reader;

import java.nio.file.Path;
import java.nio.file.Paths;

import lombok.extern.slf4j.Slf4j;

import org.mcaccess.minecraftaccess.Config;
import org.mcaccess.minecraftaccess.screen_reader.prism.PrismNarrator;

/**
 * Narration through Prism, which speaks through the screen reader the machine runs, when it runs one, and through the
 * text to speech engine of the operating system otherwise.
 *
 * <p>Unlike the implementations of the platforms that came before it, this needs nothing the user has to install or
 * download: the native library ships with the mod.
 */
@Slf4j
public class ScreenReaderPrism implements ScreenReaderInterface {
    private PrismNarrator narrator;

    @Override
    public void initializeScreenReader() {
        if (narrator != null) {
            return;
        }

        // The game directory is the working directory, which is where the mod used to expect its libraries
        Path baseDirectory = Paths.get("").toAbsolutePath();
        narrator = PrismNarrator.create(baseDirectory);

        if (narrator == null) {
            log.info("Prism is not available on this machine");
            return;
        }
        log.info("Successfully initialized the Prism screen reader");
    }

    @Override
    public boolean isInitialized() {
        return narrator != null;
    }

    @Override
    public void narrate(String text, boolean interrupt) {
        if (narrator == null) {
            return;
        }

        // Read the rate here, in case the user changes it while the game runs
        narrator.setRate(Config.getInstance().speechSettings.speechRate / 100);
        narrator.narrate(formatNarration(text), interrupt);
    }

    @Override
    public void closeScreenReader() {
        if (narrator == null) {
            return;
        }

        narrator.close();
        narrator = null;
    }
}
