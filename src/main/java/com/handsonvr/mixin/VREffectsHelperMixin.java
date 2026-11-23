package com.handsonvr.mixin;

import com.handsonvr.render.MinimapWrist;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.vivecraft.client_vr.gameplay.screenhandlers.GuiHandler;
import org.vivecraft.client_vr.render.helpers.RenderHelper;
import org.vivecraft.client_vr.render.renderstates.ScreenRenderState;
import org.vivecraft.client_vr.render.renderstates.VRRenderState;

/**
 * O painel do HUD do Vivecraft (pulso esquerdo) sem o minimapa, que vai para o pulso direito ({@link MinimapWrist}).
 * Só no painel do HUD com nenhuma tela aberta; as telas (inventário, menus) ficam como o Vivecraft desenha.
 */
@Pseudo
@Mixin(targets = "org.vivecraft.client_vr.render.helpers.VREffectsHelper", remap = false)
public abstract class VREffectsHelperMixin {
    @Inject(method = "renderScreen", at = @At("HEAD"), require = 0)
    private static void handsonvr$hudStart(SubmitNodeCollector out, CameraRenderState camera, VRRenderState vr,
            ScreenRenderState screen, RenderTarget target, boolean a, boolean b, PoseStack ms, int order,
            CallbackInfoReturnable<Integer> cir) {
        MinimapWrist.hudPass = target != null && target == GuiHandler.GUI_FRAMEBUFFER && Minecraft.getInstance().gui.screen() == null;
    }

    @Inject(method = "renderScreen", at = @At("RETURN"), require = 0)
    private static void handsonvr$hudEnd(SubmitNodeCollector out, CameraRenderState camera, VRRenderState vr,
            ScreenRenderState screen, RenderTarget target, boolean a, boolean b, PoseStack ms, int order,
            CallbackInfoReturnable<Integer> cir) {
        MinimapWrist.hudPass = false;
    }

    @Redirect(method = "renderScreen", require = 0, at = @At(value = "INVOKE",
            target = "Lorg/vivecraft/client_vr/render/helpers/RenderHelper;submitSizedQuadWithLightmap(FFFI[FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;ZLnet/minecraft/client/renderer/SubmitNodeCollector;I)I"))
    private static int handsonvr$hudQuad(float width, float height, float size, int light, float[] color, PoseStack ms,
            RenderType type, boolean flipY, SubmitNodeCollector out, int order) {
        try {
            if (MinimapWrist.hudPass && MinimapWrist.currentRect() != null) {
                return MinimapWrist.carvedQuad(width, height, size, light, color, ms, type, flipY, out, order);
            }
        } catch (Throwable ignored) {
        }
        return RenderHelper.submitSizedQuadWithLightmap(width, height, size, light, color, ms, type, flipY, out, order);
    }
}
