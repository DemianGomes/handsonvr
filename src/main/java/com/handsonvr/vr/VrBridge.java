package com.handsonvr.vr;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.vivecraft.api.client.VRClientAPI;
import org.vivecraft.api.data.VRBodyPart;
import org.vivecraft.api.data.VRBodyPartData;
import org.vivecraft.api.data.VRPose;

/**
 * Ponte com o Vivecraft (só a API pública, org.vivecraft.api). Sem Vivecraft ou fora do VR, as poses são null e
 * nada do mod age — exceto no teste automático, em que {@link SimulatedVr} finge um VR (mãos posicionadas pelo teste,
 * cabeça = olhos do jogador).
 *
 * Mão: 0 = principal (no Vivecraft, normalmente o controle direito), 1 = secundária.
 */
public final class VrBridge {
    private VrBridge() {}

    private static final boolean VIVECRAFT = FabricLoader.getInstance().isModLoaded("vivecraft");

    /** Posição, direção para onde aponta e rotação completa (null quando não há). */
    public record Pose(Vec3d position, Vec3d direction, Quaternionfc rotation) {}

    public static boolean isVivecraftLoaded() {
        return VIVECRAFT;
    }

    public static boolean isVRActive() {
        if (SimulatedVr.isEnabled()) {
            return true;
        }
        if (!VIVECRAFT) {
            return false;
        }
        try {
            return VRClientAPI.instance().isVRActive();
        } catch (Throwable t) {
            return false;
        }
    }

    private static VRPose renderPose() {
        try {
            return VRClientAPI.instance().getWorldRenderPose();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Pose toPose(VRBodyPartData data) {
        Vec3d dir = data.getDir();
        if (dir == null || dir.lengthSquared() < 1.0e-6) {
            dir = new Vec3d(0, 0, 1);
        }
        Quaternionfc rotation = null;
        try {
            Quaternionfc r = data.getRotation();
            if (r != null) {
                rotation = new Quaternionf(r);
            }
        } catch (Throwable ignored) {
        }
        return new Pose(data.getPos(), dir.normalize(), rotation);
    }

    /** Cabeça (olhos) no mundo; null fora do VR. */
    public static Pose head() {
        if (SimulatedVr.isEnabled()) {
            ClientPlayerEntity player = MinecraftClient.getInstance().player;
            return player == null ? null : new Pose(player.getEyePos(), player.getRotationVec(1f), null);
        }
        if (!isVRActive()) {
            return null;
        }
        try {
            VRPose pose = renderPose();
            VRBodyPartData head = pose == null ? null : pose.getHead();
            return head == null || head.getPos() == null ? null : toPose(head);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Mão no mundo; null fora do VR ou sem rastreio. */
    public static Pose hand(int hand) {
        if (SimulatedVr.isEnabled()) {
            return SimulatedVr.handPose(hand);
        }
        if (!isVRActive()) {
            return null;
        }
        try {
            VRPose pose = renderPose();
            VRBodyPartData data = pose == null ? null : pose.getBodyPartData(part(hand));
            return data == null || data.getPos() == null ? null : toPose(data);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Escala do mundo do Vivecraft (1 = tamanho real). */
    public static float worldScale() {
        if (SimulatedVr.isEnabled() || !isVRActive()) {
            return 1f;
        }
        try {
            return VRClientAPI.instance().getWorldScale();
        } catch (Throwable t) {
            return 1f;
        }
    }

    /** Vibra o controle da mão por {@code seconds}. */
    public static void pulse(int hand, float seconds) {
        SimulatedVr.recordPulse(hand, seconds);
        if (!VIVECRAFT || SimulatedVr.isEnabled() || !isVRActive()) {
            return;
        }
        try {
            VRClientAPI.instance().triggerHapticPulse(part(hand), seconds);
        } catch (Throwable ignored) {
        }
    }

    public static VRBodyPart part(int hand) {
        return hand == 0 ? VRBodyPart.MAIN_HAND : VRBodyPart.OFF_HAND;
    }
}
