package cx.gid.minecraft.firesprinkler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight debug logging for the sprinkler, gated behind a config flag so it
 * can be turned on to diagnose a non-triggering sprinkler and left off in normal
 * play without a rebuild.
 *
 * Enable by setting {@code debug=true} in
 * {@code config/firesprinkler.properties} (see {@link SprinklerConfig}). All
 * messages are logged at {@code INFO} under the logger name {@code
 * firesprinkler} so they show up plainly in the server console.
 */
public final class SprinklerDebug {
    private static final Logger LOGGER = LoggerFactory.getLogger("firesprinkler");

    private SprinklerDebug() {}

    /** True if debug logging is switched on in the config. */
    public static boolean enabled() {
        return SprinklerConfig.get().debug;
    }

    /** Logs a formatted message at INFO if debug is enabled ({@code {}} placeholders). */
    public static void log(String format, Object... args) {
        if (enabled()) {
            LOGGER.info("[firesprinkler] " + format, args);
        }
    }
}
