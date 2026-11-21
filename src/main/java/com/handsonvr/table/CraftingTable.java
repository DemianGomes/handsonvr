package com.handsonvr.table;

import com.handsonvr.HandsOnVR;
import com.handsonvr.inv.InvOps;
import com.handsonvr.vr.VrBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A mesa de trabalho imersiva (a nossa versão da do ImmersiveMC): a grade 3x3 fica no topo da mesa, os itens ficam
 * deitados em cada casa e o resultado flutua acima deles. Pegar o resultado com o grip fabrica e o item vem para a mão.
 *
 * <p><b>Por baixo, é a mesa do jogo.</b> Na primeira vez que a mão leva um item à mesa, o mod "usa" a mesa como o
 * jogador faria e o servidor abre o menu da mesa; o mod fica com o menu sem abrir a tela ({@code MenuScreensMixin}). A
 * grade e o resultado são os do servidor (ele confere a receita), e tudo usa os cliques normais do menu: funciona em
 * qualquer servidor, sem o mod nele. Enquanto o menu está aberto, o resto do mod continua usando o inventário pelos
 * cliques desse menu ({@link InvOps}).
 *
 * <p>Como no jogo, os itens da grade voltam para o inventário quando o menu fecha: ao se afastar da mesa, ao abrir
 * outra tela, ou depois de um tempo com a grade vazia.
 *
 * <p>Geometria: a textura do topo da mesa ({@code crafting_table_top}) tem a grade desenhada com linhas nos pixels 3, 6,
 * 9 e 12. As casas têm 3 pixels (3/16 de bloco) e ficam centradas no topo. A linha de cima da receita é a mais longe
 * do jogador, como na tela do jogo; a direção é escolhida (entre os 4 lados) quando a mesa abre e fica fixa até fechar.
 */
public final class CraftingTable {
    /** Lado de uma casa (blocos): 3 pixels da textura do topo. */
    public static final double CELL = 3 / 16.0;
    /** Alvo de cada casa: meia casa mais uma folga. */
    private static final double TOUCH_HALF = CELL / 2 + 0.012;
    /** A ponta da mão toca a mesa de um pouco abaixo até esta altura acima do topo (m). */
    private static final double TOUCH_ABOVE = 0.15, TOUCH_BELOW = 0.05;
    /** O resultado flutua a esta altura acima do topo, e a mão o pega a esta distância (m). */
    public static final double RESULT_HEIGHT = 0.30, RESULT_REACH = 0.15;
    /** Mesas mais longe que isto da cabeça (m, até o centro do topo) não contam. */
    private static final double NEAR = 2.2;
    /** Com o menu aberto, longe disto a mesa fecha e os itens voltam para o inventário (m). */
    private static final double FAR = 4.0;
    /** Espera do menu (ticks) e tempo com a grade vazia e as mãos longe até fechar sozinha. */
    private static final int OPEN_TIMEOUT = 40, IDLE_CLOSE = 100, RETRY_WAIT = 100;
    /** Índices do menu da mesa: 0 resultado, 1-9 grade. */
    private static final int MENU_RESULT = 0, MENU_GRID = 1;

    /** Alvo sob a mão: uma casa (0-8, linha a linha, a de cima é a mais longe), o resultado, ou nada. */
    public static final int RESULT = 9, NONE = -1;

    private BlockPos pos;
    /** Para longe do jogador (um dos 4 lados), no plano. */
    private Vec3 forward = new Vec3(0, 0, 1);
    private CraftingMenu menu;
    private long ticks = 0, expectUntil = -1, retryAfter = 0;
    private int idle = 0;
    private final int[] hover = {NONE, NONE};
    private final List<Consumer<Minecraft>> queue = new ArrayList<>();
    private String last = "-";

    // ------------------------------------------------------------------ estado

    public BlockPos pos() {
        return pos;
    }

    public Vec3 forward() {
        return forward;
    }

    public Vec3 right() {
        return new Vec3(-forward.z, 0, forward.x);
    }

    /** O menu da mesa está aberto (sem tela)? */
    public boolean isOpen() {
        return menu != null;
    }

    public boolean isWaiting() {
        return expectUntil >= 0;
    }

    public String last() {
        return last;
    }

    public int hover(int hand) {
        return hover[hand];
    }

    /** O item de uma casa (0-8) como o servidor mandou. */
    public ItemStack cellStack(int cell) {
        return menu == null || cell < 0 || cell > 8 ? ItemStack.EMPTY : menu.getSlot(MENU_GRID + cell).getItem();
    }

    /** O que a receita da grade fabrica agora (vazio se nada). */
    public ItemStack resultStack() {
        return menu == null ? ItemStack.EMPTY : menu.getSlot(MENU_RESULT).getItem();
    }

    /** Quantos itens o "fabricar tudo" (grip + gatilho) dá com a grade de agora (ex.: 4 toras, 16 tábuas). */
    public int resultTotal() {
        ItemStack[] grid = new ItemStack[9];
        for (int i = 0; i < 9; i++) {
            grid[i] = cellStack(i);
        }
        return InvOps.craftTotal(resultStack(), grid);
    }

    public boolean gridEmpty() {
        for (int i = 0; i < 9; i++) {
            if (!cellStack(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Uma mão está sobre a mesa: o golpe do Vivecraft fica desligado (não quebra a mesa ao pôr os itens). */
    public boolean blocksSwing() {
        return pos != null && (hover[0] != NONE || hover[1] != NONE);
    }

    public void reset() {
        pos = null;
        InvOps.clearHidden(menu);
        menu = null;
        expectUntil = -1;
        queue.clear();
        hover[0] = hover[1] = NONE;
    }

    // ------------------------------------------------------------------ geometria

    public Vec3 topCenter() {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
    }

    /** Centro de uma casa (0-8) no topo da mesa. */
    public Vec3 cellCenter(int cell) {
        int row = cell / 3, col = cell % 3;
        return topCenter().add(right().scale((col - 1) * CELL)).add(forward.scale((1 - row) * CELL));
    }

    /** Onde o resultado flutua (sem o balanço do desenho). */
    public Vec3 resultCenter() {
        return topCenter().add(0, RESULT_HEIGHT, 0);
    }

    /** A ponta da mão está no lugar do resultado (com a mesa aberta, mesmo sem nada flutuando agora)? */
    public boolean nearResult(Vec3 tip) {
        return pos != null && tip != null && menu != null && tip.distanceTo(resultCenter()) <= RESULT_REACH;
    }

    /** O alvo sob a ponta da mão (uma casa, o resultado, ou {@link #NONE}). */
    public int targetAt(Vec3 tip) {
        if (pos == null || tip == null) {
            return NONE;
        }
        if (!resultStack().isEmpty() && tip.distanceTo(resultCenter()) <= RESULT_REACH) {
            return RESULT;
        }
        Vec3 d = tip.subtract(topCenter());
        if (d.y < -TOUCH_BELOW || d.y > TOUCH_ABOVE) {
            return NONE;
        }
        double x = d.dot(right()), z = d.dot(forward);
        int col = (int) Math.round(x / CELL) + 1, row = 1 - (int) Math.round(z / CELL);
        if (col < 0 || col > 2 || row < 0 || row > 2) {
            return NONE;
        }
        if (Math.abs(x - (col - 1) * CELL) > TOUCH_HALF || Math.abs(z - (1 - row) * CELL) > TOUCH_HALF) {
            return NONE;
        }
        return row * 3 + col;
    }

    /** Atualiza o alvo da mão; true se entrou num alvo novo (vibração de "passou por cima"). */
    public boolean updateHover(int hand, int target) {
        int before = hover[hand];
        hover[hand] = target;
        return target != NONE && target != before;
    }

    // ------------------------------------------------------------------ tick

    public void tick(Minecraft client) {
        ticks++;
        LocalPlayer p = client.player;
        if (p == null || client.level == null) {
            reset();
            return;
        }
        // O menu escondido fechou por fora (o servidor fechou, ou outro menu tomou o lugar): os itens já voltaram.
        if (menu != null && p.containerMenu != menu) {
            drop("a mesa fechou (pelo jogo)");
        }
        // Uma tela de inventário abriu (a do jogador, que não passa pelo servidor): fecha a mesa antes.
        if (menu != null && client.gui.screen() instanceof AbstractContainerScreen<?>) {
            close(client, "tela aberta");
        }
        if (expectUntil >= 0 && ticks > expectUntil) {
            // O servidor não abriu (área protegida, modo aventura...): vibra "negado" e espera antes de tentar de novo.
            expectUntil = -1;
            retryAfter = ticks + RETRY_WAIT;
            queue.clear();
            last = "a mesa não abriu";
            VrBridge.pulse(0, 0.08f);
            VrBridge.pulse(1, 0.08f);
        }
        if (menu != null || expectUntil >= 0) {
            if (!isTable(client.level, pos) || headDistance(pos) > FAR) {
                close(client, "longe da mesa");
            }
        } else {
            pos = VrBridge.isVRActive() && client.gui.screen() == null ? findTable(client) : null;
            if (pos != null) {
                forward = facing(pos);
            }
        }
        if (pos == null) {
            hover[0] = hover[1] = NONE;
            return;
        }
        if (menu != null && !queue.isEmpty() && p.containerMenu == menu) {
            List<Consumer<Minecraft>> todo = new ArrayList<>(queue);
            queue.clear();
            todo.forEach(a -> a.accept(client));
        }
        // Grade vazia, nada para fabricar e as mãos longe por um tempo: fecha (libera a armadura e o crafting 2x2).
        if (menu != null && gridEmpty() && resultStack().isEmpty() && hover[0] == NONE && hover[1] == NONE && queue.isEmpty()) {
            if (++idle > IDLE_CLOSE) {
                close(client, "mesa vazia");
            }
        } else {
            idle = 0;
        }
    }

    private static boolean isTable(Level level, BlockPos pos) {
        if (pos == null || !level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
            return false;
        }
        BlockPos above = pos.above();
        return level.getBlockState(above).getCollisionShape(level, above).isEmpty(); // o topo precisa estar livre
    }

    private static double headDistance(BlockPos pos) {
        VrBridge.Pose head = VrBridge.head();
        Vec3 top = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        return head == null ? Double.MAX_VALUE : head.position().distanceTo(top);
    }

    /** A mesa de trabalho mais perto da cabeça (até {@link #NEAR}), ou null. */
    private BlockPos findTable(Minecraft client) {
        VrBridge.Pose head = VrBridge.head();
        if (head == null) {
            return null;
        }
        BlockPos center = BlockPos.containing(head.position());
        BlockPos best = null;
        double bestDistance = NEAR;
        for (BlockPos q : BlockPos.betweenClosed(center.offset(-2, -3, -2), center.offset(2, 0, 2))) {
            if (isTable(client.level, q)) {
                double d = headDistance(q);
                if (d <= bestDistance) {
                    best = q.immutable();
                    bestDistance = d;
                }
            }
        }
        return best;
    }

    /** O lado da mesa "para longe" do jogador: o eixo mais perto da direção da cabeça até a mesa. */
    private static Vec3 facing(BlockPos pos) {
        VrBridge.Pose head = VrBridge.head();
        if (head == null) {
            return new Vec3(0, 0, 1);
        }
        double dx = pos.getX() + 0.5 - head.position().x, dz = pos.getZ() + 0.5 - head.position().z;
        if (Math.abs(dx) > Math.abs(dz)) {
            return new Vec3(Math.signum(dx), 0, 0);
        }
        return new Vec3(0, 0, dz >= 0 ? 1 : -1);
    }

    // ------------------------------------------------------------------ abrir e fechar

    /**
     * Abre o menu da mesa (sem tela), se ainda não estiver aberto ou a caminho. {@code canOpen}: o resto do mod deixa
     * (nenhum item pego da armadura ou do crafting 2x2, que não existem no menu da mesa). Retorna true se está aberto
     * ou vai abrir.
     */
    public boolean ensureOpen(Minecraft client, boolean canOpen) {
        if (menu != null || expectUntil >= 0) {
            return true;
        }
        LocalPlayer p = client.player;
        if (pos == null || p == null || client.gameMode == null || !canOpen || client.gui.screen() != null
                || p.isSecondaryUseActive() || ticks < retryAfter) {
            return false;
        }
        // Uma fornalha imersiva aberta cede o menu (ela não perde nada ao fechar).
        if (p.containerMenu != p.inventoryMenu && !InvOps.releaseHidden(client)) {
            return false;
        }
        forward = facing(pos);
        expectUntil = ticks + OPEN_TIMEOUT;
        // Como o jogador usando a mesa (o "usar" do jogo): o servidor abre o menu da mesa.
        client.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(topCenter(), Direction.UP, pos, false));
        last = "abrindo a mesa";
        return true;
    }

    /**
     * O servidor abriu um menu ({@code MenuScreensMixin}). Se é o da mesa que pedimos, ficamos com ele sem abrir a
     * tela. Retorna true se ficou com ele.
     */
    public boolean onOpenScreen(MenuType<?> type, Minecraft client, int containerId) {
        if (expectUntil < 0 || type != MenuType.CRAFTING || client.player == null) {
            return false;
        }
        expectUntil = -1;
        CraftingMenu m = MenuType.CRAFTING.create(containerId, client.player.getInventory());
        client.player.containerMenu = m;
        menu = m;
        InvOps.setHidden(m);
        idle = 0;
        last = "mesa aberta (sem tela)";
        HandsOnVR.LOGGER.debug("[HandsOn VR] mesa de trabalho aberta sem tela em {}", pos);
        return true;
    }

    /** Fecha o menu da mesa: o servidor devolve os itens da grade ao inventário. */
    public void close(Minecraft client, String why) {
        LocalPlayer p = client.player;
        if (menu != null && p != null && p.containerMenu == menu) {
            p.connection.send(new ServerboundContainerClosePacket(menu.containerId));
            p.containerMenu = p.inventoryMenu;
        }
        drop(why);
    }

    private void drop(String why) {
        if (menu != null) {
            last = "mesa fechada: " + why;
        }
        InvOps.clearHidden(menu);
        menu = null;
        expectUntil = -1;
        queue.clear();
        idle = 0;
    }

    // ------------------------------------------------------------------ ações

    /**
     * Põe itens do espaço {@code from} (menu do jogador: inventário, hotbar ou mão secundária) na casa: a pilha inteira
     * ({@code all}, como soltar o grip num espaço do crafting da mochila; uma casa com outro item troca de lugar com ele
     * e o item dela vai para {@code from}) ou UM item (o gatilho). Se o menu ainda não abriu, espera por ele. Retorna
     * false se não dá (um item numa casa com outro item ou cheia, item de um lugar que a mesa não alcança, mesa não
     * abre).
     */
    public boolean place(Minecraft client, int hand, int from, int cell, boolean canOpen, boolean all) {
        LocalPlayer p = client.player;
        ItemStack expected = InvOps.stack(p, from).copy();
        if (cell < 0 || cell > 8 || expected.isEmpty() || !(from >= InvOps.MAIN_START && from <= InvOps.OFFHAND)
                || (!all && !fits(cell, expected))) {
            return false;
        }
        if (menu == null) {
            if (!ensureOpen(client, canOpen)) {
                return false;
            }
            queue.add(c -> {
                if (!doPlace(c, from, expected, cell, all)) {
                    VrBridge.pulse(hand, 0.08f);
                } else {
                    VrBridge.pulse(hand, 0.02f);
                }
            });
            return true;
        }
        return doPlace(client, from, expected, cell, all);
    }

    /** A casa aceita mais um deste item (vazia, ou com o mesmo item e com espaço)? */
    private boolean fits(int cell, ItemStack stack) {
        ItemStack in = cellStack(cell);
        return in.isEmpty() || (ItemStack.isSameItemSameComponents(in, stack) && in.getCount() < in.getMaxStackSize());
    }

    private boolean doPlace(Minecraft client, int from, ItemStack expected, int cell, boolean all) {
        LocalPlayer p = client.player;
        ItemStack now = InvOps.stack(p, from);
        if (menu == null || p.containerMenu != menu || !ItemStack.isSameItemSameComponents(now, expected)
                || (!all && !fits(cell, now)) || !InvOps.cursor(p).isEmpty()) {
            return false;
        }
        ItemStack before = cellStack(cell).copy();
        if (from == InvOps.OFFHAND) {
            // A mão secundária não é um espaço do menu da mesa: troca com a hotbar 1, coloca dali e destroca.
            int temp = InvOps.HOTBAR_START;
            InvOps.swap(client, temp, InvOps.OFFHAND_BUTTON);
            placeFrom(client, InvOps.hiddenIndex(temp), cell, all);
            InvOps.swap(client, temp, InvOps.OFFHAND_BUTTON);
        } else {
            placeFrom(client, InvOps.hiddenIndex(from), cell, all);
        }
        InvOps.flushCursor(client);
        boolean placed = !ItemStack.matches(before, cellStack(cell));
        last = placed ? (all ? "pôs a pilha na casa " : "pôs um na casa ") + cell : "não coube na casa " + cell;
        return placed;
    }

    /**
     * Pega a pilha do espaço (índice do menu da mesa), põe na casa a pilha toda (botão esquerdo: junta, ocupa ou troca)
     * ou um item (botão direito) e devolve ao espaço o que sobrou no cursor.
     */
    private static void placeFrom(Minecraft client, int menuSlot, int cell, boolean all) {
        InvOps.clickHidden(client, menuSlot, 0, ContainerInput.PICKUP);
        InvOps.clickHidden(client, MENU_GRID + cell, all ? 0 : 1, ContainerInput.PICKUP);
        InvOps.clickHidden(client, menuSlot, 0, ContainerInput.PICKUP);
    }

    /**
     * Tira os itens da casa para o inventário (como o Shift+clique da tela: inventário principal primeiro). Retorna o
     * espaço do menu do jogador em que eles ficaram, ou -1.
     */
    public int take(Minecraft client, int cell) {
        LocalPlayer p = client.player;
        ItemStack in = cellStack(cell).copy();
        if (menu == null || in.isEmpty() || p.containerMenu != menu || !InvOps.cursor(p).isEmpty()) {
            return -1;
        }
        int[] before = counts(p, in);
        InvOps.clickHidden(client, MENU_GRID + cell, 0, ContainerInput.QUICK_MOVE);
        int[] after = counts(p, in);
        int best = -1, gained = 0;
        for (int i = InvOps.MAIN_START; i < InvOps.OFFHAND; i++) {
            int g = after[i] - before[i];
            if (g > gained) {
                gained = g;
                best = i;
            }
        }
        last = best >= 0 ? "tirou da casa " + cell + " para o espaço " + best : "a casa " + cell + " não saiu (sem espaço)";
        return best;
    }

    private static int[] counts(LocalPlayer p, ItemStack like) {
        int[] c = new int[InvOps.OFFHAND + 1];
        for (int i = InvOps.MAIN_START; i < InvOps.OFFHAND; i++) {
            ItemStack s = InvOps.stack(p, i);
            c[i] = ItemStack.isSameItemSameComponents(s, like) ? s.getCount() : 0;
        }
        return c;
    }

    /**
     * Fabrica UM resultado direto no espaço {@code target} do menu do jogador (vazio, ou com o mesmo item e espaço para
     * tudo). Retorna true se fabricou.
     */
    public boolean craftInto(Minecraft client, int target) {
        LocalPlayer p = client.player;
        ItemStack result = resultStack().copy();
        if (menu == null || result.isEmpty() || p.containerMenu != menu || !InvOps.cursor(p).isEmpty()
                || InvOps.hiddenIndex(target) < 0) {
            return false;
        }
        InvOps.clickHidden(client, MENU_RESULT, 0, ContainerInput.PICKUP); // fabrica: o resultado no cursor
        if (InvOps.cursor(p).isEmpty()) {
            return false;
        }
        InvOps.clickHidden(client, InvOps.hiddenIndex(target), 0, ContainerInput.PICKUP);
        InvOps.flushCursor(client);
        last = "fabricou " + result.getCount() + "x " + result.getItem() + " (espaço " + target + ")";
        return true;
    }
}
