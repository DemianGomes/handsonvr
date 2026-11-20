package com.handsonvr.inv;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;

/**
 * Operações de inventário feitas só com os cliques da tela de inventário do Minecraft (trocar com 1-9/F, pegar,
 * colocar, Shift+clique, jogar fora). O menu do jogador está sempre "aberto" no servidor (id 0), então tudo funciona
 * em qualquer servidor, sem o mod instalado nele.
 *
 * <p>Os índices são os do menu do jogador ({@link net.minecraft.screen.PlayerScreenHandler}): 0 resultado do
 * crafting, 1-4 grade 2x2, 5-8 armadura (cabeça, peito, pernas, pés), 9-35 inventário principal, 36-44 hotbar,
 * 45 mão secundária.
 */
public final class InvOps {
    private InvOps() {}

    public static final int RESULT = 0, CRAFT_START = 1, ARMOR_START = 5, MAIN_START = 9, HOTBAR_START = 36, OFFHAND = 45;
    /** Botão do clique de troca para a mão secundária (tecla F). */
    public static final int OFFHAND_BUTTON = 40;

    /** O menu do jogador está ativo (nenhum baú/fornalha aberto)? Só então os cliques valem. */
    public static boolean ready(Minecraft client) {
        LocalPlayer p = client.player;
        return p != null && client.gameMode != null && p.containerMenu == p.inventoryMenu;
    }

    /** Espaço do menu em que a mão está: hotbar selecionada (0) ou mão secundária (1). */
    public static int handSlot(LocalPlayer p, int hand) {
        return hand == 0 ? HOTBAR_START + p.getInventory().getSelectedSlot() : OFFHAND;
    }

    /** Espaço do inventário (0-8 hotbar, 9-35, 36-39 armadura, 40 mão secundária) -> espaço do menu. */
    public static int fromInventory(int invSlot) {
        if (invSlot >= 0 && invSlot < 9) {
            return HOTBAR_START + invSlot;
        }
        if (invSlot == Inventory.SLOT_OFFHAND) {
            return OFFHAND;
        }
        if (invSlot >= 36 && invSlot <= 39) {
            return ARMOR_START + (39 - invSlot);
        }
        return invSlot;
    }

    /** Espaço do menu -> espaço do inventário (-1 para o crafting). */
    public static int toInventory(int handlerSlot) {
        if (handlerSlot >= HOTBAR_START && handlerSlot < HOTBAR_START + 9) {
            return handlerSlot - HOTBAR_START;
        }
        if (handlerSlot == OFFHAND) {
            return Inventory.SLOT_OFFHAND;
        }
        if (handlerSlot >= ARMOR_START && handlerSlot < MAIN_START) {
            return 39 - (handlerSlot - ARMOR_START);
        }
        if (handlerSlot >= MAIN_START && handlerSlot < HOTBAR_START) {
            return handlerSlot;
        }
        return -1;
    }

    public static ItemStack stack(LocalPlayer p, int handlerSlot) {
        if (handlerSlot < 0 || handlerSlot >= p.inventoryMenu.slots.size()) {
            return ItemStack.EMPTY;
        }
        return p.inventoryMenu.getSlot(handlerSlot).getItem();
    }

    public static ItemStack cursor(LocalPlayer p) {
        return p.inventoryMenu.getCarried();
    }

    private static void click(Minecraft client, int slot, int button, ContainerInput type) {
        LocalPlayer p = client.player;
        AbstractContainerMenu handler = p.inventoryMenu;
        client.gameMode.handleContainerInput(handler.containerId, slot, button, type, p);
    }

    /** Troca o espaço com a hotbar ({@code button} 0-8) ou com a mão secundária (40). */
    public static void swap(Minecraft client, int handlerSlot, int button) {
        click(client, handlerSlot, button, ContainerInput.SWAP);
    }

    /** Troca o espaço com o espaço da mão (hotbar selecionada ou mão secundária). */
    public static void swapWithHand(Minecraft client, int handlerSlot, int hand) {
        swap(client, handlerSlot, hand == 0 ? client.player.getInventory().getSelectedSlot() : OFFHAND_BUTTON);
    }

    /** Joga fora um item ({@code all} = false) ou a pilha toda do espaço. */
    public static void throwFrom(Minecraft client, int handlerSlot, boolean all) {
        if (!stack(client.player, handlerSlot).isEmpty() && cursor(client.player).isEmpty()) {
            click(client, handlerSlot, all ? 1 : 0, ContainerInput.THROW);
        }
    }

    /** Shift + clique (do crafting/armadura para o inventário; no resultado, fabrica o máximo). */
    public static void quickMove(Minecraft client, int handlerSlot) {
        click(client, handlerSlot, 0, ContainerInput.QUICK_MOVE);
    }

    /**
     * Leva a pilha de {@code from} para {@code to}: junta com a pilha igual, ocupa o espaço vazio ou troca de lugar com
     * a pilha diferente (a que estava em {@code to} vai para {@code from}). Se {@code to} não aceita o item (armadura
     * errada), nada muda. Retorna true se algo saiu de {@code from}.
     */
    public static boolean moveStack(Minecraft client, int from, int to) {
        LocalPlayer p = client.player;
        if (from == to || to == RESULT || stack(p, from).isEmpty() || !cursor(p).isEmpty()) {
            return false;
        }
        ItemStack before = stack(p, from).copy();
        click(client, from, 0, ContainerInput.PICKUP);   // pilha no cursor, "from" vazio
        click(client, to, 0, ContainerInput.PICKUP);     // junta, coloca ou troca com o cursor
        if (!cursor(p).isEmpty()) {
            click(client, from, 0, ContainerInput.PICKUP); // o que sobrou (ou o que estava em "to") volta para "from"
        }
        flushCursor(client);
        return !ItemStack.matches(before, stack(p, from));
    }

    /**
     * Coloca UM item da pilha de {@code from} em {@code to} (vazio ou com o mesmo item e com espaço). O resto fica em
     * {@code from}. Retorna true se colocou.
     */
    public static boolean placeOne(Minecraft client, int from, int to) {
        LocalPlayer p = client.player;
        ItemStack source = stack(p, from);
        ItemStack target = stack(p, to);
        if (from == to || to == RESULT || source.isEmpty() || !cursor(p).isEmpty()) {
            return false;
        }
        if (!target.isEmpty() && (!ItemStack.isSameItemSameComponents(source, target)
                || target.getCount() >= p.inventoryMenu.getSlot(to).getMaxStackSize(target))) {
            return false;
        }
        if (!p.inventoryMenu.getSlot(to).mayPlace(source)) {
            return false;
        }
        int before = target.getCount();
        click(client, from, 0, ContainerInput.PICKUP); // pilha no cursor
        click(client, to, 1, ContainerInput.PICKUP);   // botão direito: coloca um
        if (!cursor(p).isEmpty()) {
            click(client, from, 0, ContainerInput.PICKUP); // o resto volta
        }
        flushCursor(client);
        return stack(p, to).getCount() > before;
    }

    /** Fabrica UM resultado do crafting 2x2 e guarda no inventário (sem espaço: cai no chão). */
    public static boolean craftOne(Minecraft client) {
        LocalPlayer p = client.player;
        if (stack(p, RESULT).isEmpty() || !cursor(p).isEmpty()) {
            return false;
        }
        click(client, RESULT, 0, ContainerInput.PICKUP);
        boolean crafted = !cursor(p).isEmpty();
        flushCursor(client);
        return crafted;
    }

    /**
     * Nunca deixa nada "no cursor" (invisível em VR): junta numa pilha igual, senão num espaço vazio; sem espaço, joga
     * no chão.
     */
    public static void flushCursor(Minecraft client) {
        LocalPlayer p = client.player;
        for (int guard = 0; guard < 4 && !cursor(p).isEmpty(); guard++) {
            ItemStack c = cursor(p);
            int slot = -1;
            for (int i = MAIN_START; i < OFFHAND && slot < 0; i++) {
                ItemStack s = stack(p, i);
                if (!s.isEmpty() && ItemStack.isSameItemSameComponents(s, c) && s.getCount() < s.getMaxStackSize()) {
                    slot = i;
                }
            }
            if (slot < 0) {
                slot = firstEmpty(p, MAIN_START, OFFHAND);
            }
            if (slot >= 0) {
                click(client, slot, 0, ContainerInput.PICKUP);
            } else {
                click(client, AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, 0, ContainerInput.PICKUP);
            }
        }
    }

    /** Primeiro espaço vazio do menu em [from, to), ou -1. */
    public static int firstEmpty(LocalPlayer p, int from, int to) {
        for (int i = from; i < to; i++) {
            if (stack(p, i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }
}
