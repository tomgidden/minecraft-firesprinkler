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
 * The button sprays water outward one block per level for the first
 * {@link SprinklerConfig#maxRadius} levels and it falls straight down
 * thereafter, so the covered volume is a pyramid that widens to an NxN square
 * and then continues down. The divergence belongs to the head, not to each
 * cell of water: nothing spreads sideways once sprayed, so a solid block casts
 * a shadow straight down and a one-block hole in a ceiling passes a one-block
 * column, exactly as rain would.
 *
 * Queries run from the fire rather than the sprinkler -- there is no cheap way
 * to enumerate sprinklers near a position -- but the geometry is still tested
 * in the sprinkler's own direction, as a spray followed by a fall.
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
     * Rain logic, from the sprinkler's point of view. The button sprays water
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
     * spreads sideways once it has been sprayed. That is what makes a solid
     * block cast a permanent shadow straight down, and a one-block hole in a
     * ceiling pass exactly a one-block column -- ordinary rain behaviour, which
     * is what players expect.
     *
     * Rather than flood-fill the volume, we test the single position we were
     * asked about. Walking up from {@code pos} we look for a button that could
     * cover it, and for each candidate check the two straight segments the
     * water would have travelled: the diagonal spray out from the head, then
     * the vertical fall. Both are simple line walks, so a query costs at most
     * {@code maxRadius + maxDepth} block reads rather than a whole grid.
     */
    private static boolean computeSprayedAt(BlockGetter level, SprinklerConfig config, BlockPos pos) {

        int x = pos.getX(), y = pos.getY(), z = pos.getZ();

        // How high each column in reach goes, from the chunk's maintained
        // MOTION_BLOCKING heightmap. A sprinkler is a button hanging from a
        // solid block, so a column whose highest blocking block is at H can
        // only hold a button at H-1 or lower -- and since the search walks
        // downward-to-upward, once a column is exhausted it stays exhausted.
        //
        // This is what keeps the search honest in a roofed room. Without it we
        // would probe every cell of every level looking for buttons that
        // cannot be there: 1728 block reads for a fire under a low ceiling,
        // against the 34 the old flood-fill needed before it gave up. The
        // heightmap answers the same question from an array.
        // A bare BlockGetter has no heightmap; then ceiling is null and we
        // simply probe every cell as before.
        int[] ceiling = columnCeilings(level, config, pos);
        if (ceiling != null && !anyColumnReaches(ceiling, y + 2)) {
            // Nothing overhead anywhere in range is high enough to hang a
            // button from, so there is no sprinkler to find.
            return false;
        }

        int span = 2 * config.maxRadius + 1;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        // Look for a button on each level above, out to the radius its own
        // cone would have reached by the time it got down to us.
        for (int depth = 1; depth <= config.maxDepth; depth++) {
            int radius = config.radiusAtDepth(depth);
            int buttonY = y + depth;
            boolean anyColumnLeft = false;

            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {

                    // Skip columns that cannot hold a button this high. The
                    // support block sits one above the button, so the column
                    // needs to block motion at buttonY+1 or above.
                    if (ceiling != null) {
                        int top = ceiling[(dx + config.maxRadius) * span
                                          + (dz + config.maxRadius)];
                        if (top <= buttonY + 1) {
                            continue;
                        }
                        anyColumnLeft = true;
                    }

                    cursor.set(x + dx, buttonY, z + dz);
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

            // Every column in range is capped below this level. A column that
            // fails here fails at every deeper level too, since the button
            // level only rises -- so once the cone has stopped widening and
            // brought in its last new column, there is nothing left to find.
            //
            // Only safe once the radius has stopped growing: while it is still
            // widening, a taller column may sit just outside the footprint we
            // have looked at so far.
            if (ceiling != null && !anyColumnLeft
                    && config.radiusAtDepth(depth) >= config.maxRadius) {
                return false;
            }
        }

        return false;
    }

    /**
     * The MOTION_BLOCKING height of every column the cone could span, indexed
     * {@code (dx+maxRadius)*span + (dz+maxRadius)}, or {@code null} if this
     * level has no heightmap to read.
     *
     * Reading the whole footprint once is cheaper than asking per level: the
     * cone revisits the same columns at every depth, and the heightmap does
     * not change while we look at it.
     */
    private static int[] columnCeilings(BlockGetter level, SprinklerConfig config, BlockPos pos) {
        if (!(level instanceof LevelReader reader)) {
            return null;
        }

        int max = config.maxRadius;
        int span = 2 * max + 1;
        int[] heights = new int[span * span];
        int x = pos.getX(), z = pos.getZ();

        for (int dx = -max; dx <= max; dx++) {
            for (int dz = -max; dz <= max; dz++) {
                heights[(dx + max) * span + (dz + max)] =
                    reader.getHeight(Heightmap.Types.MOTION_BLOCKING, x + dx, z + dz);
            }
        }
        return heights;
    }

    /** True if any column reaches at least {@code minHeight}. */
    private static boolean anyColumnReaches(int[] ceilings, int minHeight) {
        for (int h : ceilings) {
            if (h > minHeight) {
                return true;
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
     *   1. the _spray_ -- a diagonal ray from the head out to the target
     *      offset, one block further out per level, and
     *   2. the _fall_ -- straight down that column to the target level.
     *
     * The spray reaches its full offset at depth {@code max(|ox|,|oz|)}, which
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

        // 1. The spray: step outward one block per level until we are under
        //    the target offset. The head already covers baseRadius on its own
        //    level, so the first baseRadius rings cost no travel and the spray
        //    lands at depth cheb - baseRadius.
        int sprayDepth = Math.max(0, cheb - config.baseRadius);
        int cx = 0, cz = 0;
        for (int i = 1; i <= sprayDepth; i++) {
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
        //    Starts below wherever the spray left off (at least one level
        //    down, so a button directly overhead still has to see clear air).
        for (int i = sprayDepth + 1; i <= depth; i++) {
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
            serverLevel.sendParticles(ParticleTypes.WHITE_SMOKE, x, y, z, config.steamParticleCount, config.steamParticleSpread, config.steamParticleSpread, config.steamParticleSpread, config.steamParticleSpeed);
        }
    }
}
