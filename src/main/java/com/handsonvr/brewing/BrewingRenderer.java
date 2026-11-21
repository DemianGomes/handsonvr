package com.handsonvr.brewing;

import com.handsonvr.hands.HandsController;
import com.handsonvr.render.Draw;
import com.handsonvr.render.Sprite;
import com.handsonvr.vr.Panel;
import com.handsonvr.vr.VrBridge;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Desenha o suporte de poções aberto (padrão em docs/VISUAL.md): o ingrediente girando no topo da haste, o pó de
 * blaze no meio dela (as garrafas o jogo já desenha nos braços), o nome do que está sob a mão na caixa de dica, e a
 * plaquinha com as bolhas, a seta e a barra de combustível da tela do jogo, recortadas da própria textura dela.
 */
public final class BrewingRenderer {
    private BrewingRenderer() {}

    private static final float PANEL_PX = 0.004f;
    /** A plaquinha (pixels da tela): bolhas 12x29 e seta 9x28 lado a lado, a barra de combustível 18x4 embaixo. */
    private static final int W = 33, H = 44;
    private static final int[] BUBBLES = {29, 24, 20, 16, 11, 6, 0};
    private static final String TEXTURE = "textures/gui/container/brewing_stand.png";

    public static void render(LevelRenderContext context, HandsController hands) {
        Minecraft client = Minecraft.getInstance();
        BrewingStation b = hands.brewing();
        PoseStack ms = context.poseStack();
        VrBridge.Pose head = VrBridge.head();
        if (!b.isOpen() || b.pos() == null || client.level == null || ms == null || context.submitNodeCollector() == null
                || !VrBridge.isVRActive() || head == null) {
            return;
        }
        Vec3 camera = context.levelState().cameraRenderState.pos;
        Draw d = new Draw(ms, context.submitNodeCollector());
        int light = LightCoordsUtil.getLightCoords(client.level, b.pos());
        float t = client.level.getGameTime() + client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        floating(d, ms, camera, b.stack(BrewingStation.INGREDIENT), b.spot(BrewingStation.INGREDIENT).add(0, 0.06, 0), 0.30f, t, light);
        floating(d, ms, camera, b.stack(BrewingStation.FUEL), b.spot(BrewingStation.FUEL), 0.22f, t, light);
        for (int hand = 0; hand < 2; hand++) {
            int target = b.hover(hand);
            if (target != BrewingStation.NONE && !b.stack(target).isEmpty()) {
                tooltip(d, ms, camera, b.stack(target), b.spot(target).add(0, 0.25, 0), head.position());
            }
        }
        progress(d, ms, camera, b, head.position());
    }

    private static void floating(Draw d, PoseStack ms, Vec3 camera, ItemStack stack, Vec3 at, float size, float t, int light) {
        if (stack.isEmpty()) {
            return;
        }
        ms.pushPose();
        ms.translate(at.x - camera.x, at.y - camera.y, at.z - camera.z);
        ms.rotate(Axis.YP.rotationDegrees(t * 3f % 360f));
        d.item(stack, ItemDisplayContext.GROUND, 0f, 0f, 0f, size, light);
        ms.popPose();
    }

    private static void tooltip(Draw d, PoseStack ms, Vec3 camera, ItemStack stack, Vec3 at, Vec3 eye) {
        Panel p = Panel.facing(at, eye, 0, 1.0);
        Component name = stack.getCount() > 1
                ? Component.empty().append(stack.getStyledHoverName()).append(" x" + stack.getCount()) : stack.getStyledHoverName();
        ms.pushPose();
        ms.translate(at.x - camera.x, at.y - camera.y, at.z - camera.z);
        ms.rotate(p.rotation());
        d.mcTooltip(name, 0f, 0f, 0.0027f, 0f);
        ms.popPose();
    }

    /** Bolhas e seta (o tempo de preparo) e a barra de combustível, como na tela do suporte de poções. */
    private static void progress(Draw d, PoseStack ms, Vec3 camera, BrewingStation b, Vec3 eye) {
        Vec3 c = b.panelCenter();
        Panel p = Panel.facing(c, eye, 0, 1.0);
        ms.pushPose();
        ms.translate(c.x - camera.x, c.y - camera.y, c.z - camera.z);
        ms.rotate(p.rotation());
        float px = PANEL_PX, left = -W * px / 2f, top = H * px / 2f;
        d.mcWindow(left, -top, -left, top, px, 0f);
        float bx = left + 4 * px, by = top - 4 * px, ax = left + 20 * px, ay = top - 5 * px, fx = left + 7.5f * px, fy = top - 37 * px;
        Sprite bubblesBg = Sprite.guiCrop(TEXTURE, 63, 14, 12, 29), arrowBg = Sprite.guiCrop(TEXTURE, 97, 16, 9, 28);
        Sprite fuelBg = Sprite.guiCrop(TEXTURE, 60, 44, 18, 4);
        Sprite bubbles = Sprite.gui("container/brewing_stand/bubbles"), arrow = Sprite.gui("container/brewing_stand/brew_progress");
        Sprite fuel = Sprite.gui("container/brewing_stand/fuel_length");
        if (bubblesBg == null || arrowBg == null || fuelBg == null || bubbles == null || arrow == null || fuel == null) {
            ms.popPose();
            return;
        }
        gui(d, bubblesBg, 12, bx, by, px, 0.0008f);
        gui(d, arrowBg, 9, ax, ay, px, 0.0008f);
        gui(d, fuelBg, 18, fx, fy, px, 0.0008f);
        int brew = b.brewTicks();
        if (brew > 0) {
            int l = (int) (28f * (1f - brew / 400f)); // a seta desce de cima para baixo
            Sprite part = l > 0 ? arrow.crop(0, 0, arrow.width(), Math.round(l * arrow.height() / 28f)) : null;
            if (part != null) {
                gui(d, part, 9, ax, ay, px, 0.0012f);
            }
            int bl = BUBBLES[brew / 2 % 7]; // as bolhas sobem
            int rows = Math.round(bl * bubbles.height() / 29f);
            Sprite bpart = bl > 0 ? bubbles.crop(0, bubbles.height() - rows, bubbles.width(), rows) : null;
            if (bpart != null) {
                gui(d, bpart, 12, bx, by - (29 - bl) * px, px, 0.0012f);
            }
        }
        int fl = Math.max(0, Math.min(18, (18 * b.fuel() + 19) / 20));
        Sprite fpart = fl > 0 ? fuel.crop(0, 0, Math.round(fl * fuel.width() / 18f), fuel.height()) : null;
        if (fpart != null) {
            gui(d, fpart, fl, fx, fy, px, 0.0012f);
        }
        ms.popPose();
    }

    private static void gui(Draw d, Sprite s, int guiWidth, float left, float top, float px, float z) {
        d.sprite(s, left, top, px * guiWidth / s.width(), z, Draw.MC_PANEL);
    }
}
