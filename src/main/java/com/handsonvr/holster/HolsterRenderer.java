package com.handsonvr.holster;

import com.handsonvr.HandsOnConfig;
import com.handsonvr.render.Draw;
import com.handsonvr.vr.VrBridge;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Desenha os itens guardados presos ao corpo: nos ombros, atravessados nas costas (como uma espada na bainha); nos
 * quadris, pendurados ao lado da cintura. O coldre em que a mão está fica um pouco maior (sinal de "pode pegar").
 */
public final class HolsterRenderer {
    private HolsterRenderer() {}

    private static final float SHOULDER_SCALE = 0.55f, HIP_SCALE = 0.40f, HOVER_SCALE = 1.15f;
    /** Quanto o item é desenhado para trás (ombros) / para fora (quadris) do ponto de pegar, em metros. */
    private static final double SHOULDER_BACK = 0.08, HIP_OUT = 0.05;
    /** Giro do item nas costas (graus): diagonal, o cabo perto do ombro. */
    private static final float SHOULDER_ROLL = 100f;

    public static void render(LevelRenderContext context, HolsterManager manager) {
        HandsOnConfig config = HandsOnConfig.get();
        Minecraft client = Minecraft.getInstance();
        if (!config.holsters || !config.renderHolstered || client.player == null || client.level == null
                || !VrBridge.isVRActive()) {
            return;
        }
        PoseStack ms = context.poseStack();
        BodyFrame.Frame frame = manager.body().compute();
        if (ms == null || context.submitNodeCollector() == null || frame == null) {
            return;
        }
        Draw d = new Draw(ms, context.submitNodeCollector());
        Vec3 camera = context.levelState().cameraRenderState.pos;
        for (Holster h : Holster.values()) {
            if (!h.enabled(config)) {
                continue;
            }
            ItemStack stack = manager.displayStack(client.player, h);
            if (stack.isEmpty()) {
                continue;
            }
            boolean hover = manager.zoneOf(0) == h || manager.zoneOf(1) == h;
            float side = h.right > 0 ? 1f : -1f;
            // Desenhado um pouco para fora do corpo (a zona de pegar continua no lugar): nas costas, atrás do tronco;
            // nos quadris, para o lado.
            Vec3 anchor = frame.anchor(h, config);
            Vec3 drawAt = h.shoulder
                    ? anchor.subtract(frame.forward().scale(SHOULDER_BACK * frame.scale()))
                    : anchor.add(frame.right().scale(side * HIP_OUT * frame.scale()));
            ms.pushPose();
            ms.translate(drawAt.x - camera.x, drawAt.y - camera.y, drawAt.z - camera.z);
            // Referencial do tronco (+Z = frente, +Y = cima).
            rotate(ms, Axis.YP, -frame.yaw());
            if (h.shoulder) {
                // Plano do item paralelo às costas, atravessado na diagonal: o cabo junto ao ombro.
                rotate(ms, Axis.ZP, side * SHOULDER_ROLL);
            } else {
                // Deitado ao lado do quadril, inclinado para fora e para cima (visível olhando para baixo), a ponta
                // para a frente.
                rotate(ms, Axis.ZP, side * 45f);
                rotate(ms, Axis.XP, -90f);
                rotate(ms, Axis.ZP, 180f);
            }
            float scale = (h.shoulder ? SHOULDER_SCALE : HIP_SCALE) * (float) frame.scale() * (hover ? HOVER_SCALE : 1f);
            int light = LightCoordsUtil.getLightCoords(client.level, BlockPos.containing(anchor));
            d.item(stack, ItemDisplayContext.FIXED, 0f, 0f, 0f, scale, light);
            ms.popPose();
        }
    }

    private static void rotate(PoseStack ms, Axis axis, float degrees) {
        ms.rotate(axis.rotationDegrees(degrees));
    }
}
