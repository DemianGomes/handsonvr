package com.handsonvr.hands;

import com.handsonvr.vr.VrBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * O arco na mão que o pegou.
 *
 * <p>O arco do Vivecraft ("Roomscale Bow") não segue o item: com o arco no espaço da mão principal, ele desenha o arco
 * no controle da mão secundária (esquerdo) e a flecha no da principal (direito). Só a opção "Reverse Bow" do Vivecraft
 * inverte isso. Com o arco na mão secundária, o Vivecraft só atira com as flechas na mão principal.
 *
 * <p>Por isso o arco sempre vai para o espaço da mão principal (o Vivecraft usa as flechas do inventário), também
 * quando a mão esquerda o pega, e o HandsOn liga o "Reverse Bow" quando a mão direita o pegou e desliga quando foi a
 * esquerda. A flecha aparece na outra mão. Sem o arco na mão (ou com uma tela aberta), a escolha do jogador volta.
 */
public final class BowHand {
    private BowHand() {}

    /** Mão que pegou o arco que está no espaço da mão principal (0 direita, 1 esquerda); -1 = a escolha do jogador. */
    private static int bowHand = -1;
    /** O "Reverse Bow" do jogador antes de o mod mexer (null = não mexeu). */
    private static Boolean userReverse = null;

    /** O Vivecraft trata este item como arco (respeita o modo do arco nos ajustes dele)? */
    public static boolean isBow(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (VrBridge.isVivecraftLoaded()) {
            try {
                return Viv.isBow(stack);
            } catch (Throwable ignored) {
                // Vivecraft mudou: o arco do jogo
            }
        }
        return stack.is(Items.BOW);
    }

    /** O arco que está (ou vai estar) no espaço da mão principal foi pego por esta mão. */
    public static void took(int hand) {
        bowHand = hand;
    }

    /** Mão que segura o arco agora (-1: sem arco na mão principal, ou a escolha do jogador). */
    public static int bowHand() {
        return bowHand;
    }

    /**
     * Esta mão é a "da flecha" agora (o arco está na outra)? Então o gatilho dela é do Vivecraft (encaixar e puxar a
     * flecha), não do mod.
     */
    public static boolean isArrowHand(Minecraft client, int hand) {
        LocalPlayer p = client.player;
        if (p == null || !isBow(p.getMainHandItem())) {
            return false;
        }
        int holder = bowHand >= 0 ? bowHand : reverse() == Boolean.TRUE ? 0 : 1;
        return holder != hand;
    }

    /** A cada tick: o "Reverse Bow" do Vivecraft segue a mão que pegou o arco. */
    public static void tick(Minecraft client) {
        LocalPlayer p = client.player;
        boolean bow = p != null && isBow(p.getMainHandItem());
        if (!bow) {
            bowHand = -1;
        }
        if (!bow || !VrBridge.isVRActive() || client.gui.screen() != null || !VrBridge.isVivecraftLoaded()) {
            restore(); // com uma tela aberta (os ajustes do Vivecraft), a escolha do jogador aparece e pode mudar
            return;
        }
        try {
            if (userReverse == null) {
                userReverse = Viv.reverse();
            }
            Viv.setReverse(bowHand < 0 ? userReverse : bowHand == 0);
        } catch (Throwable ignored) {
            userReverse = null;
        }
    }

    /** Devolve a escolha do jogador (o arco saiu da mão, saiu do mundo). */
    public static void restore() {
        if (userReverse == null) {
            return;
        }
        try {
            Viv.setReverse(userReverse);
        } catch (Throwable ignored) {
        }
        userReverse = null;
    }

    /** O "Reverse Bow" do Vivecraft agora (null sem Vivecraft). Para o teste automático. */
    public static Boolean reverse() {
        if (!VrBridge.isVivecraftLoaded()) {
            return null;
        }
        try {
            return Viv.reverse();
        } catch (Throwable t) {
            return null;
        }
    }

    /** O que mexe no Vivecraft (API interna) fica aqui: sem o Vivecraft, esta classe nunca é carregada. */
    private static final class Viv {
        static boolean isBow(ItemStack stack) {
            return org.vivecraft.client_vr.gameplay.trackers.BowTracker.isBow(stack);
        }

        static boolean reverse() {
            return org.vivecraft.client_vr.ClientDataHolderVR.getInstance().vrSettings.reverseShootingEye;
        }

        static void setReverse(boolean value) {
            org.vivecraft.client_vr.ClientDataHolderVR.getInstance().vrSettings.reverseShootingEye = value;
        }
    }
}
