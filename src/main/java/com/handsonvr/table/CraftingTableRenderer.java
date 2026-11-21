package com.handsonvr.table;

import com.handsonvr.hands.HandsController;
import com.handsonvr.render.Draw;
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
 * Desenha a mesa de trabalho imersiva (padrão em docs/VISUAL.md): os itens da grade deitados nas casas da própria
 * textura da mesa, com a luz do lugar; a casa sob a mão clareia como um espaço do inventário sob o cursor; o resultado
 * flutua girando devagar, como um item no chão, e com a mão perto mostra o nome na caixa de dica do jogo.
 */
public final class CraftingTableRenderer {
    private CraftingTableRenderer() {}

    /** Item deitado: o ícone ocupa a casa (2 pixels da textura e as linhas); o bloco vira um cubo de uns 2 pixels. */
    private static final float FLAT_SIZE = 0.135f, BLOCK_SIZE = 0.26f;
    /** O realce da casa sob a mão: o branco do realce de espaço do jogo (slot_highlight), translúcido. */
    private static final int HIGHLIGHT = 0x60FFFFFF;
    /** Resultado: tamanho (modelo de item no chão), maior com a mão perto; giro (graus por tick) e balanço (m). */
    private static final float RESULT_SIZE = 0.40f, HOVER_SCALE = 1.15f, SPIN = 3f, BOB = 0.025f;
    /** Pixel da caixa de dica do resultado (m), como a do radial. */
    private static final float TOOLTIP_PX = 0.0027f;
    /** A caixa de dica fica acima do resultado e um pouco à frente dele (m). */
    private static final double TOOLTIP_ABOVE = 0.15, TOOLTIP_FRONT = 0.10;
    /** Quantidade: o "espaço" de cada casa (os números têm 8/18 dele: uns 5 cm) e o do resultado; à frente do item (m). */
    private static final float CELL_COUNT = 0.12f, RESULT_COUNT = 0.13f;
    private static final double COUNT_FRONT = 0.09;

    public static void render(LevelRenderContext context, HandsController hands) {
        Minecraft client = Minecraft.getInstance();
        CraftingTable table = hands.table();
        PoseStack ms = context.poseStack();
        if (table.pos() == null || client.level == null || client.player == null || ms == null
                || context.submitNodeCollector() == null || !VrBridge.isVRActive()) {
            return;
        }
        Vec3 camera = context.levelState().cameraRenderState.pos;
        int light = LightCoordsUtil.getLightCoords(client.level, table.pos().above());
        Draw d = new Draw(ms, context.submitNodeCollector());
        boolean holding = hands.grabs().isGrabbing(0) || hands.grabs().isGrabbing(1);

        // --- a grade: plano do topo, x = direita do jogador, y = para longe (a linha de cima da receita), z = cima
        Panel top = new Panel(table.topCenter(), table.right(), table.forward(), new Vec3(0, 1, 0), 1.0);
        ms.pushPose();
        Vec3 o = top.origin();
        ms.translate(o.x - camera.x, o.y - camera.y, o.z - camera.z);
        ms.rotate(top.rotation());
        float cell = (float) CraftingTable.CELL, px = cell / 3f;
        for (int i = 0; i < 9; i++) {
            float x = (i % 3 - 1) * cell, y = (1 - i / 3) * cell;
            ItemStack stack = table.cellStack(i);
            boolean hovered = table.hover(0) == i || table.hover(1) == i;
            if (hovered && (holding || !stack.isEmpty())) {
                // Só o miolo da casa (2 pixels), entre as linhas da textura.
                d.rect(x - px, y - px, x + px, y + px, 0.002f, HIGHLIGHT);
            }
            if (!stack.isEmpty()) {
                float itemTop = lying(d, client, stack, x, y, light);
                // A quantidade, como no inventário (números do jogo com a sombra), no canto da casa mais perto do jogador, à
                // direita; deitada logo acima do item (um bloco é um cubo: por cima dele, senão o cubo cobre os números).
                d.mcCount(stack, x + cell / 2f - CELL_COUNT / 2f, y - cell / 2f + CELL_COUNT / 2f, CELL_COUNT, itemTop + 0.004f);
            }
        }
        ms.popPose();

        // --- o resultado, flutuando
        ItemStack result = table.resultStack();
        if (!result.isEmpty()) {
            int total = table.resultTotal(); // o que o "fabricar tudo" (grip + gatilho) dá
            boolean hovered = table.hover(0) == CraftingTable.RESULT || table.hover(1) == CraftingTable.RESULT;
            long time = client.level.getGameTime();
            float t = time + client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            Vec3 c = table.resultCenter().add(0, Math.sin(t / 10f) * BOB, 0);
            ms.pushPose();
            ms.translate(c.x - camera.x, c.y - camera.y, c.z - camera.z);
            ms.rotate(Axis.YP.rotationDegrees(t * SPIN % 360f));
            d.item(result, ItemDisplayContext.GROUND, 0f, 0f, 0f, RESULT_SIZE * (hovered ? HOVER_SCALE : 1f), light);
            ms.popPose();
            VrBridge.Pose head = VrBridge.head();
            if (head != null) {
                resultCount(d, ms, camera, result.copyWithCount(total), c, head.position());
            }
            if (hovered && head != null) {
                // Acima do item e um pouco à frente dele (para o rosto), para o item não cobrir a caixa.
                Vec3 toEye = head.position().subtract(c);
                Vec3 flat = new Vec3(toEye.x, 0, toEye.z);
                Vec3 front = flat.lengthSqr() < 1.0e-6 ? Vec3.ZERO : flat.normalize().scale(TOOLTIP_FRONT);
                tooltip(d, ms, camera, result.copyWithCount(total), c.add(0, TOOLTIP_ABOVE, 0).add(front), head.position());
            }
        }
    }

    /**
     * Um item deitado na casa: o ícone (modelo de inventário, achatado) com a frente para cima e o topo do desenho
     * para longe; um bloco vira um cubo pequeno apoiado na mesa.
     */
    private static float lying(Draw d, Minecraft client, ItemStack stack, float x, float y, int light) {
        return com.handsonvr.render.BlockItem.lying(d, client, stack, x, y, light, FLAT_SIZE, BLOCK_SIZE);
    }

    /**
     * O total do resultado, como a quantidade de um espaço do inventário: no canto de baixo à direita de um "espaço"
     * do tamanho do item, um pouco à frente dele, virado para o rosto.
     */
    private static void resultCount(Draw d, PoseStack ms, Vec3 camera, ItemStack shown, Vec3 c, Vec3 eye) {
        if (shown.getCount() <= 1) {
            return;
        }
        Vec3 toEye = eye.subtract(c);
        Vec3 at = toEye.lengthSqr() < 1.0e-6 ? c : c.add(toEye.normalize().scale(COUNT_FRONT));
        Panel p = Panel.facing(at, eye, 0, 1.0);
        ms.pushPose();
        ms.translate(at.x - camera.x, at.y - camera.y, at.z - camera.z);
        ms.rotate(p.rotation());
        d.mcCount(shown, 0f, 0f, RESULT_COUNT, 0f);
        ms.popPose();
    }

    /** O nome do resultado (com a quantidade) na caixa de dica do jogo, virada para o rosto. */
    private static void tooltip(Draw d, PoseStack ms, Vec3 camera, ItemStack result, Vec3 at, Vec3 eye) {
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
