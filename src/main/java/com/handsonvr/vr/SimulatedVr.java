package com.handsonvr.vr;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * "VR de mentira" usado SOMENTE pelo teste automático (dev.SelfTest): finge que o VR está ativo e deixa o teste
 * posicionar as mãos. A cabeça usa os olhos do jogador. No jogo de verdade fica sempre desligado.
 */
public final class SimulatedVr {
    private SimulatedVr() {}

    private static volatile boolean enabled = false;
    private static final Vec3[] handPos = new Vec3[2];
    private static final Vec3[] handDir = new Vec3[2];
    /** Quantas vibrações cada mão recebeu (o teste confere a resposta tátil). */
    private static final int[] pulses = new int[2];

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /** Coloca a mão (0 = principal, 1 = secundária) num ponto, apontando para uma direção (null tira a mão). */
    public static void setHand(int hand, Vec3 position, Vec3 direction) {
        handPos[hand] = position;
        handDir[hand] = direction == null || direction.lengthSqr() < 1.0e-6 ? new Vec3(0, 0, 1) : direction.normalize();
    }

    static VrBridge.Pose handPose(int hand) {
        Vec3 pos = handPos[hand], dir = handDir[hand];
        if (pos == null || dir == null) {
            return null;
        }
        // Rotação que leva a "frente" do controle (-Z) para a direção apontada.
        Quaternionf rotation = new Quaternionf().rotationTo(new Vector3f(0, 0, -1),
                new Vector3f((float) dir.x, (float) dir.y, (float) dir.z));
        return new VrBridge.Pose(pos, dir, rotation);
    }

    private static final boolean[] grip = new boolean[2];

    /** Finge o grip da mão apertado/solto. */
    public static void setGrip(int hand, boolean pressed) {
        grip[hand] = pressed;
    }

    public static boolean gripHeld(int hand) {
        return grip[hand];
    }

    private static final boolean[] menuButton = new boolean[2];

    /** Finge o botão do menu radial da mão (B = principal, X = secundária) apertado/solto. */
    public static void setMenuButton(int hand, boolean pressed) {
        menuButton[hand] = pressed;
    }

    public static boolean menuButtonHeld(int hand) {
        return menuButton[hand];
    }

    static void recordPulse(int hand, float seconds) {
        if (enabled && hand >= 0 && hand < 2) {
            pulses[hand]++;
        }
    }

    public static int pulseCount(int hand) {
        return pulses[hand];
    }
}
