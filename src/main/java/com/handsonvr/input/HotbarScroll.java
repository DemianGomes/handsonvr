package com.handsonvr.input;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** Troca o item selecionado da hotbar (como o grip do Vivecraft: próximo/anterior, dando a volta). */
public final class HotbarScroll {
    private HotbarScroll() {}

    public static void scroll(Minecraft client, int direction) {
        LocalPlayer player = client.player;
        if (player == null || client.gui.screen() != null || player.isSpectator()) {
            return;
        }
        var inventory = player.getInventory();
        inventory.setSelectedSlot(Math.floorMod(inventory.getSelectedSlot() + direction, 9));
    }
}
