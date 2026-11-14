package com.handsonvr.holster;

import com.handsonvr.HandsOnConfig;

/**
 * Os coldres no corpo. Posição no referencial do tronco (metros, escala real): {@code right} para a direita do
 * corpo, {@code up} acima do ponto de referência (pescoço nos ombros, cintura nos quadris), {@code forward} para a
 * frente. {@code radius} = alcance da mão.
 */
public enum Holster {
    // Ombros: atrás e um pouco acima do ombro, onde a mão chega ao "puxar a espada das costas".
    RIGHT_SHOULDER(true, 0.17, 0.02, -0.11, 0.16),
    LEFT_SHOULDER(true, -0.17, 0.02, -0.11, 0.16),
    // Quadris: dos lados da cintura, um pouco para trás (o cinto de Pokébolas do CobbleVR fica na frente).
    RIGHT_HIP(false, 0.23, -0.04, -0.04, 0.14),
    LEFT_HIP(false, -0.23, -0.04, -0.04, 0.14);

    public final boolean shoulder;
    public final double right, up, forward, radius;

    Holster(boolean shoulder, double right, double up, double forward, double radius) {
        this.shoulder = shoulder;
        this.right = right;
        this.up = up;
        this.forward = forward;
        this.radius = radius;
    }

    public boolean enabled(HandsOnConfig config) {
        if (!config.holsters) {
            return false;
        }
        return switch (this) {
            case RIGHT_SHOULDER -> config.rightShoulder;
            case LEFT_SHOULDER -> config.leftShoulder;
            case RIGHT_HIP -> config.rightHip;
            case LEFT_HIP -> config.leftHip;
        };
    }

    /** Chave de tradução do nome (tela de ajustes). */
    public String translationKey() {
        return "holster.handsonvr." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
