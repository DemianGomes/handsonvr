package com.handsonvr.chest;

import com.handsonvr.HandsOnVR;
import com.handsonvr.inv.InvOps;
import com.handsonvr.vr.Panel;
import com.handsonvr.vr.VrBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Baús, barris, baús do Ender e caixas de shulker usados com as mãos.
 *
 * <p>Grip no bloco (a mão encostada nele) abre a tampa sem a tela: por baixo é o menu do jogo, aberto como o jogador
 * faria e mantido sem a tela ({@code MenuScreensMixin}), então o jogo anima a tampa e tudo usa os cliques normais
 * (funciona em qualquer servidor). O conteúdo aparece numa janela do jogo acima do bloco, virada para o jogador, com
 * os espaços do inventário. As mãos fazem o mesmo que na mochila: grip num espaço traz o item para a mão (com o
 * gatilho já apertado, a metade), soltar o grip num espaço põe a pilha, o gatilho põe um. Grip no bloco de novo, se
 * afastar ou abrir uma tela fecha. Como fechar não perde nada, o menu cede o lugar quando a mochila precisa do
 * inventário inteiro ({@link InvOps}).
 */
public final class ChestStation {
    public static final int NONE = -1;
    /** Lado de um espaço da janela (m) e a folga em volta da grade; a janela fica acima do bloco. */
    public static final double SLOT = 0.06, PAD = 0.012, ABOVE = 0.75, TOUCH_DEPTH = 0.09;
    /** A mão "encosta" no bloco até este tanto fora dele (m). */
    private static final double TOUCH = 0.12;
    private static final double FAR = 4.5;
    private static final int OPEN_TIMEOUT = 40, RETRY_WAIT = 60;

    private BlockPos pos;
    private AbstractContainerMenu menu;
    private int size = 0;
    private long ticks = 0, expectUntil = -1, retryAfter = 0;
    private final int[] hover = {NONE, NONE};
    private final List<Consumer<Minecraft>> queue = new ArrayList<>();
    private String last = "-";

    public BlockPos pos() {
        return pos;
    }

    public boolean isOpen() {
        return menu != null;
    }

    public boolean isWaiting() {
        return expectUntil >= 0;
    }

    public String last() {
        return last;
    }

    public int size() {
        return size;
    }

    public int rows() {
        return Math.max(1, size / 9);
    }

    public int hover(int hand) {
        return hover[hand];
    }

    public ItemStack stack(int slot) {
        return menu == null || slot < 0 || slot >= size ? ItemStack.EMPTY : menu.getSlot(slot).getItem();
    }

    public boolean blocksSwing() {
        return menu != null && (hover[0] != NONE || hover[1] != NONE);
    }

    public void reset() {
        InvOps.clearHidden(menu);
        menu = null;
        pos = null;
        size = 0;
        expectUntil = -1;
        queue.clear();
        hover[0] = hover[1] = NONE;
    }

    /** É um bloco que guarda itens e abre um menu de 9 colunas (baú, barril, baú do Ender, caixa de shulker)? */
    public static boolean isContainer(BlockState state) {
        return state.getBlock() instanceof ChestBlock || state.getBlock() instanceof BarrelBlock
                || state.getBlock() instanceof EnderChestBlock || state.getBlock() instanceof ShulkerBoxBlock;
    }

    /** O bloco que a ponta da mão está tocando (dentro dele ou até {@link #TOUCH} fora), ou null. */
    public static BlockPos containerAt(Level level, Vec3 tip) {
        if (level == null || tip == null) {
            return null;
        }
        BlockPos base = BlockPos.containing(tip);
        for (BlockPos q : BlockPos.betweenClosed(base.offset(-1, -1, -1), base.offset(1, 1, 1))) {
            if (isContainer(level.getBlockState(q))) {
                Vec3 c = Vec3.atCenterOf(q);
                if (Math.abs(tip.x - c.x) <= 0.5 + TOUCH && Math.abs(tip.y - c.y) <= 0.5 + TOUCH && Math.abs(tip.z - c.z) <= 0.5 + TOUCH) {
                    return q.immutable();
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ a janela

    /** A janela acima do bloco, virada para o rosto (null sem o bloco ou a cabeça). */
    public Panel panel() {
        VrBridge.Pose head = VrBridge.head();
        if (pos == null || head == null) {
            return null;
        }
        Vec3 top = Vec3.atCenterOf(pos).add(0, 0.5 + ABOVE * 0.5 + rows() * SLOT * 0.5, 0);
        Vec3 toHead = head.position().subtract(top);
        Vec3 flat = new Vec3(toHead.x, 0, toHead.z);
        Vec3 center = flat.lengthSqr() < 1.0e-6 ? top : top.add(flat.normalize().scale(0.15));
        return Panel.facing(center, head.position(), 0, 1.0);
    }

    /** O centro de um espaço na janela (x, y locais). */
    public double[] slotCenter(int slot) {
        int row = slot / 9, col = slot % 9;
        return new double[] {(col - 4) * SLOT, ((rows() - 1) / 2.0 - row) * SLOT};
    }

    /** O espaço sob a ponta da mão, ou {@link #NONE}. */
    public int slotAt(Vec3 tip) {
        Panel p = menu == null ? null : panel();
        if (p == null || tip == null) {
            return NONE;
        }
        Vec3 local = p.local(tip);
        if (Math.abs(local.z) > TOUCH_DEPTH) {
            return NONE;
        }
        int col = (int) Math.round(local.x / SLOT) + 4, row = (int) Math.round((rows() - 1) / 2.0 - local.y / SLOT);
        if (col < 0 || col > 8 || row < 0 || row >= rows()) {
            return NONE;
        }
        return row * 9 + col;
    }

    /** A ponta da mão está na janela (nos espaços ou na borda)? */
    public boolean onPanel(Vec3 tip) {
        Panel p = menu == null ? null : panel();
        if (p == null || tip == null) {
            return false;
        }
        Vec3 local = p.local(tip);
        return Math.abs(local.z) <= TOUCH_DEPTH && Math.abs(local.x) <= 4.5 * SLOT + PAD && Math.abs(local.y) <= rows() * SLOT / 2 + PAD;
    }

    public boolean updateHover(int hand, int target) {
        int before = hover[hand];
        hover[hand] = target;
        return target != NONE && target != before;
    }

    // ------------------------------------------------------------------ abrir e fechar

    /** Grip no bloco: abre (se fechado) ou fecha (se é o aberto). Retorna true se fez algo. */
    public boolean toggle(Minecraft client, BlockPos block) {
        if (menu != null || expectUntil >= 0) {
            boolean same = block.equals(pos);
            close(client, "grip no bloco");
            return same || open(client, block);
        }
        return open(client, block);
    }

    private boolean open(Minecraft client, BlockPos block) {
        LocalPlayer p = client.player;
        if (p == null || client.gameMode == null || client.gui.screen() != null || p.isSecondaryUseActive() || ticks < retryAfter) {
            return false;
        }
        if (p.containerMenu != p.inventoryMenu && !InvOps.releaseHidden(client)) {
            return false; // a mesa de trabalho está aberta (ela fecha sozinha quando a grade esvazia)
        }
        pos = block;
        expectUntil = ticks + OPEN_TIMEOUT;
        client.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(block).add(0, 0.5, 0), Direction.UP, block, false));
        last = "abrindo";
        return true;
    }

    public boolean onOpenScreen(MenuType<?> type, Minecraft client, int containerId) {
        if (expectUntil < 0 || client.player == null
                || (type != MenuType.GENERIC_9x3 && type != MenuType.GENERIC_9x6 && type != MenuType.SHULKER_BOX)) {
            return false;
        }
        expectUntil = -1;
        AbstractContainerMenu m = type.create(containerId, client.player.getInventory());
        client.player.containerMenu = m;
        menu = m;
        size = m.slots.size() - 36;
        InvOps.setHidden(m, () -> close(client, "cedeu o menu ao inventário"));
        last = "aberto (sem tela), " + size + " espaços";
        HandsOnVR.LOGGER.debug("[HandsOn VR] baú aberto sem tela em {}", pos);
        return true;
    }

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
            last = "fechado: " + why;
        }
        InvOps.clearHidden(menu);
        menu = null;
        expectUntil = -1;
        queue.clear();
        hover[0] = hover[1] = NONE;
    }

    public void tick(Minecraft client) {
        ticks++;
        LocalPlayer p = client.player;
        if (p == null || client.level == null) {
            reset();
            return;
        }
        if (menu != null && p.containerMenu != menu) {
            drop("fechou pelo jogo");
        }
        if (menu != null && client.gui.screen() instanceof AbstractContainerScreen<?>) {
            close(client, "tela aberta");
        }
        if (expectUntil >= 0 && ticks > expectUntil) {
            expectUntil = -1;
            retryAfter = ticks + RETRY_WAIT;
            queue.clear();
            last = "não abriu";
        }
        if ((menu != null || expectUntil >= 0) && (pos == null || !isContainer(client.level.getBlockState(pos))
                || p.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > FAR)) {
            close(client, "longe do baú");
        }
        if (menu == null) {
            hover[0] = hover[1] = NONE;
        }
        if (menu != null && !queue.isEmpty()) {
            List<Consumer<Minecraft>> todo = new ArrayList<>(queue);
            queue.clear();
            todo.forEach(a -> a.accept(client));
        }
    }

    // ------------------------------------------------------------------ ações

    /** Põe a pilha ({@code all}) ou UM item do espaço {@code from} (menu do jogador) no espaço do baú. */
    public boolean place(Minecraft client, int from, int slot, boolean all) {
        LocalPlayer p = client.player;
        ItemStack now = InvOps.stack(p, from);
        if (menu == null || p.containerMenu != menu || now.isEmpty() || slot < 0 || slot >= size || !InvOps.cursor(p).isEmpty()
                || !menu.getSlot(slot).mayPlace(now) || !(from >= InvOps.MAIN_START && from <= InvOps.OFFHAND)) {
            return false;
        }
        ItemStack in = stack(slot);
        if (!all && !in.isEmpty() && (!ItemStack.isSameItemSameComponents(in, now) || in.getCount() >= in.getMaxStackSize())) {
            return false;
        }
        ItemStack before = in.copy();
        int temp = InvOps.HOTBAR_START;
        boolean offhand = from == InvOps.OFFHAND;
        if (offhand) {
            InvOps.swap(client, temp, InvOps.OFFHAND_BUTTON);
        }
        int src = InvOps.hiddenIndex(offhand ? temp : from);
        InvOps.clickHidden(client, src, 0, ContainerInput.PICKUP);
        InvOps.clickHidden(client, slot, all ? 0 : 1, ContainerInput.PICKUP);
        InvOps.clickHidden(client, src, 0, ContainerInput.PICKUP);
        if (offhand) {
            InvOps.swap(client, temp, InvOps.OFFHAND_BUTTON);
        }
        InvOps.flushCursor(client);
        boolean placed = !ItemStack.matches(before, stack(slot));
        last = placed ? (all ? "pôs a pilha no espaço " : "pôs um no espaço ") + slot : "não coube no espaço " + slot;
        return placed;
    }

    /** Tira o espaço do baú para o inventário (o Shift+clique). Retorna o espaço do jogador em que ficou, ou -1. */
    public int take(Minecraft client, int slot) {
        LocalPlayer p = client.player;
        ItemStack in = stack(slot).copy();
        if (menu == null || in.isEmpty() || p.containerMenu != menu || !InvOps.cursor(p).isEmpty()) {
            return -1;
        }
        int[] before = counts(p, in);
        InvOps.clickHidden(client, slot, 0, ContainerInput.QUICK_MOVE);
        int[] after = counts(p, in);
        int best = -1, gained = 0;
        for (int i = InvOps.MAIN_START; i < InvOps.OFFHAND; i++) {
            if (after[i] - before[i] > gained) {
                gained = after[i] - before[i];
                best = i;
            }
        }
        last = best >= 0 ? "tirou o espaço " + slot : "o espaço " + slot + " não saiu (sem espaço)";
        return best;
    }

    /** Tira a METADE do espaço do baú para o espaço vazio {@code to} do jogador. Retorna {@code to}, ou -1. */
    public int takeHalf(Minecraft client, int slot, int to) {
        LocalPlayer p = client.player;
        if (menu == null || stack(slot).isEmpty() || p.containerMenu != menu || !InvOps.cursor(p).isEmpty()
                || InvOps.hiddenIndex(to) < 0 || !InvOps.stack(p, to).isEmpty()) {
            return -1;
        }
        InvOps.clickHidden(client, slot, 1, ContainerInput.PICKUP);
        InvOps.clickHidden(client, InvOps.hiddenIndex(to), 0, ContainerInput.PICKUP);
        InvOps.flushCursor(client);
        last = "tirou a metade do espaço " + slot;
        return InvOps.stack(p, to).isEmpty() ? -1 : to;
    }

    private static int[] counts(LocalPlayer p, ItemStack like) {
        int[] c = new int[InvOps.OFFHAND + 1];
        for (int i = InvOps.MAIN_START; i < InvOps.OFFHAND; i++) {
            ItemStack s = InvOps.stack(p, i);
            c[i] = ItemStack.isSameItemSameComponents(s, like) ? s.getCount() : 0;
        }
        return c;
    }
}
