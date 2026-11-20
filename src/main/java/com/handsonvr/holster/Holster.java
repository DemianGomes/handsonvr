package com.handsonvr.holster;

import com.handsonvr.HandsOnConfig;
import net.minecraft.world.phys.Vec3;

/**
 * As zonas do corpo. Centro no referencial do tronco (metros, escala real): {@code right} para a direita do corpo,
 * {@code up} acima do ponto de referência (pescoço nos ombros, cintura nos quadris), {@code forward} para a frente.
 *
 * <p>A área de pegar é um <b>elipsoide</b> com semieixos próprios (direita, cima, frente), e não uma esfera pequena:
 * no ombro a mão chega por trás, por cima ou pelo lado ("puxar a espada das costas") sem posição exata. O ombro
 * esquerdo é a mochila quando ela está ligada.
 */
public enum Holster {
    // Ombros: atrás e acima do ombro; vai do pescoço até um pouco acima da cabeça e da nuca até o plano do rosto.
    RIGHT_SHOULDER(true, 0.16, 0.04, -0.10, 0.17, 0.22, 0.19),
    LEFT_SHOULDER(true, -0.16, 0.04, -0.10, 0.17, 0.22, 0.19),
    // Quadris: dos lados da cintura, um pouco à frente ou atrás (o cinto de Pokébolas do CobbleVR fica na frente).
    RIGHT_HIP(false, 0.22, -0.02, -0.02, 0.14, 0.17, 0.16),
    LEFT_HIP(false, -0.22, -0.02, -0.02, 0.14, 0.17, 0.16);

    /** A mão sai da zona só quando passa deste múltiplo da área de entrada (não fica piscando na borda). */
    public static final double LEAVE_FACTOR = 1.25;

    public final boolean shoulder;
    public final double right, up, forward;
    public final double halfRight, halfUp, halfForward;

    Holster(boolean shoulder, double right, double up, double forward, double halfRight, double halfUp, double halfForward) {
        this.shoulder = shoulder;
        this.right = right;
        this.up = up;
        this.forward = forward;
        this.halfRight = halfRight;
        this.halfUp = halfUp;
        this.halfForward = halfForward;
    }

    /** Coldre ligado (guarda itens). O ombro esquerdo deixa de ser coldre quando a mochila está ligada. */
    public boolean enabled(HandsOnConfig config) {
        if (!config.holsters || isBackpack(config)) {
            return false;
        }
        return switch (this) {
            case RIGHT_SHOULDER -> config.rightShoulder;
            case LEFT_SHOULDER -> config.leftShoulder;
            case RIGHT_HIP -> config.rightHip;
            case LEFT_HIP -> config.leftHip;
        };
    }

    /** Esta zona é a da mochila (ombro esquerdo com a mochila ligada). */
    public boolean isBackpack(HandsOnConfig config) {
        return this == LEFT_SHOULDER && config.backpack;
    }

    /** A zona responde ao grip (coldre ligado ou mochila). */
    public boolean active(HandsOnConfig config) {
        return enabled(config) || isBackpack(config);
    }

    /**
     * Distância "normalizada" da mão ao centro da zona: 1 = na borda do elipsoide, menos que 1 = dentro.
     */
    public double normalizedDistance(BodyFrame.Frame frame, Vec3 hand, HandsOnConfig config) {
        Vec3 d = hand.subtract(frame.anchor(this, config));
        double s = frame.scale();
        double r = d.dot(frame.right()) / (halfRight * s);
        double u = d.y / (halfUp * s);
        double f = d.dot(frame.forward()) / (halfForward * s);
        return Math.sqrt(r * r + u * u + f * f);
    }

    /** Chave de tradução do nome (tela de ajustes). */
    public String translationKey() {
        return "holster.handsonvr." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
