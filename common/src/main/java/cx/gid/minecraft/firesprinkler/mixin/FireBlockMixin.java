package cx.gid.minecraft.firesprinkler.mixin;

import cx.gid.minecraft.firesprinkler.SprinklerCone;
import cx.gid.minecraft.firesprinkler.SprinklerConfig;
import cx.gid.minecraft.firesprinkler.SprinklerDebug;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.LevelReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Extinguishes fire blocks sitting under an active sprinkler, independent of the
 * weather.
 *
 * Vanilla's rain-extinguish branch in {@code FireBlock.tick} is gated behind
 * {@code level.isRaining()}: when the sky is clear it never even consults
 * {@code isRainingAt}, so a hook on {@code Level.isRainingAt} alone can never put
 * a fire out under a roof (which is exactly where a sprinkler lives). Instead we
 * run our own extinguish check at the top of {@code tick}.
 *
 * Vanilla rain rolls {@code 0.2 + age*0.03}, and slower still because a rained
 * fire never ages. A sprinkler is meant to douse briskly, so we use a
 * configurable flat {@link SprinklerConfig#extinguishChance} instead. 
 * 
 * Burning entities do not go through here at all: {@code Entity.tick} consults 
 * {@code isInRain} every tick, so mobs are doused within one tick of entering 
 * the spray.)
 *
 * Each successful extinguish plays level event {@code 1009}
 * ({@code FIRE_EXTINGUISH} sound) plus a puff of white {@code CLOUD} steam,
 * which vanilla rain does silently, so it reads as water hitting flame.
 */
@Mixin(FireBlock.class)
public abstract class FireBlockMixin {

    private static final IntegerProperty FIRESPRINKLER$AGE = BlockStateProperties.AGE_15;

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;scheduleTick(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;I)V",
            shift = At.Shift.AFTER
        ),
        cancellable = true
    )
    private void firesprinkler$extinguishUnderSprinkler(
            BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        if (!SprinklerCone.isSprayedAt(level, pos)) {
            return;
        }

        SprinklerConfig config = SprinklerConfig.get();
        int age = state.hasProperty(FIRESPRINKLER$AGE) ? state.getValue(FIRESPRINKLER$AGE) : 0;

        // extinguish_chance == 0 means "behave exactly like vanilla rain": use
        // its per-tick probability instead of a flat chance.
        double chance = config.extinguishChance == 0.0
            ? 0.2 + age * 0.03
            : config.extinguishChance;

        if (random.nextFloat() < chance) {
            SprinklerDebug.log("fizzle: removing sprinkler-covered fire at {} (age {}, chance {})",
                pos, age, chance);

            // 1009 (data 0) = FIRE_EXTINGUISH sound with no particles; we add our
            // own white CLOUD steam so it reads as water-on-flame, not sooty smoke.
            level.levelEvent(null, 1009, pos, 0);
            SprinklerCone.spawnSteam(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            level.removeBlock(pos, false);

            // Cancel the rest of vanilla's tick: otherwise it runs its aging
            // branch and re-places the fire block we just removed.
            ci.cancel();
            return;
        }

        SprinklerDebug.log("sprinkler over fire at {} (age {}, chance {}): missed this check",
            pos, age, chance);

        // Skip vanilla's aging and spread for this tick, as rain does. 
        // Vanilla already scheduled the next tick (30-39 away) before we were 
        // called.
        ci.cancel();
    }

    /**
     * Reports zero ignition odds for a cell under an active sprinkler, so no
     * burning neighbour can spread fire back into the sprayed area. Targets the
     * {@code getIgniteOdds(LevelReader, BlockPos)} probe used by the spread loop
     * in {@code tick}; every other use passes through unchanged.
     */
    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/FireBlock;getIgniteOdds(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)I"
        )
    )
    private int firesprinkler$noSpreadIntoSpray(FireBlock self, LevelReader level, BlockPos testPos) {
        if (SprinklerCone.isSprayedAt(level, testPos)) {
            return 0;
        }
        return firesprinkler$invokeGetIgniteOdds(level, testPos);
    }

    @org.spongepowered.asm.mixin.gen.Invoker("getIgniteOdds")
    protected abstract int firesprinkler$invokeGetIgniteOdds(LevelReader level, BlockPos pos);
}
