package com.handsonvr.mixin;

import com.handsonvr.HandsOnVR;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * O Vivecraft 1.3.18 escolhe o item desenhado em cada mão em VRArmHelper.extractHandRenderItem(jogador, mão).
 * Enquanto uma mão segura a mochila, o item dela some (a mochila aparece no lugar). Vivecraft ausente ou mudado:
 * nada acontece.
 */
@Pseudo
@Mixin(targets = "org.vivecraft.client_vr.render.helpers.VRArmHelper", remap = false)
public abstract class VRArmHelperMixin {
    @Inject(method = "extractHandRenderItem", at = @At("HEAD"), cancellable = true, require = 0)
    private static void handsonvr$hideHandItem(LocalPlayer player, InteractionHand hand, CallbackInfoReturnable<ItemStack> cir) {
        if (HandsOnVR.hideHandItem(hand == InteractionHand.MAIN_HAND ? 0 : 1)) {
            cir.setReturnValue(ItemStack.EMPTY);
        }
    }
}
