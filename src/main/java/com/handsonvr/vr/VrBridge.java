package com.handsonvr.vr;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
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
    public record Pose(Vec3 position, Vec3 direction, Quaternionfc rotation) {}

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
        Vec3 dir = data.getDir();
        if (dir == null || dir.lengthSqr() < 1.0e-6) {
            dir = new Vec3(0, 0, 1);
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
            LocalPlayer player = Minecraft.getInstance().player;
            return player == null ? null : new Pose(player.getEyePosition(), player.getViewVector(1f), null);
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

    /** Quanto à frente do controle fica a "ponta do dedo" usada para tocar a mochila e o radial (m). */
    public static final double POINTER_OFFSET = 0.05;

    /** Ponta da mão (um pouco à frente do controle), para tocar espaços da mochila e do radial; null sem mão. */
    public static Vec3 pointer(int hand) {
        Pose pose = hand(hand);
        if (pose == null) {
            return null;
        }
        return pose.position().add(pose.direction().scale(POINTER_OFFSET * worldScale()));
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

    // ------------------------------------------------------------------ área de jogo

    /**
     * A área de jogo do Vivecraft (o "quarto") no mundo: {@code mundo = origem + giroY(yaw)·(quarto·escala)}, com o
     * quarto em metros. Andar, cair ou virar com o analógico move a área de jogo. Mexer a cabeça ou dar um passo de
     * verdade, não. O que fica preso a ela (o menu radial) acompanha o jogador sem fugir da mão.
     */
    public record RoomFrame(Vec3 origin, double yaw, double scale) {
        public Vec3 toWorld(Vec3 room) {
            return origin.add(rotate(room.scale(scale), yaw));
        }

        public Vec3 toRoom(Vec3 world) {
            return rotate(world.subtract(origin), -yaw).scale(1.0 / scale);
        }

        public Vec3 dirToWorld(Vec3 dir) {
            return rotate(dir, yaw);
        }

        public Vec3 dirToRoom(Vec3 dir) {
            return rotate(dir, -yaw);
        }

        /** Giro em volta do eixo Y, no mesmo sentido do Vivecraft (JOML {@code rotateY}). */
        private static Vec3 rotate(Vec3 v, double angle) {
            double c = Math.cos(angle), s = Math.sin(angle);
            return new Vec3(c * v.x + s * v.z, v.y, -s * v.x + c * v.z);
        }
    }

    /** A área de jogo do último quadro desenhado: a mesma das poses de {@link #hand}/{@link #pointer} nos ticks. */
    private static RoomFrame lastFrame;

    /**
     * A área de jogo no mundo agora (null fora do VR). Nos ticks é a do último quadro desenhado, porque as poses do
     * mundo que o Vivecraft entrega são as desse quadro.
     */
    public static RoomFrame roomFrame() {
        if (SimulatedVr.isEnabled()) {
            // VR de mentira: a área de jogo anda com o jogador e não gira.
            LocalPlayer player = Minecraft.getInstance().player;
            return player == null ? null : new RoomFrame(player.position(), 0, 1);
        }
        if (lastFrame == null) {
            lastFrame = computeFrame();
        }
        return lastFrame;
    }

    /** Chamado a cada quadro, antes de desenhar: guarda a área de jogo deste quadro. */
    public static void captureFrame() {
        if (!SimulatedVr.isEnabled()) {
            lastFrame = computeFrame();
        }
    }

    /**
     * A pose do quarto mais recente e a pose do mundo deste quadro vêm da mesma leitura do óculos (o Vivecraft lê os
     * controles uma vez por quadro, antes de montar a pose do mundo). Comparando a cabeça nas duas sai a área de jogo.
     */
    private static RoomFrame computeFrame() {
        if (!VIVECRAFT || !isVRActive()) {
            return null;
        }
        try {
            VRPose room = VRClientAPI.instance().getLatestRoomPose();
            VRPose world = renderPose();
            VRBodyPartData rh = room == null ? null : room.getHead(), wh = world == null ? null : world.getHead();
            if (rh == null || wh == null || rh.getPos() == null || wh.getPos() == null) {
                return null;
            }
            double yaw = yawBetween(rh, wh);
            double scale = worldScale();
            VRBodyPartData rm = room.getMainHand(), wm = world.getMainHand();
            if (rm != null && wm != null && rm.getPos() != null && wm.getPos() != null) {
                double roomDistance = rm.getPos().distanceTo(rh.getPos());
                if (roomDistance > 0.05) {
                    scale = wm.getPos().distanceTo(wh.getPos()) / roomDistance;
                }
            }
            Vec3 origin = wh.getPos().subtract(RoomFrame.rotate(rh.getPos().scale(scale), yaw));
            return new RoomFrame(origin, yaw, scale);
        } catch (Throwable t) {
            return null;
        }
    }

    /** O giro (em Y) que leva a parte do corpo do quarto para o mundo. */
    private static double yawBetween(VRBodyPartData room, VRBodyPartData world) {
        Quaternionfc qr = room.getRotation(), qw = world.getRotation();
        if (qr != null && qw != null) {
            Quaternionf q = new Quaternionf(qw).mul(new Quaternionf(qr).invert());
            return 2 * Math.atan2(q.y, q.w);
        }
        Vec3 dr = room.getDir(), dw = world.getDir();
        return Math.atan2(dw.x, dw.z) - Math.atan2(dr.x, dr.z);
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
