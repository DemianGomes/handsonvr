package com.handsonvr.mixin;

import com.handsonvr.render.MinimapWrist;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Anota o retângulo de cada coisa posta no HUD enquanto o minimapa do Xaero desenha ({@link MinimapWrist}). Só lê:
 * nada no desenho muda.
 */
@Mixin(GuiRenderState.class)
public abstract class GuiRenderStateMixin {
    @Inject(method = "addItem", at = @At("HEAD"))
    private void handsonvr$item(GuiItemRenderState state, CallbackInfo ci) {
        MinimapWrist.capture(state);
    }

    @Inject(method = "addText", at = @At("HEAD"))
    private void handsonvr$text(GuiTextRenderState state, CallbackInfo ci) {
        MinimapWrist.capture(state);
    }

    @Inject(method = "addPicturesInPictureState", at = @At("HEAD"))
    private void handsonvr$pip(PictureInPictureRenderState state, CallbackInfo ci) {
        MinimapWrist.capture(state);
    }

    @Inject(method = "addGuiElement", at = @At("HEAD"))
    private void handsonvr$element(GuiElementRenderState state, CallbackInfo ci) {
        MinimapWrist.capture(state);
    }

    @Inject(method = "addBlitToCurrentLayer", at = @At("HEAD"))
    private void handsonvr$blit(BlitRenderState state, CallbackInfo ci) {
        MinimapWrist.capture(state);
    }

    @Inject(method = "addGlyphToCurrentLayer", at = @At("HEAD"))
    private void handsonvr$glyph(GuiElementRenderState state, CallbackInfo ci) {
        MinimapWrist.capture(state);
    }
}
