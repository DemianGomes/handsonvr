package com.handsonvr.furnace;

import com.handsonvr.HandsOnVR;
import com.handsonvr.inv.InvOps;
import com.handsonvr.mixin.AbstractFurnaceMenuAccessor;
import com.handsonvr.vr.Panel;
import com.handsonvr.vr.VrBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * As fornalhas imersivas: a fornalha comum, o alto-forno e o defumador, usados com as mãos, como a mesa de trabalho.
 *
 * <p><b>Onde vai cada coisa</b> (os pixels são os da textura do próprio bloco):
 * <ul>
 *   <li><b>Fornalha:</b> o que vai queimar fica na abertura de cima da frente; o combustível, na abertura de baixo
 *       (onde aparece o fogo).</li>
 *   <li><b>Alto-forno:</b> carregado por cima, como um alto-forno de verdade: o minério fica deitado no topo. O
 *       combustível vai na grade de fogo da frente.</li>
 *   <li><b>Defumador:</b> a comida fica deitada em cima da chaminé (o quadrado escuro do topo), como numa grelha. O
 *       combustível vai no fogo da frente.</li>
 *   <li>Acima do bloco, virado para o rosto: a barra de progresso da tela do jogo (a chama do combustível e a seta do
 *       cozimento) e, à direita da seta, o resultado flutuando, pronto para pegar.</li>
 * </ul>
 *
 * <p><b>Por baixo, é a fornalha do jogo.</b> Olhando para uma fornalha perto (ou levando um item até ela), o mod a
 * "usa" como o jogador faria e fica com o menu dela sem abrir a tela ({@code MenuScreensMixin}), como na mesa de
 * trabalho: o conteúdo, o progresso e o resultado são os do servidor, e tudo usa os cliques normais do menu (funciona
 * em qualquer servidor). Diferente da mesa, fechar o menu não muda nada (os itens ficam na fornalha): por isso o menu
 * fecha sozinho quando o resto do mod precisa da armadura ou do crafting 2x2 da mochila ({@link InvOps}), ao olhar
 * para outro lado por um tempo, ao olhar para outra fornalha ou ao se afastar.
 */
public final class FurnaceStation {
    /** A face do bloco em que fica um lugar. */
    public enum Face { FRONT, TOP }

    /**
     * Um lugar do bloco onde a mão põe e tira itens: a face e o retângulo em pixels da textura dela (x para a direita
     * de quem olha, y para baixo; no topo, y para baixo = para perto do jogador).
     */
    public record Spot(Face face, int x0, int y0, int x1, int y1) {
        public double width() {
            return (x1 - x0) / 16.0;
        }

        public double height() {
            return (y1 - y0) / 16.0;
        }

        /** O lado do "espaço" do item (o lado menor do retângulo). */
        public double box() {
            return Math.min(width(), height());
        }
    }

    /** Os três blocos e onde ficam a entrada e o combustível em cada um. */
    public enum Kind {
        /** furnace_front: abertura de cima (3-12, 3-6) e a do fogo (4-11, 11-14). */
        FURNACE("furnace", MenuType.FURNACE, new Spot(Face.FRONT, 3, 3, 13, 7), new Spot(Face.FRONT, 4, 11, 12, 15)),
        /** blast_furnace: o centro do topo e a grade de fogo da frente (4-11, 7-11). */
        BLAST_FURNACE("blast_furnace", MenuType.BLAST_FURNACE, new Spot(Face.TOP, 4, 4, 12, 12),
                new Spot(Face.FRONT, 4, 7, 12, 12)),
        /** smoker: a chaminé do topo (4-11, 4-11) e o fogo da frente (3-12, 10-14). */
        SMOKER("smoker", MenuType.SMOKER, new Spot(Face.TOP, 4, 4, 12, 12), new Spot(Face.FRONT, 3, 10, 13, 15));

        /** Nome das texturas da tela ({@code gui/container/<name>.png} e {@code container/<name>/...}). */
        public final String gui;
        public final MenuType<?> menuType;
        public final Spot input, fuel;

        Kind(String gui, MenuType<?> menuType, Spot input, Spot fuel) {
            this.gui = gui;
            this.menuType = menuType;
            this.input = input;
            this.fuel = fuel;
        }

        static Kind of(BlockState state) {
            if (state.is(Blocks.FURNACE)) {
                return FURNACE;
            }
            if (state.is(Blocks.BLAST_FURNACE)) {
                return BLAST_FURNACE;
            }
            if (state.is(Blocks.SMOKER)) {
                return SMOKER;
            }
            return null;
        }
    }

    /** Alvos sob a mão (iguais aos índices do menu da fornalha: 0 entrada, 1 combustível, 2 resultado). */
    public static final int INPUT = 0, FUEL = 1, RESULT = 2, NONE = -1;

    /** Folga em volta de cada lugar (m) e até onde a mão alcança à frente da face (e para dentro dela). */
    private static final double PAD = 0.03, FRONT_OUT = 0.20, FRONT_IN = 0.06;
    /** No topo, a mão toca de um pouco abaixo até esta altura acima dele (m). */
    private static final double TOP_ABOVE = 0.18, TOP_BELOW = 0.05;
    /** A linha da barra de progresso e do resultado: altura acima do topo; o painel à esquerda e o resultado à direita. */
    public static final double ROW_HEIGHT = 0.30, PANEL_SIDE = 0.15, RESULT_SIDE = 0.17, RESULT_REACH = 0.15;
    /** Fornalhas mais longe que isto da cabeça (m, até o centro do bloco) não contam; e o ângulo da vista que conta. */
    private static final double NEAR = 2.4, LOOK_ANGLE = Math.toRadians(45), HAND_NEAR = 0.7;
    /** Com o menu aberto, longe disto fecha (m). */
    private static final double FAR = 4.0;
    /** Espera do menu, espera depois de não abrir, tempo sem olhar até fechar e tempo sem reabrir depois de ceder o menu. */
    private static final int OPEN_TIMEOUT = 40, RETRY_WAIT = 100, LOOK_AWAY = 60, IDLE_SWITCH = 20, SUPPRESS = 100;

    private BlockPos pos;
    private Kind kind;
    private Direction facing = Direction.NORTH;
    private AbstractFurnaceMenu menu;
    private long ticks = 0, expectUntil = -1, retryAfter = 0, suppressUntil = 0, seen = 0, lastUse = 0;
    private final int[] hover = {NONE, NONE};
    private final List<Consumer<Minecraft>> queue = new ArrayList<>();
    private String last = "-";

    // ------------------------------------------------------------------ estado

    public BlockPos pos() {
        return pos;
    }

    public Kind kind() {
        return kind;
    }

    public Direction facing() {
        return facing;
    }

    /** O menu da fornalha está aberto (sem tela)? */
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

    public Spot spot(int target) {
        return target == INPUT ? kind.input : kind.fuel;
    }

    /** O item de um lugar (entrada, combustível ou resultado), como o servidor mandou. */
    public ItemStack stack(int target) {
        return menu == null || target < INPUT || target > RESULT ? ItemStack.EMPTY : menu.getSlot(target).getItem();
    }

    public ItemStack resultStack() {
        return stack(RESULT);
    }

    /** Quanto do item atual já cozinhou (0 a 1): a seta da tela. */
    public float burnProgress() {
        return menu == null ? 0f : menu.getBurnProgress();
    }

    /** Quanto ainda queima do combustível atual (0 a 1): a chama da tela. */
    public float litProgress() {
        return menu == null ? 0f : menu.getLitProgress();
    }

    public boolean isLit() {
        return menu != null && menu.isLit();
    }

    /** Tem algo para mostrar (itens, fogo ou resultado)? */
    public boolean busy() {
        return isLit() || !stack(INPUT).isEmpty() || !stack(FUEL).isEmpty() || !resultStack().isEmpty();
    }

    /** Uma mão está num lugar da fornalha: o golpe do Vivecraft fica desligado (não quebra a fornalha ao pôr itens). */
    public boolean blocksSwing() {
        return pos != null && (hover[0] != NONE || hover[1] != NONE);
    }

    public void reset() {
        pos = null;
        kind = null;
        InvOps.clearHidden(menu);
        menu = null;
        expectUntil = -1;
        queue.clear();
        hover[0] = hover[1] = NONE;
    }

    // ------------------------------------------------------------------ geometria (a de FurnaceView) e o retrato

    /** A fornalha aberta como {@link FurnaceView}: o conteúdo de agora, pelo menu. Null se nenhuma. */
    public FurnaceView view() {
        if (pos == null || kind == null) {
            return null;
        }
        return new FurnaceView(pos, kind, facing, stack(INPUT).copy(), stack(FUEL).copy(), resultStack().copy(), isLit(),
                litProgress(), burnProgress(), menu != null);
    }

    private FurnaceView shape() {
        return FurnaceView.shape(pos, kind, facing);
    }

    public Vec3 center() {
        return Vec3.atCenterOf(pos);
    }

    public Vec3 topCenter() {
        return shape().topCenter();
    }

    /** O centro da frente (a face para onde a fornalha olha). */
    public Vec3 frontCenter() {
        return shape().frontCenter();
    }

    /** O plano de um lugar (veja {@link FurnaceView#spotPlane}). */
    public Panel spotPlane(int target) {
        return shape().spotPlane(target);
    }

    /** O centro da barra de progresso (à esquerda na linha acima do bloco). */
    public Vec3 panelCenter() {
        return shape().panelCenter();
    }

    /** Onde o resultado flutua (sem o balanço do desenho): à direita da seta. */
    public Vec3 resultCenter() {
        return shape().resultCenter();
    }

    /** A ponta da mão está no lugar do resultado (com o menu aberto, mesmo sem nada pronto agora)? */
    public boolean nearResult(Vec3 tip) {
        return pos != null && tip != null && menu != null && tip.distanceTo(resultCenter()) <= RESULT_REACH;
    }

    /** O alvo sob a ponta da mão: a entrada, o combustível, o resultado (se houver) ou {@link #NONE}. */
    public int targetAt(Vec3 tip) {
        if (pos == null || kind == null || tip == null) {
            return NONE;
        }
        if (!resultStack().isEmpty() && tip.distanceTo(resultCenter()) <= RESULT_REACH) {
            return RESULT;
        }
        for (int target : new int[] {INPUT, FUEL}) {
            if (inSpot(target, tip)) {
                return target;
            }
        }
        return NONE;
    }

    private boolean inSpot(int target, Vec3 tip) {
        Spot s = spot(target);
        Vec3 local = spotPlane(target).local(tip);
        if (Math.abs(local.x) > s.width() / 2 + PAD || Math.abs(local.y) > s.height() / 2 + PAD) {
            return false;
        }
        return s.face() == Face.FRONT ? local.z >= -FRONT_IN && local.z <= FRONT_OUT
                : local.z >= -TOP_BELOW && local.z <= TOP_ABOVE;
    }

    /** Atualiza o alvo da mão; true se entrou num alvo novo (vibração de "passou por cima"). */
    public boolean updateHover(int hand, int target) {
        int before = hover[hand];
        hover[hand] = target;
        if (target != NONE) {
            lastUse = ticks;
        }
        return target != NONE && target != before;
    }

    // ------------------------------------------------------------------ tick

    /**
     * {@code canAuto}: o resto do mod deixa abrir sozinha agora (nada pego da armadura ou do crafting, nada sendo
     * fabricado, a mesa de trabalho fechada). {@code inUse}: as mãos estão mexendo nela (fica aberta).
     */
    public void tick(Minecraft client, boolean canAuto, boolean inUse) {
        ticks++;
        LocalPlayer p = client.player;
        if (p == null || client.level == null) {
            reset();
            return;
        }
        // O menu escondido fechou por fora (o servidor fechou, outro menu tomou o lugar, o mod precisou do inventário).
        if (menu != null && p.containerMenu != menu) {
            drop("a fornalha fechou (pelo jogo)");
        }
        if (menu != null && client.gui.screen() instanceof AbstractContainerScreen<?>) {
            close(client, "tela aberta");
        }
        if (expectUntil >= 0 && ticks > expectUntil) {
            // O servidor não abriu (área protegida, modo aventura...): espera antes de tentar de novo.
            expectUntil = -1;
            retryAfter = ticks + RETRY_WAIT;
            if (!queue.isEmpty()) {
                VrBridge.pulse(0, 0.08f);
                VrBridge.pulse(1, 0.08f);
            }
            queue.clear();
            last = "a fornalha não abriu";
        }
        boolean vr = VrBridge.isVRActive() && client.gui.screen() == null;
        BlockPos best = vr ? findBest(client) : null;
        if (menu != null || expectUntil >= 0) {
            if (Kind.of(client.level.getBlockState(pos)) != kind || headDistance(pos) > FAR) {
                close(client, "longe da fornalha");
            }
        }
        if (menu != null) {
            if (inUse || !queue.isEmpty()) {
                lastUse = ticks;
            }
            if (pos.equals(best)) {
                seen = ticks;
            }
            boolean idle = ticks - lastUse > IDLE_SWITCH && hover[0] == NONE && hover[1] == NONE;
            if (idle && best != null && !best.equals(pos)) {
                close(client, "olhou para outra fornalha");
            } else if (idle && ticks - seen > LOOK_AWAY) {
                close(client, "olhou para outro lado");
            }
        }
        if (menu == null && expectUntil < 0) {
            choose(client, best);
            if (pos != null && canAuto && vr && ticks >= suppressUntil) {
                ensureOpen(client, false);
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
    }

    private void choose(Minecraft client, BlockPos best) {
        if (best == null) {
            pos = null;
            kind = null;
            return;
        }
        // Relido sempre: no mesmo lugar pode ter outro bloco agora (uma fornalha trocada por um defumador).
        pos = best;
        BlockState state = client.level.getBlockState(best);
        kind = Kind.of(state);
        facing = state.getValue(AbstractFurnaceBlock.FACING);
    }

    private static double headDistance(BlockPos pos) {
        VrBridge.Pose head = VrBridge.head();
        return head == null ? Double.MAX_VALUE : head.position().distanceTo(Vec3.atCenterOf(pos));
    }

    /**
     * A fornalha que o jogador está usando: perto da cabeça (até {@link #NEAR}) e na direção do olhar (o menor ângulo)
     * ou com uma mão bem perto dela. Null se nenhuma.
     */
    private BlockPos findBest(Minecraft client) {
        VrBridge.Pose head = VrBridge.head();
        if (head == null) {
            return null;
        }
        Vec3 eye = head.position(), look = head.direction();
        BlockPos center = BlockPos.containing(eye);
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos q : BlockPos.betweenClosed(center.offset(-3, -3, -3), center.offset(3, 1, 3))) {
            if (Kind.of(client.level.getBlockState(q)) == null) {
                continue;
            }
            Vec3 c = Vec3.atCenterOf(q);
            Vec3 to = c.subtract(eye);
            double distance = to.length();
            if (distance > NEAR || distance < 1.0e-3) {
                continue;
            }
            double angle = Math.acos(Math.max(-1, Math.min(1, to.normalize().dot(look))));
            boolean reaching = false;
            for (int hand = 0; hand < 2; hand++) {
                Vec3 tip = VrBridge.pointer(hand);
                reaching |= tip != null && tip.distanceTo(c) <= HAND_NEAR;
            }
            if (angle > LOOK_ANGLE && !reaching) {
                continue;
            }
            double score = reaching ? angle - 1.0 : angle; // a mão perto ganha da vista
            if (q.equals(pos)) {
                score -= Math.toRadians(8); // fica na que já está aberta, a não ser que outra esteja bem mais no centro
            }
            if (score < bestScore) {
                bestScore = score;
                best = q.immutable();
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ abrir e fechar

    /**
     * Abre o menu da fornalha escolhida (sem tela), se ainda não estiver aberto ou a caminho. {@code asked}: a mão
     * levou um item até ela (abre mesmo logo depois de ter cedido o menu). Retorna true se está aberto ou vai abrir.
     */
    public boolean ensureOpen(Minecraft client, boolean asked) {
        if (menu != null || expectUntil >= 0) {
            return true;
        }
        LocalPlayer p = client.player;
        if (pos == null || kind == null || p == null || client.gameMode == null || client.gui.screen() != null
                || p.containerMenu != p.inventoryMenu || ticks < retryAfter
                || (!asked && ticks < suppressUntil)) {
            return false;
        }
        expectUntil = ticks + OPEN_TIMEOUT;
        seen = lastUse = ticks;
        // Como o jogador usando a fornalha (o "usar" do jogo, na frente dela): o servidor abre o menu.
        com.handsonvr.hands.NoShift.run(client, () -> client.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(frontCenter(), facing, pos, false)));
        last = "abrindo a fornalha";
        return true;
    }

    /** O servidor abriu um menu. Se é o da fornalha que pedimos, ficamos com ele sem abrir a tela (true). */
    public boolean onOpenScreen(MenuType<?> type, Minecraft client, int containerId) {
        if (expectUntil < 0 || kind == null || type != kind.menuType || client.player == null) {
            return false;
        }
        expectUntil = -1;
        AbstractFurnaceMenu m = (AbstractFurnaceMenu) type.create(containerId, client.player.getInventory());
        client.player.containerMenu = m;
        menu = m;
        InvOps.setHidden(m, () -> yieldMenu(client));
        seen = lastUse = ticks;
        last = "fornalha aberta (sem tela)";
        HandsOnVR.LOGGER.debug("[HandsOn VR] {} aberta sem tela em {}", kind, pos);
        return true;
    }

    /** O resto do mod precisa do inventário inteiro (armadura, crafting 2x2): fecha e espera um pouco para reabrir. */
    private void yieldMenu(Minecraft client) {
        close(client, "cedeu o menu ao inventário");
        suppressUntil = ticks + SUPPRESS;
    }

    /** Fecha o menu da fornalha (os itens ficam nela, como no jogo). */
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
            last = "fornalha fechada: " + why;
        }
        InvOps.clearHidden(menu);
        menu = null;
        expectUntil = -1;
        queue.clear();
    }

    // ------------------------------------------------------------------ ações

    /**
     * Põe itens do espaço {@code from} (menu do jogador) na entrada ou no combustível: a pilha inteira ({@code all};
     * um item diferente troca de lugar e vai para {@code from}) ou UM item. Se o menu ainda não abriu, espera por ele.
     * Retorna false se não dá (nada a pôr, lugar cheio ou com outro item, menu que não abre). Um item que o lugar não
     * aceita (o que não cozinha nesta fornalha, ou o que não queima) vibra "negado" quando o menu responde.
     */
    public boolean place(Minecraft client, int hand, int from, int target, boolean all) {
        LocalPlayer p = client.player;
        ItemStack expected = InvOps.stack(p, from).copy();
        if ((target != INPUT && target != FUEL) || expected.isEmpty() || !(from >= InvOps.MAIN_START && from <= InvOps.OFFHAND)) {
            return false;
        }
        if (menu == null) {
            if (!ensureOpen(client, true)) {
                return false;
            }
            queue.add(c -> VrBridge.pulse(hand, doPlace(c, from, expected, target, all) ? 0.02f : 0.08f));
            return true;
        }
        return doPlace(client, from, expected, target, all);
    }

    /** O lugar aceita este item? A entrada só o que cozinha nesta fornalha; o combustível só o que queima. */
    public boolean accepts(int target, ItemStack stack) {
        if (menu == null || stack.isEmpty()) {
            return false;
        }
        if (target == INPUT) {
            return ((AbstractFurnaceMenuAccessor) menu).handsonvr$canSmelt(stack);
        }
        return target == FUEL && menu.getSlot(FUEL).mayPlace(stack);
    }

    /** O lugar recebe mais um deste item (vazio, ou com o mesmo item e espaço)? */
    private boolean fits(int target, ItemStack stack) {
        ItemStack in = stack(target);
        return in.isEmpty() || (ItemStack.isSameItemSameComponents(in, stack) && in.getCount() < in.getMaxStackSize());
    }

    private boolean doPlace(Minecraft client, int from, ItemStack expected, int target, boolean all) {
        LocalPlayer p = client.player;
        ItemStack now = InvOps.stack(p, from);
        if (menu == null || p.containerMenu != menu || !ItemStack.isSameItemSameComponents(now, expected)
                || !accepts(target, now) || (!all && !fits(target, now)) || !InvOps.cursor(p).isEmpty()) {
            last = menu != null && !accepts(target, now) ? "o lugar " + target + " não aceita " + now.getItem() : last;
            return false;
        }
        ItemStack before = stack(target).copy();
        if (from == InvOps.OFFHAND) {
            // A mão secundária não é um espaço do menu da fornalha: troca com a hotbar 1, coloca dali e destroca.
            int temp = InvOps.HOTBAR_START;
            InvOps.swap(client, temp, InvOps.OFFHAND_BUTTON);
            placeFrom(client, InvOps.hiddenIndex(temp), target, all);
            InvOps.swap(client, temp, InvOps.OFFHAND_BUTTON);
        } else {
            placeFrom(client, InvOps.hiddenIndex(from), target, all);
        }
        InvOps.flushCursor(client);
        boolean placed = !ItemStack.matches(before, stack(target));
        last = placed ? (all ? "pôs a pilha no lugar " : "pôs um no lugar ") + target : "não coube no lugar " + target;
        return placed;
    }

    /** Pega a pilha do espaço (índice do menu da fornalha), põe tudo (botão esquerdo) ou um (direito) e devolve o resto. */
    private static void placeFrom(Minecraft client, int menuSlot, int target, boolean all) {
        InvOps.clickHidden(client, menuSlot, 0, ContainerInput.PICKUP);
        InvOps.clickHidden(client, target, all ? 0 : 1, ContainerInput.PICKUP);
        InvOps.clickHidden(client, menuSlot, 0, ContainerInput.PICKUP);
    }

    /**
     * Tira os itens da entrada ou do combustível para o inventário (o Shift+clique da tela). Retorna o espaço do menu
     * do jogador em que eles ficaram, ou -1.
     */
    public int take(Minecraft client, int target) {
        LocalPlayer p = client.player;
        ItemStack in = stack(target).copy();
        if (menu == null || in.isEmpty() || p.containerMenu != menu || !InvOps.cursor(p).isEmpty()) {
            return -1;
        }
        int[] before = counts(p, in);
        InvOps.clickHidden(client, target, 0, ContainerInput.QUICK_MOVE);
        int[] after = counts(p, in);
        int best = -1, gained = 0;
        for (int i = InvOps.MAIN_START; i < InvOps.OFFHAND; i++) {
            int g = after[i] - before[i];
            if (g > gained) {
                gained = g;
                best = i;
            }
        }
        last = best >= 0 ? "tirou o lugar " + target + " para o espaço " + best : "o lugar " + target + " não saiu (sem espaço)";
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
     * Pega o resultado (a pilha toda, como o clique na tela, com a experiência do jogo) direto no espaço {@code target}
     * do menu do jogador (vazio, ou com o mesmo item). Retorna true se pegou.
     */
    public boolean takeResultInto(Minecraft client, int target) {
        LocalPlayer p = client.player;
        ItemStack result = resultStack().copy();
        if (menu == null || result.isEmpty() || p.containerMenu != menu || !InvOps.cursor(p).isEmpty()
                || InvOps.hiddenIndex(target) < 0) {
            return false;
        }
        InvOps.clickHidden(client, RESULT, 0, ContainerInput.PICKUP);
        if (InvOps.cursor(p).isEmpty()) {
            return false;
        }
        InvOps.clickHidden(client, InvOps.hiddenIndex(target), 0, ContainerInput.PICKUP);
        InvOps.flushCursor(client);
        last = "pegou " + result.getCount() + "x " + result.getItem() + " (espaço " + target + ")";
        return true;
    }

    /** Para o teste: o bloco é uma das três fornalhas? */
    public static boolean isFurnace(Level level, BlockPos pos) {
        return pos != null && Kind.of(level.getBlockState(pos)) != null;
    }
}
