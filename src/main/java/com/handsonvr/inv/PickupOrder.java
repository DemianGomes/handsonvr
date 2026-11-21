package com.handsonvr.inv;

import com.handsonvr.HandsOnConfig;
import com.handsonvr.HandsOnVR;
import com.handsonvr.hands.HandsController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * Itens que chegam ao inventário (coletados do chão, sobras do crafting, recompensas) vão primeiro para o inventário
 * principal (a mochila) e só depois para a hotbar. No jogo original é o contrário: a hotbar enche primeiro e vai
 * ficando com blocos que o jogador não escolheu.
 *
 * <ul>
 *   <li><b>Mundo um jogador (e LAN aberta pelo jogador):</b> o servidor roda neste mesmo programa, então o mixin do
 *       inventário ({@code InventoryMixin}) muda a escolha do espaço livre ({@code Inventory.getFreeSlot}) para o
 *       jogador local: nada passa pela hotbar.</li>
 *   <li><b>Servidor de outra pessoa (o mod só no cliente):</b> o servidor põe o item na hotbar. A cada tick, um item
 *       novo (a quantidade dele no inventário aumentou) que caiu num espaço vazio da hotbar é levado para um espaço
 *       vazio do inventário principal, com os cliques normais do inventário.</li>
 * </ul>
 *
 * Pilhas iguais continuam se juntando onde estiverem (a escolha do espaço livre só vale para o que não coube nelas).
 */
public final class PickupOrder {
    private PickupOrder() {}

    /** Teste automático: finge um servidor de outra pessoa (sem o mixin, com a correção pelo cliente). */
    public static boolean debugRemote = false;

    // ------------------------------------------------------------------ servidor integrado (mixin)

    /** O mixin muda a escolha do espaço livre deste inventário? (O jogador local, com o servidor neste programa.) */
    public static boolean appliesTo(Player player) {
        if (player == null || debugRemote || !HandsOnConfig.get().pickupToMain) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null || !client.hasSingleplayerServer()) {
            return false; // servidor remoto: quem escolhe é ele (a correção pelo cliente cuida)
        }
        UUID local = client.getUser().getProfileId();
        return local != null && local.equals(player.getUUID());
    }

    /**
     * O espaço livre do inventário do jogador: primeiro o inventário principal (9-35), depois a hotbar (0-8). -1 se
     * não houver.
     */
    public static int freeSlot(NonNullList<ItemStack> items) {
        for (int i = 9; i < items.size(); i++) {
            if (items.get(i).isEmpty()) {
                return i;
            }
        }
        for (int i = 0; i < Math.min(9, items.size()); i++) {
            if (items.get(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ servidor remoto (correção pelo cliente)

    /** Os espaços do menu do jogador (1-45, sem o resultado do crafting) e o cursor no tick anterior. */
    private static final ItemStack[] before = new ItemStack[InvOps.OFFHAND + 1];
    private static ItemStack cursorBefore = ItemStack.EMPTY;
    private static boolean hasBefore = false;
    /** Quantos itens a correção levou para o inventário principal (teste automático). */
    private static int moved = 0;

    public static int debugMoved() {
        return moved;
    }

    public static void reset() {
        hasBefore = false;
    }

    /** A cada tick: num servidor remoto, leva o item novo que caiu na hotbar para o inventário principal. */
    public static void tick(Minecraft client, HandsController hands) {
        LocalPlayer p = client.player;
        if (p == null) {
            hasBefore = false;
            return;
        }
        boolean remote = debugRemote || !client.hasSingleplayerServer();
        boolean canAct = remote && hasBefore && HandsOnConfig.get().pickupToMain && InvOps.ready(client)
                && client.gui.screen() == null && InvOps.cursor(p).isEmpty();
        if (canAct) {
            for (int from = InvOps.HOTBAR_START; from < InvOps.HOTBAR_START + 9; from++) {
                ItemStack now = InvOps.stack(p, from);
                if (now.isEmpty() || !before[from].isEmpty() || !arrived(p, now)) {
                    continue;
                }
                if (hands.isLocked(p, from) || from - InvOps.HOTBAR_START == hands.offSource()) {
                    continue; // espaço em uso por um item pego / guardado para a mão esquerda
                }
                int to = emptyMain(p, hands);
                if (to < 0) {
                    break; // inventário principal cheio: fica na hotbar, como no jogo
                }
                if (InvOps.moveStack(client, from, to)) {
                    moved++;
                    HandsOnVR.LOGGER.debug("[HandsOn VR] item que chegou levado da hotbar ({}) para o espaço {}", from, to);
                }
            }
        }
        snapshot(p);
    }

    /**
     * A quantidade deste item aumentou desde o tick anterior, contando todos os espaços (hotbar, inventário, armadura,
     * mão esquerda, crafting 2x2) e o cursor? Então ele chegou de fora; se só mudou de lugar, a soma é a mesma.
     */
    private static boolean arrived(LocalPlayer p, ItemStack stack) {
        int now = count(InvOps.cursor(p), stack), was = count(cursorBefore, stack);
        for (int i = InvOps.CRAFT_START; i <= InvOps.OFFHAND; i++) {
            now += count(InvOps.stack(p, i), stack);
            was += count(before[i], stack);
        }
        return now > was;
    }

    private static int count(ItemStack s, ItemStack like) {
        return s != null && ItemStack.isSameItemSameComponents(s, like) ? s.getCount() : 0;
    }

    private static void snapshot(LocalPlayer p) {
        for (int i = InvOps.CRAFT_START; i <= InvOps.OFFHAND; i++) {
            before[i] = InvOps.stack(p, i).copy();
        }
        cursorBefore = InvOps.cursor(p).copy();
        hasBefore = true;
    }

    private static int emptyMain(LocalPlayer p, HandsController hands) {
        for (int i = InvOps.MAIN_START; i < InvOps.HOTBAR_START; i++) {
            if (InvOps.stack(p, i).isEmpty() && !hands.isLocked(p, i)) {
                return i;
            }
        }
        return -1;
    }
}
