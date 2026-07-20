package cx.gid.minecraft.firesprinkler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Tunable limits for the sprinkler spray cone, loaded once from a plain
 * {@code config/firesprinkler.properties} file.
 *
 * The cone widens by one block of radius per level of descent, starting at
 * {@link #baseRadius} on the button's own level, until it reaches
 * {@link #maxRadius}; from there down it stays at {@code maxRadius} until it is
 * either blocked by a floor or reaches {@link #maxDepth} levels below the
 * button. All three values are configurable so servers can trade reach against
 * the small per-fire-tick lookup cost.
 */
public final class SprinklerConfig {
    /** Chebyshev radius of the cone on the button's own level (0 = just the button cell). */
    public final int baseRadius;
    /** Radius the cone widens to before it stops widening (5 => an 11x11 area). */
    public final int maxRadius;
    /** Deepest level below the button the spray can reach, in blocks. */
    public final int maxDepth;
    /** Per-check probability that a sprayed fire is extinguished (replaces vanilla's 0.2+age*0.03). */
    public final double extinguishChance;
    /** Game ticks between extinguish checks on a fire under spray (vanilla fire re-ticks every ~30-39). */
    public final int checkInterval;
    /** When true, the mod logs detection/extinguish diagnostics to the server console. */
    public final boolean debug;

    public final int steamSize;
    public final float steamSpread;
    public final float steamSpeed;

    private SprinklerConfig(int baseRadius, int maxRadius, int maxDepth, double extinguishChance, int checkInterval, int steamSize, float steamSpread, float steamSpeed, boolean debug) {
        this.baseRadius = baseRadius;
        this.maxRadius = maxRadius;
        this.maxDepth = maxDepth;
        this.extinguishChance = extinguishChance;
        this.checkInterval = checkInterval;
        this.debug = debug;

        this.steamSize = steamSize;
        this.steamSpread = steamSpread;
        this.steamSpeed = steamSpeed;
    }

    // Defaults reproduce the spec: 3x3 on the button level, widening to 11x11,
    // reaching 16 blocks down. maxDepth doubles as the cheap upper bound on how
    // far the lookup ever has to walk up a column.
    private static final int DEFAULT_BASE_RADIUS = 1;
    private static final int DEFAULT_MAX_RADIUS = 5;
    private static final int DEFAULT_MAX_DEPTH = 16;

    // A brisk sprinkler: 100% per check, and checked every 10 ticks (twice a
    // second) rather than waiting on the 30-39-tick vanilla fire cadence.
    private static final double DEFAULT_EXTINGUISH_CHANCE = 0.8;
    private static final int DEFAULT_CHECK_INTERVAL = 10;

    private static final int DEFAULT_STEAM_SIZE = 32;
    private static final float DEFAULT_STEAM_SPREAD = 0.5F;
    private static final float DEFAULT_STEAM_SPEED = 0;

    private static volatile SprinklerConfig instance;

    /** Returns the loaded config, reading it from disk on first use. */
    public static SprinklerConfig get() {
        SprinklerConfig local = instance;
        if (local == null) {
            synchronized (SprinklerConfig.class) {
                local = instance;
                if (local == null) {
                    local = load();
                    instance = local;
                }
            }
        }
        return local;
    }

    private static SprinklerConfig load() {
        Path path = Paths.get("config", "firesprinkler.properties");
        Properties props = new Properties();

        if (Files.isRegularFile(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                props.load(in);
            } catch (IOException ignored) {
                // Fall back to defaults if the file is unreadable.
            }
        }

        int baseRadius = readInt(props, "base_radius", DEFAULT_BASE_RADIUS, 0, 32);
        int maxRadius = readInt(props, "max_radius", DEFAULT_MAX_RADIUS, baseRadius, 32);
        int maxDepth = readInt(props, "max_depth", DEFAULT_MAX_DEPTH, 1, 512);
        
        double extinguishChance = readDouble(props, "extinguish_chance", DEFAULT_EXTINGUISH_CHANCE, 0.0, 1.0);
        int checkInterval = readInt(props, "check_interval", DEFAULT_CHECK_INTERVAL, 1, 200);

        int steamSize = readInt(props, "steam_size", DEFAULT_STEAM_SIZE, 0, 32);
        float steamSpread = (float)readDouble(props, "steam_spread", DEFAULT_STEAM_SPREAD, 0.0F, 1.0F);
        float steamSpeed = (float)readDouble(props, "steam_speed", DEFAULT_STEAM_SPEED, 0.0F, 1.0F);

        boolean debug = Boolean.parseBoolean(props.getProperty("debug", "false").trim());

        SprinklerConfig config = new SprinklerConfig(
            baseRadius, maxRadius, maxDepth, 
            extinguishChance, checkInterval, 
            steamSize, steamSpread, steamSpeed,
            debug
        );

        // Write the file back out (creating it the first time) so operators have
        // a documented, editable copy with the values actually in effect.
        if (!Files.isRegularFile(path)) {
            writeDefault(path, config);
        }
        return config;
    }

    private static int readInt(Properties props, String key, int fallback, int min, int max) {
        String raw = props.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double readDouble(Properties props, String key, double fallback, double min, double max) {
        String raw = props.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        try {
            double value = Double.parseDouble(raw.trim());
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void writeDefault(Path path, SprinklerConfig config) {
        try {
            Files.createDirectories(path.getParent());
            try (OutputStream out = Files.newOutputStream(path)) {
                Properties props = new Properties();
                props.setProperty("base_radius", Integer.toString(config.baseRadius));
                props.setProperty("max_radius", Integer.toString(config.maxRadius));
                props.setProperty("max_depth", Integer.toString(config.maxDepth));
                props.setProperty("extinguish_chance", Double.toString(config.extinguishChance));
                props.setProperty("check_interval", Integer.toString(config.checkInterval));
                props.setProperty("debug", Boolean.toString(config.debug));
                props.store(out,
                    "Fire Sprinkler spray-cone limits.\n"
                    + "base_radius:       Chebyshev radius on the button's own level (0 = just the button cell; 1 = 3x3).\n"
                    + "max_radius:        radius the cone widens to before it stops widening (5 = 11x11).\n"
                    + "max_depth:         deepest level below the button the spray can reach, in blocks.\n"
                    + "extinguish_chance: probability (0.0-1.0) a sprayed fire is put out per check (1.0 = instant).\n"
                    + "check_interval:    game ticks between extinguish checks on a sprayed fire (20 = 1 second).\n"
                    + "debug:             true to log detection/extinguish diagnostics to the server console.");
            }
        } catch (IOException ignored) {
            // A missing config file simply means defaults are used next launch.
        }
    }

    /**
     * Chebyshev radius of the cone at {@code depth} levels below the button
     * (depth 0 is the button's own level). Grows one block per level from
     * {@link #baseRadius} up to {@link #maxRadius}.
     */
    public int radiusAtDepth(int depth) {
        return Math.min(maxRadius, baseRadius + depth);
    }
}
