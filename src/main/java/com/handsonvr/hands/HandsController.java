package com.handsonvr.hands;

import com.handsonvr.HandsOnConfig;
import com.handsonvr.HandsOnVR;
import com.handsonvr.backpack.Backpack;
import com.handsonvr.holster.Holster;
import com.handsonvr.holster.HolsterManager;
import com.handsonvr.input.GripState;
import com.handsonvr.inv.InvOps;
import com.handsonvr.radial.RadialMenu;
import com.handsonvr.vr.VrBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * O "cérebro" das mãos: recebe grip, gatilho e os botões do radial (B/X) de cada mão e decide quem responde — o
 * menu radial aberto, a mochila aberta, a zona do corpo (mochila no ombro esquerdo, coldres) — e aplica a regra das
 * mãos:
 * <ul>
 *   <li>o que o grip pega é emprestado ({@link GrabSystem}): soltar o grip devolve, ou coloca no espaço/coldre em
 *       que a mão está, ou descarta na zona de descarte;</li>
 *   <li>o que o radial escolhe fica na mão (mão principal: seleção da hotbar; mão secundária: troca com a hotbar,
 *       lembrando de onde veio);</li>
 *   <li>gatilho com o grip apertado sobre um espaço/descarte = UM item.</li>
 * </ul>
 * Mão: 0 = principal (B, gatilho direito), 1 = secundária (X, gatilho esquerdo).
 */
public final class HandsController {
    private final GrabSystem grabs = new GrabSystem();
    private final HolsterManager holsters = new HolsterManager(grabs);
    private final Backpack backpack = new Backpack();
    private final RadialMenu radial = new RadialMenu();
    /** Espaço da hotbar (0-8) de onde veio o item que o radial pôs na mão esquerda; -1 = nenhum. */
    private int offSource = -1;
    /** O aperto do botão do radial foi nosso (o soltar também é). */
    private final boolean[] menuConsumed = new boolean[2];
    /** O que aconteceu por último (log e teste automático). */
    private String lastAction = "-";

    public GrabSystem grabs() {
        return grabs;
    }

    public HolsterManager holsters() {
        return holsters;
    }

    public Backpack backpack() {
        return backpack;
    }

    public RadialMenu radial() {
        return radial;
    }

    public int offSource() {
        return offSource;
    }

    public String lastAction() {
        return lastAction;
    }

    private void action(String what) {
        lastAction = what;
        HandsOnVR.LOGGER.debug("[HandsOn VR] {}", what);
    }

    // ------------------------------------------------------------------ mundo

    public void onJoin(Minecraft client) {
        holsters.onJoin(client);
        resetState();
    }

    public void onLeave() {
        holsters.onLeave();
        resetState();
    }

    private void resetState() {
        grabs.reset();
        backpack.reset();
        radial.close();
        offSource = -1;
        menuConsumed[0] = menuConsumed[1] = false;
    }

    // ------------------------------------------------------------------ consultas (desenho, mixins)

    /** O item desta mão fica escondido (ela segura a mochila). */
    public boolean isHandHidden(int hand) {
        return backpack.holder() == hand;
    }

    /** O golpe com o braço do Vivecraft fica desligado (mexendo na mochila ou no radial). */
    public boolean blocksSwing() {
        return backpack.isOpen() || radial.isOpen();
    }

    /** O espaço está "em uso" por um item pego (o lugar de onde ele saiu, ou a mão que o segura). */
    public boolean isLocked(LocalPlayer p, int handlerSlot) {
        for (int hand = 0; hand < 2; hand++) {
            GrabSystem.Grab g = grabs.get(hand);
            if (g != null && (g.source() == handlerSlot || InvOps.handSlot(p, hand) == handlerSlot)) {
                return true;
            }
        }
        return false;
    }

    /**
     * O que um espaço "tem" para o jogador ver: o lugar de onde um item foi pego aparece vazio (o item está na mão), e
     * o espaço da hotbar de onde o radial tirou o item da mão esquerda mostra esse item.
     */
    public ItemStack displayStack(LocalPlayer p, int handlerSlot) {
        for (int hand = 0; hand < 2; hand++) {
            GrabSystem.Grab g = grabs.get(hand);
            if (g != null && g.source() == handlerSlot && g.mode() != GrabSystem.Mode.NONE) {
                return ItemStack.EMPTY;
            }
        }
        if (offSource >= 0 && handlerSlot == InvOps.HOTBAR_START + offSource) {
            return realHandStack(p, 1);
        }
        GrabSystem.Grab main = grabs.get(0);
        if (main != null && main.mode() == GrabSystem.Mode.SWAPPED && handlerSlot == InvOps.handSlot(p, 0)) {
            return InvOps.stack(p, main.source()); // o que a mão tinha antes (guardado no lugar do item pego)
        }
        return InvOps.stack(p, handlerSlot);
    }

    /** O item "de verdade" da mão (sem contar o pego com o grip, que é emprestado). */
    private ItemStack realHandStack(LocalPlayer p, int hand) {
        GrabSystem.Grab g = grabs.get(hand);
        if (g != null && g.mode() == GrabSystem.Mode.SWAPPED) {
            return InvOps.stack(p, g.source());
        }
        return InvOps.stack(p, InvOps.handSlot(p, hand));
    }

    /** A opção "mão vazia" do radial funciona para esta mão agora? */
    public boolean canEmpty(LocalPlayer p, int hand) {
        if (hand == 0) {
            return InvOps.stack(p, InvOps.handSlot(p, 0)).isEmpty() || emptyHotbarFor(p) >= 0;
        }
        if (p.getOffhandItem().isEmpty()) {
            return true;
        }
        if (offSource >= 0 && InvOps.stack(p, InvOps.HOTBAR_START + offSource).isEmpty()) {
            return true;
        }
        return emptySlotForOffhand(p) >= 0;
    }

    // ------------------------------------------------------------------ grip

    /** Grip apertado. Retorna true se foi nosso (o Vivecraft não troca a hotbar). */
    public boolean onGripPressed(Minecraft client, int hand) {
        LocalPlayer p = client.player;
        HandsOnConfig config = HandsOnConfig.get();
        if (p == null || client.gui.screen() != null || p.isSpectator() || !VrBridge.isVRActive()) {
            return false;
        }
        if (grabs.isGrabbing(hand) || backpack.holder() == hand) {
            return true;
        }
        Vec3 tip = VrBridge.pointer(hand);
        // 1) Menu radial aberto: pegar um item da hotbar.
        if (radial.isOpen()) {
            int target = radial.targetAt(tip);
            if (target != RadialMenu.NONE) {
                radialGrip(client, hand, target);
                return true;
            }
        }
        // 2) Mochila aberta (na outra mão): o alvo sob a mão.
        if (backpack.isOpen()) {
            Backpack.Element e = backpack.elementAt(tip);
            if (e != null) {
                backpackGrip(client, hand, e);
                return true;
            }
            if (backpack.onPanel(tip)) {
                return true;
            }
        }
        // 3) Zonas do corpo: mochila (ombro esquerdo) ou coldres.
        Holster zone = holsters.refreshZone(hand);
        if (zone != null && zone.isBackpack(config) && !backpack.isOpen() && InvOps.ready(client)) {
            backpack.open(hand);
            action("mochila aberta na mão " + hand);
            return true;
        }
        if (holsters.press(client, hand)) {
            action("coldre " + zone + " (mão " + hand + ")");
            return true;
        }
        return !config.gripScrollsHotbar;
    }

    /** Grip solto. Retorna true se foi nosso. */
    public boolean onGripReleased(Minecraft client, int hand) {
        if (backpack.holder() == hand) {
            backpack.close(client);
            action("mochila fechada");
            return true;
        }
        if (grabs.isGrabbing(hand)) {
            releaseGrab(client, hand);
            return true;
        }
        return false;
    }

    private void radialGrip(Minecraft client, int hand, int target) {
        LocalPlayer p = client.player;
        if (target < 0) {
            return;
        }
        int slot = InvOps.HOTBAR_START + target;
        if (target == offSource || isLocked(p, slot)) {
            refuse(hand);
            return;
        }
        if (grabs.begin(client, hand, slot, GrabSystem.Origin.RADIAL, null)) {
            action("pegou do radial o espaço " + target);
        }
    }

    private void backpackGrip(Minecraft client, int hand, Backpack.Element e) {
        LocalPlayer p = client.player;
        if (!InvOps.ready(client)) {
            return;
        }
        switch (e.kind()) {
            case SLOT -> {
                if (isLocked(p, e.handler())) {
                    refuse(hand);
                } else if (grabs.begin(client, hand, e.handler(), GrabSystem.Origin.BACKPACK, null)) {
                    action("pegou da mochila o espaço " + e.handler());
                }
            }
            case CRAFT_IN, ARMOR -> {
                if (!InvOps.stack(p, e.handler()).isEmpty()) {
                    InvOps.quickMove(client, e.handler());
                    VrBridge.pulse(hand, 0.02f);
                    action("devolveu ao inventário o espaço " + e.handler());
                }
            }
            case CRAFT_OUT -> {
                if (InvOps.craftOne(client)) {
                    VrBridge.pulse(hand, 0.03f);
                    action("fabricou um");
                }
            }
            case PREV -> flip(hand, -1);
            case NEXT -> flip(hand, 1);
            default -> { }
        }
    }

    private void flip(int hand, int direction) {
        backpack.flip(direction);
        VrBridge.pulse(hand, 0.015f);
        action("página " + (backpack.page() + 1));
    }

    /**
     * O grip de uma mão que pegou um item foi solto: coloca no espaço do radial/mochila sob a mão, descarta na zona de
     * descarte, guarda no coldre em que a mão está ou, em qualquer outro lugar, devolve ao lugar de onde veio.
     */
    private void releaseGrab(Minecraft client, int hand) {
        LocalPlayer p = client.player;
        GrabSystem.Grab g = grabs.get(hand);
        if (g == null || p == null) {
            return;
        }
        if (!InvOps.ready(client)) {
            return; // uma tela (baú...) está aberta: devolve quando fechar
        }
        Vec3 tip = VrBridge.pointer(hand);
        HandsOnConfig config = HandsOnConfig.get();
        if (radial.isOpen()) {
            int target = radial.targetAt(tip);
            if (target >= 0) {
                grabs.end(client, hand);
                int slot = InvOps.HOTBAR_START + target;
                if (target == offSource || isLocked(p, slot)) {
                    refuse(hand);
                } else {
                    InvOps.moveStack(client, g.source(), slot);
                    radial.markAssigned();
                    action("pôs na hotbar, espaço " + target);
                }
                return;
            }
        }
        if (backpack.isOpen() && backpack.holder() != hand) {
            Backpack.Element e = backpack.elementAt(tip);
            if (e != null && e.kind() == Backpack.Kind.DROP) {
                InvOps.throwFrom(client, InvOps.handSlot(p, hand), true);
                grabs.end(client, hand);
                action("descartou a pilha");
                return;
            }
            if (e != null && e.isSlotLike()) {
                grabs.end(client, hand);
                if (isLocked(p, e.handler())) {
                    refuse(hand);
                } else {
                    InvOps.moveStack(client, g.source(), e.handler());
                    action("colocou no espaço " + e.handler());
                }
                return;
            }
        }
        Holster zone = holsters.refreshZone(hand);
        grabs.end(client, hand);
        if (zone != null && zone.enabled(config) && zone != g.holster()) {
            if (holsters.assign(p, zone, g.source())) {
                if (g.origin() == GrabSystem.Origin.HOLSTER && g.holster() != null) {
                    holsters.clear(g.holster());
                }
                VrBridge.pulse(hand, 0.05f);
                action("guardou no coldre " + zone);
                return;
            }
        }
        action("devolveu ao lugar");
    }

    // ------------------------------------------------------------------ gatilho

    /**
     * Gatilho (direito = atacar, esquerdo = teleporte no Vivecraft). Retorna true se foi nosso: colocar/descartar um
     * item, virar a página, fabricar tudo, ou a mão segura a mochila (não ataca com o item escondido).
     */
    public boolean onTrigger(Minecraft client, int hand) {
        LocalPlayer p = client.player;
        if (p == null || client.gui.screen() != null || !VrBridge.isVRActive()) {
            return false;
        }
        if (backpack.holder() == hand) {
            return true;
        }
        Vec3 tip = VrBridge.pointer(hand);
        int handSlot = InvOps.handSlot(p, hand);
        if (grabs.isGrabbing(hand)) {
            if (radial.isOpen()) {
                int target = radial.targetAt(tip);
                if (target >= 0) {
                    int slot = InvOps.HOTBAR_START + target;
                    if (target != offSource && !isLocked(p, slot) && InvOps.placeOne(client, handSlot, slot)) {
                        radial.markAssigned();
                        VrBridge.pulse(hand, 0.01f);
                        action("pôs um na hotbar, espaço " + target);
                    } else {
                        refuse(hand);
                    }
                    return true;
                }
                if (target == RadialMenu.CENTER) {
                    return true;
                }
            }
            if (backpack.isOpen()) {
                Backpack.Element e = backpack.elementAt(tip);
                if (e != null) {
                    switch (e.kind()) {
                        case DROP -> {
                            InvOps.throwFrom(client, handSlot, false);
                            VrBridge.pulse(hand, 0.01f);
                            action("descartou um");
                        }
                        case SLOT, CRAFT_IN, ARMOR -> {
                            if (!isLocked(p, e.handler()) && InvOps.placeOne(client, handSlot, e.handler())) {
                                VrBridge.pulse(hand, 0.01f);
                                action("colocou um no espaço " + e.handler());
                            } else {
                                refuse(hand);
                            }
                        }
                        case PREV -> flip(hand, -1);
                        case NEXT -> flip(hand, 1);
                        default -> { }
                    }
                    return true;
                }
                return backpack.onPanel(tip);
            }
            return false; // pegou um item e está longe dos menus: o gatilho usa o item normalmente
        }
        if (backpack.isOpen()) {
            Backpack.Element e = backpack.elementAt(tip);
            if (e != null) {
                switch (e.kind()) {
                    case PREV -> flip(hand, -1);
                    case NEXT -> flip(hand, 1);
                    case CRAFT_OUT -> {
                        if (!InvOps.stack(p, InvOps.RESULT).isEmpty() && InvOps.ready(client)) {
                            InvOps.quickMove(client, InvOps.RESULT);
                            VrBridge.pulse(hand, 0.04f);
                            action("fabricou o máximo");
                        }
                    }
                    default -> { }
                }
                return true;
            }
            if (backpack.onPanel(tip)) {
                return true;
            }
        }
        return radial.isOpen() && radial.targetAt(tip) != RadialMenu.NONE;
    }

    /** "Usar" (A): não usa o item escondido da mão que segura a mochila, nem aponta para o painel. */
    public boolean onUse(Minecraft client) {
        if (client.player == null || client.gui.screen() != null) {
            return false;
        }
        return backpack.holder() == 0 || (backpack.isOpen() && backpack.onPanel(VrBridge.pointer(0)));
    }

    // ------------------------------------------------------------------ menu radial (B / X)

    /** Botão do radial da mão (B principal, X secundária). Retorna true se foi nosso. */
    public boolean onMenuButton(Minecraft client, int hand, boolean pressed) {
        if (!pressed) {
            boolean ours = menuConsumed[hand];
            menuConsumed[hand] = false;
            if (radial.isOpen() && radial.opener() == hand) {
                commitRadial(client);
            }
            return ours;
        }
        if (!HandsOnConfig.get().radialMenu || client.player == null || client.gui.screen() != null
                || !VrBridge.isVRActive()) {
            return false;
        }
        if (!radial.isOpen() && !radial.open(hand)) {
            return false;
        }
        menuConsumed[hand] = true;
        return true;
    }

    /** O botão do radial foi solto: equipa o que está sob a mão que abriu. */
    private void commitRadial(Minecraft client) {
        int hand = radial.opener();
        int target = hand < 0 ? RadialMenu.NONE : radial.targetAt(VrBridge.pointer(hand));
        boolean assigned = radial.wasAssigned();
        radial.close();
        if (assigned || target == RadialMenu.NONE || hand < 0 || grabs.isGrabbing(hand) || backpack.holder() == hand
                || !InvOps.ready(client)) {
            return;
        }
        boolean ok = hand == 0 ? equipMain(client, target) : equipOff(client, target);
        if (ok) {
            VrBridge.pulse(hand, 0.03f);
            action("radial: mão " + hand + " -> " + (target == RadialMenu.CENTER ? "vazia" : "espaço " + target));
        } else {
            refuse(hand);
            action("radial: mão " + hand + " recusou " + target);
        }
    }

    /** Mão principal: seleciona o espaço da hotbar ({@link RadialMenu#CENTER} = mão vazia). */
    public boolean equipMain(Minecraft client, int target) {
        LocalPlayer p = client.player;
        if (grabs.isGrabbing(0)) {
            return false;
        }
        if (target == RadialMenu.CENTER) {
            if (InvOps.stack(p, InvOps.handSlot(p, 0)).isEmpty()) {
                return true;
            }
            int empty = emptyHotbarFor(p);
            if (empty < 0) {
                return false;
            }
            p.getInventory().setSelectedSlot(empty);
            return true;
        }
        if (target == offSource) {
            if (grabs.isGrabbing(1)) {
                return false;
            }
            unequipOff(client); // o item volta da mão esquerda para o espaço dele, e a direita o pega
        }
        if (isLocked(p, InvOps.HOTBAR_START + target)) {
            return false;
        }
        p.getInventory().setSelectedSlot(target);
        return true;
    }

    /** Mão secundária: traz o item do espaço da hotbar ({@link RadialMenu#CENTER} = mão vazia). */
    public boolean equipOff(Minecraft client, int target) {
        LocalPlayer p = client.player;
        if (grabs.isGrabbing(1)) {
            return false;
        }
        if (target == RadialMenu.CENTER) {
            return emptyOff(client);
        }
        if (target == offSource) {
            return true;
        }
        int slot = InvOps.HOTBAR_START + target;
        if (isLocked(p, slot)) {
            return false;
        }
        unequipOff(client);
        if (InvOps.stack(p, slot).isEmpty()) {
            return emptyOff(client); // espaço vazio = mão vazia
        }
        if (slot == InvOps.handSlot(p, 0) && grabs.isGrabbing(0)) {
            return false;
        }
        InvOps.swap(client, slot, InvOps.OFFHAND_BUTTON);
        offSource = target;
        return true;
    }

    /** Devolve o item que o radial pôs na mão esquerda ao espaço da hotbar de onde veio. */
    private void unequipOff(Minecraft client) {
        if (offSource >= 0) {
            InvOps.swap(client, InvOps.HOTBAR_START + offSource, InvOps.OFFHAND_BUTTON);
            offSource = -1;
        }
    }

    private boolean emptyOff(Minecraft client) {
        LocalPlayer p = client.player;
        unequipOff(client);
        if (p.getOffhandItem().isEmpty()) {
            return true;
        }
        int slot = emptySlotForOffhand(p);
        if (slot < 0) {
            return false;
        }
        InvOps.swap(client, slot, InvOps.OFFHAND_BUTTON);
        return true;
    }

    /** Espaço vazio da hotbar para a mão principal ficar vazia (o mais perto do atual), ou -1. */
    private int emptyHotbarFor(LocalPlayer p) {
        int selected = p.getInventory().getSelectedSlot();
        for (int d = 1; d < 9; d++) {
            for (int sign : new int[] {1, -1}) {
                int i = Math.floorMod(selected + sign * d, 9);
                int slot = InvOps.HOTBAR_START + i;
                if (i != offSource && !isLocked(p, slot) && InvOps.stack(p, slot).isEmpty()) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Espaço vazio para guardar o item da mão esquerda (inventário principal, depois hotbar), ou -1. */
    private int emptySlotForOffhand(LocalPlayer p) {
        for (int i = InvOps.MAIN_START; i < InvOps.OFFHAND; i++) {
            if (i == InvOps.handSlot(p, 0) || (offSource >= 0 && i == InvOps.HOTBAR_START + offSource) || isLocked(p, i)) {
                continue;
            }
            if (InvOps.stack(p, i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private void refuse(int hand) {
        VrBridge.pulse(hand, 0.08f);
    }

    // ------------------------------------------------------------------ tick

    public void tick(Minecraft client) {
        LocalPlayer p = client.player;
        if (p == null) {
            return;
        }
        boolean vr = VrBridge.isVRActive();
        boolean ready = InvOps.ready(client) && client.gui.screen() == null;
        if (!vr || client.gui.screen() != null) {
            radial.close();
            if (backpack.isOpen()) {
                backpack.close(client);
            }
        }
        // Itens pegos: soltar o grip (ou sair do VR) devolve/coloca. Com uma tela aberta, espera ela fechar.
        for (int hand = 0; hand < 2; hand++) {
            if (grabs.isGrabbing(hand) && ready && (!vr || !GripState.isHeld(hand))) {
                releaseGrab(client, hand);
            }
        }
        if (vr && backpack.isOpen()) {
            if (!GripState.isHeld(backpack.holder())) {
                backpack.close(client);
                action("mochila fechada");
            } else {
                backpack.computePanel();
                int free = 1 - backpack.holder();
                Backpack.Element e = backpack.elementAt(VrBridge.pointer(free));
                if (backpack.updateHover(free, e)) {
                    VrBridge.pulse(free, 0.005f);
                }
                if (grabs.isGrabbing(free) && backpack.dwellTick(free)) {
                    flip(free, e.kind() == Backpack.Kind.PREV ? -1 : 1);
                }
            }
        }
        if (vr && radial.isOpen()) {
            if (!GripState.isMenuButtonHeld(radial.opener())) {
                menuConsumed[radial.opener()] = false;
                commitRadial(client);
            } else {
                for (int hand = 0; hand < 2; hand++) {
                    if (backpack.holder() == hand) {
                        continue;
                    }
                    if (radial.updateHover(hand, radial.targetAt(VrBridge.pointer(hand)))) {
                        VrBridge.pulse(hand, 0.006f);
                    }
                }
            }
        }
        if (offSource >= 0 && !grabs.isGrabbing(1) && p.getOffhandItem().isEmpty()) {
            offSource = -1; // o item acabou (tochas usadas): nada a devolver
        }
        holsters.tick(client);
    }
}
