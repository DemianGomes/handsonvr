package com.handsonvr.render;

import com.handsonvr.hands.HandsController;
import com.handsonvr.vr.Panel;
import com.handsonvr.vr.VrBridge;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Onde pôr a comida na fogueira (padrão em docs/VISUAL.md): com comida crua na mão perto de uma fogueira, um espaço do
 * inventário do jogo flutua sobre ela, claro quando a mão está no lugar certo. Encostar ali põe um item.
 */
public final class CampfireHint {
    private CampfireHint() {}

    private static final float SLOT = 0.12f;
    private static final double NEAR = 1.0;

    public static void render(LevelRenderContext context, HandsController hands) {
        Minecraft client = Minecraft.getInstance();
        PoseStack ms = context.poseStack();
        VrBridge.Pose head = VrBridge.head();
        if (client.player == null || client.level == null || ms == null || context.submitNodeCollector() == null
                || !VrBridge.isVRActive() || head == null) {
            return;
        }
        Vec3 camera = context.levelState().cameraRenderState.pos;
        Draw d = new Draw(ms, context.submitNodeCollector());
        for (int hand = 0; hand < 2; hand++) {
            Vec3 tip = VrBridge.pointer(hand);
            ItemStack held = hand == 0 ? client.player.getMainHandItem() : client.player.getOffhandItem();
            if (tip == null || hands.isHandHidden(hand) || !HandsController.isCampfireFood(client, held)) {
                continue;
            }
            BlockHitResult in = HandsController.campfireUnder(client, tip);
            BlockPos fire = in != null ? in.getBlockPos() : nearest(client, tip);
            if (fire == null) {
                continue;
            }
            Vec3 at = Vec3.atBottomCenterOf(fire).add(0, 0.62, 0);
            Panel p = Panel.facing(at, head.position(), 0, 1.0);
            ms.pushPose();
            ms.translate(at.x - camera.x, at.y - camera.y, at.z - camera.z);
            ms.rotate(p.rotation());
            d.mcSlot(0f, 0f, SLOT, 0f, in != null);
            ms.popPose();
        }
    }

    private static BlockPos nearest(Minecraft client, Vec3 tip) {
        BlockPos base = BlockPos.containing(tip), best = null;
        double bestDistance = NEAR;
        for (BlockPos q : BlockPos.betweenClosed(base.offset(-1, -2, -1), base.offset(1, 0, 1))) {
            if (client.level.getBlockState(q).getBlock() instanceof CampfireBlock) {
                double distance = Vec3.atCenterOf(q).distanceTo(tip);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = q.immutable();
                }
            }
        }
        return best;
    }
}
