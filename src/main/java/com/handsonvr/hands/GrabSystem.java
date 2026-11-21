package com.handsonvr.hands;

import com.handsonvr.holster.Holster;
import com.handsonvr.inv.InvOps;
import com.handsonvr.vr.VrBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Itens "pegos emprestado" com o grip: o item vai para a mão e o que a mão segurava vai para o lugar dele; soltar o
 * grip desfaz. É uma troca, então funciona com o inventário 100% cheio (nenhum espaço fica vazio no caminho).
 *
 * <ul>
 *   <li>{@link Mode#NONE}: o item já estava no espaço da mão.</li>
 *   <li>{@link Mode#SELECTED}: mão principal, item da hotbar: só muda a seleção (volta para {@code previousSelected}).</li>
 *   <li>{@link Mode#SWAPPED}: trocado com o espaço da mão; trocar de novo devolve tudo. Se o item da mão não pode ir
 *   para o lugar do pego (uma espada no espaço da bota), a mão principal passa para um espaço vazio da hotbar
 *   enquanto segura e volta para {@code restoreSelected} ao soltar.</li>
 * </ul>
 *
 * O arco é a exceção: pego pela mão esquerda, ele vai para o espaço da mão principal ({@link Grab#itemHand()} = 0) e
 * o Vivecraft o desenha na esquerda ({@link BowHand}).
 *
 * Mão: 0 = principal, 1 = secundária. {@code source}: espaço do menu do jogador de onde o item veio.
 */
public final class GrabSystem {
    public enum Mode { NONE, SELECTED, SWAPPED }

    /** De onde o item veio. {@code HAND}: o item que a mão já segurava (o grip só o "segura" para guardar). */
    public enum Origin { HOLSTER, BACKPACK, RADIAL, HAND, TABLE }

    /**
     * {@code previousSelected}: SELECTED, a seleção a restaurar; SWAPPED, o espaço da hotbar que fez a troca.
     * {@code restoreSelected}: a seleção depois de desfazer a troca (igual a {@code previousSelected}, menos quando a
     * mão usou um espaço vazio da hotbar). {@code itemHand}: o espaço de mão em que o item pego está (0 principal, 1
     * secundária). É a própria mão, menos no arco pego pela mão esquerda: ele vai para o espaço da mão principal
     * ({@link BowHand}).
     */
    public record Grab(int hand, int source, Mode mode, int previousSelected, Origin origin, Holster holster,
            int restoreSelected, int itemHand) {}

    private final Grab[] grabs = new Grab[2];

    public boolean isGrabbing(int hand) {
        return grabs[hand] != null;
    }

    public Grab get(int hand) {
        return grabs[hand];
    }

    /** Algum item pego agora saiu deste espaço? (Ele fica reservado até o grip soltar.) */
    public boolean isSource(int slot) {
        return (grabs[0] != null && grabs[0].source() == slot) || (grabs[1] != null && grabs[1].source() == slot);
    }

    /** O pego que ocupa este espaço de mão (0 principal, 1 secundária), ou null. */
    public Grab inHandSlot(int itemHand) {
        for (Grab g : grabs) {
            if (g != null && g.itemHand() == itemHand) {
                return g;
            }
        }
        return null;
    }

    /** O espaço do menu em que o item pego está agora (o espaço da mão que o segura). */
    public static int itemSlot(LocalPlayer p, Grab g) {
        return InvOps.handSlot(p, g.itemHand());
    }

    /** Pega o item do espaço {@code source} com a mão. Retorna false se não deu (já pegando, espaço vazio...). */
    public boolean begin(Minecraft client, int hand, int source, Origin origin, Holster holster) {
        return begin(client, hand, source, origin, holster, -1);
    }

    /**
     * Como {@link #begin(Minecraft, int, int, Origin, Holster)}. {@code spareHotbar}: um espaço vazio da hotbar (0-8)
     * que a mão principal usa quando o item dela não pode ir para o lugar do pego (uma espada no espaço da bota); -1 =
     * nenhum. Sem ele (ou na mão esquerda), um item que não cabe no lugar do pego impede de pegar.
     */
    public boolean begin(Minecraft client, int hand, int source, Origin origin, Holster holster, int spareHotbar) {
        LocalPlayer p = client.player;
        if (grabs[hand] != null || !InvOps.ready(client) || InvOps.stack(p, source).isEmpty()) {
            return false;
        }
        Inventory inv = p.getInventory();
        ItemStack taken = InvOps.stack(p, source);
        // O arco pego pela mão esquerda vai para o espaço da mão principal (o Vivecraft só atira assim com as flechas
        // do inventário) e aparece na mão esquerda ({@link BowHand}). Com a mão principal ocupada, fica na esquerda.
        int itemHand = hand;
        boolean bow = origin != Origin.HAND && BowHand.isBow(taken); // segurar o próprio item não troca o arco de mão
        if (hand == 1 && source != InvOps.OFFHAND && bow && grabs[0] == null) {
            itemHand = 0;
            if (spareHotbar < 0) {
                spareHotbar = spareHotbar(p);
            }
        }
        if (inHandSlot(itemHand) != null) {
            return false; // o espaço desta mão já está com o item que a outra mão pegou
        }
        int handSlot = InvOps.handSlot(p, itemHand);
        int selected = inv.getSelectedSlot();
        Grab grab;
        if (source == handSlot) {
            grab = new Grab(hand, source, Mode.NONE, selected, origin, holster, selected, itemHand);
        } else if (itemHand == 0 && source >= InvOps.HOTBAR_START && source < InvOps.HOTBAR_START + 9) {
            inv.setSelectedSlot(source - InvOps.HOTBAR_START);
            grab = new Grab(hand, source, Mode.SELECTED, selected, origin, holster, selected, itemHand);
        } else if (!p.inventoryMenu.getSlot(source).mayPickup(p) || !InvOps.reachable(p, source)) {
            return false; // armadura com Maldição do Vínculo
        } else if (fitsIn(p, handSlot, source)) {
            InvOps.swapWithHand(client, source, itemHand);
            grab = new Grab(hand, source, Mode.SWAPPED, selected, origin, holster, selected, itemHand);
        } else if (itemHand == 0 && spareHotbar >= 0 && InvOps.stack(p, InvOps.HOTBAR_START + spareHotbar).isEmpty()) {
            inv.setSelectedSlot(spareHotbar);
            InvOps.swapWithHand(client, source, itemHand);
            grab = new Grab(hand, source, Mode.SWAPPED, spareHotbar, origin, holster, selected, itemHand);
        } else {
            return false;
        }
        grabs[hand] = grab;
        if (itemHand == 0 && bow) {
            BowHand.took(hand);
        }
        VrBridge.pulse(hand, 0.03f);
        return true;
    }

    /**
     * Devolve o item ao lugar de onde veio e a mão volta a segurar o que tinha. Retorna o pego (null se não havia).
     */
    public Grab end(Minecraft client, int hand) {
        Grab g = grabs[hand];
        grabs[hand] = null;
        LocalPlayer p = client.player;
        if (g == null || p == null || client.gameMode == null) {
            return g;
        }
        switch (g.mode()) {
            case SELECTED -> p.getInventory().setSelectedSlot(g.previousSelected());
            case SWAPPED -> {
                if (InvOps.ready(client)) {
                    // A troca foi com o espaço da mão; se a seleção mudou no meio, devolve com a seleção original.
                    if (g.itemHand() == 0) {
                        p.getInventory().setSelectedSlot(g.previousSelected());
                    }
                    InvOps.swapWithHand(client, g.source(), g.itemHand());
                    if (g.itemHand() == 0) {
                        p.getInventory().setSelectedSlot(g.restoreSelected());
                    }
                }
            }
            default -> { }
        }
        VrBridge.pulse(hand, 0.015f);
        return g;
    }

    /** Um espaço vazio da hotbar (0-8) que não é de nenhum item pego, ou -1. */
    private int spareHotbar(LocalPlayer p) {
        for (int i = 0; i < 9; i++) {
            int slot = InvOps.HOTBAR_START + i;
            if (InvOps.stack(p, slot).isEmpty() && !isSource(slot)) {
                return i;
            }
        }
        return -1;
    }

    /** O item do espaço {@code from} pode ir para o espaço {@code to}? (Vazio sempre pode.) */
    private static boolean fitsIn(LocalPlayer p, int from, int to) {
        ItemStack stack = InvOps.stack(p, from);
        return stack.isEmpty() || p.inventoryMenu.getSlot(to).mayPlace(stack);
    }

    /** Esquece tudo sem mexer no inventário (saiu do mundo). */
    public void reset() {
        grabs[0] = grabs[1] = null;
    }
}
