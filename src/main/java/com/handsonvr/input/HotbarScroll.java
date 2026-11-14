package com.handsonvr.input;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

/** Troca o item selecionado da hotbar (como o grip do Vivecraft: próximo/anterior, dando a volta). */
public final class HotbarScroll {
    private HotbarScroll() {}

    public static void scroll(MinecraftClient client, int direction) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.currentScreen != null || player.isSpectator()) {
            return;
        }
        var inventory = player.getInventory();
        inventory.selectedSlot = Math.floorMod(inventory.selectedSlot + direction, 9);
    }
}
