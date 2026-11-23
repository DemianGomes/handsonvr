package com.handsonvr.mixin;

import com.handsonvr.compat.UltimineVr;
import com.handsonvr.mine.MineServer;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Com a mineração em grupo do HandsOn ligada, ao começar a quebrar um bloco o cliente avisa o servidor qual face foi
 * batida (as formas seguem a face, como no FTB Ultimine). O aviso vai antes do pedido de quebra do jogo. Só lê: a
 * quebra continua a do jogo.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
    @Inject(method = "startDestroyBlock", at = @At("HEAD"))
    private void handsonvr$aimStart(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
        aim(pos, face);
    }

    @Inject(method = "continueDestroyBlock", at = @At("HEAD"))
    private void handsonvr$aimContinue(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
        aim(pos, face);
    }

    private static void aim(BlockPos pos, Direction face) {
        try {
            if (UltimineVr.ownActive()) {
                MineServer.sendAim(pos, face);
            }
        } catch (Throwable ignored) {
        }
    }
}
