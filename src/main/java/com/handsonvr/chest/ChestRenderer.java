package com.handsonvr.chest;

import com.handsonvr.hands.HandsController;
import com.handsonvr.render.Draw;
import com.handsonvr.vr.Panel;
import com.handsonvr.vr.VrBridge;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * A janela do baú aberto (padrão "Janela" de docs/VISUAL.md, como a mochila): o fundo e os espaços do inventário do
 * jogo, os itens e as quantidades, e o espaço sob a mão claro. Fica acima do bloco, virada para o rosto.
 */
public final class ChestRenderer {
    private ChestRenderer() {}

    public static void render(LevelRenderContext context, HandsController hands) {
        ChestStation chest = hands.chest();
        PoseStack ms = context.poseStack();
        Panel panel = chest.isOpen() ? chest.panel() : null;
        if (panel == null || ms == null || context.submitNodeCollector() == null || !VrBridge.isVRActive()) {
            return;
        }
        Vec3 camera = context.levelState().cameraRenderState.pos;
        Draw d = new Draw(ms, context.submitNodeCollector());
        float slot = (float) ChestStation.SLOT, px = slot / 18f, pad = (float) ChestStation.PAD;
        float halfW = 4.5f * slot + pad, halfH = chest.rows() * slot / 2f + pad;
        ms.pushPose();
        Vec3 o = panel.origin();
        ms.translate(o.x - camera.x, o.y - camera.y, o.z - camera.z);
        ms.rotate(panel.rotation());
        d.mcWindow(-halfW, -halfH, halfW, halfH, px, 0f);
        for (int i = 0; i < chest.size(); i++) {
            double[] c = chest.slotCenter(i);
            boolean hovered = chest.hover(0) == i || chest.hover(1) == i;
            d.mcSlot((float) c[0], (float) c[1], slot, 0.001f, hovered);
            ItemStack stack = chest.stack(i);
            if (!stack.isEmpty()) {
                d.item(stack, (float) c[0], (float) c[1], 0.003f, slot * 16f / 18f);
                d.mcCount(stack, (float) c[0], (float) c[1], slot, 0.006f);
            }
        }
        ms.popPose();
    }
}
