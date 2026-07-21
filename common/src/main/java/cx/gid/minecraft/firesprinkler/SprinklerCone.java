package cx.gid.minecraft.firesprinkler;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * Geometry and detection for the downward spray cone beneath an active fire
 * sprinkler.
 *
 * An _active sprinkler_ is a button attached to the underside of a block (its
 * {@code FACE} is {@link AttachFace#CEILING}) where that supporting block is
 * fed by water: it is waterlogged, or has a water source above it, or has a
 * solid block above it with a water source above _that_.
 * 
 * Currently, the pattern actually works in reverse, working from the fire
 * upwards, so the cone is upside-down. However, it seems _good enough_
 * and obviates the need for excessive caching and multi-pass scans.
 *
 * All queries are read-only against the world and are bounded by
 * {@link SprinklerConfig#maxDepth}, so they are cheap enough to run from the
 * hot {@code isRainingAt} path; but callers should still only invoke
 * them for positions that actually matter (fire blocks, burning mobs).
 */
public final class SprinklerCone {

    // FaceAttachedHorizontalDirectionalBlock.FACE / ButtonBlock's ATTACH_FACE.
    // getConnectedDirection() is protected, so we read the property directly.
    private static final EnumProperty<AttachFace> FACE = BlockStateProperties.ATTACH_FACE;
    private static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;

    private SprinklerCone() {}

    /**
     * Cached {@link #isSprayedAt} answers for the tick currently being
     * processed, keyed on packed block position.
     *
     * A single burning block asks the question far more than once. Vanilla's
     * fire spread loop probes {@code getIgniteOdds} across a 3x3x6
     * neighbourhood, and our redirect turns every one of those into a full cone
     * walk -- so one fire is ~54 searches per tick before any neighbouring fire
     * adds its own overlapping set.
     *
     * The cache is scoped to exactly one game tick: any change in
     * {@code getGameTime()} drops everything. That makes it transparent rather
     * than approximate -- the world shouldn't change under us within a tick on the
     * server thread, so a same-tick hit is the answer the search would have
     * recomputed, not a stale one. It follows that no block-update invalidation
     * is needed: there is no window in which a block update could land.
     *
     * Access is confined to the server thread (fire ticks and ignition probes
     * both run there), so a plain HashMap is safe.
     */
    private static final Map<Long, Boolean> SPRAY_CACHE = new HashMap<>();
    private static long sprayCacheTick = Long.MIN_VALUE;
    private static Level sprayCacheLevel = null;

    /**
     * True if {@code pos} lies within the spray cone of some active sprinkler,
     * with an unobstructed path of open cells between them.
     *
     * Answers are cached for the duration of a single tick (see
     * {@link #SPRAY_CACHE})
     */
    public static boolean isSprayedAt(BlockGetter level, BlockPos pos) {
        SprinklerConfig config = SprinklerConfig.get();

        // Caching needs a clock to scope entries to a tick, which plain
        // BlockGetter doesn't have.
        if (level instanceof Level clock) {
            long now = clock.getGameTime();

            // Drop everything on any tick change, and also whenever the level
            // changes: positions are only unique within a dimension, so
            // entries from another level would collide on identical
            // coordinates. Comparing identity is enough -- each dimension is a
            // single long-lived Level instance.
            if (now != sprayCacheTick || level != sprayCacheLevel) {
                SPRAY_CACHE.clear();
                sprayCacheTick = now;
                sprayCacheLevel = clock;
            }

            // Note pos may be a MutableBlockPos that the caller goes on to
            // reuse, so key on the packed long rather than holding the object.
            long key = pos.asLong();
            Boolean hit = SPRAY_CACHE.get(key);
            if (hit != null) {
                return hit;
            }

            boolean answer = computeSprayedAt(level, config, pos);
            SPRAY_CACHE.put(key, answer);
            return answer;
        }

        return computeSprayedAt(level, config, pos);
    }

    /** The cone search proper, behind {@link #isSprayedAt}'s per-tick cache. */
    private static boolean computeSprayedAt(BlockGetter level, SprinklerConfig config, BlockPos pos) {

        // Cheap rejection for open-air fire, which is the common case and the
        // expensive one: with nothing above it the sweep has no reason to stop
        // early and walks every cell of all maxDepth levels before giving up.
        //
        // A sprinkler is a button on the underside of a block, so it needs
        // something solid overhead. MOTION_BLOCKING is the same "stops falling
        // things" notion the cone itself uses, and the chunk keeps it as a
        // maintained heightmap, so one array read per column answers "is there
        // any ceiling at all here". If no column the cone could span has a
        // blocking block above pos, there is nowhere for a sprinkler to hang.
        if (!hasAnyCeilingAbove(level, config, pos)) {
            return false;
        }

        // We're inverting the cone. Rather than starting at a sprinkler and
        // spraying downwards:
        //
        //      W   (waterlogged block)
        //      ^   (button)
        //     /|\  (cone)
        //    /|||\
        //   /|||||\
        //   |||||||
        //   |||||||
        //    F     (pos - the fire)
        //
        // we sweep bottom-up from pos, one level at a time, carrying a grid of
        // which cells water could have fallen *from* to reach fire:
        //
        //   W
        //   ^
        //   |||||||
        //   |||||||
        //   \|||||/ 
        //    \|||/
        //     \|/
        //      F
        //
        // As a result, it acts less like:
        //
        //       W                    W
        //       ^                    ^
        //      /                     |
        //     /                      |
        //    /     and more like:    |
        //   |                       /
        //   |                      /
        //   |                     /
        //   F                     F
        //
        // which is admittedly a little weird.
    
        //
        // Doing it this way rather than testing a single column above pos
        // matters in both directions: a sprinkler off to one side is still
        // found when the cell directly above pos is sealed, and a sprinkler
        // walled off diagonally is correctly *not* found.  However, it isn't
        // accurate, and seems to over-water a bit, as if each hole in a
        // barrier acts like a sprinkler of its own to a limited degree.
        //
        // We stop as soon as the reachable set empties: no sprinkler above that
        // point can get water down here, whatever the radius would allow.

        // Cache pos's coordinates rather than re-calling the accessors.
        int x = pos.getX(), y0 = pos.getY(), z = pos.getZ();

        // Offsets are indexed [dx+max][dz+max], so the grid is exactly wide
        // enough for the fully-widened cone. At the default maxRadius of 5
        // that's 11x11 booleans -- small enough to allocate per call.
        int max = config.maxRadius;
        int span = 2 * max + 1;
        boolean[][] reachable = new boolean[span][span];
        boolean[][] next = new boolean[span][span];

        // pos is trivially reachable from itself.
        reachable[max][max] = true;

        // `cursor` is the position we're currently examining, starting at
        // `pos` and moving around and upwards.
        BlockPos.MutableBlockPos cursor = pos.mutable();

        // For each vertical level above the fire...
        for (int depth = 1; depth <= config.maxDepth; depth++) {

            // Set the cursor's Y coordinate to the current level
            cursor.setY(y0 + depth);

            // Get the radius of the cone
            int radius = config.radiusAtDepth(depth);
            boolean any = false;

            // Clear the grid of cells that are reachable from this level
            for (boolean[] row : next) {
                Arrays.fill(row, false);
            }

            // For each position in the cone at this level...
            for (int dx = -radius; dx <= radius; dx++) {
                cursor.setX(x + dx);

                for (int dz = -radius; dz <= radius; dz++) {
                    cursor.setZ(z + dz);

                    // Water spreads out as it falls, so this cell is only in
                    // play if one of its neighbours a level down was.
                    if (!wasReachable(reachable, dx, dz, max)) {
                        continue;
                    }

                    // Get the block at the cursor's position
                    BlockState state = level.getBlockState(cursor);

                    // Reaching here means the path from this cell down to pos
                    // is clear, so a sprinkler here really does reach pos.
                    // Deliberately not logged: this runs once per probed cell,
                    // and vanilla's spread loop probes 54 cells per burning
                    // block per tick. The extinguish itself is logged instead.
                    if (isActiveSprinklerAt(level, cursor, state)) {
                        return true;
                    }

                    // An unloaded chunk -- or a position past the world height
                    // -- reads as VOID_AIR through BlockGetter. Treat that as
                    // sealed rather than open: we can't see a sprinkler we
                    // can't load, and quietly spraying out of unloaded space
                    // would be worse than declining to spray at all.
                    if (state.is(Blocks.VOID_AIR)) {
                        continue;
                    }

                    // Otherwise the spray carries on up through this cell only
                    // if the block there doesn't stop it.
                    if (isSolidAsFarAsWeAreConcerned(state)) {
                        continue;
                    }

                    next[dx + max][dz + max] = true;
                    any = true;
                }
            }

            if (!any) {
                return false;
            }

            boolean[][] swap = reachable;
            reachable = next;
            next = swap;
        }

        return false;
    }

    /**
     * True if any column the cone could span has a "solid" block
     * somewhere above {@code pos} -- i.e. if there is anywhere at all for a
     * ceiling-mounted button to hang.
     *
     * This is a conservative pre-filter, not an answer: it says only that the
     * full search might find something, never that it will. A false result is
     * reliable, because a sprinkler needs a solid block above it to attach to
     * and MOTION_BLOCKING records exactly that.
     *
     * Reading the chunk's maintained heightmap is far cheaper than probing
     * blocks: at most (2*maxRadius+1)^2 array reads, against the ~1,900
     * getBlockState calls the full sweep makes for fire in the open. Only
     * available on a LevelReader; a bare BlockGetter has no heightmap, so we
     * fall through to the full search rather than guess.
     */
    private static boolean hasAnyCeilingAbove(BlockGetter level, SprinklerConfig config, BlockPos pos) {
        if (!(level instanceof LevelReader reader)) {
            return true;
        }

        int x = pos.getX(), y = pos.getY(), z = pos.getZ();

        // The cone is widest at its deepest, so that radius bounds every column
        // any sprinkler in range could occupy.
        int radius = config.radiusAtDepth(config.maxDepth);

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                // getHeight returns the first free Y above the highest blocking
                // block, so a value above pos means something solid overhead.
                // Unloaded columns report getMinY(), which fails this test --
                // fine, since we can't see a sprinkler in a chunk we can't read.
                if (reader.getHeight(Heightmap.Types.MOTION_BLOCKING, x + dx, z + dz) > y + 1) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * True if water could have arrived at horizontal offset {@code (dx,dz)}
     * from the level below.
     *
     * Water spreads *outwards* as it falls. Read bottom-up, that means a cell
     * can only be fed from the cell directly below it or from one nearer the
     * axis -- never from one further out, which would require water to have
     * moved inwards on the way down. So we look at the (up to) four cells
     * between this offset and the centre, inclusive.
     *
     * Allowing the full 3x3 neighbourhood instead is wrong in a way that is
     * easy to miss: it lets the fill travel sideways within a level, so a
     * single block never seals anything -- the sweep simply flows around it
     * and carries on up, and a sprinkler directly above a covered fire still
     * reaches it. Restricting to inward steps keeps the covered volume an
     * actual cone.
     *
     * Diagonal movement is still allowed (both axes may step at once), so the
     * cone stays square and beacon-like rather than losing its corners.
     */
    private static boolean wasReachable(boolean[][] prev, int dx, int dz, int max) {
        int span = prev.length;

        // Step towards the axis on each axis independently; signum is 0 when
        // already centred, so the loop naturally collapses to fewer cells.
        int stepX = Integer.signum(dx);
        int stepZ = Integer.signum(dz);

        for (int ox = 0; ox <= 1; ox++) {
            int ix = dx - stepX * ox + max;
            if (ix < 0 || ix >= span) {
                continue;
            }
            for (int oz = 0; oz <= 1; oz++) {
                int iz = dz - stepZ * oz + max;
                if (iz < 0 || iz >= span) {
                    continue;
                }
                if (prev[ix][iz]) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * True if an active sprinkler button occupies the block at the given
     * coordinates.
     *
     * Takes the state as a parameter because the cone search has already
     * fetched it: looking it up again here doubled the block reads over the
     * whole cone, which in an open room is every cell of all 16 levels.
     */
    private static boolean isActiveSprinklerAt(BlockGetter level, BlockPos buttonPos, BlockState state) {
        if (!(state.getBlock() instanceof ButtonBlock))
            // Not a button, so it's not a sprinkler.
            return false;

        // Only ceiling-mounted buttons act as sprinkler heads.
        if (!state.hasProperty(FACE) || state.getValue(FACE) != AttachFace.CEILING)
            return false;

        // Check to see if the block above has a water supply.
        // Note, we rely on game mechanics for the block to be solid and capable
        // of supporting a button.
        BlockPos supportPos = buttonPos.above();
        return hasWaterSupply(level, supportPos);
    }

    /**
     * True if the block above the button feeds it water: either the block is
     * waterlogged, or the block above is water or is waterlogged, OR the block
     * above _that_ is water or is waterlogged (so drips can be avoided)
     */
    private static boolean hasWaterSupply(BlockGetter level, BlockPos supportPos) {
        BlockState supportState = level.getBlockState(supportPos);

        // Is the block waterlogged (eg. leaves, stairs)?
        if (supportState.hasProperty(WATERLOGGED) && supportState.getValue(WATERLOGGED)) {
            return true;
        }

        // Look one block above the support block
        BlockPos oneabovePos = supportPos.above();
        BlockState oneaboveState = level.getBlockState(oneabovePos);

        // Is the block above the support block waterlogged?
        if (oneaboveState.hasProperty(WATERLOGGED) && oneaboveState.getValue(WATERLOGGED)) {
            return true;
        }

        // Is the block above the support block a water source?
        if (oneaboveState.getFluidState().isSourceOfType(net.minecraft.world.level.material.Fluids.WATER)) {
            return true;
        }

        // If the block above is solid, then we can also check the block above _that_.
        // This is a bit of a hack, but it's so we can have this sprinkler behaviour without
        // having client-side drips, as we can't set #minecraft:impermeable on individual
        // blocks, unfortunately.
        if (isSolidAsFarAsWeAreConcerned(oneaboveState)) {
                
            // Look two blocks above the support block
            BlockPos twoabovePos = oneabovePos.above();
            BlockState twoaboveState = level.getBlockState(twoabovePos);

            // Is the block above waterlogged?
            if (twoaboveState.hasProperty(WATERLOGGED) && twoaboveState.getValue(WATERLOGGED)) {
                return true;
            }

            // Is the block above a water source?
            if (twoaboveState.getFluidState().isSourceOfType(net.minecraft.world.level.material.Fluids.WATER)) {
                return true;
            }
        }

        return false;
    }

    /**
     * True if the block is solid enough to stop the spray falling through it,
     * or to carry a water supply down to the block below.
     *
     * We use vanilla rain's own blocker rule so the sprinkler shields the same
     * way rain does: a block stops the spray if it {@code blocksMotion()} (has
     * a solid collision box). Open trapdoors, string, torches, carpets and
     * flowers therefore let the spray through, while closed trapdoors, slabs,
     * stairs, fences and full blocks stop it.
     *
     * Both {@code BlockState::blocksMotion()} and {@code BlockState::isSolid()}
     * are deprecated, but vanilla rain uses the MOTION_BLOCKING heightmap which
     * itself uses blocksMotion(). Once there's a different rain implementation,
     * we can change this.
     */
    private static boolean isSolidAsFarAsWeAreConcerned(BlockState state) {
        @SuppressWarnings("deprecation") boolean blocksMotion = state.blocksMotion();
        return blocksMotion;
    }

    /**
     * Emits a small puff of white steam at the given point. Uses
     * {@code WHITE_SMOKE} (a pale, wispy puff) rather than the sooty
     * {@code LARGE_SMOKE} of level event 1501, so water hitting flame reads as
     * steam rather than smoke. Used both for extinguished fire blocks and for
     * burning mobs put out by the spray.
     */
    public static void spawnSteam(Level level, double x, double y, double z) {
        if (level instanceof ServerLevel serverLevel) {
            SprinklerConfig config = SprinklerConfig.get();
            serverLevel.sendParticles(ParticleTypes.WHITE_SMOKE, x, y, z, config.steamSize, config.steamSpread, config.steamSpread, config.steamSpread, config.steamSpeed);
        }
    }
}
