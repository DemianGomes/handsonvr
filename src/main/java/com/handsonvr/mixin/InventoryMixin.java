package com.handsonvr.mixin;

import com.handsonvr.inv.PickupOrder;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * O espaço livre do inventário do jogador local: primeiro o inventário principal, depois a hotbar
 * ({@link PickupOrder}). Vale para tudo o que o jogo coloca "no primeiro espaço livre": coletar do chão, receber um
 * item, as sobras que voltam de uma grade de crafting. Juntar com uma pilha igual continua como no jogo.
 */
@Mixin(Inventory.class)
public abstract class InventoryMixin {
    @Shadow
    @Final
    private NonNullList<ItemStack> items;

    @Shadow
    @Final
    public Player player;

    @Inject(method = "getFreeSlot", at = @At("HEAD"), cancellable = true)
    private void handsonvr$mainInventoryFirst(CallbackInfoReturnable<Integer> cir) {
        try {
            if (PickupOrder.appliesTo(player)) {
                cir.setReturnValue(PickupOrder.freeSlot(items));
            }
        } catch (Throwable ignored) {
            // nunca atrapalha o inventário do jogo
        }
    }
}
