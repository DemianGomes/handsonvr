package com.handsonvr.holster;

import com.handsonvr.HandsOnConfig;
import com.handsonvr.vr.VrBridge;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Referencial do tronco a partir da cabeça (o Quest 2 só rastreia cabeça e mãos): o pescoço fica 20 cm abaixo e
 * 10 cm atrás dos olhos (os números do modelo de jogador do Vivecraft), a cintura 66 cm abaixo dos olhos (como o
 * cinto do CobbleVR). O tronco só gira quando a cabeça vira além de uma zona morta — olhar para o lado não leva os
 * coldres junto, como num corpo de verdade.
 */
public final class BodyFrame {
    private static final double NECK_BELOW_EYES = 0.20, NECK_BEHIND_EYES = 0.10, WAIST_BELOW_EYES = 0.66;
    private static final float YAW_DEADZONE = 35f;

    private float bodyYaw = 0f;
    private boolean initialized = false;
    private long lastNanos = 0L;

    /** Pontos do corpo num instante: pescoço, cintura, direção da frente e da direita (horizontais) e a escala. */
    public record Frame(Vec3d neck, Vec3d waist, Vec3d forward, Vec3d right, double scale, float yaw) {
        public Vec3d anchor(Holster h, HandsOnConfig config) {
            Vec3d base = h.shoulder ? neck : waist;
            double extraUp = h.shoulder ? config.shoulderHeightOffset : config.hipHeightOffset;
            return base.add(right.multiply(h.right * scale))
                    .add(0, (h.up + extraUp) * scale, 0)
                    .add(forward.multiply(h.forward * scale));
        }
    }

    /** Referencial atual (null fora do VR). Chamado em ticks e quadros; a suavização usa o relógio real. */
    public Frame compute() {
        VrBridge.Pose head = VrBridge.head();
        if (head == null) {
            return null;
        }
        double scale = VrBridge.worldScale();
        Vec3d dir = head.direction();
        float headYaw = dir.x * dir.x + dir.z * dir.z < 0.02 ? bodyYaw : (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        float yaw = updateYaw(headYaw);
        Vec3d forward = new Vec3d(-MathHelper.sin(yaw * MathHelper.RADIANS_PER_DEGREE), 0,
                MathHelper.cos(yaw * MathHelper.RADIANS_PER_DEGREE));
        Vec3d right = new Vec3d(-forward.z, 0, forward.x);
        // Para trás na horizontal da CABEÇA (olhando para baixo, os olhos vão para a frente do corpo).
        Vec3d headFlat = new Vec3d(dir.x, 0, dir.z);
        headFlat = headFlat.lengthSquared() < 1.0e-6 ? forward : headFlat.normalize();
        Vec3d eyes = head.position();
        Vec3d neck = eyes.add(0, -NECK_BELOW_EYES * scale, 0).subtract(headFlat.multiply(NECK_BEHIND_EYES * scale));
        Vec3d waist = new Vec3d(neck.x, eyes.y - WAIST_BELOW_EYES * scale, neck.z);
        return new Frame(neck, waist, forward, right, scale, yaw);
    }

    private float updateYaw(float headYaw) {
        long now = System.nanoTime();
        if (!initialized) {
            bodyYaw = headYaw;
            initialized = true;
            lastNanos = now;
            return bodyYaw;
        }
        float dt = Math.min(0.1f, (now - lastNanos) / 1.0e9f);
        if (dt < 0.001f) {
            return bodyYaw;
        }
        lastNanos = now;
        float diff = MathHelper.wrapDegrees(headYaw - bodyYaw);
        float excess = Math.abs(diff) - YAW_DEADZONE;
        if (excess > 0) {
            bodyYaw = MathHelper.wrapDegrees(bodyYaw + Math.signum(diff) * excess * Math.min(1f, dt * 6f));
        }
        return bodyYaw;
    }

    /** O jogador virou de vez (ex.: entrou no mundo): o tronco acompanha a cabeça na hora. */
    public void reset() {
        initialized = false;
    }
}
