package com.handsonvr.mixin;

import com.handsonvr.HandsOnVR;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * O servidor mandou abrir um menu (ClientboundOpenScreenPacket). Se é o da mesa de trabalho que o mod pediu (a mesa
 * imersiva), o mod fica com o menu e a tela não abre. Qualquer outro menu, ou a mesa usada normalmente, abre a tela
 * como sempre.
 */
@Mixin(MenuScreens.class)
public abstract class MenuScreensMixin {
    @Inject(method = "create", at = @At("HEAD"), cancellable = true)
    private static <T extends AbstractContainerMenu> void handsonvr$hiddenCraftingTable(MenuType<T> type, Minecraft client,
            int containerId, Component title, CallbackInfo ci) {
        if (HandsOnVR.openHiddenMenu(type, client, containerId)) {
            ci.cancel();
        }
    }
}
