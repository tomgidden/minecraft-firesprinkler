package cx.gid.minecraft.firesprinkler;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
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
 * block (its {@code FACE} is {@link AttachFace#CEILING}) where that supporting
 * block above is a water source or is itself waterlogged; i.e. there is
 * water feeding the sprinkler head.
 *
 * The spray fills a cone that starts small at the button and widens with
 * depth (see {@link SprinklerConfig}). A given position is covered only if it
 * lies within the cone's radius for its depth _and_ the vertical column
 * directly above it, up to the button's level, is not sealed by a floor that
 * water could not fall through.
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
     * True if {@code pos} lies within the spray cone of some active sprinkler,
     * with an unobstructed vertical column between them.
     */
    public static boolean isSprayedAt(BlockGetter level, BlockPos pos) {
        SprinklerConfig config = SprinklerConfig.get();

        // Walk straight up from pos toward each potential button level. depth is
        // the vertical distance from a candidate button down to pos; it also
        // indexes the cone radius at pos's level.
        BlockPos.MutableBlockPos cursor = pos.mutable();

        for (int depth = 0; depth <= config.maxDepth; depth++) {
            int buttonY = pos.getY() + depth;

            // Any active button whose (x,z) is within radiusAtDepth(depth) of pos
            // covers pos at this depth. Scan that horizontal window at buttonY.
            int radius = config.radiusAtDepth(depth);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (isActiveSprinklerAt(level, pos.getX() + dx, buttonY, pos.getZ() + dz)) {
                        SprinklerDebug.log("SPRAYED {} by active sprinkler at ({},{},{}), depth {}",
                            pos, pos.getX() + dx, buttonY, pos.getZ() + dz, depth);
                        return true;
                    }
                }
            }

            // Move the vertical-column check up one block. If the block now
            // directly above pos's running column would stop water falling
            // through, no higher button can reach pos, so we can stop.
            if (depth < config.maxDepth) {
                cursor.set(pos.getX(), pos.getY() + depth + 1, pos.getZ());
                if (blocksDownwardSpray(level, cursor)) {
                    SprinklerDebug.log("no spray at {}: column blocked at {} ({}) after depth {}",
                        pos, cursor.immutable(), level.getBlockState(cursor).getBlock(), depth);
                    return false;
                }
            }
        }

        SprinklerDebug.log("no spray at {}: no active sprinkler within {} levels", pos, config.maxDepth);
        return false;
    }

    /** True if an active sprinkler button occupies the block at the given coordinates. */
    private static boolean isActiveSprinklerAt(BlockGetter level, int x, int y, int z) {
        BlockPos.MutableBlockPos buttonPos = new BlockPos.MutableBlockPos(x, y, z);
        BlockState state = level.getBlockState(buttonPos);

        if (!(state.getBlock() instanceof ButtonBlock))
            // Not a button, so it's not a sprinkler.
            return false;

        // Only ceiling-mounted buttons act as sprinkler heads.
        if (!state.hasProperty(FACE) || state.getValue(FACE) != AttachFace.CEILING) {
            SprinklerDebug.log("button at ({},{},{}) ignored: FACE={} (need CEILING)",
                x, y, z, state.hasProperty(FACE) ? state.getValue(FACE) : "<none>");
            return false;
        }

        // Check to see if the block above has a water supply.
        // Note, we rely on game mechanics for the block to be solid and capable
        // of supporting a button.
        BlockPos supportPos = buttonPos.above();
        boolean hasWater = hasWaterSupply(level, supportPos);
        SprinklerDebug.log("ceiling button at ({},{},{}): support {} is {}, water={}",
            x, y, z, supportPos, level.getBlockState(supportPos).getBlock(), hasWater);
        return hasWater;
    }

    /**
     * True if the block above the button feeds it water: either the block is
     * waterlogged, or it is has a water source one or two blocks above it.
     */
    private static boolean hasWaterSupply(BlockGetter level, BlockPos supportPos) {
        BlockState support = level.getBlockState(supportPos);

        // Is the block waterlogged (eg. leaves, stairs)?
        if (support.hasProperty(WATERLOGGED) && support.getValue(WATERLOGGED)) {
            SprinklerDebug.log("ceiling button support {} is {} and waterlogged", supportPos, level.getBlockState(supportPos).getBlock());
            return true;
        }

        // Is the block above it a water source (eg. water, waterlogged blocks)?
        BlockState oneabove = level.getBlockState(supportPos.above());
        if (oneabove.getFluidState().isSourceOfType(net.minecraft.world.level.material.Fluids.WATER)) {
            SprinklerDebug.log("ceiling button support {} is {} and above is {}: a water source", supportPos, level.getBlockState(supportPos).getBlock());
            return true;
        }

        // If it's solid, then is the block above _that_ a water source (eg. water, waterlogged blocks)?
        // This is a bit of a hack, but it's so we can have this sprinkler behaviour without
        // having client-side drips, as we can't set #minecraft:impermeable on individual
        // blocks, unfortunately.
        if (isSolidAsFarAsWeAreConcerned(oneabove)) {
            BlockState twoabove = level.getBlockState(supportPos.above().above());
            if (twoabove.getFluidState().isSourceOfType(net.minecraft.world.level.material.Fluids.WATER)) {
                SprinklerDebug.log("ceiling button support {} is {} and two above is {}: a water source", supportPos, level.getBlockState(supportPos).getBlock());
                return true;
            }
        }

        SprinklerDebug.log("ceiling button support {} is {} and above is {}: no water source", supportPos, level.getBlockState(supportPos).getBlock());
        return false;
    }

    /**
     * True if the block is solid enough to block downward spray or to pass water
     * through from a source.
     * 
     * Both BlockState::blocksMotion() and BlockState::isSolid() are deprecated
     * but vanilla rain uses the MOTION_BLOCKING heightmap which itself uses
     * blocksMotion(). Once there's a different rain implementation, we can
     * change this.
     */
    private static boolean isSolidAsFarAsWeAreConcerned(BlockState state) {
        @SuppressWarnings("deprecation") boolean blocksMotion = state.blocksMotion();
        return blocksMotion;
    }

    /**
     * True if the spray cannot pass down through this block, i.e. it acts as a
     * floor.
     *
     * We use vanilla rain's own blocker rule so the sprinkler shields the
     * same way rain does: a block stops the spray if it {@code blocksMotion()}
     * (has a solid collision box). Open trapdoors, string, torches, carpets and
     * flowers therefore let the spray through, while closed trapdoors, slabs,
     * stairs, fences and full blocks stop it.
     */
    private static boolean blocksDownwardSpray(BlockGetter level, BlockPos pos) {
        return isSolidAsFarAsWeAreConcerned(level.getBlockState(pos)); 
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
