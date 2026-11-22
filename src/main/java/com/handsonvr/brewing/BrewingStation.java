package com.handsonvr.brewing;

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
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * O suporte de poções usado com as mãos, como as fornalhas: olhando para ele de perto (ou levando um item até ele), o
 * mod fica com o menu do jogo sem a tela.
 *
 * <p>Os lugares são os do próprio modelo do bloco (brewing_stand_bottle0..2): as três garrafas penduradas nos braços
 * (leste, noroeste e sudoeste da haste), o ingrediente no topo da haste e o pó de blaze ao lado do ingrediente, à esquerda de quem olha o suporte (como na tela do
 * jogo: o combustível à esquerda do ingrediente), flutuando e bem separado dos outros. As garrafas o
 * jogo já desenha no bloco; o mod desenha o ingrediente, o pó de blaze e, acima, a plaquinha com as bolhas, a seta e a
 * barra de combustível da tela do jogo. Soltar o grip num lugar põe a pilha, o gatilho põe um, grip num lugar com item
 * o traz para a mão.
 */
public final class BrewingStation {
    /** Alvos = índices do menu: 0-2 garrafas, 3 ingrediente, 4 combustível. */
    public static final int BOTTLE_0 = 0, INGREDIENT = 3, FUEL = 4, NONE = -1;
    /** Onde fica cada lugar no bloco (x, y, z de 0 a 1) e o raio do toque (m). */
    private static final double[][] SPOTS = {
            {0.75, 0.30, 0.50}, {0.31, 0.30, 0.31}, {0.31, 0.30, 0.69}, {0.50, 1.02, 0.50}, {0.50, 1.02, 0.50}}; // o do pó de blaze sai de spot()
    private static final double[] REACH = {0.17, 0.17, 0.17, 0.17, 0.15};
    /** A plaquinha do progresso: altura acima do bloco (m). */
    public static final double PANEL_HEIGHT = 1.45;
    private static final double NEAR = 2.4, FAR = 4.0, LOOK_ANGLE = Math.toRadians(45), HAND_NEAR = 0.6;
    private static final int OPEN_TIMEOUT = 40, RETRY_WAIT = 100, LOOK_AWAY = 60, SUPPRESS = 100;

    private BlockPos pos;
    private BrewingStandMenu menu;
    private long ticks = 0, expectUntil = -1, retryAfter = 0, suppressUntil = 0, seen = 0, lastUse = 0;
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

    public int hover(int hand) {
        return hover[hand];
    }

    public ItemStack stack(int target) {
        return menu == null || target < 0 || target > FUEL ? ItemStack.EMPTY : menu.getSlot(target).getItem();
    }

    /** Ticks que faltam para a poção ficar pronta (400 no começo; 0 = parado). */
    public int brewTicks() {
        return menu == null ? 0 : menu.getBrewingTicks();
    }

    /** Usos de pó de blaze que restam (0 a 20). */
    public int fuel() {
        return menu == null ? 0 : menu.getFuel();
    }

    public boolean blocksSwing() {
        return pos != null && (hover[0] != NONE || hover[1] != NONE);
    }

    public void reset() {
        InvOps.clearHidden(menu);
        menu = null;
        pos = null;
        expectUntil = -1;
        queue.clear();
        hover[0] = hover[1] = NONE;
    }

    /** Afastamento (m) do pó de blaze ao ingrediente, para o lado. */
    private static final double FUEL_SIDE = 0.32;
    /** Para que lado do bloco está quem olha (0 = +z, 1 = -x, 2 = -z, 3 = +x); só muda quando a pessoa dá a volta no bloco. */
    private int viewSide = 0;
    private static final int[][] CARDINAL = {{0, 1}, {-1, 0}, {0, -1}, {1, 0}};

    private void updateViewSide() {
        VrBridge.Pose head = VrBridge.head();
        if (head == null || pos == null || hover[0] != NONE || hover[1] != NONE) {
            return;
        }
        double dx = head.position().x - (pos.getX() + 0.5), dz = head.position().z - (pos.getZ() + 0.5);
        double len = Math.hypot(dx, dz);
        if (len < 0.2) {
            return;
        }
        int[] now = CARDINAL[viewSide];
        if ((dx * now[0] + dz * now[1]) / len >= Math.cos(Math.toRadians(55))) {
            return; // ainda de frente para o mesmo lado: o lugar não anda
        }
        int best = viewSide;
        double bestDot = -2;
        for (int i = 0; i < 4; i++) {
            double dot = (dx * CARDINAL[i][0] + dz * CARDINAL[i][1]) / len;
            if (dot > bestDot) {
                bestDot = dot;
                best = i;
            }
        }
        viewSide = best;
    }

    /** O centro de um lugar no mundo. */
    public Vec3 spot(int target) {
        double[] s = SPOTS[target];
        if (target == FUEL) {
            int[] f = CARDINAL[viewSide];
            return new Vec3(pos.getX() + 0.5 - f[1] * FUEL_SIDE, pos.getY() + s[1], pos.getZ() + 0.5 + f[0] * FUEL_SIDE);
        }
        return new Vec3(pos.getX() + s[0], pos.getY() + s[1], pos.getZ() + s[2]);
    }

    /** O centro da plaquinha de progresso. */
    public Vec3 panelCenter() {
        return Vec3.atBottomCenterOf(pos).add(0, PANEL_HEIGHT, 0);
    }

    public int targetAt(Vec3 tip) {
        if (pos == null || tip == null) {
            return NONE;
        }
        int best = NONE;
        double bestDistance = Double.MAX_VALUE;
        for (int t = 0; t <= FUEL; t++) {
            double d = tip.distanceTo(spot(t));
            if (d <= REACH[t] && d < bestDistance) {
                best = t;
                bestDistance = d;
            }
        }
        return best;
    }

    public boolean updateHover(int hand, int target) {
        int before = hover[hand];
        hover[hand] = target;
        if (target != NONE) {
            lastUse = ticks;
        }
        return target != NONE && target != before;
    }

    // ------------------------------------------------------------------ tick, abrir e fechar

    public void tick(Minecraft client, boolean canAuto) {
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
        boolean vr = VrBridge.isVRActive() && client.gui.screen() == null;
        BlockPos best = vr ? findBest(client) : null;
        if ((menu != null || expectUntil >= 0)
                && (!client.level.getBlockState(pos).is(Blocks.BREWING_STAND) || headDistance(pos) > FAR)) {
            close(client, "longe");
        }
        if (menu != null) {
            if (!queue.isEmpty() || hover[0] != NONE || hover[1] != NONE) {
                lastUse = ticks;
            }
            if (pos.equals(best)) {
                seen = ticks;
            }
            if (ticks - lastUse > 20 && ticks - seen > LOOK_AWAY) {
                close(client, "olhou para outro lado");
            }
        }
        if (menu == null && expectUntil < 0) {
            pos = best;
            updateViewSide();
            if (pos != null && canAuto && vr && ticks >= suppressUntil) {
                ensureOpen(client, false);
            }
        }
        if (pos != null) {
            updateViewSide();
        }
        if (pos == null) {
            hover[0] = hover[1] = NONE;
        }
        if (menu != null && !queue.isEmpty() && p.containerMenu == menu) {
            List<Consumer<Minecraft>> todo = new ArrayList<>(queue);
            queue.clear();
            todo.forEach(a -> a.accept(client));
        }
    }

    private static double headDistance(BlockPos pos) {
        VrBridge.Pose head = VrBridge.head();
        return head == null ? Double.MAX_VALUE : head.position().distanceTo(Vec3.atCenterOf(pos));
    }

    private BlockPos findBest(Minecraft client) {
        VrBridge.Pose head = VrBridge.head();
        if (head == null) {
            return null;
        }
        Vec3 eye = head.position(), look = head.direction();
        BlockPos center = BlockPos.containing(eye), best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos q : BlockPos.betweenClosed(center.offset(-3, -3, -3), center.offset(3, 1, 3))) {
            if (!client.level.getBlockState(q).is(Blocks.BREWING_STAND)) {
                continue;
            }
            Vec3 c = Vec3.atCenterOf(q), to = c.subtract(eye);
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
            double score = (reaching ? angle - 1.0 : angle) - (q.equals(pos) ? Math.toRadians(8) : 0);
            if (score < bestScore) {
                bestScore = score;
                best = q.immutable();
            }
        }
        return best;
    }

    public boolean ensureOpen(Minecraft client, boolean asked) {
        if (menu != null || expectUntil >= 0) {
            return true;
        }
        LocalPlayer p = client.player;
        if (pos == null || p == null || client.gameMode == null || client.gui.screen() != null
                || p.containerMenu != p.inventoryMenu || ticks < retryAfter
                || (!asked && ticks < suppressUntil)) {
            return false;
        }
        expectUntil = ticks + OPEN_TIMEOUT;
        seen = lastUse = ticks;
        com.handsonvr.hands.NoShift.run(client, () -> client.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false)));
        last = "abrindo";
        return true;
    }

    public boolean onOpenScreen(MenuType<?> type, Minecraft client, int containerId) {
        if (expectUntil < 0 || type != MenuType.BREWING_STAND || client.player == null) {
            return false;
        }
        expectUntil = -1;
        BrewingStandMenu m = MenuType.BREWING_STAND.create(containerId, client.player.getInventory());
        client.player.containerMenu = m;
        menu = m;
        InvOps.setHidden(m, () -> {
            close(client, "cedeu o menu ao inventário");
            suppressUntil = ticks + SUPPRESS;
        });
        seen = lastUse = ticks;
        last = "aberto (sem tela)";
        HandsOnVR.LOGGER.debug("[HandsOn VR] suporte de poções aberto sem tela em {}", pos);
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
    }

    // ------------------------------------------------------------------ ações

    /**
     * O lugar aceitaria este item? Com o menu aberto, pergunta ao menu; antes de abrir, o mesmo teste dele: garrafa ou
     * poção nos braços, ingrediente de poção no topo, pó de blaze no meio.
     */
    public boolean likelyAccepts(Minecraft client, int target, ItemStack stack) {
        if (menu != null) {
            return accepts(target, stack);
        }
        if (stack.isEmpty() || client.level == null) {
            return false;
        }
        if (target == FUEL) {
            return stack.is(net.minecraft.world.item.Items.BLAZE_POWDER);
        }
        if (target == INGREDIENT) {
            return client.level.recipeAccess().propertySet(net.minecraft.world.item.crafting.RecipePropertySet.BREWING_REAGENTS).test(stack);
        }
        return net.minecraft.world.item.crafting.PotionIngredient.isPotionInput(stack, client.level.recipeAccess());
    }

    /** O lugar aceita este item (garrafa ou poção nos braços, ingrediente de poção no topo, pó de blaze no meio)? */
    public boolean accepts(int target, ItemStack stack) {
        return menu != null && !stack.isEmpty() && target >= 0 && target <= FUEL && menu.getSlot(target).mayPlace(stack);
    }

    /** Põe a pilha ({@code all}) ou UM item do espaço {@code from} no lugar; se o menu ainda não abriu, espera por ele. */
    public boolean place(Minecraft client, int hand, int from, int target, boolean all) {
        LocalPlayer p = client.player;
        ItemStack expected = InvOps.stack(p, from).copy();
        if (target < 0 || target > FUEL || expected.isEmpty() || !(from >= InvOps.MAIN_START && from <= InvOps.OFFHAND)) {
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

    private boolean doPlace(Minecraft client, int from, ItemStack expected, int target, boolean all) {
        LocalPlayer p = client.player;
        ItemStack now = InvOps.stack(p, from);
        if (menu == null || p.containerMenu != menu || !ItemStack.isSameItemSameComponents(now, expected)
                || !accepts(target, now) || !InvOps.cursor(p).isEmpty()) {
            return false;
        }
        ItemStack before = stack(target).copy();
        int temp = InvOps.HOTBAR_START;
        boolean offhand = from == InvOps.OFFHAND;
        if (offhand) {
            InvOps.swap(client, temp, InvOps.OFFHAND_BUTTON);
        }
        int src = InvOps.hiddenIndex(offhand ? temp : from);
        InvOps.clickHidden(client, src, 0, ContainerInput.PICKUP);
        InvOps.clickHidden(client, target, all ? 0 : 1, ContainerInput.PICKUP);
        InvOps.clickHidden(client, src, 0, ContainerInput.PICKUP);
        if (offhand) {
            InvOps.swap(client, temp, InvOps.OFFHAND_BUTTON);
        }
        InvOps.flushCursor(client);
        boolean placed = !ItemStack.matches(before, stack(target));
        last = placed ? "pôs no lugar " + target : "não coube no lugar " + target;
        return placed;
    }

    /** Tira o lugar para o inventário (Shift+clique). Retorna o espaço do jogador em que ficou, ou -1. */
    public int take(Minecraft client, int target) {
        LocalPlayer p = client.player;
        ItemStack in = stack(target).copy();
        if (menu == null || in.isEmpty() || p.containerMenu != menu || !InvOps.cursor(p).isEmpty()) {
            return -1;
        }
        int[] before = new int[InvOps.OFFHAND + 1];
        for (int i = InvOps.MAIN_START; i < InvOps.OFFHAND; i++) {
            ItemStack s = InvOps.stack(p, i);
            before[i] = ItemStack.isSameItemSameComponents(s, in) ? s.getCount() : 0;
        }
        InvOps.clickHidden(client, target, 0, ContainerInput.QUICK_MOVE);
        int best = -1, gained = 0;
        for (int i = InvOps.MAIN_START; i < InvOps.OFFHAND; i++) {
            ItemStack s = InvOps.stack(p, i);
            int g = (ItemStack.isSameItemSameComponents(s, in) ? s.getCount() : 0) - before[i];
            if (g > gained) {
                gained = g;
                best = i;
            }
        }
        last = best >= 0 ? "tirou o lugar " + target : "o lugar " + target + " não saiu";
        return best;
    }
}
