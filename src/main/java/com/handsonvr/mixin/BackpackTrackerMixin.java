package com.handsonvr.mixin;

import com.handsonvr.HandsOnVR;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A "troca pelas costas" do Vivecraft (BackpackTracker: mão atrás da cabeça, apontando para baixo, troca o item
 * sozinha, sem botão) usa a mesma zona dos coldres dos ombros: ao buscar a espada, o item da mão mudava antes do
 * grip. Com coldres nos ombros ligados, ela fica desligada. Opcional (Vivecraft ausente ou mudado: nada acontece).
 */
@Pseudo
@Mixin(targets = "org.vivecraft.client_vr.gameplay.trackers.BackpackTracker", remap = false)
public abstract class BackpackTrackerMixin {
    @Inject(method = "isActive", at = @At("HEAD"), cancellable = true, require = 0)
    private void handsonvr$offWithShoulderHolsters(CallbackInfoReturnable<Boolean> cir) {
        if (HandsOnVR.shoulderHolstersReplaceBackpack()) {
            cir.setReturnValue(false);
        }
    }
}
