package com.handsonvr.hands;

import com.handsonvr.HandsOnConfig;
import com.handsonvr.HandsOnVR;
import com.handsonvr.backpack.Backpack;
import com.handsonvr.furnace.FurnaceStation;
import com.handsonvr.holster.Holster;
import com.handsonvr.holster.HolsterManager;
import com.handsonvr.input.GripState;
import com.handsonvr.inv.InvOps;
import com.handsonvr.radial.RadialMenu;
import com.handsonvr.table.CraftingTable;
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
 *   <li>o item da própria mão também pode ser seguro com o grip: soltar num coldre, no ombro da mochila ou num
 *       espaço o guarda ali; soltar em outro lugar não muda nada;</li>
 *   <li>gatilho com o grip apertado sobre um espaço/descarte = UM item.</li>
 * </ul>
 * Mão: 0 = principal (B, gatilho direito), 1 = secundária (X, gatilho esquerdo).
 */
public final class HandsController {
    private final GrabSystem grabs = new GrabSystem();
    private final HolsterManager holsters = new HolsterManager(grabs);
    private final Backpack backpack = new Backpack();
    private final RadialMenu radial = new RadialMenu();
    private final CraftingTable table = new CraftingTable();
    private final FurnaceStation furnace = new FurnaceStation();
    private final com.handsonvr.furnace.FurnaceWatcher furnaceWatcher = new com.handsonvr.furnace.FurnaceWatcher();
    /** Espaço da hotbar (0-8) de onde veio o item que o radial pôs na mão esquerda; -1 = nenhum. */
    private int offSource = -1;
    /** O aperto do botão do radial foi nosso (o soltar também é). */
    private final boolean[] menuConsumed = new boolean[2];

    /** De onde vem o resultado que a mão pegou com o grip. */
    public enum ResultSource { NONE, BACKPACK, TABLE, FURNACE }

    /**
     * O resultado (do crafting 2x2, da mesa ou da fornalha) que cada mão pegou com o grip, enquanto o grip continua
     * apertado: o gatilho com a mão no resultado fabrica (ou pega) o resto para ela.
     */
    private final ResultSource[] resultHold = {ResultSource.NONE, ResultSource.NONE};

    /** "Fabricar tudo" (grip + gatilho): até acabar a grade (o máximo de uma pilha, como o Shift+clique). */
    private static final int ALL = 64;

    /**
     * Fabricando para uma mão: uma vez (grip no resultado) ou tudo (grip + gatilho, como o Shift+clique da tela do
     * jogo), um por tick (o servidor manda o próximo resultado no tick seguinte). {@code target}: o espaço em que o
     * resultado se junta (o da mão, ou um vazio que vai para a mão no fim, {@code equipAtEnd}).
     */
    private static final class CraftJob {
        final int hand;
        final ResultSource source;
        int target = -1, crafts = 0, wait = 0, limit = 1;
        boolean equipAtEnd = false;
        ItemStack item = ItemStack.EMPTY;

        CraftJob(int hand, ResultSource source) {
            this.hand = hand;
            this.source = source;
        }
    }

    private CraftJob craftJob;
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

    public CraftingTable table() {
        return table;
    }

    public FurnaceStation furnace() {
        return furnace;
    }

    /** As fornalhas por perto (até 10 blocos), para mostrar o que tem em cada uma. */
    public com.handsonvr.furnace.FurnaceWatcher furnaceWatcher() {
        return furnaceWatcher;
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
        BowHand.restore();
    }

    private void resetState() {
        grabs.reset();
        backpack.reset();
        radial.close();
        table.reset();
        furnace.reset();
        furnaceWatcher.reset();
        offSource = -1;
        menuConsumed[0] = menuConsumed[1] = false;
        resultHold[0] = resultHold[1] = ResultSource.NONE;
        craftJob = null;
    }

    // ------------------------------------------------------------------ consultas (desenho, mixins)

    /** O item desta mão fica escondido (ela segura a mochila). */
    public boolean isHandHidden(int hand) {
        return backpack.holder() == hand;
    }

    /** O golpe com o braço do Vivecraft fica desligado (mexendo na mochila, no radial, na mesa ou numa fornalha). */
    public boolean blocksSwing() {
        return backpack.isOpen() || radial.isOpen() || table.blocksSwing() || furnace.blocksSwing();
    }

    /** Alguma mão segura um item pego da armadura ou do crafting 2x2 (espaços que não existem no menu de um bloco)? */
    private boolean grabbingOutsideMenus() {
        for (int hand = 0; hand < 2; hand++) {
            GrabSystem.Grab g = grabs.get(hand);
            if (g != null && g.source() < InvOps.MAIN_START) {
                return true;
            }
        }
        return false;
    }

    /**
     * A mesa de trabalho pode abrir o menu dela agora? Não com um item pego da armadura ou do crafting 2x2, nem com o
     * crafting 2x2 ocupado: esses espaços não existem no menu da mesa (ficariam sem como voltar).
     */
    private boolean tableCanOpen(LocalPlayer p) {
        for (int hand = 0; hand < 2; hand++) {
            GrabSystem.Grab g = grabs.get(hand);
            if (g != null && g.source() < InvOps.MAIN_START) {
                return false;
            }
        }
        for (int i = InvOps.CRAFT_START; i < InvOps.ARMOR_START; i++) {
            if (!InvOps.stack(p, i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** O espaço está "em uso" por um item pego (o lugar de onde ele saiu, ou a mão que o segura). */
    public boolean isLocked(LocalPlayer p, int handlerSlot) {
        for (int hand = 0; hand < 2; hand++) {
            GrabSystem.Grab g = grabs.get(hand);
            if (g != null && (g.source() == handlerSlot || GrabSystem.itemSlot(p, g) == handlerSlot)) {
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
        GrabSystem.Grab main = grabs.inHandSlot(0);
        if (main != null && main.mode() == GrabSystem.Mode.SWAPPED && handlerSlot == InvOps.handSlot(p, 0)) {
            return InvOps.stack(p, main.source()); // o que a mão tinha antes (guardado no lugar do item pego)
        }
        return InvOps.stack(p, handlerSlot);
    }

    /** O item "de verdade" do espaço da mão (sem contar o pego com o grip, que é emprestado). */
    private ItemStack realHandStack(LocalPlayer p, int hand) {
        GrabSystem.Grab g = grabs.inHandSlot(hand);
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
        if (grabs.isGrabbing(hand) || backpack.holder() == hand || resultHold[hand] != ResultSource.NONE) {
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
        // 2) Mochila aberta (na outra mão): o alvo sob a mão. Num espaço vazio, no descarte ou no fundo do painel, o
        //    grip segura o item da própria mão (soltar num espaço o guarda ali).
        if (backpack.isOpen()) {
            Backpack.Element e = backpack.elementAt(tip);
            if (e != null && !takesOwnItem(p, hand, e)) {
                backpackGrip(client, hand, e);
                return true;
            }
            if (e != null || backpack.onPanel(tip)) {
                holdOwnItem(client, hand);
                return true;
            }
        }
        // 3) Mesa de trabalho: o resultado flutuando (o grip fabrica uma vez para a mão; com o gatilho, tudo) ou uma casa
        //    com item (o item volta para a mão).
        int onTable = table.targetAt(tip);
        if (onTable == CraftingTable.RESULT) {
            gripResult(client, hand, ResultSource.TABLE);
            return true;
        }
        if (onTable >= 0 && !table.cellStack(onTable).isEmpty()) {
            blockToHand(client, hand, table.take(client, onTable), "tirou da mesa a casa " + onTable);
            return true;
        }
        // 3b) Fornalha: o resultado (vem para a mão, com a experiência do jogo), ou a entrada/o combustível com item
        //     (o item volta para a mão).
        int onFurnace = furnace.targetAt(tip);
        if (onFurnace == FurnaceStation.RESULT) {
            gripResult(client, hand, ResultSource.FURNACE);
            return true;
        }
        if (onFurnace >= 0 && !furnace.stack(onFurnace).isEmpty()) {
            blockToHand(client, hand, furnace.take(client, onFurnace), "tirou da fornalha o lugar " + onFurnace);
            return true;
        }
        // 4) Zonas do corpo: mochila (ombro esquerdo) ou um coldre com item (sacar).
        Holster zone = holsters.refreshZone(hand);
        if (zone != null && zone.isBackpack(config) && !backpack.isOpen() && InvOps.ready(client)) {
            backpack.open(hand);
            action("mochila aberta na mão " + hand);
            return true;
        }
        if (holsters.press(client, hand)) {
            action("sacou do coldre " + zone + " (mão " + hand + ")");
            return true;
        }
        // 4) Em qualquer outro lugar (ou num coldre vazio): segura o item da mão. Soltar num coldre, no ombro da
        //    mochila ou num espaço o guarda; soltar em outro lugar não muda nada.
        if (zone == null && config.gripScrollsHotbar) {
            return false;
        }
        holdOwnItem(client, hand);
        return true;
    }

    // ------------------------------------------------------------------ resultado do crafting: grip (uma vez) e gatilho (tudo)

    /** O resultado que esta mão pegou com o grip (ainda apertado), ou {@link ResultSource#NONE}. */
    public ResultSource resultHeld(int hand) {
        return resultHold[hand];
    }

    /** Fabricando agora (uma vez ou tudo)? */
    public boolean isCrafting() {
        return craftJob != null;
    }

    /** A mão está no lugar do resultado (o da mesa flutuando, ou o do crafting 2x2 da mochila aberta na outra mão)? */
    private ResultSource resultUnder(int hand, Vec3 tip) {
        if (table.nearResult(tip)) {
            return ResultSource.TABLE;
        }
        if (furnace.nearResult(tip)) {
            return ResultSource.FURNACE;
        }
        if (backpack.isOpen() && backpack.holder() != hand) {
            Backpack.Element e = backpack.elementAt(tip);
            if (e != null && e.kind() == Backpack.Kind.CRAFT_OUT) {
                return ResultSource.BACKPACK;
            }
        }
        return ResultSource.NONE;
    }

    private ItemStack resultStack(LocalPlayer p, ResultSource source) {
        return switch (source) {
            case TABLE -> table.resultStack();
            case FURNACE -> furnace.resultStack();
            case BACKPACK -> InvOps.reachable(p, InvOps.RESULT) ? InvOps.stack(p, InvOps.RESULT) : ItemStack.EMPTY;
            default -> ItemStack.EMPTY;
        };
    }

    /**
     * Grip no resultado: fabrica UMA vez para a mão (um pack de toras na mesa: 4 tábuas na mão e sobram 63 toras). Com
     * o gatilho já apertado, fabrica tudo. Enquanto o grip continua apertado, o gatilho com a mão no resultado fabrica o
     * resto, juntando na mesma mão ({@link #onTrigger}).
     */
    private void gripResult(Minecraft client, int hand, ResultSource source) {
        LocalPlayer p = client.player;
        if (!InvOps.ready(client) || resultStack(p, source).isEmpty()) {
            refuse(hand); // nada para fabricar (ou o crafting 2x2 espera a mesa de trabalho fechar)
            return;
        }
        resultHold[hand] = source;
        startCraft(client, hand, source, GripState.isTriggerHeld(hand) ? ALL : 1);
    }

    /** {@code a} recebe mais de {@code b} (mesmo item e espaço na pilha)? */
    private static boolean joins(ItemStack a, ItemStack b) {
        return !a.isEmpty() && ItemStack.isSameItemSameComponents(a, b) && a.getCount() < a.getMaxStackSize();
    }

    /**
     * Fabrica {@code limit} vezes (1 = o grip; {@link #ALL} = grip + gatilho) para a mão. A mão direita usa o espaço dela
     * se estiver vazio ou já tiver o mesmo item (junta), senão um espaço vazio da hotbar (o item que ela tinha fica onde
     * está); a esquerda recebe o resultado no fim (o que ela já tinha do mesmo item junta com ele).
     */
    private void startCraft(Minecraft client, int hand, ResultSource source, int limit) {
        LocalPlayer p = client.player;
        if (craftJob != null) {
            if (craftJob.hand == hand && craftJob.source == source) {
                craftJob.limit = Math.max(craftJob.limit, limit); // o gatilho chegou com a primeira ainda a caminho
            }
            return;
        }
        ItemStack result = resultStack(p, source);
        if (result.isEmpty() || !InvOps.ready(client)) {
            refuse(hand);
            return;
        }
        CraftJob job = new CraftJob(hand, source);
        job.limit = limit;
        if (hand == 0 && grabs.inHandSlot(0) == null) {
            int selected = InvOps.handSlot(p, 0);
            ItemStack inHand = InvOps.stack(p, selected);
            if (!isLocked(p, selected) && (inHand.isEmpty() || joins(inHand, result))) {
                job.target = selected;
            } else {
                int empty = emptyHotbarFor(p);
                if (empty >= 0) {
                    p.getInventory().setSelectedSlot(empty); // a mão fica vazia (o item dela continua na hotbar)
                    job.target = InvOps.HOTBAR_START + empty;
                }
            }
        }
        if (job.target < 0) {
            job.target = freeSlotForResult(p);
            job.equipAtEnd = true;
            if (hand == 1 && job.target >= 0 && offSource < 0 && grabs.inHandSlot(1) == null && !grabs.isGrabbing(1)
                    && joins(p.getOffhandItem(), result)) {
                // A mão esquerda já tem este item (a vez anterior): ele vai para o espaço livre, junta com o novo e volta.
                InvOps.swap(client, job.target, InvOps.OFFHAND_BUTTON);
            }
        }
        if (job.target < 0) {
            refuse(hand); // inventário cheio
            action("sem espaço para o resultado");
            return;
        }
        craftJob = job;
        action((limit == 1 ? "fabricando uma vez" : "fabricando tudo") + " para a mão " + hand);
    }

    /** Espaço vazio para o resultado: o inventário principal antes da hotbar, sem os espaços em uso. */
    private int freeSlotForResult(LocalPlayer p) {
        for (int i = InvOps.MAIN_START; i < InvOps.OFFHAND; i++) {
            if (InvOps.stack(p, i).isEmpty() && !isLocked(p, i) && !(offSource >= 0 && i == InvOps.HOTBAR_START + offSource)) {
                return i;
            }
        }
        return -1;
    }

    /** O espaço da mão está cheio: o resto vai para uma pilha igual com espaço, ou um espaço vazio (principal antes). */
    private int overflowSlot(LocalPlayer p, ItemStack result) {
        for (int i = InvOps.MAIN_START; i < InvOps.OFFHAND; i++) {
            ItemStack s = InvOps.stack(p, i);
            if (!isLocked(p, i) && !s.isEmpty() && ItemStack.isSameItemSameComponents(s, result)
                    && s.getCount() + result.getCount() <= s.getMaxStackSize()) {
                return i;
            }
        }
        return freeSlotForResult(p);
    }

    /** Um tick do "fabricar tudo": um resultado por tick, até a grade acabar (ou faltar espaço). */
    private void craftTick(Minecraft client) {
        CraftJob job = craftJob;
        LocalPlayer p = client.player;
        if (job == null) {
            return;
        }
        boolean open = switch (job.source) {
            case TABLE -> table.isOpen();
            case FURNACE -> furnace.isOpen();
            default -> backpack.isOpen();
        };
        if (p == null || !InvOps.ready(client) || client.gui.screen() != null || !open) {
            finishCraft(client);
            return;
        }
        ItemStack result = resultStack(p, job.source);
        if (result.isEmpty()) {
            if (++job.wait > 6) {
                finishCraft(client); // a grade acabou (o servidor não mandou outro resultado)
            }
            return;
        }
        if (!job.item.isEmpty() && !ItemStack.isSameItemSameComponents(result, job.item)) {
            finishCraft(client); // a receita mudou
            return;
        }
        int target = job.target;
        ItemStack t = InvOps.stack(p, target);
        if (!t.isEmpty() && (!ItemStack.isSameItemSameComponents(t, result) || t.getCount() + result.getCount() > t.getMaxStackSize())) {
            target = overflowSlot(p, result);
        }
        boolean crafted = target >= 0 && switch (job.source) {
            case TABLE -> table.craftInto(client, target);
            case FURNACE -> furnace.takeResultInto(client, target); // o que está pronto (não fabrica nada)
            default -> InvOps.craftInto(client, target);
        };
        if (!crafted) {
            finishCraft(client);
            return;
        }
        job.item = result.copy();
        job.crafts++;
        job.wait = 0;
        VrBridge.pulse(job.hand, 0.012f);
        if (job.crafts >= job.limit) {
            finishCraft(client);
        }
    }

    /** Fim da fabricação: o resultado na mão; com o grip ainda apertado, ela o segura (soltar decide). */
    private void finishCraft(Minecraft client) {
        CraftJob job = craftJob;
        craftJob = null;
        LocalPlayer p = client.player;
        if (job == null || p == null) {
            return;
        }
        if (!GripState.isHeld(job.hand)) {
            resultHold[job.hand] = ResultSource.NONE;
        }
        if (job.crafts == 0) {
            refuse(job.hand);
            return;
        }
        if (job.equipAtEnd && InvOps.ready(client)) {
            equipInHand(client, job.hand, job.target);
        }
        if (GripState.isHeld(job.hand) && !grabs.isGrabbing(job.hand) && InvOps.ready(client)) {
            holdOwnItem(client, job.hand);
        }
        VrBridge.pulse(job.hand, 0.05f);
        action("fabricou " + job.crafts + "x: o resultado está na mão " + job.hand);
    }

    /** Põe na mão a pilha do espaço {@code slot} (o resultado fabricado). O que a mão tinha vai para onde ela estava. */
    private void equipInHand(Minecraft client, int hand, int slot) {
        LocalPlayer p = client.player;
        if (InvOps.stack(p, slot).isEmpty() || grabs.inHandSlot(hand) != null) {
            return;
        }
        if (hand == 0) {
            if (slot >= InvOps.HOTBAR_START && slot < InvOps.HOTBAR_START + 9) {
                p.getInventory().setSelectedSlot(slot - InvOps.HOTBAR_START);
                return;
            }
            InvOps.swapWithHand(client, slot, 0); // sem espaço vazio na hotbar: o item da mão vai para a mochila
            return;
        }
        unequipOff(client); // o item que o radial pôs na mão esquerda volta para a hotbar
        InvOps.swap(client, slot, InvOps.OFFHAND_BUTTON);
    }

    /**
     * O item que saiu da mesa (tirado de uma casa) foi para o espaço {@code slot} do inventário: a mão o pega dali,
     * como em todo o mod (soltar decide para onde ele vai; longe de tudo, fica no inventário).
     */
    private void blockToHand(Minecraft client, int hand, int slot, String what) {
        LocalPlayer p = client.player;
        if (slot < 0) {
            refuse(hand); // nada para fabricar, ou sem espaço no inventário
            return;
        }
        VrBridge.pulse(hand, 0.04f);
        if (grabs.begin(client, hand, slot, GrabSystem.Origin.TABLE, null, hand == 0 ? emptyHotbarFor(p) : -1)) {
            action(what + " (na mão)");
        } else {
            action(what + " (ficou no inventário, espaço " + slot + ")");
        }
    }

    /** Com a mochila aberta, o grip sobre este alvo segura o item da mão (em vez de pegar o que está no alvo)? */
    private boolean takesOwnItem(LocalPlayer p, int hand, Backpack.Element e) {
        if (InvOps.stack(p, InvOps.handSlot(p, hand)).isEmpty()) {
            return false;
        }
        return switch (e.kind()) {
            case SLOT, CRAFT_IN, ARMOR -> displayStack(p, e.handler()).isEmpty() && !isLocked(p, e.handler());
            case DROP -> true;
            default -> false;
        };
    }

    /**
     * Segura com o grip o item que a mão já tem: nada muda na hora (ele continua na mão). Ao soltar, o lugar sob a mão
     * decide: um coldre, o ombro da mochila ou um espaço guardam o item; qualquer outro lugar o deixa na mão.
     */
    private void holdOwnItem(Minecraft client, int hand) {
        LocalPlayer p = client.player;
        int slot = InvOps.handSlot(p, hand);
        if (!InvOps.stack(p, slot).isEmpty() && grabs.begin(client, hand, slot, GrabSystem.Origin.HAND, null)) {
            action("segurou o item da mão " + hand);
        }
    }

    /** Grip solto. Retorna true se foi nosso. */
    public boolean onGripReleased(Minecraft client, int hand) {
        boolean wasResult = resultHold[hand] != ResultSource.NONE;
        resultHold[hand] = ResultSource.NONE; // (se ainda está fabricando, termina e o resultado fica na mão)
        if (backpack.holder() == hand) {
            closeBackpack(client);
            action("mochila fechada");
            return true;
        }
        if (grabs.isGrabbing(hand)) {
            releaseGrab(client, hand);
            return true;
        }
        return wasResult;
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
            // Como em todo lugar, o grip pega emprestado: o item vem para a mão (inclusive a armadura vestida e o que
            // está no crafting) e soltar decide para onde ele vai.
            case SLOT, CRAFT_IN, ARMOR -> {
                if (isLocked(p, e.handler())) {
                    refuse(hand);
                } else if (grabs.begin(client, hand, e.handler(), GrabSystem.Origin.BACKPACK, null,
                        hand == 0 ? emptyHotbarFor(p) : -1)) {
                    action("pegou da mochila o espaço " + e.handler());
                } else if (!InvOps.stack(p, e.handler()).isEmpty()) {
                    refuse(hand); // a mão esquerda ocupada (ou a hotbar cheia) não segura uma armadura
                    action("não deu para pegar o espaço " + e.handler());
                }
            }
            case CRAFT_OUT -> gripResult(client, hand, ResultSource.BACKPACK); // uma vez (com o gatilho, tudo) para a mão
            case PREV -> flip(hand, -1);
            case NEXT -> flip(hand, 1);
            default -> { }
        }
    }

    /**
     * Levar a pilha de {@code from} para {@code to} deixa tudo num lugar válido? Num espaço ocupado por outro item, ele
     * vai para {@code from}: não pode se {@code from} for de armadura e o item não for vestível (terra no pé).
     */
    private boolean exchangeFits(LocalPlayer p, int from, int to) {
        ItemStack target = InvOps.stack(p, to);
        return target.isEmpty() || ItemStack.isSameItemSameComponents(target, InvOps.stack(p, from))
                || p.inventoryMenu.getSlot(from).mayPlace(target);
    }

    /** Fecha a mochila. Um item pego do crafting volta para lá antes: o que sobra no crafting vai para o inventário. */
    private void closeBackpack(Minecraft client) {
        for (int h = 0; h < 2 && InvOps.ready(client); h++) {
            GrabSystem.Grab g = grabs.get(h);
            if (g != null && g.source() >= InvOps.CRAFT_START && g.source() < InvOps.ARMOR_START) {
                grabs.end(client, h);
            }
        }
        backpack.close(client);
    }

    private void flip(int hand, int direction) {
        backpack.flip(direction);
        VrBridge.pulse(hand, 0.015f);
        action("página " + (backpack.page() + 1));
    }

    /**
     * Soltar o grip como arremesso ({@link ThrowGesture}): o item da mão é arremessável, a mão estava rápida e não está
     * sobre um espaço do radial ou da mochila (lá, soltar guarda o item). Joga UM item; o resto segue a regra de
     * sempre (volta para o lugar de onde veio, ou fica na mão). Retorna true se arremessou.
     */
    private boolean throwRelease(Minecraft client, int hand, GrabSystem.Grab g, Vec3 tip) {
        LocalPlayer p = client.player;
        if (!HandsOnConfig.get().physicalThrow || g.itemHand() != hand
                || !ThrowGesture.isThrowable(InvOps.stack(p, GrabSystem.itemSlot(p, g)))) {
            return false;
        }
        if ((radial.isOpen() && radial.targetAt(tip) != RadialMenu.NONE)
                || (backpack.isOpen() && backpack.holder() != hand && (backpack.elementAt(tip) != null || backpack.onPanel(tip)))) {
            return false;
        }
        Vec3 velocity = ThrowGesture.roomVelocity(hand);
        if (velocity == null || velocity.length() < ThrowGesture.MIN_SPEED || !ThrowGesture.throwItem(client, hand, velocity)) {
            return false;
        }
        grabs.end(client, hand); // o resto volta para o lugar de onde veio (do coldre, da mochila) ou fica na mão
        VrBridge.pulse(hand, 0.04f);
        action(String.format(java.util.Locale.ROOT, "arremessou com a mão %d (%.1f m/s)", hand, velocity.length()));
        return true;
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
        // Arremesso: um item arremessável e a mão rápida ao soltar = joga UM na direção do movimento.
        if (throwRelease(client, hand, g, tip)) {
            return;
        }
        if (radial.isOpen()) {
            int target = radial.targetAt(tip);
            if (target >= 0) {
                grabs.end(client, hand);
                int slot = InvOps.HOTBAR_START + target;
                if (target == offSource || isLocked(p, slot) || !exchangeFits(p, g.source(), slot)) {
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
                InvOps.throwFrom(client, GrabSystem.itemSlot(p, g), true);
                grabs.end(client, hand);
                action("descartou a pilha");
                return;
            }
            if (e != null && e.isSlotLike()) {
                grabs.end(client, hand);
                if (isLocked(p, e.handler()) || !exchangeFits(p, g.source(), e.handler())
                        || !InvOps.reachable(p, e.handler())) {
                    refuse(hand); // (com a mesa de trabalho aberta, a armadura e o crafting 2x2 esperam ela fechar)
                } else {
                    InvOps.moveStack(client, g.source(), e.handler());
                    action("colocou no espaço " + e.handler());
                }
                return;
            }
        }
        // Mesa de trabalho: soltar sobre uma casa põe a pilha inteira ali (como no crafting 2x2 da mochila); uma casa
        // com outro item troca de lugar com ele.
        int cell = table.targetAt(tip);
        if (cell >= 0 && cell < 9) {
            grabs.end(client, hand);
            if (table.place(client, hand, g.source(), cell, tableCanOpen(p), true)) {
                VrBridge.pulse(hand, 0.02f);
                action("pôs a pilha na mesa, casa " + cell);
            } else {
                refuse(hand);
                action("a casa " + cell + " da mesa recusou");
            }
            return;
        }
        // Fornalha: soltar na entrada ou no combustível põe a pilha inteira (um item diferente troca de lugar).
        int spot = furnace.targetAt(tip);
        if (spot == FurnaceStation.INPUT || spot == FurnaceStation.FUEL) {
            grabs.end(client, hand);
            if (furnace.place(client, hand, g.source(), spot, true)) {
                VrBridge.pulse(hand, 0.02f);
                action("pôs a pilha na fornalha, lugar " + spot);
            } else {
                refuse(hand);
                action("a fornalha recusou (lugar " + spot + ")");
            }
            return;
        }
        Holster zone = holsters.refreshZone(hand);
        grabs.end(client, hand);
        // Por cima do ombro, como no Saints & Sinners: soltar no ombro da mochila guarda o item nela.
        if (zone != null && zone.isBackpack(config) && !backpack.isOpen()
                && (g.source() >= InvOps.HOTBAR_START || g.source() < InvOps.MAIN_START)) {
            if (storeInBackpack(client, g.source())) {
                VrBridge.pulse(hand, 0.05f);
                action("guardou na mochila (por cima do ombro)");
            } else {
                refuse(hand);
                action("mochila cheia: o item ficou na mão");
            }
            return;
        }
        if (zone != null && zone.enabled(config) && zone != g.holster()) {
            if (holsters.store(client, zone, g.source())) {
                if (g.origin() == GrabSystem.Origin.HOLSTER && g.holster() != null) {
                    holsters.clear(g.holster());
                }
                VrBridge.pulse(hand, 0.05f);
                action("guardou no coldre " + zone);
                return;
            }
        }
        action(g.origin() == GrabSystem.Origin.HAND ? "soltou: o item continua na mão" : "devolveu ao lugar");
    }

    /**
     * Guarda a pilha do espaço {@code from} na mochila (o inventário principal): primeiro junta com pilhas iguais,
     * depois ocupa o primeiro espaço vazio. Não usa o Shift+clique, que vestiria uma armadura. Retorna true se algo
     * entrou (o que não couber fica onde estava).
     */
    private boolean storeInBackpack(Minecraft client, int from) {
        LocalPlayer p = client.player;
        int before = InvOps.stack(p, from).getCount();
        for (int i = InvOps.MAIN_START; i < InvOps.HOTBAR_START && !InvOps.stack(p, from).isEmpty(); i++) {
            ItemStack s = InvOps.stack(p, i);
            if (!isLocked(p, i) && !s.isEmpty() && ItemStack.isSameItemSameComponents(s, InvOps.stack(p, from))
                    && s.getCount() < s.getMaxStackSize()) {
                InvOps.moveStack(client, from, i);
            }
        }
        for (int i = InvOps.MAIN_START; i < InvOps.HOTBAR_START && !InvOps.stack(p, from).isEmpty(); i++) {
            if (!isLocked(p, i) && InvOps.stack(p, i).isEmpty()) {
                InvOps.moveStack(client, from, i);
            }
        }
        ItemStack left = InvOps.stack(p, from);
        return left.isEmpty() || left.getCount() < before;
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
        // Grip (que pegou o resultado) + gatilho com a mão no resultado: fabrica o resto, juntando na mesma mão.
        if (resultHold[hand] != ResultSource.NONE && resultUnder(hand, tip) == resultHold[hand]) {
            if (craftJob == null && resultStack(p, resultHold[hand]).isEmpty()) {
                refuse(hand); // a grade acabou: nada mais a fabricar
                return true;
            }
            GrabSystem.Grab own = grabs.get(hand);
            if (own != null && own.origin() == GrabSystem.Origin.HAND) {
                grabs.end(client, hand); // a mão segurava o que fabricou: o resto junta com ele
            }
            if (!grabs.isGrabbing(hand)) {
                startCraft(client, hand, resultHold[hand], ALL);
            }
            return true;
        }
        if (grabs.isGrabbing(hand)) {
            int handSlot = GrabSystem.itemSlot(p, grabs.get(hand)); // onde o item pego está
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
                if (backpack.onPanel(tip)) {
                    return true;
                }
            }
            // Mesa de trabalho: gatilho sobre uma casa põe UM item e a mão continua segurando o resto.
            int cell = table.targetAt(tip);
            if (cell >= 0 && cell < 9) {
                if (table.place(client, hand, handSlot, cell, tableCanOpen(p), false)) {
                    VrBridge.pulse(hand, 0.01f);
                    action("pôs um na mesa (gatilho), casa " + cell);
                } else {
                    refuse(hand);
                }
                return true;
            }
            // Fornalha: gatilho na entrada ou no combustível põe UM item.
            int spot = furnace.targetAt(tip);
            if (spot == FurnaceStation.INPUT || spot == FurnaceStation.FUEL) {
                if (furnace.place(client, hand, handSlot, spot, false)) {
                    VrBridge.pulse(hand, 0.01f);
                    action("pôs um na fornalha (gatilho), lugar " + spot);
                } else {
                    refuse(hand);
                }
                return true;
            }
            return false; // pegou um item e está longe dos menus: o gatilho usa o item normalmente
        }
        if (backpack.isOpen()) {
            Backpack.Element e = backpack.elementAt(tip);
            if (e != null) {
                switch (e.kind()) {
                    case PREV -> flip(hand, -1);
                    case NEXT -> flip(hand, 1);
                    default -> { }
                }
                return true;
            }
            if (backpack.onPanel(tip)) {
                return true;
            }
        }
        if (radial.isOpen() && radial.targetAt(tip) != RadialMenu.NONE) {
            return true;
        }
        // Gatilho sozinho no resultado da mesa: nada (o resultado sai com o grip). Numa fornalha também: o gatilho não
        // quebra o bloco em que a mão está mexendo.
        if (table.targetAt(tip) == CraftingTable.RESULT || furnace.targetAt(tip) != FurnaceStation.NONE) {
            return true;
        }
        return false;
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
        GrabSystem.Grab held = hand < 0 ? null : grabs.get(hand);
        if (!assigned && target != RadialMenu.NONE && held != null && held.origin() == GrabSystem.Origin.HAND) {
            grabs.end(client, hand); // segurava o próprio item com o grip: escolher outro no radial vale normalmente
        }
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
        if (grabs.isGrabbing(0) || grabs.inHandSlot(0) != null) {
            return false; // a mão principal (ou o espaço dela, com o arco da mão esquerda) está ocupada
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
        if (BowHand.isBow(InvOps.stack(p, InvOps.HOTBAR_START + target))) {
            BowHand.took(0); // escolhido pela mão direita: o arco fica nela
        }
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
        if (BowHand.isBow(InvOps.stack(p, slot)) && !grabs.isGrabbing(0) && grabs.inHandSlot(0) == null) {
            // Arco: vai para o espaço da mão principal (o Vivecraft usa as flechas do inventário) e aparece na esquerda.
            unequipOff(client);
            p.getInventory().setSelectedSlot(target);
            BowHand.took(1);
            return true;
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
                closeBackpack(client);
            }
        }
        // O item da mão seguro com o grip mudou (a hotbar mudou, o item acabou): solta, sem guardar nada.
        for (int hand = 0; hand < 2; hand++) {
            GrabSystem.Grab g = grabs.get(hand);
            if (g != null && g.origin() == GrabSystem.Origin.HAND
                    && (InvOps.handSlot(p, hand) != g.source() || InvOps.stack(p, g.source()).isEmpty())) {
                grabs.end(client, hand);
                action("soltou o item da mão " + hand + " (ele mudou)");
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
                closeBackpack(client);
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
        BowHand.tick(client);
        tableTick(client);
        furnaceTick(client);
        craftTick(client);
    }

    /**
     * As fornalhas: abre sozinha a que o jogador olha (se nada mais usa o inventário agora), o lugar sob cada mão (vibra
     * ao entrar) e, com um item na mão sobre a entrada ou o combustível, abre na hora.
     */
    private void furnaceTick(Minecraft client) {
        LocalPlayer p = client.player;
        boolean canAuto = InvOps.ready(client) && p.containerMenu == p.inventoryMenu && !table.isOpen()
                && !table.isWaiting() && craftJob == null && !grabbingOutsideMenus();
        boolean inUse = craftJob != null && craftJob.source == ResultSource.FURNACE;
        furnace.tick(client, canAuto, inUse);
        furnaceWatcher.tick(client, furnace);
        for (int hand = 0; hand < 2; hand++) {
            int target = backpack.holder() == hand || !VrBridge.isVRActive() ? FurnaceStation.NONE
                    : furnace.targetAt(VrBridge.pointer(hand));
            if (furnace.updateHover(hand, target)) {
                VrBridge.pulse(hand, 0.005f);
            }
            if ((target == FurnaceStation.INPUT || target == FurnaceStation.FUEL) && grabs.isGrabbing(hand)
                    && InvOps.ready(client) && !table.isOpen() && !grabbingOutsideMenus()) {
                furnace.ensureOpen(client, true);
            }
        }
    }

    /** A mesa de trabalho: a casa ou o resultado sob cada mão (vibra ao entrar) e, com um item na mão sobre a mesa, já
     * pede o menu dela (assim ele está aberto quando a mão soltar). */
    private void tableTick(Minecraft client) {
        LocalPlayer p = client.player;
        table.tick(client);
        for (int hand = 0; hand < 2; hand++) {
            int target = backpack.holder() == hand || !VrBridge.isVRActive() ? CraftingTable.NONE
                    : table.targetAt(VrBridge.pointer(hand));
            if (table.updateHover(hand, target)) {
                VrBridge.pulse(hand, 0.005f);
            }
            if (target >= 0 && target < 9 && grabs.isGrabbing(hand) && InvOps.ready(client)) {
                table.ensureOpen(client, tableCanOpen(p));
            }
        }
    }
}
