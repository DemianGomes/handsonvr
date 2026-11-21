package com.handsonvr.radial;

import com.handsonvr.hands.HandsController;
import com.handsonvr.inv.InvOps;
import com.handsonvr.render.Draw;
import com.handsonvr.render.Sprite;
import com.handsonvr.vr.Panel;
import com.handsonvr.vr.VrBridge;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Desenha o menu radial com a cara da hotbar do jogo (padrão em docs/VISUAL.md): cada espaço é uma casa da hotbar
 * ({@code hud/hotbar}), a moldura de seleção ({@code hud/hotbar_selection}) fica no espaço sob a mão (sem a mão em
 * cima, no espaço que está na mão), o nome do item aparece na caixa de dica do jogo e o item da mão esquerda leva o
 * ícone do escudo, o mesmo do espaço da mão secundária no inventário. No centro, a "mão vazia" é uma casa vazia; sem
 * espaço livre para esvaziar a mão, mostra a barreira.
 */
public final class RadialRenderer {
    private RadialRenderer() {}

    private static final float PX = (float) RadialMenu.PX, CELL = (float) RadialMenu.CELL;
    /** Profundidades (m, para o rosto): casas, itens, quantidade, moldura de seleção, caixa de dica. */
    private static final float Z_CELL = 0.002f, Z_SELECTION = 0.018f, Z_TOOLTIP = 0.024f;
    /** A caixa de dica é um pouco menor que o resto (o texto fica com ~2 cm). */
    private static final float TOOLTIP_PX = PX * 0.8f;
    /** Contorno do ícone do escudo (mão esquerda), como a quantidade: branco com a sombra do jogo. */
    private static final int MARK = 0xFFFFFFFF, MARK_SHADOW = 0xFF3F3F3F;

    public static void render(LevelRenderContext context, HandsController hands) {
        Minecraft client = Minecraft.getInstance();
        RadialMenu radial = hands.radial();
        LocalPlayer p = client.player;
        if (!radial.isOpen() || p == null || client.level == null || !VrBridge.isVRActive()) {
            return;
        }
        Panel panel = radial.panel();
        PoseStack ms = context.poseStack();
        if (panel == null || ms == null || context.submitNodeCollector() == null) {
            return;
        }
        Draw d = new Draw(ms, context.submitNodeCollector());
        Vec3 camera = context.levelState().cameraRenderState.pos;
        int opener = radial.opener();
        ms.pushPose();
        Vec3 o = panel.origin();
        ms.translate(o.x - camera.x, o.y - camera.y, o.z - camera.z);
        ms.rotate(panel.rotation());
        float s = (float) panel.scale();
        ms.scale(s, s, s);

        int highlighted = radial.highlighted();
        int inHand = opener == 0 ? p.getInventory().getSelectedSlot() : hands.offSource() >= 0 ? hands.offSource()
                : RadialMenu.NONE;
        int framed = highlighted != RadialMenu.NONE ? highlighted : inHand;
        boolean canEmpty = hands.canEmpty(p, opener);

        Sprite cell = Sprite.hotbarCell();
        for (int i = RadialMenu.CENTER; i < 9; i++) {
            double[] c = RadialMenu.position(i);
            float x = (float) c[0], y = (float) c[1];
            if (cell != null) {
                d.spriteCentered(cell, x, y, CELL, Z_CELL, Draw.KEEP_ALPHA);
            } else {
                d.mcSlot(x, y, CELL, Z_CELL, false);
            }
            ItemStack stack = i == RadialMenu.CENTER ? (canEmpty ? ItemStack.EMPTY : new ItemStack(Items.BARRIER))
                    : hands.displayStack(p, InvOps.HOTBAR_START + i);
            // Como na hotbar do jogo: o item de 16 pixels no meio da casa e a quantidade no canto de um espaço de 18.
            float slot = 18 * PX, itemSize = slot * 0.85f, itemZ = 0.004f + itemSize * 0.1f;
            d.item(stack, x, y, itemZ, itemSize);
            if (i != RadialMenu.CENTER) {
                d.mcCount(stack, x, y, slot, itemZ + itemSize * 0.13f);
            }
            if (i >= 0 && i == hands.offSource()) {
                offhandMark(d, x - 8 * PX, y + 8 * PX, itemZ + itemSize * 0.13f);
            }
        }
        if (framed != RadialMenu.NONE) {
            double[] c = RadialMenu.position(framed);
            selection(d, (float) c[0], (float) c[1]);
        }
        if (highlighted != RadialMenu.NONE) {
            Component name = tooltip(hands, p, highlighted, canEmpty);
            if (name != null) {
                double[] c = RadialMenu.position(highlighted);
                d.mcTooltip(name, (float) c[0], (float) c[1] + CELL / 2f + 3 * PX, TOOLTIP_PX, Z_TOOLTIP);
            }
        }
        ms.popPose();
    }

    /** A moldura de seleção da hotbar (24x23): começa um pixel acima e à esquerda da casa e vai até a borda de baixo. */
    private static void selection(Draw d, float x, float y) {
        Sprite frame = Sprite.gui("hud/hotbar_selection");
        if (frame != null) {
            d.spriteCentered(frame, x, y + PX / 2f, 24 * PX, Z_SELECTION, Draw.KEEP_ALPHA);
        } else {
            d.frame(x - 12 * PX, y - 11 * PX, x + 12 * PX, y + 12 * PX, 2 * PX, Z_SELECTION, 0xFFFFFFFF);
        }
    }

    /** O ícone do escudo (espaço da mão secundária no inventário), pela metade, no canto de cima à esquerda. */
    private static void offhandMark(Draw d, float left, float top, float z) {
        Sprite shield = Sprite.gui("container/slot/shield");
        if (shield == null) {
            return;
        }
        float px = 8 * PX / shield.width();
        d.spriteSolid(shield, left + px, top - px, px, z, MARK_SHADOW);
        d.spriteSolid(shield, left, top, px, z + 0.0006f, MARK);
    }

    /** O texto da caixa de dica: o nome do item (com a cor da raridade) ou o da mão vazia; nada num espaço vazio. */
    private static Component tooltip(HandsController hands, LocalPlayer p, int target, boolean canEmpty) {
        if (target == RadialMenu.CENTER) {
            return Component.translatable(canEmpty ? "radial.handsonvr.empty" : "radial.handsonvr.empty_full");
        }
        ItemStack stack = hands.displayStack(p, InvOps.HOTBAR_START + target);
        return stack.isEmpty() ? null : stack.getStyledHoverName();
    }
}
