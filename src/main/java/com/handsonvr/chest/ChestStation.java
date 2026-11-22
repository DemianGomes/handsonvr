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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Baús, barris, baús do Ender e caixas de shulker usados com as mãos.
 *
 * <p><b>Abrir com o movimento:</b> segure a borda da frente da tampa com o grip e levante a mão (no barril e na caixa
 * de shulker, puxe a face da tampa para fora). A tampa abre como no jogo: por baixo é o menu do jogo, aberto como o
 * jogador faria e mantido sem a tela ({@code MenuScreensMixin}), então o jogo anima a tampa para todo mundo e tudo usa
 * os cliques normais (funciona em qualquer servidor). Para fechar, grip no bloco e empurre para baixo (ou para dentro),
 * ou se afaste.
 *
 * <p><b>Os itens ficam dentro do baú</b>, sem página nem rolagem: no baú, deitados no fundo escuro da caixa aberta:
 * 9x3 no baú simples e 9x6 no duplo, que é duas vezes mais largo. No barril e na caixa de shulker,
 * deitados na face da tampa. As mãos fazem o mesmo que na mochila: grip num espaço traz o item para a mão (com o gatilho
 * já apertado, a metade), soltar o grip num espaço põe a pilha, o gatilho põe um. Como fechar não perde nada, o menu
 * cede o lugar quando a mochila precisa do inventário inteiro ({@link InvOps}).
 */
public final class ChestStation {
    public static final int NONE = -1;
    /** O quanto a mão precisa andar com o grip para abrir ou fechar a tampa (m). */
    private static final double LID_MOVE = 0.12;
    /** A mão "encosta" no bloco até este tanto fora dele (m). */
    private static final double TOUCH = 0.15;
    /** A mão toca um espaço até este tanto na frente (e atrás) da superfície dos itens (m). */
    public static final double TOUCH_DEPTH = 0.09;
    private static final double FAR = 4.5;
    private static final int OPEN_TIMEOUT = 40, RETRY_WAIT = 40;

    private BlockPos pos;
    private AbstractContainerMenu menu;
    private int size = 0;
    private long ticks = 0, expectUntil = -1, retryAfter = 0;
    private final int[] hover = {NONE, NONE};
    private final List<Consumer<Minecraft>> queue = new ArrayList<>();
    private String last = "-";

    /** O gesto da tampa de cada mão: o bloco, de onde a mão começou e se é para abrir (true) ou fechar. */
    private record Lid(BlockPos block, Vec3 start, boolean opening) {}

    private final Lid[] lids = new Lid[2];

    /**
     * Baú suspenso: ele cedeu o menu a outro bloco (fornalha, suporte de poções, mesa) ou à mochila, porque o servidor só
     * deixa um menu aberto por vez. Continua desenhado com o que tinha (esta cópia) e reabre sozinho quando a mão volta
     * aos espaços dele ({@link #resume}). Null = não suspenso.
     */
    private ItemStack[] ghost = null;

    /** O baú aparece (aberto ou suspenso)? */
    public boolean isShown() {
        return menu != null || ghost != null;
    }

    public boolean isSuspended() {
        return menu == null && ghost != null;
    }

    /** A mão voltou ao baú suspenso: reabre (o menu do outro bloco fecha sem perder nada). */
    public boolean resume(Minecraft client) {
        return isSuspended() && expectUntil < 0 && pos != null && open(client, pos);
    }

    private void suspend(Minecraft client) {
        ItemStack[] snap = new ItemStack[size];
        for (int i = 0; i < size && menu != null; i++) {
            snap[i] = menu.getSlot(i).getItem().copy();
        }
        close(client, "cedeu o menu (suspenso: a mão no baú reabre)");
        ghost = snap;
    }

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
        if (menu == null) {
            return ghost == null || slot < 0 || slot >= ghost.length || ghost[slot] == null ? ItemStack.EMPTY : ghost[slot];
        }
        return slot < 0 || slot >= size ? ItemStack.EMPTY : menu.getSlot(slot).getItem();
    }

    public boolean blocksSwing() {
        return (menu != null && (hover[0] != NONE || hover[1] != NONE)) || lids[0] != null || lids[1] != null;
    }

    public void reset() {
        ghost = null;
        InvOps.clearHidden(menu);
        menu = null;
        pos = null;
        size = 0;
        expectUntil = -1;
        queue.clear();
        hover[0] = hover[1] = NONE;
        lids[0] = lids[1] = null;
    }

    /** É um bloco que guarda itens e abre um menu de 9 colunas (baú, barril, baú do Ender, caixa de shulker)? */
    public static boolean isContainer(BlockState state) {
        return state.getBlock() instanceof ChestBlock || state.getBlock() instanceof BarrelBlock
                || state.getBlock() instanceof EnderChestBlock || state.getBlock() instanceof ShulkerBoxBlock;
    }

    /** Baú com tampa de dobradiça (os itens ficam deitados no fundo da caixa aberta)? */
    private static boolean hasHingedLid(BlockState state) {
        return state.getBlock() instanceof ChestBlock || state.getBlock() instanceof EnderChestBlock;
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

    // ------------------------------------------------------------------ a superfície dos itens

    /**
     * Onde ficam os itens: o plano (x para a direita de quem olha a frente, y para cima nos itens, z para fora) e o lado
     * de um espaço. No baú, o fundo da caixa aberta (a parte escura de dentro), deitado; no duplo, as duas metades
     * juntas. No barril e na caixa de shulker, a face da tampa. Null sem o bloco.
     */
    public Panel surface() {
        Minecraft client = Minecraft.getInstance();
        if (pos == null || client.level == null) {
            return null;
        }
        BlockState state = client.level.getBlockState(pos);
        if (hasHingedLid(state) && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            Vec3 front = Vec3.atLowerCornerOf(facing.getUnitVec3i());
            Vec3 right = new Vec3(front.z, 0, -front.x);
            Vec3 center = Vec3.atBottomCenterOf(pos);
            if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
                center = center.add(Vec3.atBottomCenterOf(other)).scale(0.5);
            }
            // O fundo do baú aberto (a parte escura de dentro da caixa, a 10/16 do chão), deitado: o "cima" dos itens
            // aponta para longe de quem está na frente do baú, e a normal para cima.
            Vec3 origin = center.add(0, 0.625 + 0.01, 0);
            return new Panel(origin, right, front.reverse(), new Vec3(0, 1, 0), 1.0);
        }
        Direction face = state.hasProperty(BlockStateProperties.FACING) ? state.getValue(BlockStateProperties.FACING) : Direction.UP;
        Vec3 n = Vec3.atLowerCornerOf(face.getUnitVec3i());
        Vec3 origin = Vec3.atCenterOf(pos).add(n.scale(0.5 + 0.01));
        if (face.getAxis() == Direction.Axis.Y) {
            // Deitado no topo: o "cima" dos itens aponta para longe de quem olha.
            VrBridge.Pose head = VrBridge.head();
            Vec3 away = head == null ? new Vec3(0, 0, 1) : Vec3.atCenterOf(pos).subtract(head.position());
            away = new Vec3(away.x, 0, away.z).lengthSqr() < 1.0e-6 ? new Vec3(0, 0, 1) : new Vec3(away.x, 0, away.z).normalize();
            Vec3 right = new Vec3(-away.z, 0, away.x);
            return new Panel(origin, face == Direction.UP ? right : right.reverse(), away, n, 1.0);
        }
        Vec3 right = new Vec3(n.z, 0, -n.x);
        return new Panel(origin, right, new Vec3(0, 1, 0), n, 1.0);
    }

    /** A largura (m) que os itens podem ocupar: a da tampa (o dobro no baú duplo). */
    private double surfaceWidth() {
        Minecraft client = Minecraft.getInstance();
        BlockState state = client.level == null || pos == null ? null : client.level.getBlockState(pos);
        boolean twice = state != null && state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE;
        return twice ? 1.7 : 0.82;
    }

    /** O lado de um espaço (m): 9 colunas na largura da tampa, com as linhas cabendo na altura dela. */
    public double slot() {
        return Math.min(surfaceWidth() / 9.0, 0.84 / rows());
    }

    /** O centro de um espaço na superfície (x, y locais). */
    public double[] slotCenter(int slot) {
        int row = slot / 9, col = slot % 9;
        double s = slot();
        return new double[] {(col - 4) * s, ((rows() - 1) / 2.0 - row) * s};
    }

    /** Quanto a mão pode estar abaixo e acima da superfície dos itens (m). No fundo do baú, que é deitado, a folga é maior (a ponta fica à frente do controle). */
    private double depthMin() {
        return isFlat() ? -0.10 : -TOUCH_DEPTH * 0.5;
    }

    private double depthMax() {
        return isFlat() ? 0.20 : TOUCH_DEPTH;
    }

    private boolean isFlat() {
        Minecraft client = Minecraft.getInstance();
        return pos != null && client.level != null && hasHingedLid(client.level.getBlockState(pos));
    }

    /** O bloco é o baú aberto (ou a outra metade do baú duplo)? */
    private boolean isOpenChest(BlockPos block) {
        Minecraft client = Minecraft.getInstance();
        if (pos == null || client.level == null) {
            return false;
        }
        if (block.equals(pos)) {
            return true;
        }
        BlockState state = client.level.getBlockState(pos);
        return state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE
                && block.equals(pos.relative(ChestBlock.getConnectedDirection(state)));
    }

    /** O espaço sob a ponta da mão, ou {@link #NONE}. */
    public int slotAt(Vec3 tip) {
        Panel p = !isShown() ? null : surface();
        if (p == null || tip == null) {
            return NONE;
        }
        Vec3 local = p.local(tip);
        double s = slot();
        if (local.z < depthMin() || local.z > depthMax()) {
            return NONE;
        }
        int col = (int) Math.round(local.x / s) + 4, row = (int) Math.round((rows() - 1) / 2.0 - local.y / s);
        if (col < 0 || col > 8 || row < 0 || row >= rows()) {
            return NONE;
        }
        return row * 9 + col;
    }

    /** O botão "Organizar" (x, y locais e meio lado): abaixo da grade, à direita, do lado de quem olha. */
    public double[] sortButton() {
        double s = slot();
        return new double[] {3.5 * s, -(rows() * s / 2 + s * 0.85), s * 0.4};
    }

    /** A ponta da mão está no botão "Organizar" (com o baú aberto)? */
    public boolean onSortButton(Vec3 tip) {
        Panel p = menu == null ? null : surface();
        if (p == null || tip == null) {
            return false;
        }
        Vec3 l = p.local(tip);
        double[] b = sortButton();
        double reach = b[2] + 0.015;
        return l.z >= depthMin() && l.z <= depthMax() && Math.abs(l.x - b[0]) <= reach && Math.abs(l.y - b[1]) <= reach;
    }

    /** "Organizar": junta e ordena os itens do baú com cliques normais. True se organizou. */
    public boolean sort(Minecraft client) {
        LocalPlayer p = client.player;
        if (menu == null || p == null || p.containerMenu != menu || !InvOps.cursor(p).isEmpty()) {
            return false;
        }
        AbstractContainerMenu m = menu;
        com.handsonvr.inv.Sorter.sort(size, new com.handsonvr.inv.Sorter.Slots() {
            @Override
            public ItemStack get(int i) {
                return m.getSlot(i).getItem();
            }

            @Override
            public void pickup(int i) {
                InvOps.clickHidden(client, i, 0, ContainerInput.PICKUP);
            }
        });
        InvOps.flushCursor(client);
        last = "organizado";
        return true;
    }

    /** A ponta da mão está na superfície dos itens? */
    public boolean onPanel(Vec3 tip) {
        Panel p = !isShown() ? null : surface();
        if (p == null || tip == null) {
            return false;
        }
        Vec3 local = p.local(tip);
        double s = slot();
        return local.z >= depthMin() && local.z <= depthMax() && Math.abs(local.x) <= 4.5 * s && Math.abs(local.y) <= rows() * s / 2;
    }

    public boolean updateHover(int hand, int target) {
        int before = hover[hand];
        hover[hand] = target;
        return target != NONE && target != before;
    }

    // ------------------------------------------------------------------ o gesto da tampa

    /**
     * Grip encostado no bloco: começa o gesto da tampa. Fechado, levantar a mão abre; aberto, empurrar para baixo fecha.
     * Retorna true (o grip é do baú).
     */
    public boolean beginLid(int hand, BlockPos block, Vec3 tip) {
        boolean opening = !(isOpenChest(block) && isShownOrWaiting());
        lids[hand] = new Lid(block, tip, opening);
        return true;
    }

    private boolean isShownOrWaiting() {
        return isShown() || expectUntil >= 0;
    }

    /** Há um gesto da tampa nesta mão? */
    public boolean lidActive(int hand) {
        return lids[hand] != null;
    }

    /** A cada tick: com o grip ainda apertado, a mão andou o bastante para abrir ou fechar? Retorna 1 abriu, -1 fechou, 0. */
    public int lidTick(Minecraft client, int hand, Vec3 tip, boolean gripHeld) {
        Lid lid = lids[hand];
        if (lid == null) {
            return 0;
        }
        if (!gripHeld || tip == null) {
            lids[hand] = null;
            return 0;
        }
        BlockState state = client.level == null ? null : client.level.getBlockState(lid.block());
        if (state == null || !isContainer(state)) {
            lids[hand] = null;
            return 0;
        }
        Vec3 moved = tip.subtract(lid.start());
        // A direção em que a tampa abre: para cima no baú; para fora da face no barril e na caixa de shulker.
        Vec3 out = new Vec3(0, 1, 0);
        if (!hasHingedLid(state) && state.hasProperty(BlockStateProperties.FACING)) {
            out = Vec3.atLowerCornerOf(state.getValue(BlockStateProperties.FACING).getUnitVec3i());
        }
        double along = moved.dot(out);
        if (lid.opening() && along >= LID_MOVE) {
            lids[hand] = null;
            return open(client, lid.block()) ? 1 : 0;
        }
        if (!lid.opening() && along <= -LID_MOVE) {
            lids[hand] = null;
            close(client, "a mão fechou a tampa");
            return -1;
        }
        return 0;
    }

    // ------------------------------------------------------------------ abrir e fechar

    /**
     * "Usar" (A) no baú que a mira aponta, em VR: abre o baú imersivo em vez da janela antiga (só em VR; sem VR nada muda).
     * Retorna true se o "usar" foi nosso. Com o baú já aberto por nós, não faz nada.
     */
    public boolean useOnContainer(Minecraft client) {
        if (client.player == null || client.level == null || client.gui.screen() != null || !VrBridge.isVRActive()
                || !(client.hitResult instanceof BlockHitResult hit) || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK
                || !isContainer(client.level.getBlockState(hit.getBlockPos()))) {
            return false;
        }
        BlockPos block = hit.getBlockPos().immutable();
        if (isOpenChest(block) && (menu != null || expectUntil >= 0)) {
            return true;
        }
        open(client, block);
        return true;
    }

    private boolean open(Minecraft client, BlockPos block) {
        LocalPlayer p = client.player;
        if (menu != null || expectUntil >= 0) {
            close(client, "abrindo outro");
        }
        if (p == null || client.gameMode == null || client.gui.screen() != null || ticks < retryAfter) {
            return false;
        }
        if (p.containerMenu != p.inventoryMenu && !InvOps.releaseHidden(client)) {
            return false; // a mesa de trabalho está aberta (ela fecha sozinha quando a grade esvazia)
        }
        pos = block;
        expectUntil = ticks + OPEN_TIMEOUT;
        com.handsonvr.hands.NoShift.run(client, () -> client.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(block).add(0, 0.5, 0), Direction.UP, block, false)));
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
        InvOps.setHidden(m, () -> suspend(client));
        ghost = null;
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
        ghost = null;
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
        if ((menu != null || expectUntil >= 0 || ghost != null) && (pos == null || !isContainer(client.level.getBlockState(pos))
                || p.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > FAR)) {
            close(client, "longe do baú");
        }
        if (!isShown()) {
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
