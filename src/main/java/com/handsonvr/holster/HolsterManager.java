package com.handsonvr.holster;

import com.handsonvr.HandsOnConfig;
import com.handsonvr.HandsOnVR;
import com.handsonvr.hands.GrabSystem;
import com.handsonvr.inv.InvOps;
import com.handsonvr.vr.VrBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.EnumMap;

/**
 * Coldres físicos. O item guardado continua no inventário do jogador (de preferência no inventário principal, fora
 * da hotbar); o coldre só lembra qual é. Sacar é um "pegar emprestado" do {@link GrabSystem} (soltar o grip
 * devolve), então funciona em qualquer servidor e com o inventário cheio.
 *
 * <ul>
 *   <li><b>Sacar</b>: mão no coldre + grip — o item vai para a mão; o que estava na mão vai para o lugar dele.</li>
 *   <li><b>Guardar</b>: segurar o item com o grip (o da mão, ou um pego da mochila/radial) e soltar o grip dentro do
 *       coldre ({@link #store}). Num coldre ocupado, o coldre passa a lembrar do item novo.</li>
 * </ul>
 *
 * Também acompanha em que zona cada mão está (inclusive a da mochila, no ombro esquerdo). Mão: 0 = principal,
 * 1 = secundária.
 */
public final class HolsterManager {
    private final BodyFrame body = new BodyFrame();
    private final GrabSystem grabs;
    private EnumMap<Holster, HolsterStorage.Binding> bindings = new EnumMap<>(Holster.class);
    private String worldKey;
    /** Zona em que cada mão está agora (com folga para sair: não fica piscando na borda). */
    private final Holster[] zone = new Holster[2];

    public HolsterManager(GrabSystem grabs) {
        this.grabs = grabs;
    }

    public BodyFrame body() {
        return body;
    }

    // ------------------------------------------------------------------ mundo

    public void onJoin(Minecraft client) {
        worldKey = HolsterStorage.worldKey(client);
        bindings = HolsterStorage.load(worldKey);
        zone[0] = zone[1] = null;
        body.reset();
        HandsOnVR.LOGGER.info("[HandsOn VR] Coldres de '{}': {}", worldKey, bindings.keySet());
    }

    public void onLeave() {
        zone[0] = zone[1] = null;
    }

    // ------------------------------------------------------------------ zonas

    /** Atualiza a zona de cada mão e dá um toque leve ao chegar numa zona onde o grip faz algo. */
    public void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }
        BodyFrame.Frame frame = VrBridge.isVRActive() ? body.compute() : null;
        HandsOnConfig config = HandsOnConfig.get();
        for (int hand = 0; hand < 2; hand++) {
            VrBridge.Pose pose = frame == null ? null : VrBridge.hand(hand);
            Holster before = zone[hand];
            zone[hand] = pose == null ? null : zoneAt(frame, pose.position(), before, config);
            if (zone[hand] != null && zone[hand] != before && config.zoneHaptics && actionable(player, zone[hand], hand)) {
                VrBridge.pulse(hand, 0.006f);
            }
        }
    }

    private Holster zoneAt(BodyFrame.Frame frame, Vec3 handPos, Holster current, HandsOnConfig config) {
        if (current != null && current.active(config)
                && current.normalizedDistance(frame, handPos, config) <= Holster.LEAVE_FACTOR) {
            return current;
        }
        Holster best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Holster h : Holster.values()) {
            if (!h.active(config)) {
                continue;
            }
            double d = h.normalizedDistance(frame, handPos, config);
            if (d <= 1.0 && d < bestDistance) {
                bestDistance = d;
                best = h;
            }
        }
        return best;
    }

    /**
     * O grip faz algo nesta zona: abrir a mochila, sacar o item guardado ou segurar o item da mão; com um item seguro,
     * soltar aqui o guarda (no coldre, ou na mochila por cima do ombro).
     */
    private boolean actionable(LocalPlayer player, Holster h, int hand) {
        HandsOnConfig config = HandsOnConfig.get();
        GrabSystem.Grab g = grabs.get(hand);
        if (h.isBackpack(config)) {
            return g == null || g.source() >= InvOps.HOTBAR_START; // abrir, ou guardar na mochila o item seguro
        }
        if (g != null) {
            return g.holster() != h; // soltar aqui guarda o item seguro
        }
        return findBound(player, h, hand) >= 0 || !held(player, hand).isEmpty();
    }

    /** Zona em que a mão está (null se nenhuma). Inclui o ombro esquerdo da mochila. */
    public Holster zoneOf(int hand) {
        return zone[hand];
    }

    /** Recalcula a zona da mão agora (o grip pode chegar antes do tick). */
    public Holster refreshZone(int hand) {
        BodyFrame.Frame frame = VrBridge.isVRActive() ? body.compute() : null;
        VrBridge.Pose pose = frame == null ? null : VrBridge.hand(hand);
        zone[hand] = pose == null ? null : zoneAt(frame, pose.position(), zone[hand], HandsOnConfig.get());
        return zone[hand];
    }

    // ------------------------------------------------------------------ grip

    /**
     * Grip apertado num coldre (não na mochila) que tem um item: saca o item (pegar emprestado). Retorna true se sacou.
     * Coldre vazio não faz nada aqui: o {@link com.handsonvr.hands.HandsController} segura o item da mão, e soltar o
     * grip no coldre o guarda ({@link #store}).
     */
    public boolean press(Minecraft client, int hand) {
        LocalPlayer player = client.player;
        HandsOnConfig config = HandsOnConfig.get();
        if (player == null || !InvOps.ready(client) || client.gui.screen() != null || grabs.isGrabbing(hand)
                || player.isSpectator()) {
            return false;
        }
        Holster h = refreshZone(hand);
        if (h == null || !h.enabled(config)) {
            return false;
        }
        int slot = findBound(player, h, hand);
        return slot >= 0 && grabs.begin(client, hand, InvOps.fromInventory(slot), GrabSystem.Origin.HOLSTER, h);
    }

    /**
     * Guarda no coldre o item do espaço {@code handlerSlot} (o item que a mão segurava com o grip, solto aqui). Se ele
     * está na hotbar ou na mão secundária e há espaço livre no inventário principal, vai para lá: a mão fica vazia,
     * como ao pôr a espada na bainha. Sem espaço livre, o item fica onde está e o coldre lembra dele assim mesmo. Se o
     * coldre já tinha um item, passa a lembrar do novo; o antigo continua no inventário. Uma armadura vestida ou um
     * item do crafting sempre vai para o inventário principal (sem espaço livre, não guarda).
     */
    public boolean store(Minecraft client, Holster h, int handlerSlot) {
        LocalPlayer player = client.player;
        if (player == null || InvOps.stack(player, handlerSlot).isEmpty() || !h.enabled(HandsOnConfig.get())) {
            return false;
        }
        int slot = handlerSlot;
        boolean outsideMain = handlerSlot < InvOps.MAIN_START;
        if (handlerSlot >= InvOps.HOTBAR_START || outsideMain) {
            int empty = freeMainSlot(player);
            if (empty >= 0 && InvOps.moveStack(client, handlerSlot, empty)) {
                slot = empty;
            } else if (outsideMain) {
                return false;
            }
        }
        return assign(player, h, slot);
    }

    /** Primeiro espaço vazio do inventário principal que não é a origem de um item pego agora, ou -1. */
    private int freeMainSlot(LocalPlayer player) {
        for (int i = InvOps.MAIN_START; i < InvOps.HOTBAR_START; i++) {
            if (InvOps.stack(player, i).isEmpty() && !grabs.isSource(i)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * O coldre passa a lembrar do item que está no espaço {@code handlerSlot}, sem mexer nele (um item pego da
     * mochila já está no inventário principal).
     */
    private boolean assign(LocalPlayer player, Holster h, int handlerSlot) {
        int invSlot = InvOps.toInventory(handlerSlot);
        ItemStack stack = InvOps.stack(player, handlerSlot);
        if (invSlot < 0 || stack.isEmpty() || !h.enabled(HandsOnConfig.get())) {
            return false;
        }
        HolsterStorage.Binding binding = bindingOf(stack, invSlot);
        bindings.put(h, binding);
        HolsterStorage.save(worldKey, bindings);
        HandsOnVR.LOGGER.info("[HandsOn VR] {} guardado em {} (solto no coldre)", binding.item(), h);
        return true;
    }

    /** Esvazia um coldre (tela de ajustes). O item continua no inventário. */
    public void clear(Holster h) {
        if (bindings.remove(h) != null) {
            HolsterStorage.save(worldKey, bindings);
        }
    }

    // ------------------------------------------------------------------ inventário

    private static ItemStack held(LocalPlayer player, int hand) {
        return hand == 0 ? player.getMainHandItem() : player.getOffhandItem();
    }

    private static HolsterStorage.Binding bindingOf(ItemStack stack, int slot) {
        return new HolsterStorage.Binding(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), customName(stack), slot);
    }

    private static String customName(ItemStack stack) {
        Component name = stack.get(DataComponents.CUSTOM_NAME);
        return name == null ? null : name.getString();
    }

    private static boolean sameItem(ItemStack stack, HolsterStorage.Binding b, boolean checkName) {
        if (stack.isEmpty() || !BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(b.item())) {
            return false;
        }
        return !checkName || java.util.Objects.equals(customName(stack), b.name());
    }

    /**
     * Onde está o item deste coldre (espaço do inventário), ou -1 se não houver (coldre vazio, ou o item sumiu).
     * Primeiro o espaço lembrado; depois o mesmo item com o mesmo nome; depois o mesmo item (a durabilidade muda com
     * o uso, por isso não entra na comparação). Ignora o que a outra mão pegou agora.
     */
    private int findBound(LocalPlayer player, Holster h, int hand) {
        HolsterStorage.Binding b = bindings.get(h);
        if (b == null) {
            return -1;
        }
        GrabSystem.Grab other = grabs.get(1 - hand);
        if (other != null && other.origin() == GrabSystem.Origin.HOLSTER && other.holster() == h) {
            return -1; // a outra mão está com ele
        }
        int reserved = other == null ? -1 : InvOps.toInventory(InvOps.handSlot(player, 1 - hand));
        Inventory inv = player.getInventory();
        if (b.slot() != reserved && b.slot() >= 0 && b.slot() < inv.getContainerSize() && sameItem(inv.getItem(b.slot()), b, false)) {
            return b.slot();
        }
        for (boolean checkName : new boolean[] {true, false}) {
            for (int slot : searchOrder()) {
                if (slot != reserved && sameItem(inv.getItem(slot), b, checkName)) {
                    if (slot != b.slot()) {
                        bindings.put(h, new HolsterStorage.Binding(b.item(), b.name(), slot));
                    }
                    return slot;
                }
            }
        }
        return -1;
    }

    private static int[] searchOrder() {
        int[] order = new int[37];
        int n = 0;
        for (int i = 9; i < 36; i++) {
            order[n++] = i;
        }
        for (int i = 0; i < 9; i++) {
            order[n++] = i;
        }
        order[n] = Inventory.SLOT_OFFHAND;
        return order;
    }

    // ------------------------------------------------------------------ desenho e ajustes

    /** O que aparece no coldre agora (vazio se o coldre está vazio ou o item está na mão). */
    public ItemStack displayStack(LocalPlayer player, Holster h) {
        for (int hand = 0; hand < 2; hand++) {
            GrabSystem.Grab g = grabs.get(hand);
            if (g != null && g.origin() == GrabSystem.Origin.HOLSTER && g.holster() == h) {
                return ItemStack.EMPTY;
            }
        }
        if (bindings.get(h) == null) {
            return ItemStack.EMPTY;
        }
        int slot = findBound(player, h, 0);
        return slot < 0 ? ItemStack.EMPTY : player.getInventory().getItem(slot);
    }

    public HolsterStorage.Binding binding(Holster h) {
        return bindings.get(h);
    }

    /** A mão sacou um item deste sistema de coldres e ainda segura o grip. */
    public boolean isDrawing(int hand) {
        GrabSystem.Grab g = grabs.get(hand);
        return g != null && g.origin() == GrabSystem.Origin.HOLSTER;
    }
}
