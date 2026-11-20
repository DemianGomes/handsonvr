package com.handsonvr.hands;

import com.handsonvr.holster.Holster;
import com.handsonvr.inv.InvOps;
import com.handsonvr.vr.VrBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;

/**
 * Itens "pegos emprestado" com o grip: o item vai para a mão e o que a mão segurava vai para o lugar dele; soltar o
 * grip desfaz. É uma troca, então funciona com o inventário 100% cheio (nenhum espaço fica vazio no caminho).
 *
 * <ul>
 *   <li>{@link Mode#NONE}: o item já estava no espaço da mão.</li>
 *   <li>{@link Mode#SELECTED}: mão principal, item da hotbar: só muda a seleção (volta para {@code previousSelected}).</li>
 *   <li>{@link Mode#SWAPPED}: trocado com o espaço da mão; trocar de novo devolve tudo.</li>
 * </ul>
 *
 * Mão: 0 = principal, 1 = secundária. {@code source}: espaço do menu do jogador de onde o item veio.
 */
public final class GrabSystem {
    public enum Mode { NONE, SELECTED, SWAPPED }

    /** De onde o item veio. {@code HAND}: o item que a mão já segurava (o grip só o "segura" para guardar). */
    public enum Origin { HOLSTER, BACKPACK, RADIAL, HAND }

    public record Grab(int hand, int source, Mode mode, int previousSelected, Origin origin, Holster holster) {}

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

    /** Pega o item do espaço {@code source} com a mão. Retorna false se não deu (já pegando, espaço vazio...). */
    public boolean begin(Minecraft client, int hand, int source, Origin origin, Holster holster) {
        LocalPlayer p = client.player;
        if (grabs[hand] != null || !InvOps.ready(client) || InvOps.stack(p, source).isEmpty()) {
            return false;
        }
        Inventory inv = p.getInventory();
        int handSlot = InvOps.handSlot(p, hand);
        Grab grab;
        if (source == handSlot) {
            grab = new Grab(hand, source, Mode.NONE, inv.getSelectedSlot(), origin, holster);
        } else if (hand == 0 && source >= InvOps.HOTBAR_START && source < InvOps.HOTBAR_START + 9) {
            int previous = inv.getSelectedSlot();
            inv.setSelectedSlot(source - InvOps.HOTBAR_START);
            grab = new Grab(hand, source, Mode.SELECTED, previous, origin, holster);
        } else {
            InvOps.swapWithHand(client, source, hand);
            grab = new Grab(hand, source, Mode.SWAPPED, inv.getSelectedSlot(), origin, holster);
        }
        grabs[hand] = grab;
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
                    if (hand == 0) {
                        p.getInventory().setSelectedSlot(g.previousSelected());
                    }
                    InvOps.swapWithHand(client, g.source(), hand);
                }
            }
            default -> { }
        }
        VrBridge.pulse(hand, 0.015f);
        return g;
    }

    /** Esquece tudo sem mexer no inventário (saiu do mundo). */
    public void reset() {
        grabs[0] = grabs[1] = null;
    }
}
