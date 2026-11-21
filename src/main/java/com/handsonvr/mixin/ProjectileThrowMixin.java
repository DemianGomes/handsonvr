package com.handsonvr.mixin;

import com.handsonvr.hands.ThrowPower;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Força real do arremesso (só no singleplayer, {@link ThrowPower}): todos os itens arremessáveis do jogo saem por
 * {@code Projectile.spawnProjectileFromRotation(fábrica, mundo, item, quem jogou, ajuste da mira, velocidade,
 * imprecisão)}. Aqui a velocidade é multiplicada pela força do gesto, se o cliente acabou de armá-la para esse jogador.
 * Só roda no servidor que tem o mod: o do singleplayer.
 */
@Mixin(Projectile.class)
public abstract class ProjectileThrowMixin {
    @ModifyVariable(method = "spawnProjectileFromRotation", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private static float handsonvr$throwPower(float velocity, @Local(argsOnly = true) LivingEntity shooter) {
        return ThrowPower.apply(shooter, velocity);
    }
}
