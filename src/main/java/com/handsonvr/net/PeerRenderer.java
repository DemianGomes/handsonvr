package com.handsonvr.net;

import com.handsonvr.holster.Holster;
import com.handsonvr.render.Draw;
import com.handsonvr.vr.VrBridge;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Os coldres e a mochila dos OUTROS jogadores em VR ({@link PeerSync}): os itens guardados nos ombros e nos quadris,
 * como no próprio corpo, e uma bolsa (o bundle do jogo) na mão de quem está com a mochila aberta. Aparece para quem
 * tem o HandsOn, em VR ou não (os visuais de blocos, como a fornalha, continuam só para quem está em VR).
 */
public final class PeerRenderer {
    private PeerRenderer() {}

    private static final double RANGE = 64;
    /** Onde ficam os coldres no corpo de outro jogador (m, a partir dos pés): ombros e quadris, à direita/esquerda. */
    private static final double SHOULDER_Y = 1.36, SHOULDER_SIDE = 0.17, SHOULDER_BACK = 0.14, HIP_Y = 0.88, HIP_SIDE = 0.27;
    private static final float ITEM_SIZE = 0.42f, BAG_SIZE = 0.5f;

    public static void render(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        PoseStack ms = context.poseStack();
        if (client.level == null || client.player == null || ms == null || context.submitNodeCollector() == null) {
            return;
        }
        Vec3 camera = context.levelState().cameraRenderState.pos;
        Draw d = new Draw(ms, context.submitNodeCollector());
        float partial = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        for (Player other : client.level.players()) {
            if (other == client.player || other.isInvisible() || other.distanceTo(client.player) > RANGE) {
                continue;
            }
            PeerSync.State state = PeerSync.peer(other.getUUID());
            if (!state.vr()) {
                continue;
            }
            Vec3 feet = other.getPosition(partial);
            float yaw = other.yBodyRot;
            double rad = Math.toRadians(yaw);
            Vec3 forward = new Vec3(-Math.sin(rad), 0, Math.cos(rad)), right = new Vec3(-Math.cos(rad), 0, -Math.sin(rad));
            double crouch = other.isCrouching() ? -0.25 : 0;
            Holster[] order = Holster.values();
            for (int i = 0; i < order.length && i < state.holsters().size(); i++) {
                ItemStack stack = state.holsters().get(i);
                if (stack.isEmpty()) {
                    continue;
                }
                Holster h = order[i];
                boolean shoulder = h == Holster.RIGHT_SHOULDER || h == Holster.LEFT_SHOULDER;
                boolean rightSide = h == Holster.RIGHT_SHOULDER || h == Holster.RIGHT_HIP;
                Vec3 at = feet.add(0, (shoulder ? SHOULDER_Y : HIP_Y) + crouch, 0)
                        .add(right.scale((rightSide ? 1 : -1) * (shoulder ? SHOULDER_SIDE : HIP_SIDE)))
                        .add(forward.scale(shoulder ? -SHOULDER_BACK : 0));
                item(d, ms, camera, client, stack, at, yaw, shoulder ? (rightSide ? -45f : 45f) : 0f, ITEM_SIZE);
            }
            if (state.backpackHand() >= 0) {
                Vec3 hand = Viv.hand(other, state.backpackHand());
                if (hand == null) {
                    hand = feet.add(0, 1.05 + crouch, 0).add(forward.scale(0.35));
                }
                item(d, ms, camera, client, new ItemStack(Items.BUNDLE), hand.add(0, -0.12, 0), yaw, 0f, BAG_SIZE);
            }
        }
    }

    private static void item(Draw d, PoseStack ms, Vec3 camera, Minecraft client, ItemStack stack, Vec3 at, float yaw, float roll,
            float size) {
        int light = LightCoordsUtil.getLightCoords(client.level, BlockPos.containing(at));
        ms.pushPose();
        ms.translate(at.x - camera.x, at.y - camera.y, at.z - camera.z);
        ms.rotate(Axis.YP.rotationDegrees(-yaw));
        ms.rotate(Axis.ZP.rotationDegrees(roll));
        d.item(stack, ItemDisplayContext.FIXED, 0f, 0f, 0f, size, light);
        ms.popPose();
    }

    /** A mão de outro jogador em VR, pelo Vivecraft (null sem o Vivecraft ou sem a pose dele). */
    private static final class Viv {
        static Vec3 hand(Player player, int hand) {
            if (!VrBridge.isVivecraftLoaded()) {
                return null;
            }
            try {
                var api = org.vivecraft.api.VRAPI.instance();
                if (!api.isVRPlayer(player)) {
                    return null;
                }
                var pose = api.getVRPose(player);
                var data = pose == null ? null : pose.getBodyPartData(hand == 0
                        ? org.vivecraft.api.data.VRBodyPart.MAIN_HAND : org.vivecraft.api.data.VRBodyPart.OFF_HAND);
                return data == null ? null : data.getPos();
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
