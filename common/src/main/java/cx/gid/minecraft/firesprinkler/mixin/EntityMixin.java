package cx.gid.minecraft.firesprinkler.mixin;

import cx.gid.minecraft.firesprinkler.SprinklerDebug;
import cx.gid.minecraft.firesprinkler.SprinklerCone;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Extinguishes burning mobs (and players) standing in a sprinkler's spray, the
 * same way rain does.
 *
 * {@code Entity.isInRain()} is what the entity tick consults to decide
 * whether to {@code clearFire()} and play the fire-extinguish hiss. By making
 * it return {@code true} for a burning entity inside an active cone, we reuse
 * vanilla's rain extinguishing wholesale - including its sound.
 *
 * We only override when the entity is actually on fire, so a sprinkler never
 * makes a dry mob "wet" for any other rain-driven behaviour.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {

    @Inject(method = "isInRain", at = @At("HEAD"), cancellable = true)
    private void firesprinkler$extinguishBurningEntity(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity)(Object)this;
        Level level = self.level();

        // Only deal with this server-side
        if (level.isClientSide())
            return;

        // Only deal with this entity if it's on fire
        if (!self.isOnFire())
            return;

        // Mirror vanilla isInRain()'s two sample points: the entity's feet and
        // the top of its bounding box.
        BlockPos feet = self.blockPosition();
        BlockPos head = BlockPos.containing(feet.getX(), self.getBoundingBox().maxY, feet.getZ());

        if (SprinklerCone.isSprayedAt(level, feet) || SprinklerCone.isSprayedAt(level, head)) {
            SprinklerDebug.log("doused burning {} at {}", self.getType(), feet);
            SprinklerCone.spawnSteam(level, self.getX(), self.getY() + self.getBbHeight() * 0.5, self.getZ());
            cir.setReturnValue(true);
        }
    }
}
