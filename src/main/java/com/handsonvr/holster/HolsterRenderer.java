package com.handsonvr.holster;

import com.handsonvr.HandsOnConfig;
import com.handsonvr.vr.VrBridge;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

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

    public static void render(WorldRenderContext context, HolsterManager manager) {
        HandsOnConfig config = HandsOnConfig.get();
        MinecraftClient client = MinecraftClient.getInstance();
        if (!config.holsters || !config.renderHolstered || client.player == null || client.world == null
                || !VrBridge.isVRActive()) {
            return;
        }
        MatrixStack matrices = context.matrixStack();
        VertexConsumerProvider consumers = context.consumers();
        BodyFrame.Frame frame = manager.body().compute();
        if (matrices == null || consumers == null || frame == null) {
            return;
        }
        Vec3d camera = context.camera().getPos();
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
            Vec3d anchor = frame.anchor(h, config);
            Vec3d drawAt = h.shoulder
                    ? anchor.subtract(frame.forward().multiply(SHOULDER_BACK * frame.scale()))
                    : anchor.add(frame.right().multiply(side * HIP_OUT * frame.scale()));
            matrices.push();
            matrices.translate(drawAt.x - camera.x, drawAt.y - camera.y, drawAt.z - camera.z);
            // Referencial do tronco (+Z = frente, +Y = cima).
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-frame.yaw()));
            if (h.shoulder) {
                // Plano do item paralelo às costas, atravessado na diagonal: o cabo junto ao ombro.
                matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(side * SHOULDER_ROLL));
            } else {
                // Deitado ao lado do quadril, inclinado para fora e para cima (visível olhando para baixo), a ponta
                // para a frente.
                matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(side * 45f));
                matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-90f));
                matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(180f));
            }
            float scale = (h.shoulder ? SHOULDER_SCALE : HIP_SCALE) * (float) frame.scale() * (hover ? HOVER_SCALE : 1f);
            matrices.scale(scale, scale, scale);
            int light = WorldRenderer.getLightmapCoordinates(client.world, BlockPos.ofFloored(anchor));
            client.getItemRenderer().renderItem(stack, ModelTransformationMode.FIXED, light, OverlayTexture.DEFAULT_UV,
                    matrices, consumers, client.world, 0);
            matrices.pop();
        }
    }
}
