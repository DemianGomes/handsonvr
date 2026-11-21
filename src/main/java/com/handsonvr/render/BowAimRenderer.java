package com.handsonvr.render;

import com.handsonvr.hands.BowAim;
import com.handsonvr.vr.Panel;
import com.handsonvr.vr.VrBridge;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Desenha a mira opcional do arco ({@link BowAim}): a mira do próprio jogo ({@code hud/crosshair}) no ponto em que a
 * flecha vai acertar, virada para o rosto e sempre do mesmo tamanho na vista (como a mira da tela). Branca com a
 * sombra do jogo, como a quantidade dos itens (docs/VISUAL.md): a mira da tela inverte as cores do fundo, o que não
 * existe no mundo.
 */
public final class BowAimRenderer {
    private BowAimRenderer() {}

    /** Tamanho na vista: a mira (15 pixels) ocupa este tanto da distância (uns 2 graus). */
    private static final double ANGULAR_SIZE = 0.035;
    /** Um pouco à frente do ponto (para o rosto), para não entrar no bloco. */
    private static final double LIFT = 0.06;
    private static final int WHITE = 0xFFFFFFFF, SHADOW = 0xFF3F3F3F;

    public static void render(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        PoseStack ms = context.poseStack();
        if (ms == null || context.submitNodeCollector() == null) {
            return;
        }
        Vec3 hit = BowAim.predict(client);
        VrBridge.Pose head = VrBridge.head();
        Sprite cross = Sprite.gui("hud/crosshair");
        if (hit == null || head == null || cross == null) {
            return;
        }
        Vec3 eye = head.position();
        double distance = eye.distanceTo(hit);
        if (distance < 0.3) {
            return;
        }
        Vec3 at = hit.add(eye.subtract(hit).normalize().scale(Math.min(LIFT, distance * 0.5)));
        Panel p = Panel.facing(at, eye, 0, 1.0);
        Vec3 camera = context.levelState().cameraRenderState.pos;
        float size = (float) (distance * ANGULAR_SIZE), px = size / cross.width();
        Draw d = new Draw(ms, context.submitNodeCollector());
        ms.pushPose();
        ms.translate(at.x - camera.x, at.y - camera.y, at.z - camera.z);
        ms.rotate(p.rotation());
        float left = -size / 2f, top = px * cross.height() / 2f;
        d.spriteSolid(cross, left + px, top - px, px, 0f, SHADOW);
        d.spriteSolid(cross, left, top, px, px * 0.5f, WHITE);
        ms.popPose();
    }
}
