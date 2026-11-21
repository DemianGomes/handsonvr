package com.handsonvr.furnace;

import com.handsonvr.hands.HandsController;
import com.handsonvr.render.BlockItem;
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
 * Desenha as fornalhas imersivas (padrão em docs/VISUAL.md):
 * <ul>
 *   <li>a entrada e o combustível no próprio bloco, com a luz do lugar: em pé numa abertura da frente ou deitados no
 *       topo, com a quantidade como no inventário; o lugar sob a mão clareia como um espaço do inventário;</li>
 *   <li>acima do bloco, virada para o rosto, uma plaquinha com a barra de progresso da tela do jogo: a chama (o quanto
 *       ainda queima o combustível) e a seta (o quanto o item já cozinhou), recortadas da própria textura da tela;</li>
 *   <li>à direita da seta, o resultado flutuando como um item no chão, pronto para pegar.</li>
 * </ul>
 */
public final class FurnaceRenderer {
    private FurnaceRenderer() {}

    /** O realce do lugar sob a mão: o branco do realce de espaço do jogo (slot_highlight), translúcido. */
    private static final int HIGHLIGHT = 0x60FFFFFF;
    /** Plaquinha: um pixel da tela (m) e as medidas em pixels (52x25): a chama 14x14 e a seta 24x17, como na tela. */
    private static final float PANEL_PX = 0.005f;
    private static final int PANEL_W = 52, PANEL_H = 25, FLAME_X = 4, FLAME_Y = 6, ARROW_X = 24, ARROW_Y = 4;
    /** Onde a chama e a seta ficam na textura da tela da fornalha (gui/container/furnace.png, 256x256). */
    private static final int FLAME_U = 56, FLAME_V = 36, ARROW_U = 79, ARROW_V = 34;
    /** Resultado: tamanho (modelo de item no chão), maior com a mão perto; giro (graus por tick) e balanço (m). */
    private static final float RESULT_SIZE = 0.40f, HOVER_SCALE = 1.15f, SPIN = 3f, BOB = 0.02f;
    /** Caixa de dica do resultado: pixel (m), acima dele e um pouco à frente (m). */
    private static final float TOOLTIP_PX = 0.0027f;
    private static final double TOOLTIP_ABOVE = 0.15, TOOLTIP_FRONT = 0.10, COUNT_FRONT = 0.09;
    /** Quantidade: o "espaço" de um lugar (os números têm 8/18 dele) e o do resultado. */
    private static final float SPOT_COUNT = 0.14f, RESULT_COUNT = 0.13f;

    public static void render(LevelRenderContext context, HandsController hands) {
        Minecraft client = Minecraft.getInstance();
        FurnaceStation f = hands.furnace();
        PoseStack ms = context.poseStack();
        if (f.pos() == null || f.kind() == null || !f.isOpen() || client.level == null || client.player == null
                || ms == null || context.submitNodeCollector() == null || !VrBridge.isVRActive()) {
            return;
        }
        Vec3 camera = context.levelState().cameraRenderState.pos;
        Draw d = new Draw(ms, context.submitNodeCollector());
        boolean holding = hands.grabs().isGrabbing(0) || hands.grabs().isGrabbing(1);
        for (int target : new int[] {FurnaceStation.INPUT, FurnaceStation.FUEL}) {
            spot(d, ms, camera, client, f, target, holding);
        }
        VrBridge.Pose head = VrBridge.head();
        if (head == null || !f.busy()) {
            return;
        }
        progress(d, ms, camera, f, head.position());
        result(d, ms, camera, client, f, head.position());
    }

    /** A entrada ou o combustível: o item no lugar dele (em pé na frente ou deitado no topo) e o realce da mão. */
    private static void spot(Draw d, PoseStack ms, Vec3 camera, Minecraft client, FurnaceStation f, int target,
            boolean holding) {
        FurnaceStation.Spot s = f.spot(target);
        Panel plane = f.spotPlane(target);
        ItemStack stack = f.stack(target);
        boolean hovered = f.hover(0) == target || f.hover(1) == target;
        boolean front = s.face() == FurnaceStation.Face.FRONT;
        int light = LightCoordsUtil.getLightCoords(client.level, front ? f.pos().relative(f.facing()) : f.pos().above());
        float w = (float) s.width(), h = (float) s.height(), box = (float) s.box();
        ms.pushPose();
        Vec3 o = plane.origin();
        ms.translate(o.x - camera.x, o.y - camera.y, o.z - camera.z);
        ms.rotate(plane.rotation());
        if (hovered && (holding || !stack.isEmpty())) {
            d.rect(-w / 2f, -h / 2f, w / 2f, h / 2f, 0.002f, HIGHLIGHT); // a abertura (ou o quadrado do topo) inteira
        }
        if (!stack.isEmpty()) {
            float flat = Math.min(box * 0.72f, 0.30f);
            float top = BlockItem.lying(d, client, stack, 0f, 0f, light, flat, Math.min(box * 1.39f, 0.40f));
            // A quantidade, como no inventário, no canto de baixo à direita do "espaço" do item (o item tem 16 dos 18
            // pixels do espaço), não do lugar inteiro (o topo do alto-forno é bem maior que o item).
            float slot = Math.min(box, flat * 18f / 16f);
            d.mcCount(stack, slot / 2f - SPOT_COUNT / 2f, -slot / 2f + SPOT_COUNT / 2f, SPOT_COUNT, top + 0.004f);
        }
        ms.popPose();
    }

    /** A plaquinha com a chama e a seta da tela do jogo, virada para o rosto. */
    private static void progress(Draw d, PoseStack ms, Vec3 camera, FurnaceStation f, Vec3 eye) {
        Vec3 c = f.panelCenter();
        Panel p = Panel.facing(c, eye, 0, 1.0);
        ms.pushPose();
        ms.translate(c.x - camera.x, c.y - camera.y, c.z - camera.z);
        ms.rotate(p.rotation());
        float px = PANEL_PX, left = -PANEL_W * px / 2f, top = PANEL_H * px / 2f;
        d.mcWindow(left, -top, -left, top, px, 0f);
        String texture = "textures/gui/container/" + f.kind().gui + ".png";
        Sprite flameBg = Sprite.guiCrop(texture, FLAME_U, FLAME_V, 14, 14);
        Sprite arrowBg = Sprite.guiCrop(texture, ARROW_U, ARROW_V, 24, 17);
        Sprite lit = Sprite.gui("container/" + f.kind().gui + "/lit_progress");
        Sprite burn = Sprite.gui("container/" + f.kind().gui + "/burn_progress");
        float flameLeft = left + FLAME_X * px, flameTop = top - FLAME_Y * px;
        float arrowLeft = left + ARROW_X * px, arrowTop = top - ARROW_Y * px;
        // Como a tela do jogo: o fundo (a chama e a seta apagadas) e, por cima, só a parte cheia.
        if (flameBg != null && arrowBg != null && lit != null && burn != null) {
            gui(d, flameBg, 14, flameLeft, flameTop, px, 0.0008f);
            gui(d, arrowBg, 24, arrowLeft, arrowTop, px, 0.0008f);
            if (f.isLit()) {
                int l = (int) Math.ceil(f.litProgress() * 13f) + 1; // a chama baixa de cima para baixo
                int rows = Math.round(l * lit.height() / 14f), skip = lit.height() - rows;
                Sprite part = lit.crop(0, skip, lit.width(), rows);
                if (part != null) {
                    gui(d, part, 14 * part.width() / lit.width(), flameLeft, flameTop - (14 - l) * px, px, 0.0012f);
                }
            }
            int l = (int) Math.ceil(f.burnProgress() * 24f); // a seta enche da esquerda para a direita
            if (l > 0) {
                Sprite part = burn.crop(0, 0, Math.round(l * burn.width() / 24f), burn.height());
                if (part != null) {
                    gui(d, part, l, arrowLeft, arrowTop, px, 0.0012f);
                }
            }
        } else {
            // Substituto (resource pack sem as texturas): a seta como uma barra cinza que enche de branco.
            float y0 = arrowTop - 11 * px, y1 = arrowTop - 6 * px;
            d.rect(arrowLeft, y0, arrowLeft + 24 * px, y1, 0.0008f, Draw.MC_SLOT);
            d.rect(arrowLeft, y0, arrowLeft + 24 * px * f.burnProgress(), y1, 0.0012f, 0xFFFFFFFF);
        }
        ms.popPose();
    }

    /** Um sprite da tela com {@code guiWidth} pixels de largura na tela, qualquer que seja a resolução da textura. */
    private static void gui(Draw d, Sprite s, int guiWidth, float left, float top, float px, float z) {
        d.sprite(s, left, top, px * guiWidth / s.width(), z, Draw.MC_PANEL);
    }

    /** O resultado flutuando à direita da seta, como um item no chão; com a mão perto, maior e com o nome. */
    private static void result(Draw d, PoseStack ms, Vec3 camera, Minecraft client, FurnaceStation f, Vec3 eye) {
        ItemStack result = f.resultStack();
        if (result.isEmpty()) {
            return;
        }
        int light = LightCoordsUtil.getLightCoords(client.level, f.pos().above());
        boolean hovered = f.hover(0) == FurnaceStation.RESULT || f.hover(1) == FurnaceStation.RESULT;
        float t = client.level.getGameTime() + client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 c = f.resultCenter().add(0, Math.sin(t / 10f) * BOB, 0);
        ms.pushPose();
        ms.translate(c.x - camera.x, c.y - camera.y, c.z - camera.z);
        ms.rotate(Axis.YP.rotationDegrees(t * SPIN % 360f));
        d.item(result, ItemDisplayContext.GROUND, 0f, 0f, 0f, RESULT_SIZE * (hovered ? HOVER_SCALE : 1f), light);
        ms.popPose();
        // A quantidade à frente do item, virada para o rosto.
        if (result.getCount() > 1) {
            Vec3 toEye = eye.subtract(c);
            Vec3 at = toEye.lengthSqr() < 1.0e-6 ? c : c.add(toEye.normalize().scale(COUNT_FRONT));
            Panel p = Panel.facing(at, eye, 0, 1.0);
            ms.pushPose();
            ms.translate(at.x - camera.x, at.y - camera.y, at.z - camera.z);
            ms.rotate(p.rotation());
            d.mcCount(result, 0f, 0f, RESULT_COUNT, 0f);
            ms.popPose();
        }
        if (hovered) {
            Vec3 toEye = eye.subtract(c);
            Vec3 flat = new Vec3(toEye.x, 0, toEye.z);
            Vec3 front = flat.lengthSqr() < 1.0e-6 ? Vec3.ZERO : flat.normalize().scale(TOOLTIP_FRONT);
            Vec3 at = c.add(0, TOOLTIP_ABOVE, 0).add(front);
            Panel p = Panel.facing(at, eye, 0, 1.0);
            Component name = result.getCount() > 1
                    ? Component.empty().append(result.getStyledHoverName()).append(" x" + result.getCount())
                    : result.getStyledHoverName();
            ms.pushPose();
            ms.translate(at.x - camera.x, at.y - camera.y, at.z - camera.z);
            ms.rotate(p.rotation());
            d.mcTooltip(name, 0f, 0f, TOOLTIP_PX, 0f);
            ms.popPose();
        }
    }
}
