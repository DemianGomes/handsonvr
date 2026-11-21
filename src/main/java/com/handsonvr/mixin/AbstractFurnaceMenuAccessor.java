package com.handsonvr.mixin;

import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * A fornalha imersiva pergunta ao menu da fornalha se um item cozinha nela (o mesmo teste do Shift+clique da tela),
 * para não pôr na entrada o que ela não usa.
 */
@Mixin(AbstractFurnaceMenu.class)
public interface AbstractFurnaceMenuAccessor {
    @Invoker("canSmelt")
    boolean handsonvr$canSmelt(ItemStack stack);
}
