package cx.gid.minecraft.firesprinkler;

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
 * The button throws water outward one block per level for the first
 * {@link SprinklerConfig#maxRadius} levels and it falls straight down
 * thereafter, so the covered volume is a pyramid that widens to an NxN square
 * and then continues down. The divergence belongs to the head, not to each
 * cell of water: nothing spreads sideways once thrown, so a solid block casts
 * a shadow straight down and a one-block hole in a ceiling passes a one-block
 * column, exactly as rain would.
 *
 * Queries run from the fire rather than the sprinkler -- there is no cheap way
 * to enumerate sprinklers near a position -- but the geometry is still tested
 * in the sprinkler's own direction, as a throw followed by a fall.
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

    /**
     * The spray test proper, behind {@link #isSprayedAt}'s per-tick cache.
     *
     * Rain logic, from the sprinkler's point of view. The button throws water
     * outward one block per level for the first {@link SprinklerConfig#maxRadius}
     * levels, and after that the water just falls. So the covered volume is a
     * pyramid that widens to an NxN square and then continues straight down:
     *
     *      S      (button)         d=0   ....S....
     *     ###                      d=1   ...###...
     *    #####                     d=2   ..#####..
     *   #######                    d=3   .#######.
     *   #######                    d=4+  .#######.  (no wider)
     *
     * The divergence belongs to the head, not to each cell of water: nothing
     * spreads sideways once it has been thrown. That is what makes a solid
     * block cast a permanent shadow straight down, and a one-block hole in a
     * ceiling pass exactly a one-block column -- ordinary rain behaviour, which
     * is what players expect.
     *
     * Rather than flood-fill the volume, we test the single position we were
     * asked about. Walking up from {@code pos} we look for a button that could
     * cover it, and for each candidate check the two straight segments the
     * water would have travelled: the diagonal throw out from the head, then
     * the vertical fall. Both are simple line walks, so a query costs at most
     * {@code maxRadius + maxDepth} block reads rather than a whole grid.
     */
    private static boolean computeSprayedAt(BlockGetter level, SprinklerConfig config, BlockPos pos) {

        // Cheap rejection for open-air fire, which is the common case and the
        // expensive one. A sprinkler is a button on the underside of a block,
        // so it needs something solid overhead. MOTION_BLOCKING is the same
        // "stops falling things" notion the cone itself uses, and the chunk
        // keeps it as a maintained heightmap, so one array read per column
        // answers "is there any ceiling at all here". If no column the cone
        // could span has a blocking block above pos, there is nowhere for a
        // sprinkler to hang.
        if (!hasAnyCeilingAbove(level, config, pos)) {
            return false;
        }

        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        // Look for a button on each level above, out to the radius its own
        // cone would have reached by the time it got down to us.
        for (int depth = 1; depth <= config.maxDepth; depth++) {
            int radius = config.radiusAtDepth(depth);

            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    cursor.set(x + dx, y + depth, z + dz);
                    BlockState state = level.getBlockState(cursor);

                    if (!isActiveSprinklerAt(level, cursor, state)) {
                        continue;
                    }

                    // pos sits at offset (-dx,-dz) from this button's axis.
                    if (reaches(level, cursor, -dx, -dz, depth, config)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * True if the sprinkler at {@code head} wets the cell {@code depth} levels
     * below it at horizontal offset {@code (ox,oz)} from its axis.
     *
     * Two segments, matching the two things the water does:
     *
     *   1. the _throw_ -- a diagonal ray from the head out to the target
     *      offset, one block further out per level, and
     *   2. the _fall_ -- straight down that column to the target level.
     *
     * The throw reaches its full offset at depth {@code max(|ox|,|oz|)}, which
     * is why a cone that has stopped widening still casts vertical shadows:
     * everything below that depth is pure falling.
     */
    private static boolean reaches(BlockGetter level, BlockPos head, int ox, int oz, int depth, SprinklerConfig config) {
        int cheb = Math.max(Math.abs(ox), Math.abs(oz));

        // Outside the cone at this depth.
        if (cheb > config.radiusAtDepth(depth)) {
            return false;
        }

        int hx = head.getX(), hy = head.getY(), hz = head.getZ();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        // 1. The throw: step outward one block per level until we are under
        //    the target offset. The head already covers baseRadius on its own
        //    level, so the first baseRadius rings cost no travel and the throw
        //    lands at depth cheb - baseRadius.
        int throwDepth = Math.max(0, cheb - config.baseRadius);
        int cx = 0, cz = 0;
        for (int i = 1; i <= throwDepth; i++) {
            int nx = cx + Integer.signum(ox - cx);
            int nz = cz + Integer.signum(oz - cz);

            cursor.set(hx + nx, hy - i, hz + nz);
            if (blocksSpray(level, cursor)) {
                return false;
            }

            // On a step that moves in both axes at once, refuse to squeeze
            // between two blocks set corner to corner: if both of the
            // orthogonal cells we are cutting past are solid, the water is
            // walled in, even though the diagonal gap is technically open.
            if (nx != cx && nz != cz) {
                cursor.set(hx + nx, hy - i, hz + cz);
                boolean sideA = blocksSpray(level, cursor);
                cursor.set(hx + cx, hy - i, hz + nz);
                boolean sideB = blocksSpray(level, cursor);
                if (sideA && sideB) {
                    return false;
                }
            }

            cx = nx;
            cz = nz;
        }

        // 2. The fall: straight down the target column to the target level.
        //    Starts below wherever the throw left off (at least one level
        //    down, so a button directly overhead still has to see clear air).
        for (int i = throwDepth + 1; i <= depth; i++) {
            cursor.set(hx + ox, hy - i, hz + oz);
            if (blocksSpray(level, cursor)) {
                return false;
            }
        }

        return true;
    }

    /**
     * True if this cell stops the spray -- either a solid block, or a position
     * we cannot see into.
     *
     * An unloaded chunk (or a position past the world height) reads as
     * VOID_AIR through BlockGetter. Treat that as sealed rather than open: we
     * cannot see a sprinkler we cannot load, and quietly spraying out of
     * unloaded space would be worse than declining to spray at all.
     */
    private static boolean blocksSpray(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.VOID_AIR) || isSolidAsFarAsWeAreConcerned(state);
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
