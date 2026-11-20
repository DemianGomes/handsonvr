package com.handsonvr.backpack;

import com.handsonvr.HandsOnConfig;
import com.handsonvr.hands.HandsController;
import com.handsonvr.holster.BodyFrame;
import com.handsonvr.holster.Holster;
import com.handsonvr.inv.InvOps;
import com.handsonvr.render.Draw;
import com.handsonvr.vr.Panel;
import com.handsonvr.vr.VrBridge;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Desenha a mochila: fechada, uma bolsa simples nas costas (ombro esquerdo); aberta, o painel na mão — tecido verde
 * oliva com alça, crafting 2x2 + resultado e armadura no topo, a página 3x3, setas, "Página X/3 · Espaços N/27" e a
 * zona de descarte. O alvo sob a outra mão fica amarelo.
 */
public final class BackpackRenderer {
    private BackpackRenderer() {}

    private static final int CLOTH = 0xF0353D27, CLOTH_DARK = 0xF0262C1B, TRIM = 0xF04A5436, STRAP = 0xFF1C2114;
    private static final int SLOT_BG = 0x9010120C, RING = 0xE6E8E8E0, RING_DIM = 0x70E8E8E0;
    private static final int HOVER = 0xFFFFD34D, HOVER_BG = 0x50FFD34D, DROP_BG = 0xB0902A22, DROP_RING = 0xFFE05A4A;

    public static void render(LevelRenderContext context, HandsController hands) {
        Minecraft client = Minecraft.getInstance();
        HandsOnConfig config = HandsOnConfig.get();
        if (!config.backpack || client.player == null || client.level == null || !VrBridge.isVRActive()) {
            return;
        }
        PoseStack ms = context.poseStack();
        if (ms == null || context.submitNodeCollector() == null) {
            return;
        }
        Draw d = new Draw(ms, context.submitNodeCollector(), CLOTH);
        Vec3 camera = context.levelState().cameraRenderState.pos;
        Backpack bp = hands.backpack();
        if (bp.isOpen()) {
            renderOpen(client.player, hands, bp, d, camera);
        } else if (config.renderHolstered) {
            renderOnBack(hands, d, camera, config);
        }
    }

    private static void renderOpen(LocalPlayer p, HandsController hands, Backpack bp, Draw d, Vec3 camera) {
        Panel panel = bp.computePanel();
        if (panel == null) {
            return;
        }
        PoseStack ms = d.pose();
        ms.pushPose();
        Vec3 o = panel.origin();
        ms.translate(o.x - camera.x, o.y - camera.y, o.z - camera.z);
        ms.rotate(panel.rotation());
        float s = (float) panel.scale();
        ms.scale(s, s, s);
        float w = (float) (Backpack.WIDTH / 2), h = (float) Backpack.HEIGHT;

        // Corpo, bordas e alça.
        d.rect(-w, -h, w, 0, 0f, CLOTH);
        d.frame(-w, -h, w, 0, 0.012f, 0.001f, TRIM);
        d.rect(-w + 0.02f, -0.172f, w - 0.02f, -0.166f, 0.001f, CLOTH_DARK);
        d.rect(-w + 0.02f, -0.462f, w - 0.02f, -0.456f, 0.001f, CLOTH_DARK);
        d.rect(-0.07f, 0f, 0.07f, (float) Backpack.HANDLE + 0.012f, 0f, STRAP);
        d.rect(-0.05f, 0.01f, 0.05f, (float) Backpack.HANDLE, 0.001f, CLOTH_DARK);

        int free = 1 - bp.holder();
        Backpack.Element hover = bp.hover(free);
        for (Backpack.Element e : bp.layout()) {
            boolean hovered = hover != null && hover.kind() == e.kind() && hover.handler() == e.handler() && hover.x() == e.x();
            drawElement(p, hands, d, e, hovered);
        }

        // Rodapé: "Página X/3" e "Espaços N/27" (como no Saints & Sinners).
        int used = 0;
        for (int i = InvOps.MAIN_START; i < InvOps.HOTBAR_START; i++) {
            if (!hands.displayStack(p, i).isEmpty()) {
                used++;
            }
        }
        float labelX = -0.035f, by = (float) Backpack.BOTTOM_Y;
        d.rect(labelX - 0.06f, by - 0.026f, labelX + 0.06f, by + 0.026f, 0.002f, 0xC0101010);
        d.text(Component.translatable("backpack.handsonvr.page", bp.page() + 1, Backpack.PAGES).getString(),
                labelX, by + 0.010f, 0.006f, 0.014f, 0xFFFFFFFF, true);
        d.text(Component.translatable("backpack.handsonvr.slots", used, 27).getString(),
                labelX, by - 0.010f, 0.006f, 0.012f, 0xFFD8D8D0, true);
        // Seta do crafting para o resultado (desenhada: haste + ponta).
        d.rect(-0.054f, -0.1025f, -0.036f, -0.0975f, 0.004f, 0xFFE8E8E0);
        d.quad(new Vector3f(-0.037f, -0.111f, 0.004f), new Vector3f(-0.024f, -0.100f, 0.004f),
                new Vector3f(-0.024f, -0.100f, 0.004f), new Vector3f(-0.037f, -0.089f, 0.004f), 0xFFE8E8E0);
        ms.popPose();
    }

    private static void drawElement(LocalPlayer p, HandsController hands, Draw d, Backpack.Element e, boolean hovered) {
        float x = (float) e.x(), y = (float) e.y(), r = (float) e.radius();
        switch (e.kind()) {
            case SLOT, CRAFT_OUT -> {
                d.disc(x, y, r, 0.002f, hovered ? HOVER_BG : SLOT_BG);
                boolean locked = e.handler() >= 0 && hands.isLocked(p, e.handler());
                d.ring(x, y, r - (hovered ? 0.006f : 0.0035f), r, 0.003f, hovered ? HOVER : locked ? RING_DIM : RING);
                ItemStack stack = hands.displayStack(p, e.handler());
                d.item(stack, x, y, 0.02f, r * 1.25f);
                d.count(stack, x, y, r, 0.032f);
            }
            case CRAFT_IN, ARMOR -> {
                float t = r * 0.95f;
                d.rect(x - t, y - t, x + t, y + t, 0.002f, hovered ? HOVER_BG : SLOT_BG);
                d.frame(x - t, y - t, x + t, y + t, hovered ? 0.004f : 0.0025f, 0.003f, hovered ? HOVER : RING);
                ItemStack stack = hands.displayStack(p, e.handler());
                if (stack.isEmpty() && e.kind() == Backpack.Kind.ARMOR) {
                    String key = "backpack.handsonvr.armor." + (e.handler() - InvOps.ARMOR_START);
                    d.text(Component.translatable(key).getString(), x, y, 0.004f, r * 0.36f, 0xA0E8E8E0, true);
                }
                d.item(stack, x, y, 0.016f, r * 1.25f);
                d.count(stack, x, y, r, 0.03f);
            }
            case DROP -> {
                d.disc(x, y, r, 0.002f, hovered ? 0xE0C03A2E : DROP_BG);
                d.ring(x, y, r - (hovered ? 0.006f : 0.0035f), r, 0.003f, hovered ? HOVER : DROP_RING);
                // Seta para baixo sobre o "chão": soltar no mundo.
                float a = r * 0.55f;
                d.rect(x - a * 0.18f, y - a * 0.05f, x + a * 0.18f, y + a * 0.75f, 0.005f, 0xFFFFFFFF);
                d.quad(new Vector3f(x - a * 0.55f, y, 0.005f), new Vector3f(x + a * 0.55f, y, 0.005f),
                        new Vector3f(x, y - a * 0.6f, 0.005f), new Vector3f(x, y - a * 0.6f, 0.005f), 0xFFFFFFFF);
                d.rect(x - a * 0.7f, y - a * 0.85f, x + a * 0.7f, y - a * 0.72f, 0.005f, 0xFFFFFFFF);
                d.text(Component.translatable("backpack.handsonvr.drop").getString(), x, y - r - 0.012f, 0.006f,
                        0.011f, 0xFFE8C0B8, true);
            }
            case PREV, NEXT -> {
                if (hovered) {
                    d.disc(x, y, r, 0.002f, HOVER_BG);
                }
                float a = r * 0.6f, dir = e.kind() == Backpack.Kind.PREV ? -1f : 1f;
                d.quad(new Vector3f(x - dir * a * 0.6f, y + a, 0.005f), new Vector3f(x + dir * a * 0.8f, y, 0.005f),
                        new Vector3f(x + dir * a * 0.8f, y, 0.005f), new Vector3f(x - dir * a * 0.6f, y - a, 0.005f),
                        hovered ? HOVER : 0xFFE8E8E0);
            }
        }
    }

    /** Fechada: uma bolsa nas costas, atrás do ombro esquerdo (fica mais clara com a mão na zona). */
    private static void renderOnBack(HandsController hands, Draw d, Vec3 camera, HandsOnConfig config) {
        BodyFrame.Frame frame = hands.holsters().body().compute();
        if (frame == null) {
            return;
        }
        boolean hover = hands.holsters().zoneOf(0) == Holster.LEFT_SHOULDER || hands.holsters().zoneOf(1) == Holster.LEFT_SHOULDER;
        Vec3 anchor = frame.anchor(Holster.LEFT_SHOULDER, config);
        // Pendurada atrás do ombro: um pouco para trás e para baixo do ponto de pegar.
        Vec3 at = anchor.subtract(frame.forward().scale(0.12 * frame.scale())).add(0, -0.16 * frame.scale(), 0)
                .add(frame.right().scale(0.06 * frame.scale()));
        PoseStack ms = d.pose();
        ms.pushPose();
        ms.translate(at.x - camera.x, at.y - camera.y, at.z - camera.z);
        ms.rotate(Axis.YP.rotationDegrees(-frame.yaw()));
        float s = (float) frame.scale();
        ms.scale(s, s, s);
        int body = hover ? 0xFF4F5A39 : 0xFF3A4329, side = hover ? 0xFF434D30 : 0xFF2E3521;
        box(d, 0.12f, 0.15f, 0.05f, body, side);
        // Aba e alça.
        d.rect(-0.12f, 0.06f, 0.12f, 0.15f, -0.0505f, side);
        d.rect(-0.03f, -0.15f, 0.03f, 0.18f, 0.0505f, STRAP);
        ms.popPose();
    }

    /** Caixa centrada na origem com meias-medidas (x, y, z). Frente (+Z) e trás com {@code front}, lados com {@code side}. */
    private static void box(Draw d, float hx, float hy, float hz, int front, int side) {
        Vector3f[] c = new Vector3f[8];
        for (int i = 0; i < 8; i++) {
            c[i] = new Vector3f((i & 1) == 0 ? -hx : hx, (i & 2) == 0 ? -hy : hy, (i & 4) == 0 ? -hz : hz);
        }
        d.quad(c[4], c[5], c[7], c[6], front); // +Z
        d.quad(c[0], c[2], c[3], c[1], front); // -Z
        d.quad(c[1], c[3], c[7], c[5], side);  // +X
        d.quad(c[0], c[4], c[6], c[2], side);  // -X
        d.quad(c[2], c[6], c[7], c[3], side);  // +Y
        d.quad(c[0], c[1], c[5], c[4], side);  // -Y
    }
}
