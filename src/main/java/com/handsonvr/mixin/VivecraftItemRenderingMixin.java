package com.handsonvr.mixin;

import com.handsonvr.hands.ThrowGesture;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.vivecraft.client_vr.render.VivecraftItemRendering;

/**
 * O Vivecraft escolhe como desenhar o item na mão por "tipo" ({@code VivecraftItemRendering.getTransformType}). Os
 * arremessáveis (bola de neve, ovo, pérola, poções arremessáveis, frasco de experiência, carga de vento) caem no tipo
 * genérico, desenhado uns 8 cm à frente do controle: parecem flutuar perto da mão. A comida e a bebida usam o tipo
 * "NOMS", na palma. Os arremessáveis passam a usar esse também: ficam na mão, como quem vai arremessar.
 */
@Pseudo
@Mixin(targets = "org.vivecraft.client_vr.render.VivecraftItemRendering", remap = false)
public abstract class VivecraftItemRenderingMixin {
    @Inject(method = "getTransformType", at = @At("RETURN"), cancellable = true, require = 0)
    private static void handsonvr$throwablesInPalm(ItemStack stack, AbstractClientPlayer player,
            CallbackInfoReturnable<VivecraftItemRendering.VivecraftItemTransformType> cir) {
        try {
            if (cir.getReturnValue() == VivecraftItemRendering.VivecraftItemTransformType.ITEM && ThrowGesture.isThrowable(stack)) {
                cir.setReturnValue(VivecraftItemRendering.VivecraftItemTransformType.NOMS);
            }
        } catch (Throwable ignored) {
        }
    }
}
