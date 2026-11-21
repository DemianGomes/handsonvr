package com.handsonvr.hands;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/**
 * A força real do arremesso, só no singleplayer. Lá o servidor é o do próprio jogo do jogador (o mod está nele), então
 * dá para mudar a velocidade do item pela força do gesto ({@code ProjectileThrowMixin}). Num servidor remoto, o
 * servidor não roda este código: a velocidade é sempre a do jogo.
 *
 * <p>O cliente "arma" a força logo antes de mandar o "usar item"; o servidor do singleplayer (outra thread do mesmo
 * jogo) a usa no próximo arremesso desse jogador, uma vez só, e só se for logo em seguida.
 */
public final class ThrowPower {
    private ThrowPower() {}

    private static final long FRESH = 1_000_000_000L;

    private static volatile UUID player;
    private static volatile float factor = 1f;
    private static volatile long armedAt;
    /** Teste automático: a velocidade do último arremesso de um jogador (blocos por tick), já com a força. */
    public static volatile float lastVelocity = -1f;

    public static void arm(UUID id, float power) {
        factor = power;
        armedAt = System.nanoTime();
        player = id;
    }

    public static void disarm() {
        player = null;
    }

    /** No servidor: a velocidade do arremesso, com a força armada para este jogador (se houver). */
    public static float apply(LivingEntity shooter, float velocity) {
        if (!(shooter instanceof Player)) {
            return velocity;
        }
        UUID id = player;
        float v = velocity;
        if (id != null && id.equals(shooter.getUUID()) && System.nanoTime() - armedAt < FRESH) {
            player = null;
            v = velocity * factor;
        }
        lastVelocity = v;
        return v;
    }
}
