package com.handsonvr.holster;

import com.handsonvr.HandsOnConfig;
import com.handsonvr.HandsOnVR;
import com.handsonvr.input.GripState;
import com.handsonvr.vr.VrBridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.util.EnumMap;

/**
 * Coldres físicos. O item guardado continua no inventário do jogador (de preferência no inventário principal, fora
 * da hotbar); o coldre só lembra qual é. Tudo com os pacotes normais de inventário do Minecraft (trocar um espaço
 * com a hotbar/mão esquerda, como as teclas 1-9/F na tela de inventário), então funciona em qualquer servidor.
 *
 * <ul>
 *   <li><b>Sacar</b>: mão no coldre + apertar o grip — o item vai para a mão (principal: o espaço selecionado da
 *       hotbar; esquerda: a mão secundária) e o que estava na mão vai para o lugar dele.</li>
 *   <li>Enquanto segura o grip, o item é usado normalmente (gatilho, A, golpe com o braço).</li>
 *   <li><b>Soltar o grip</b>: o item volta para o coldre e a mão volta a segurar o que tinha antes.</li>
 *   <li><b>Guardar</b>: coldre vazio + item na mão + grip nele — o item sai da mão e fica no coldre.</li>
 * </ul>
 *
 * Mão: 0 = principal, 1 = secundária.
 */
public final class HolsterManager {
    private enum Mode { NONE, SELECTED, SWAPPED }

    /**
     * Item sacado por uma mão. SELECTED: estava na hotbar, só mudou a seleção (volta para {@code previousSelected}).
     * SWAPPED: veio do inventário (trocado com {@code button}: espaço da hotbar ou 40 = mão esquerda; trocar de novo
     * devolve tudo). {@code invSlot}: onde o item do coldre mora.
     */
    private record Draw(Holster holster, Mode mode, int handlerSlot, int button, int previousSelected, int invSlot) {}

    private static final int OFFHAND = 40;
    private static final double LEAVE_FACTOR = 1.3;

    private final BodyFrame body = new BodyFrame();
    private EnumMap<Holster, HolsterStorage.Binding> bindings = new EnumMap<>(Holster.class);
    private String worldKey;
    private final Draw[] draws = new Draw[2];
    /** Coldre em que cada mão está agora (com folga para sair: não fica piscando na borda). */
    private final Holster[] zone = new Holster[2];

    public BodyFrame body() {
        return body;
    }

    // ------------------------------------------------------------------ mundo

    public void onJoin(MinecraftClient client) {
        worldKey = HolsterStorage.worldKey(client);
        bindings = HolsterStorage.load(worldKey);
        draws[0] = draws[1] = null;
        zone[0] = zone[1] = null;
        body.reset();
        HandsOnVR.LOGGER.info("[HandsOn VR] Coldres de '{}': {}", worldKey, bindings.keySet());
    }

    public void onLeave() {
        draws[0] = draws[1] = null;
        zone[0] = zone[1] = null;
    }

    // ------------------------------------------------------------------ tick

    public void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return;
        }
        BodyFrame.Frame frame = VrBridge.isVRActive() ? body.compute() : null;
        HandsOnConfig config = HandsOnConfig.get();
        for (int hand = 0; hand < 2; hand++) {
            // Item sacado: o grip foi solto (ou abriu uma tela, ou saiu do VR) -> volta para o coldre.
            if (draws[hand] != null && (frame == null || client.currentScreen != null || !GripState.isHeld(hand))) {
                release(client, hand);
            }
            VrBridge.Pose pose = frame == null ? null : VrBridge.hand(hand);
            Holster before = zone[hand];
            zone[hand] = pose == null ? null : zoneAt(frame, pose.position(), before, config);
            // Chegou num coldre onde o grip faz algo: um toque leve na mão.
            if (zone[hand] != null && zone[hand] != before && config.zoneHaptics && draws[hand] == null
                    && actionable(player, zone[hand], hand)) {
                VrBridge.pulse(hand, 0.006f);
            }
        }
    }

    private Holster zoneAt(BodyFrame.Frame frame, Vec3d handPos, Holster current, HandsOnConfig config) {
        if (current != null && current.enabled(config)
                && handPos.distanceTo(frame.anchor(current, config)) <= current.radius * frame.scale() * LEAVE_FACTOR) {
            return current;
        }
        Holster best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Holster h : Holster.values()) {
            if (!h.enabled(config)) {
                continue;
            }
            double d = handPos.distanceTo(frame.anchor(h, config));
            if (d <= h.radius * frame.scale() && d < bestDistance) {
                bestDistance = d;
                best = h;
            }
        }
        return best;
    }

    /** O grip faz algo neste coldre: sacar o item guardado, ou guardar o item da mão. */
    private boolean actionable(ClientPlayerEntity player, Holster h, int hand) {
        return findBound(player, h, hand) >= 0 || !held(player, hand).isEmpty();
    }

    // ------------------------------------------------------------------ grip

    /**
     * Grip apertado (vem do Vivecraft, ou do teste). Retorna true se o coldre agiu — aí o grip não troca o item da
     * hotbar.
     */
    public boolean press(MinecraftClient client, int hand) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.interactionManager == null || client.currentScreen != null || draws[hand] != null
                || player.isSpectator()) {
            return false;
        }
        HandsOnConfig config = HandsOnConfig.get();
        BodyFrame.Frame frame = body.compute();
        VrBridge.Pose pose = VrBridge.hand(hand);
        if (frame == null || pose == null) {
            return false;
        }
        Holster h = zoneAt(frame, pose.position(), zone[hand], config);
        if (h == null) {
            return false;
        }
        int slot = findBound(player, h, hand);
        if (slot >= 0) {
            draw(client, player, hand, h, slot);
            return true;
        }
        ItemStack inHand = held(player, hand);
        if (!inHand.isEmpty()) {
            store(client, player, hand, h, inHand);
            return true;
        }
        return false;
    }

    /** Grip solto. Retorna true se havia um item sacado (que voltou para o coldre). */
    public boolean releaseGrip(MinecraftClient client, int hand) {
        if (draws[hand] == null) {
            return false;
        }
        release(client, hand);
        return true;
    }

    private void draw(MinecraftClient client, ClientPlayerEntity player, int hand, Holster h, int slot) {
        PlayerInventory inv = player.getInventory();
        if (hand == 0) {
            int selected = inv.selectedSlot;
            if (slot == selected) {
                draws[0] = new Draw(h, Mode.NONE, -1, -1, selected, slot);
            } else if (slot < 9) {
                inv.selectedSlot = slot;
                draws[0] = new Draw(h, Mode.SELECTED, -1, -1, selected, slot);
            } else {
                swap(client, player, toHandler(slot), selected);
                draws[0] = new Draw(h, Mode.SWAPPED, toHandler(slot), selected, selected, slot);
            }
        } else {
            if (slot == OFFHAND) {
                draws[1] = new Draw(h, Mode.NONE, -1, -1, -1, slot);
            } else {
                swap(client, player, toHandler(slot), OFFHAND);
                draws[1] = new Draw(h, Mode.SWAPPED, toHandler(slot), OFFHAND, -1, slot);
            }
        }
        VrBridge.pulse(hand, 0.03f);
    }

    private void release(MinecraftClient client, int hand) {
        Draw d = draws[hand];
        draws[hand] = null;
        ClientPlayerEntity player = client.player;
        if (d == null || player == null || client.interactionManager == null) {
            return;
        }
        switch (d.mode()) {
            case SELECTED -> player.getInventory().selectedSlot = d.previousSelected();
            case SWAPPED -> swap(client, player, d.handlerSlot(), d.button());
            default -> { }
        }
        VrBridge.pulse(hand, 0.015f);
    }

    private void store(MinecraftClient client, ClientPlayerEntity player, int hand, Holster h, ItemStack stack) {
        PlayerInventory inv = player.getInventory();
        int from = hand == 0 ? inv.selectedSlot : OFFHAND;
        HolsterStorage.Binding binding = bindingOf(stack, from);
        // Tira da mão: para um espaço livre do inventário principal (fora da hotbar). Sem espaço, fica onde está.
        int empty = firstEmptyMain(inv);
        if (empty >= 0) {
            swap(client, player, toHandler(empty), from);
            binding = bindingOf(stack, empty);
        }
        bindings.put(h, binding);
        HolsterStorage.save(worldKey, bindings);
        VrBridge.pulse(hand, 0.05f);
        HandsOnVR.LOGGER.info("[HandsOn VR] {} guardado em {}", binding.item(), h);
    }

    /** Esvazia um coldre (tela de ajustes). O item continua no inventário. */
    public void clear(Holster h) {
        if (bindings.remove(h) != null) {
            HolsterStorage.save(worldKey, bindings);
        }
    }

    // ------------------------------------------------------------------ inventário

    private static ItemStack held(ClientPlayerEntity player, int hand) {
        return hand == 0 ? player.getMainHandStack() : player.getOffHandStack();
    }

    /** Índice do espaço do inventário no menu do jogador (o que os pacotes de clique usam). */
    private static int toHandler(int invSlot) {
        if (invSlot < 9) {
            return 36 + invSlot;
        }
        return invSlot == OFFHAND ? 45 : invSlot;
    }

    private static void swap(MinecraftClient client, ClientPlayerEntity player, int handlerSlot, int button) {
        client.interactionManager.clickSlot(player.playerScreenHandler.syncId, handlerSlot, button, SlotActionType.SWAP, player);
    }

    private static int firstEmptyMain(PlayerInventory inv) {
        for (int i = 9; i < 36; i++) {
            if (inv.getStack(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private static HolsterStorage.Binding bindingOf(ItemStack stack, int slot) {
        return new HolsterStorage.Binding(Registries.ITEM.getId(stack.getItem()).toString(), customName(stack), slot);
    }

    private static String customName(ItemStack stack) {
        Text name = stack.get(DataComponentTypes.CUSTOM_NAME);
        return name == null ? null : name.getString();
    }

    private static boolean sameItem(ItemStack stack, HolsterStorage.Binding b, boolean checkName) {
        if (stack.isEmpty() || !Registries.ITEM.getId(stack.getItem()).toString().equals(b.item())) {
            return false;
        }
        return !checkName || java.util.Objects.equals(customName(stack), b.name());
    }

    /**
     * Onde está o item deste coldre (espaço do inventário), ou -1 se não houver (coldre vazio, ou o item sumiu).
     * Primeiro o espaço lembrado; depois o mesmo item com o mesmo nome; depois o mesmo item (a durabilidade muda com
     * o uso, por isso não entra na comparação). Ignora o que a outra mão sacou agora.
     */
    private int findBound(ClientPlayerEntity player, Holster h, int hand) {
        HolsterStorage.Binding b = bindings.get(h);
        if (b == null) {
            return -1;
        }
        Draw other = draws[1 - hand];
        if (other != null && other.holster() == h) {
            return -1; // a outra mão está com ele
        }
        int reserved = other == null ? -1 : other.mode() == Mode.SWAPPED ? other.button() : other.invSlot();
        PlayerInventory inv = player.getInventory();
        if (b.slot() != reserved && b.slot() >= 0 && b.slot() < inv.size() && sameItem(inv.getStack(b.slot()), b, false)) {
            return b.slot();
        }
        for (boolean checkName : new boolean[] {true, false}) {
            for (int slot : searchOrder()) {
                if (slot != reserved && sameItem(inv.getStack(slot), b, checkName)) {
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
        order[n] = OFFHAND;
        return order;
    }

    // ------------------------------------------------------------------ desenho e ajustes

    /** O que aparece no coldre agora (vazio se o coldre está vazio ou o item está na mão). */
    public ItemStack displayStack(ClientPlayerEntity player, Holster h) {
        if ((draws[0] != null && draws[0].holster() == h) || (draws[1] != null && draws[1].holster() == h)) {
            return ItemStack.EMPTY;
        }
        HolsterStorage.Binding b = bindings.get(h);
        if (b == null) {
            return ItemStack.EMPTY;
        }
        int slot = findBound(player, h, 0);
        return slot < 0 ? ItemStack.EMPTY : player.getInventory().getStack(slot);
    }

    public HolsterStorage.Binding binding(Holster h) {
        return bindings.get(h);
    }

    /** Coldre em que a mão está (null se nenhum). */
    public Holster zoneOf(int hand) {
        return zone[hand];
    }

    public boolean isDrawing(int hand) {
        return draws[hand] != null;
    }
}
