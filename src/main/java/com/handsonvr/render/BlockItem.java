package com.handsonvr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Itens apoiados num bloco do mundo (a grade da mesa de trabalho, as fornalhas), padrão em docs/VISUAL.md. */
public final class BlockItem {
    private BlockItem() {}

    /** Altura do ícone apoiado (o que fica por cima dele, como a quantidade, começa aqui). */
    public static final float FLAT_TOP = 0.006f;

    /**
     * Um item apoiado na face do bloco, no plano atual da pilha (x, y na face; z para fora dela): o ícone (modelo de
     * inventário, achatado) com a frente para fora e o topo do desenho para +y, de {@code flatSize} metros; um bloco
     * vira um cubo (o modelo "fixo", como num suporte) de {@code blockSize / 2} apoiado na face. Retorna a altura do
     * que ficou desenhado acima da face.
     */
    public static float lying(Draw d, Minecraft client, ItemStack stack, float x, float y, int light, float flatSize,
            float blockSize) {
        ItemStackRenderState state = new ItemStackRenderState();
        client.getItemModelResolver().updateForTopItem(state, stack, ItemDisplayContext.GUI, client.level, null, 0);
        if (state.isEmpty()) {
            return FLAT_TOP;
        }
        if (state.usesBlockLight()) {
            // Bloco: o modelo "fixo" é um cubo de meio tamanho, centrado; sobe meio cubo.
            d.item(stack, ItemDisplayContext.FIXED, x, y, blockSize * 0.25f + 0.001f, blockSize, light);
            return blockSize * 0.5f + 0.001f; // o topo do cubo
        }
        PoseStack ms = d.pose();
        ms.pushPose();
        ms.translate(x, y, 0.004f);
        ms.scale(flatSize, flatSize, flatSize * 0.2f);
        state.submit(ms, d.collector(), light, OverlayTexture.NO_OVERLAY, 0);
        ms.popPose();
        return FLAT_TOP;
    }
}
