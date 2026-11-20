package com.handsonvr.mixin;

import com.handsonvr.HandsOnVR;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * O golpe com o braço do Vivecraft (SwingTracker) fica desligado enquanto o jogador mexe na mochila ou no radial: mover
 * a mão entre os espaços não pode bater em nada com o item escondido. Opcional (Vivecraft ausente/mudado: nada).
 */
@Pseudo
@Mixin(targets = "org.vivecraft.client_vr.gameplay.trackers.SwingTracker", remap = false)
public abstract class SwingTrackerMixin {
    @Inject(method = "isActive", at = @At("HEAD"), cancellable = true, require = 0)
    private void handsonvr$offWhileManaging(CallbackInfoReturnable<Boolean> cir) {
        if (HandsOnVR.blockSwing()) {
            cir.setReturnValue(false);
        }
    }
}
