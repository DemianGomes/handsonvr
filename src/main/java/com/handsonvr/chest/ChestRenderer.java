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
 * jogo, os itens e as quantidades, e o espaço sob a mão claro. No baú, no fundo escuro da caixa aberta, deitada; no barril
 * e na caixa de shulker, deitada na face da tampa ({@link ChestStation#surface}).
 */
public final class ChestRenderer {
    private ChestRenderer() {}

    public static void render(LevelRenderContext context, HandsController hands) {
        ChestStation chest = hands.chest();
        PoseStack ms = context.poseStack();
        Panel panel = chest.isShown() ? chest.surface() : null; // (suspenso também: o que tinha)
        if (panel == null || ms == null || context.submitNodeCollector() == null || !VrBridge.isVRActive()) {
            return;
        }
        Vec3 camera = context.levelState().cameraRenderState.pos;
        Draw d = new Draw(ms, context.submitNodeCollector());
        float slot = (float) chest.slot(), px = slot / 18f, pad = px * 3f;
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
                float itemSize = slot * 16f / 18f;
                d.item(stack, (float) c[0], (float) c[1], 0.003f, itemSize);
                d.mcCount(stack, (float) c[0], (float) c[1], slot, 0.003f + itemSize * 0.13f); // à frente do bloco 3D (areia, pedra)
            }
        }
        if (chest.isOpen()) {
            // "Organizar" (A-Z), abaixo da grade.
            double[] b = chest.sortButton();
            boolean on = chest.onSortButton(VrBridge.pointer(0)) || chest.onSortButton(VrBridge.pointer(1));
            d.mcSlot((float) b[0], (float) b[1], (float) (b[2] * 2), 0.001f, on);
            d.text("A-Z", (float) b[0], (float) b[1], 0.003f, (float) (b[2] * 0.7), Draw.MC_LABEL, true);
        }
        ms.popPose();
        // A dica do item sob cada mão, em pé acima do espaço e virada para o rosto (o baú é deitado).
        VrBridge.Pose head = VrBridge.head();
        for (int hand = 0; hand < 2 && head != null; hand++) {
            if (chest.isOpen() && chest.onSortButton(VrBridge.pointer(hand))) {
                double[] b = chest.sortButton();
                Vec3 at = panel.world(b[0], b[1], 0.08);
                Panel face = Panel.facing(at, head.position(), 0, 1.0);
                ms.pushPose();
                ms.translate(at.x - camera.x, at.y - camera.y, at.z - camera.z);
                ms.rotate(face.rotation());
                d.mcTooltip(net.minecraft.network.chat.Component.translatable("button.handsonvr.sort"), 0f, 0f, 0.0022f, 0f);
                ms.popPose();
                continue;
            }
            int i = chest.hover(hand);
            ItemStack stack = chest.stack(i);
            if (i < 0 || stack.isEmpty()) {
                continue;
            }
            double[] c = chest.slotCenter(i);
            Vec3 at = panel.world(c[0], c[1], 0.12);
            Panel face = Panel.facing(at, head.position(), 0, 1.0);
            ms.pushPose();
            ms.translate(at.x - camera.x, at.y - camera.y, at.z - camera.z);
            ms.rotate(face.rotation());
            d.mcTooltip(Draw.itemTooltip(stack), 0f, 0f, 0.0022f, 0f);
            ms.popPose();
        }
    }
}
