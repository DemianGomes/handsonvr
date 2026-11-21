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
 *
 * <p>A mesa de trabalho e as fornalhas imersivas ({@link com.handsonvr.table.CraftingTable},
 * {@link com.handsonvr.furnace.FurnaceStation}) abrem o menu do bloco no servidor sem a tela ("menu escondido").
 * Enquanto ele está aberto, os cliques do inventário vão para ele: o inventário principal e a hotbar existem lá (em
 * outros índices, traduzidos por {@link #hiddenIndex}) e a troca com a mão secundária continua (botão 40). A armadura,
 * o crafting 2x2 e o resultado dele não existem nesse menu. Com a mesa, eles esperam ela fechar ({@link #reachable});
 * a fornalha, que não perde nada ao fechar, cede o menu na hora ({@link #prepare}).
 */
public final class InvOps {
    private InvOps() {}

    public static final int RESULT = 0, CRAFT_START = 1, ARMOR_START = 5, MAIN_START = 9, HOTBAR_START = 36, OFFHAND = 45;
    /** Botão do clique de troca para a mão secundária (tecla F). */
    public static final int OFFHAND_BUTTON = 40;

    /** O menu de um bloco aberto sem tela pelo mod (null = nenhum). */
    private static AbstractContainerMenu hidden;
    /** Fecha o menu escondido quando o inventário inteiro é preciso (null = não pode: a mesa devolveria a grade). */
    private static Runnable hiddenRelease;
    /** Espaço do inventário (0-40) -> espaço do menu escondido (-1 = não existe nele). */
    private static int[] hiddenMap = new int[0];

    public static void setHidden(AbstractContainerMenu menu) {
        setHidden(menu, null);
    }

    /** O menu escondido agora; {@code release} fecha ele sem perder nada (null se fechar mudaria algo). */
    public static void setHidden(AbstractContainerMenu menu, Runnable release) {
        hidden = menu;
        hiddenRelease = menu == null ? null : release;
        hiddenMap = new int[Inventory.SLOT_OFFHAND + 1];
        java.util.Arrays.fill(hiddenMap, -1);
        if (menu != null) {
            for (int i = 0; i < menu.slots.size(); i++) {
                net.minecraft.world.inventory.Slot s = menu.slots.get(i);
                int c = s.getContainerSlot();
                if (s.container instanceof Inventory && c >= 0 && c < hiddenMap.length && hiddenMap[c] < 0) {
                    hiddenMap[c] = i;
                }
            }
        }
    }

    /** Esquece o menu escondido, se ainda for este (outro bloco pode já ter aberto o dele). */
    public static void clearHidden(AbstractContainerMenu menu) {
        if (menu != null && hidden == menu) {
            setHidden(null);
        }
    }

    /**
     * Fecha o menu escondido, se ele pode fechar sem perder nada (a fornalha). Retorna true se o menu do jogador ficou
     * ativo (ou já estava).
     */
    public static boolean releaseHidden(Minecraft client) {
        LocalPlayer p = client.player;
        if (p == null) {
            return false;
        }
        if (hiddenOpen(p) && hiddenRelease != null) {
            hiddenRelease.run();
        }
        return p.containerMenu == p.inventoryMenu;
    }

    /**
     * Antes de uma operação com estes espaços: se algum não existe no menu escondido e ele pode fechar sem perder nada
     * (a fornalha), fecha agora, para a operação inteira acontecer no menu do jogador.
     */
    private static void prepare(Minecraft client, int... slots) {
        LocalPlayer p = client.player;
        if (!hiddenOpen(p) || hiddenRelease == null) {
            return;
        }
        for (int slot : slots) {
            if (slot != AbstractContainerMenu.SLOT_CLICKED_OUTSIDE && hiddenIndex(slot) < 0) {
                releaseHidden(client);
                return;
            }
        }
    }

    /** O menu escondido está aberto agora (é o menu ativo do jogador)? */
    public static boolean hiddenOpen(LocalPlayer p) {
        return p != null && hidden != null && p.containerMenu == hidden;
    }

    /**
     * O menu do jogador está ativo (nenhum baú/fornalha aberto, só o menu escondido da mesa)? Só então os cliques
     * valem.
     */
    public static boolean ready(Minecraft client) {
        LocalPlayer p = client.player;
        return p != null && client.gameMode != null && (p.containerMenu == p.inventoryMenu || hiddenOpen(p));
    }

    /**
     * Este espaço do menu do jogador pode ser pego/colocado agora? Sempre, menos com o menu escondido da mesa aberto:
     * aí só o inventário principal e a hotbar (a mão secundária só pela troca, {@link #swap}). Com o da fornalha, sim:
     * ele fecha antes ({@link #prepare}).
     */
    public static boolean reachable(LocalPlayer p, int handlerSlot) {
        if (p == null || !hiddenOpen(p)) {
            return true;
        }
        return hiddenIndex(handlerSlot) >= 0 || hiddenRelease != null;
    }

    /** Espaço do menu do jogador -> espaço do menu escondido (-1 se não existe lá). */
    public static int hiddenIndex(int handlerSlot) {
        int inv = toInventory(handlerSlot);
        return inv < 0 || inv >= hiddenMap.length ? -1 : hiddenMap[inv];
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

    /** O item "no cursor" do menu ativo (o do jogador ou o escondido da mesa). */
    public static ItemStack cursor(LocalPlayer p) {
        return hiddenOpen(p) ? hidden.getCarried() : p.inventoryMenu.getCarried();
    }

    /**
     * Um clique com o índice do menu do jogador, no menu ativo. Com o menu escondido da mesa aberto, o índice é
     * traduzido; um espaço que não existe lá é ignorado (veja {@link #reachable}).
     */
    private static void click(Minecraft client, int slot, int button, ContainerInput type) {
        LocalPlayer p = client.player;
        if (hiddenOpen(p) && hiddenRelease != null && slot != AbstractContainerMenu.SLOT_CLICKED_OUTSIDE
                && !(type == ContainerInput.SWAP && slot == OFFHAND) && hiddenIndex(slot) < 0) {
            releaseHidden(client); // a fornalha cede o menu: o clique vai para o menu do jogador
        }
        AbstractContainerMenu menu = p.containerMenu;
        if (menu != p.inventoryMenu) {
            if (!hiddenOpen(p)) {
                return; // outro menu (baú, fornalha) aberto: nada
            }
            if (slot != AbstractContainerMenu.SLOT_CLICKED_OUTSIDE) {
                if (type == ContainerInput.SWAP && slot == OFFHAND) {
                    // A mão secundária não é um espaço do menu da mesa, mas a troca com ela é: trocar a mão secundária
                    // com a hotbar N é o mesmo que trocar a hotbar N com a mão secundária.
                    if (button == OFFHAND_BUTTON) {
                        return;
                    }
                    slot = HOTBAR_START + button;
                    button = OFFHAND_BUTTON;
                }
                slot = hiddenIndex(slot);
                if (slot < 0) {
                    return;
                }
            }
        }
        client.gameMode.handleContainerInput(menu.containerId, slot, button, type, p);
    }

    /** Um clique direto no menu escondido da mesa (índices do menu da mesa: 0 resultado, 1-9 grade). */
    public static void clickHidden(Minecraft client, int menuSlot, int button, ContainerInput type) {
        LocalPlayer p = client.player;
        if (hiddenOpen(p)) {
            client.gameMode.handleContainerInput(hidden.containerId, menuSlot, button, type, p);
        }
    }

    /** Troca o espaço com a hotbar ({@code button} 0-8) ou com a mão secundária (40). */
    public static void swap(Minecraft client, int handlerSlot, int button) {
        if (handlerSlot != OFFHAND) {
            prepare(client, handlerSlot);
        }
        click(client, handlerSlot, button, ContainerInput.SWAP);
    }

    /** Troca o espaço com o espaço da mão (hotbar selecionada ou mão secundária). */
    public static void swapWithHand(Minecraft client, int handlerSlot, int hand) {
        swap(client, handlerSlot, hand == 0 ? client.player.getInventory().getSelectedSlot() : OFFHAND_BUTTON);
    }

    /** Joga fora um item ({@code all} = false) ou a pilha toda do espaço. */
    public static void throwFrom(Minecraft client, int handlerSlot, boolean all) {
        prepare(client, handlerSlot);
        if (!stack(client.player, handlerSlot).isEmpty() && cursor(client.player).isEmpty()) {
            click(client, handlerSlot, all ? 1 : 0, ContainerInput.THROW);
        }
    }

    /** Shift + clique (do crafting/armadura para o inventário; no resultado, fabrica o máximo). */
    public static void quickMove(Minecraft client, int handlerSlot) {
        prepare(client, handlerSlot);
        click(client, handlerSlot, 0, ContainerInput.QUICK_MOVE);
    }

    /**
     * Leva a pilha de {@code from} para {@code to}: junta com a pilha igual, ocupa o espaço vazio ou troca de lugar com
     * a pilha diferente (a que estava em {@code to} vai para {@code from}). Se {@code to} não aceita o item (armadura
     * errada), nada muda. Retorna true se algo saiu de {@code from}.
     */
    public static boolean moveStack(Minecraft client, int from, int to) {
        LocalPlayer p = client.player;
        prepare(client, from, to);
        if (from == to || to == RESULT || stack(p, from).isEmpty() || !cursor(p).isEmpty() || !reachable(p, from)
                || !reachable(p, to)) {
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
        prepare(client, from, to);
        ItemStack source = stack(p, from);
        ItemStack target = stack(p, to);
        if (from == to || to == RESULT || source.isEmpty() || !cursor(p).isEmpty() || !reachable(p, from)
                || !reachable(p, to)) {
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

    /**
     * Fabrica UM resultado do crafting 2x2 direto no espaço {@code target} (vazio, ou com o mesmo item e espaço para
     * tudo; o que não couber vai para o inventário). Retorna true se fabricou.
     */
    public static boolean craftInto(Minecraft client, int target) {
        LocalPlayer p = client.player;
        prepare(client, RESULT, target);
        if (stack(p, RESULT).isEmpty() || !cursor(p).isEmpty() || !reachable(p, RESULT) || !reachable(p, target)) {
            return false;
        }
        click(client, RESULT, 0, ContainerInput.PICKUP);
        boolean crafted = !cursor(p).isEmpty();
        if (crafted) {
            click(client, target, 0, ContainerInput.PICKUP);
        }
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

    /**
     * Quantos itens o "fabricar tudo" (o Shift+clique) faria com esta grade: o resultado de uma receita vezes quantas
     * vezes a grade dá (cada fabricação gasta um item de cada espaço ocupado). Ex.: 4 toras dão 4 x 4 = 16 tábuas.
     */
    public static int craftTotal(ItemStack result, ItemStack... grid) {
        if (result.isEmpty()) {
            return 0;
        }
        int times = Integer.MAX_VALUE;
        for (ItemStack s : grid) {
            if (!s.isEmpty()) {
                times = Math.min(times, s.getCount());
            }
        }
        return times == Integer.MAX_VALUE ? result.getCount() : result.getCount() * times;
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
