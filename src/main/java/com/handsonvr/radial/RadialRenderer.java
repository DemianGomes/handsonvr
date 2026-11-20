package com.handsonvr.radial;

import com.handsonvr.hands.HandsController;
import com.handsonvr.inv.InvOps;
import com.handsonvr.render.Draw;
import com.handsonvr.vr.Panel;
import com.handsonvr.vr.VrBridge;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Desenha o menu radial: 9 círculos da hotbar em volta da "mão vazia". O alvo sob a mão fica amarelo (como no Alyx),
 * o espaço da mão direita tem anel azul, o da mão esquerda anel verde com "E", e a mão vazia fica apagada quando não
 * há espaço livre para esvaziar a mão.
 */
public final class RadialRenderer {
    private RadialRenderer() {}

    private static final int BG = 0xA0202020, HOVER_BG = 0xD0E8C547, RING = 0xE6F0F0F0, HOVER_RING = 0xFFFFE680;
    private static final int MAIN_RING = 0xFF5AA9FF, OFF_RING = 0xFF6BD66B, DISABLED = 0x80505050;

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

        int selected = p.getInventory().getSelectedSlot();
        for (int i = RadialMenu.CENTER; i < 9; i++) {
            double[] c = RadialMenu.position(i);
            float x = (float) c[0], y = (float) c[1], r = (float) RadialMenu.radius(i);
            boolean hovered = radial.hover(opener) == i || radial.hover(1 - opener) == i;
            if (i == RadialMenu.CENTER) {
                boolean enabled = hands.canEmpty(p, opener);
                d.disc(x, y, r, 0.002f, !enabled ? DISABLED : hovered ? HOVER_BG : BG);
                d.ring(x, y, r - (hovered ? 0.006f : 0.0035f), r, 0.003f, hovered && enabled ? HOVER_RING : RING);
                d.text(enabled ? "✋" : "✕", x, y + 0.008f, 0.006f, 0.03f, enabled ? 0xFFFFFFFF : 0xFFB0B0B0, true);
                d.text(Component.translatable("radial.handsonvr.empty").getString(), x, y - 0.022f, 0.006f, 0.010f,
                        0xFFE0E0E0, true);
                continue;
            }
            int slot = InvOps.HOTBAR_START + i;
            ItemStack stack = hands.displayStack(p, slot);
            d.disc(x, y, r, 0.002f, hovered ? HOVER_BG : BG);
            int ring = hovered ? HOVER_RING : i == hands.offSource() ? OFF_RING : i == selected ? MAIN_RING : RING;
            float thick = hovered || i == selected || i == hands.offSource() ? 0.006f : 0.0035f;
            d.ring(x, y, r - thick, r, 0.003f, ring);
            d.item(stack, x, y, 0.02f, r * 1.25f);
            d.count(stack, x, y, r, 0.032f);
            d.text(Integer.toString(i + 1), x - r * 0.62f, y + r * 0.62f, 0.006f, r * 0.34f, 0xC0FFFFFF, true);
            if (i == hands.offSource()) {
                d.text("E", x + r * 0.62f, y + r * 0.62f, 0.006f, r * 0.4f, OFF_RING, true);
            } else if (i == selected) {
                d.text("D", x + r * 0.62f, y + r * 0.62f, 0.006f, r * 0.4f, MAIN_RING, true);
            }
        }
        ms.popPose();
    }
}
