package com.handsonvr.hands;

import com.handsonvr.HandsOnConfig;
import com.handsonvr.HandsOnVR;
import com.handsonvr.backpack.Backpack;
import com.handsonvr.brewing.BrewingStation;
import com.handsonvr.chest.ChestStation;
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

import java.util.ArrayList;
import java.util.List;

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
    private final ChestStation chest = new ChestStation();
    private final BrewingStation brewing = new BrewingStation();
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

    /** Onde se arrasta uma pilha com o gatilho: as casas da mesa de trabalho ou os espaços da mochila. */
    public enum DragKind { TABLE, BACKPACK }

    /**
     * Arrastar com o gatilho (como arrastar a pilha pela tela do jogo): começa no gatilho sobre uma casa, com a pilha
     * segura pelo grip, e marca cada casa por que a mão passa. Soltar o gatilho põe um (uma casa) ou divide a pilha
     * igualmente (duas ou mais). {@code cells}: casas da mesa (0-8) ou espaços do menu do jogador (mochila).
     */
    private static final class Drag {
        final DragKind kind;
        final List<Integer> cells = new ArrayList<>();

        Drag(DragKind kind, int first) {
            this.kind = kind;
            cells.add(first);
        }
    }

    private final Drag[] drags = new Drag[2];
    /** "Mergulhar" a mão com o item na fogueira ou num lugar do suporte de poções: há quantos ticks e se já pôs. */
    private final int[] dipTicks = new int[2];
    private final boolean[] dipped = new boolean[2];
    /** Ticks com a mão parada no lugar antes de pôr (evita pôr só de passar a mão). */
    private static final int DIP_TICKS = 5;

    /** Esta casa está marcada pelo arrastar de alguma mão (o desenho a clareia)? */
    public boolean dragMarked(DragKind kind, int cell) {
        for (Drag d : drags) {
            if (d != null && d.kind == kind && d.cells.contains(cell)) {
                return true;
            }
        }
        return false;
    }

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

    public ChestStation chest() {
        return chest;
    }

    public BrewingStation brewing() {
        return brewing;
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
        chest.reset();
        brewing.reset();
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
        return backpack.isOpen() || radial.isOpen() || table.blocksSwing() || furnace.blocksSwing() || chest.blocksSwing()
                || brewing.blocksSwing();
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
            if (GripState.isTriggerHeld(hand)) {
                // Gatilho apertado + grip: só a metade (como o clique direito da tela do jogo).
                int free = freeSlotForResult(p);
                blockToHand(client, hand, free < 0 ? -1 : table.takeHalf(client, onTable, free), "tirou a metade da casa " + onTable);
            } else {
                blockToHand(client, hand, table.take(client, onTable), "tirou da mesa a casa " + onTable);
            }
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
        // 3c) Suporte de poções: um lugar com item volta para a mão.
        int onBrewing = brewing.targetAt(tip);
        if (onBrewing >= 0 && brewing.isOpen() && !brewing.stack(onBrewing).isEmpty()) {
            blockToHand(client, hand, brewing.take(client, onBrewing), "tirou do suporte de poções o lugar " + onBrewing);
            return true;
        }
        if (chest.onSortButton(tip)) {
            sortChest(client, hand);
            return true;
        }
        // 3d) Baú aberto: um espaço com item vem para a mão (com o gatilho já apertado, a metade).
        int onChest = chest.slotAt(tip);
        if (onChest >= 0 && !chest.stack(onChest).isEmpty()) {
            if (GripState.isTriggerHeld(hand)) {
                int free = freeSlotForResult(p);
                blockToHand(client, hand, free < 0 ? -1 : chest.takeHalf(client, onChest, free), "tirou a metade do espaço " + onChest + " do baú");
            } else {
                blockToHand(client, hand, chest.take(client, onChest), "tirou do baú o espaço " + onChest);
            }
            return true;
        }
        if (chest.onPanel(tip) && !(hand == 0 ? p.getMainHandItem() : p.getOffhandItem()).isEmpty()) {
            holdOwnItem(client, hand); // com um item na mão: soltar num espaço o guarda (mão vazia: grip no fundo e empurrar para baixo fecha)
            return true;
        }
        // 3e) Grip encostado num baú, barril ou caixa de shulker: começa o gesto da tampa (levantar abre, empurrar
        //     para baixo fecha; ver ChestStation.lidTick).
        net.minecraft.core.BlockPos box = ChestStation.containerAt(client.level, tip);
        if (box != null && chest.beginLid(hand, box, tip)) {
            VrBridge.pulse(hand, 0.01f);
            action("segurou a tampa do baú");
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
                int half = e.kind() != Backpack.Kind.ARMOR && GripState.isTriggerHeld(hand) && !isLocked(p, e.handler())
                        && InvOps.stack(p, e.handler()).getCount() > 1 ? freeSlotForResult(p) : -1;
                if (half >= 0 && InvOps.takeHalf(client, e.handler(), half)) {
                    // Gatilho apertado + grip: a metade vem para a mão (pega do espaço vazio em que ela ficou).
                    if (grabs.begin(client, hand, half, GrabSystem.Origin.BACKPACK, null, hand == 0 ? emptyHotbarFor(p) : -1)) {
                        action("pegou a metade do espaço " + e.handler());
                    }
                } else if (isLocked(p, e.handler())) {
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
            case SORT -> sortBackpack(client, hand);
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

    /** O item seguro veio de um coldre e foi guardado na mochila: o coldre esquece dele (fica vazio). */
    private void leaveHolster(GrabSystem.Grab g) {
        if (g.origin() == GrabSystem.Origin.HOLSTER && g.holster() != null) {
            holsters.clear(g.holster());
        }
    }

    /** "Organizar" da mochila: junta e ordena o inventário principal (as 3 páginas). */
    private void sortBackpack(Minecraft client, int hand) {
        LocalPlayer p = client.player;
        if (p == null || !InvOps.ready(client) || grabs.isGrabbing(0) || grabs.isGrabbing(1) || !InvOps.cursor(p).isEmpty()) {
            refuse(hand);
            return;
        }
        int clicks = com.handsonvr.inv.Sorter.sort(27, new com.handsonvr.inv.Sorter.Slots() {
            @Override
            public ItemStack get(int i) {
                return InvOps.stack(p, InvOps.MAIN_START + i);
            }

            @Override
            public void pickup(int i) {
                InvOps.pickup(client, InvOps.MAIN_START + i);
            }
        });
        InvOps.flushCursor(client);
        VrBridge.pulse(hand, 0.03f);
        action("organizou a mochila (" + clicks + " cliques)");
    }

    /** "Organizar" do baú aberto. */
    private void sortChest(Minecraft client, int hand) {
        if (grabs.isGrabbing(0) || grabs.isGrabbing(1) || !chest.sort(client)) {
            refuse(hand);
            return;
        }
        VrBridge.pulse(hand, 0.03f);
        action("organizou o baú");
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
        // Soltou o grip ainda arrastando (o gatilho apertado): o arrastar vale (como soltar o botão do mouse), e o resto
        // da pilha segue a regra de sempre.
        Drag drag = drags[hand];
        if (drag != null) {
            drags[hand] = null;
            finishDrag(client, hand, drag);
        }
        Vec3 tip = VrBridge.pointer(hand);
        HandsOnConfig config = HandsOnConfig.get();
        // Arremesso: um item arremessável e a mão rápida ao soltar = joga UM na direção do movimento.
        if (throwRelease(client, hand, g, tip)) {
            return;
        }
        if (radial.isOpen()) {
            int target = radial.targetAt(tip);
            if (target == RadialMenu.DROP) {
                InvOps.throwFrom(client, GrabSystem.itemSlot(p, g), true);
                grabs.end(client, hand);
                radial.markAssigned();
                VrBridge.pulse(hand, 0.03f);
                action("descartou a pilha (radial)");
                return;
            }
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
                    leaveHolster(g); // guardado na mochila: o coldre de onde ele saiu fica vazio
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
        // Baú aberto: soltar num espaço põe a pilha ali (um item diferente troca de lugar).
        int chestSlot = chest.slotAt(tip);
        if (chestSlot >= 0) {
            grabs.end(client, hand);
            if (chest.place(client, g.source(), chestSlot, true)) {
                VrBridge.pulse(hand, 0.02f);
                action("pôs a pilha no baú, espaço " + chestSlot);
            } else {
                refuse(hand);
            }
            return;
        }
        // Suporte de poções: soltar num lugar (garrafa, ingrediente, pó de blaze) põe a pilha.
        int brew = brewing.targetAt(tip);
        if (brew >= 0) {
            grabs.end(client, hand);
            if (brewing.place(client, hand, g.source(), brew, true)) {
                VrBridge.pulse(hand, 0.02f);
                action("pôs no suporte de poções, lugar " + brew);
            } else {
                refuse(hand);
            }
            return;
        }
        // Fogueira: soltar em cima dela com comida crua põe um item (o "usar" do jogo); o resto volta.
        if (g.itemHand() == hand) {
            net.minecraft.world.phys.BlockHitResult fire = campfireUnder(client, tip);
            if (fire != null) {
                boolean placed = useOnBlock(client, hand, fire);
                grabs.end(client, hand);
                if (placed) {
                    VrBridge.pulse(hand, 0.03f);
                    action("pôs na fogueira");
                } else {
                    refuse(hand);
                }
                return;
            }
        }
        // Armadura: soltar a peça na parte do corpo dela veste (capacete na cabeça, peitoral no peito...).
        int armorSlot = bodyArmorSlot(p, InvOps.stack(p, GrabSystem.itemSlot(p, g)), tip);
        if (armorSlot >= 0 && g.source() != armorSlot) {
            grabs.end(client, hand);
            if (!isLocked(p, armorSlot) && exchangeFits(p, g.source(), armorSlot) && InvOps.moveStack(client, g.source(), armorSlot)) {
                VrBridge.pulse(hand, 0.05f);
                action("vestiu a peça (espaço " + armorSlot + ")");
            } else {
                refuse(hand);
            }
            return;
        }
        Holster zone = holsters.refreshZone(hand);
        grabs.end(client, hand);
        // Por cima do ombro, como no Saints & Sinners: soltar no ombro da mochila guarda o item nela.
        boolean fromHolster = g.origin() == GrabSystem.Origin.HOLSTER && g.holster() != null;
        if (zone != null && zone.isBackpack(config) && !backpack.isOpen() && fromHolster
                && g.source() >= InvOps.MAIN_START && g.source() < InvOps.HOTBAR_START) {
            // O item do coldre já mora na mochila (o inventário principal): guardar por cima do ombro só tira do coldre.
            leaveHolster(g);
            VrBridge.pulse(hand, 0.05f);
            action("guardou na mochila (saiu do coldre)");
            return;
        }
        if (zone != null && zone.isBackpack(config) && !backpack.isOpen()
                && (g.source() >= InvOps.HOTBAR_START || g.source() < InvOps.MAIN_START)) {
            if (storeInBackpack(client, g.source())) {
                leaveHolster(g);
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
                if (target == RadialMenu.DROP) {
                    InvOps.throwFrom(client, handSlot, false);
                    radial.markAssigned();
                    VrBridge.pulse(hand, 0.01f);
                    action("descartou um (radial)");
                    return true;
                }
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
                        case SLOT, CRAFT_IN -> {
                            // Começa a arrastar: soltar o gatilho aqui põe um; passando por outros espaços, divide.
                            if (!isLocked(p, e.handler())) {
                                drags[hand] = new Drag(DragKind.BACKPACK, e.handler());
                                VrBridge.pulse(hand, 0.005f);
                            } else {
                                refuse(hand);
                            }
                        }
                        case ARMOR -> {
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
            // Mesa de trabalho: o gatilho começa a arrastar. Soltar o gatilho na mesma casa põe UM item (a mão continua
            // segurando o resto); passando por outras casas, a pilha se divide igualmente entre elas.
            int cell = table.targetAt(tip);
            if (cell >= 0 && cell < 9) {
                drags[hand] = new Drag(DragKind.TABLE, cell);
                VrBridge.pulse(hand, 0.005f);
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
            // Baú aberto: gatilho num espaço põe UM item.
            int chestSlot = chest.slotAt(tip);
            if (chestSlot >= 0) {
                if (chest.place(client, handSlot, chestSlot, false)) {
                    VrBridge.pulse(hand, 0.01f);
                    action("pôs um no baú, espaço " + chestSlot);
                } else {
                    refuse(hand);
                }
                return true;
            }
            // Suporte de poções: gatilho num lugar põe UM item.
            int brew = brewing.targetAt(tip);
            if (brew >= 0) {
                if (brewing.place(client, hand, handSlot, brew, false)) {
                    VrBridge.pulse(hand, 0.01f);
                    action("pôs um no suporte de poções, lugar " + brew);
                } else {
                    refuse(hand);
                }
                return true;
            }
            // Fogueira: gatilho em cima dela põe UM item e a mão continua segurando o resto.
            net.minecraft.world.phys.BlockHitResult fire = grabs.get(hand).itemHand() == hand ? campfireUnder(client, tip) : null;
            if (fire != null) {
                if (useOnBlock(client, hand, fire)) {
                    VrBridge.pulse(hand, 0.02f);
                    action("pôs um na fogueira (gatilho)");
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
                    case SORT -> sortBackpack(client, hand);
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
        if (table.targetAt(tip) != CraftingTable.NONE || furnace.targetAt(tip) != FurnaceStation.NONE || chest.onPanel(tip)
                || brewing.targetAt(tip) != BrewingStation.NONE) {
            return true;
        }
        return false;
    }

    /** "Usar" (A): não usa o item escondido da mão que segura a mochila, nem aponta para o painel; no baú, abre o imersivo. */
    public boolean onUse(Minecraft client) {
        if (client.player == null || client.gui.screen() != null) {
            return false;
        }
        if (backpack.holder() == 0 || (backpack.isOpen() && backpack.onPanel(VrBridge.pointer(0)))) {
            return true;
        }
        return chest.useOnContainer(client); // A no baú: o baú imersivo, não a janela antiga (só em VR)
    }

    // ------------------------------------------------------------------ arrastar com o gatilho (distribuir)

    /** Gatilho solto. Retorna true se foi nosso (terminou um arrastar). */
    public boolean onTriggerReleased(Minecraft client, int hand) {
        Drag d = drags[hand];
        drags[hand] = null;
        if (d == null || client.player == null) {
            return false;
        }
        finishDrag(client, hand, d);
        return true;
    }

    /** Fim do arrastar: uma casa = um item; duas ou mais = a pilha dividida igualmente entre elas. */
    private void finishDrag(Minecraft client, int hand, Drag d) {
        LocalPlayer p = client.player;
        GrabSystem.Grab g = grabs.get(hand);
        if (g == null || !InvOps.ready(client)) {
            return;
        }
        int handSlot = GrabSystem.itemSlot(p, g);
        List<Integer> cells = new ArrayList<>(d.cells);
        if (d.kind == DragKind.BACKPACK) {
            cells.removeIf(c -> isLocked(p, c) || c == handSlot);
        }
        if (cells.isEmpty()) {
            refuse(hand);
            return;
        }
        boolean one = cells.size() == 1;
        boolean ok = switch (d.kind) {
            case TABLE -> one ? table.place(client, hand, handSlot, cells.get(0), tableCanOpen(p), false)
                    : table.distribute(client, handSlot, cells);
            case BACKPACK -> one ? InvOps.placeOne(client, handSlot, cells.get(0)) : InvOps.distribute(client, handSlot, cells);
        };
        if (ok) {
            VrBridge.pulse(hand, one ? 0.01f : 0.03f);
            action(one ? "pôs um (" + d.kind + " " + cells.get(0) + ")" : "dividiu a pilha em " + cells.size() + " (" + d.kind + ")");
        } else {
            refuse(hand);
        }
    }

    /** Durante o arrastar: marca cada casa nova por que a mão passa (vibra) e termina se o gatilho foi solto. */
    private void dragTick(Minecraft client) {
        LocalPlayer p = client.player;
        for (int hand = 0; hand < 2; hand++) {
            Drag d = drags[hand];
            if (d == null) {
                continue;
            }
            if (!grabs.isGrabbing(hand) || !VrBridge.isVRActive()) {
                drags[hand] = null;
                continue;
            }
            if (!GripState.isTriggerHeld(hand)) {
                onTriggerReleased(client, hand); // o aviso de soltar não chegou: termina aqui
                continue;
            }
            Vec3 tip = VrBridge.pointer(hand);
            int cell = -1;
            if (d.kind == DragKind.TABLE) {
                int t = table.targetAt(tip);
                cell = t >= 0 && t < 9 ? t : -1;
            } else if (backpack.isOpen() && backpack.holder() != hand) {
                Backpack.Element e = backpack.elementAt(tip);
                if (e != null && (e.kind() == Backpack.Kind.SLOT || e.kind() == Backpack.Kind.CRAFT_IN) && !isLocked(p, e.handler())) {
                    cell = e.handler();
                }
            }
            if (cell >= 0 && !d.cells.contains(cell)) {
                d.cells.add(cell);
                VrBridge.pulse(hand, 0.008f);
            }
        }
    }

    // ------------------------------------------------------------------ fogueira, armadura no corpo, baú, suporte de poções

    /**
     * Sem botão nenhum: a mão com o item encosta na fogueira (comida crua) ou num lugar do suporte de poções que aceita
     * o item (garrafa, ingrediente, pó de blaze) e fica ali um instante: põe UM. Para pôr outro, tire a mão e encoste de
     * novo. Usa o "usar" do jogo (a fogueira) e os cliques do menu (o suporte), como o jogador faria.
     */
    private void dipTick(Minecraft client) {
        LocalPlayer p = client.player;
        for (int hand = 0; hand < 2; hand++) {
            if (grabs.isGrabbing(hand)) {
                // Segurando com o grip: soltar é que põe. Depois de soltar ali, só põe de novo tirando a mão e voltando.
                dipped[hand] = true;
                dipTicks[hand] = 0;
                continue;
            }
            Vec3 tip = VrBridge.isVRActive() && backpack.holder() != hand ? VrBridge.pointer(hand) : null;
            ItemStack stack = hand == 0 ? p.getMainHandItem() : p.getOffhandItem();
            int brew = tip == null || stack.isEmpty() ? BrewingStation.NONE : brewing.targetAt(tip);
            if (brew >= 0 && !brewing.likelyAccepts(client, brew, stack)) {
                brew = BrewingStation.NONE;
            }
            net.minecraft.world.phys.BlockHitResult fire = tip == null || brew >= 0 || !isCampfireFood(client, stack) ? null : campfireUnder(client, tip);
            if (brew < 0 && fire == null) {
                dipTicks[hand] = 0;
                dipped[hand] = false;
                continue;
            }
            if (dipped[hand] || ++dipTicks[hand] < DIP_TICKS || !InvOps.ready(client) || table.isOpen()) {
                continue;
            }
            dipped[hand] = true;
            boolean placed;
            if (brew >= 0) {
                if (p.containerMenu != p.inventoryMenu && !brewing.isOpen()) {
                    InvOps.releaseHidden(client);
                }
                placed = brewing.place(client, hand, InvOps.handSlot(p, hand), brew, false);
            } else {
                placed = useOnBlock(client, hand, fire);
            }
            if (placed) {
                VrBridge.pulse(hand, 0.03f);
                action(brew >= 0 ? "encostou e pôs um no suporte de poções, lugar " + brew : "encostou e pôs um na fogueira");
            } else {
                refuse(hand);
            }
        }
    }

    /** O item vai na fogueira (a lista que o servidor manda ao cliente: carne, peixe e batata crus...)? */
    public static boolean isCampfireFood(Minecraft client, ItemStack stack) {
        return client.level != null && !stack.isEmpty()
                && client.level.recipeAccess().propertySet(net.minecraft.world.item.crafting.RecipePropertySet.CAMPFIRE_INPUT).test(stack);
    }

    /** Raio de cada canto da fogueira em que a ponta da mão conta como "no lugar da comida". */
    public static final double CAMPFIRE_SLOT_RADIUS = 0.17;

    /**
     * Os 4 lugares de comida da fogueira, nos cantos (o mesmo cálculo do CampfireRenderer do jogo), na altura da brasa.
     * O servidor enche sempre o primeiro lugar vazio, na ordem do bloco, então {@code empty[i]} diz se o lugar i está livre.
     */
    public record CampfireSlots(Vec3[] at, boolean[] empty) {}

    public static CampfireSlots campfireSlots(Minecraft client, net.minecraft.core.BlockPos pos) {
        var state = client.level.getBlockState(pos);
        int facing = state.getValue(net.minecraft.world.level.block.CampfireBlock.FACING).get2DDataValue();
        var be = client.level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.CampfireBlockEntity fire ? fire : null;
        Vec3[] at = new Vec3[4];
        boolean[] empty = new boolean[4];
        for (int i = 0; i < 4; i++) {
            double theta = Math.toRadians(-net.minecraft.core.Direction.from2DDataValue((i + facing) % 4).toYRot());
            double x = -0.25, z = -0.25;
            at[i] = new Vec3(pos.getX() + 0.5 + x * Math.cos(theta) + z * Math.sin(theta), pos.getY() + 0.4375,
                    pos.getZ() + 0.5 - x * Math.sin(theta) + z * Math.cos(theta));
            empty[i] = be == null || be.getItems().get(i).isEmpty();
        }
        return new CampfireSlots(at, empty);
    }

    /** A fogueira com a ponta da mão sobre um de seus lugares de comida livres (ou até uns 40 cm acima dele); ou null. */
    public static net.minecraft.world.phys.BlockHitResult campfireUnder(Minecraft client, Vec3 tip) {
        if (tip == null || client.level == null) {
            return null;
        }
        net.minecraft.core.BlockPos at = net.minecraft.core.BlockPos.containing(tip);
        for (net.minecraft.core.BlockPos q : net.minecraft.core.BlockPos.betweenClosed(at.offset(-1, -1, -1), at.offset(1, 0, 1))) {
            if (client.level.getBlockState(q).getBlock() instanceof net.minecraft.world.level.block.CampfireBlock) {
                double above = tip.y - q.getY(); // a fogueira tem 7/16 de altura
                if (above < 0.0 || above > 0.85) {
                    continue;
                }
                CampfireSlots slots = campfireSlots(client, q);
                for (int i = 0; i < 4; i++) {
                    if (slots.empty()[i] && Math.hypot(tip.x - slots.at()[i].x, tip.z - slots.at()[i].z) <= CAMPFIRE_SLOT_RADIUS) {
                        return new net.minecraft.world.phys.BlockHitResult(slots.at()[i], net.minecraft.core.Direction.UP, q.immutable(), false);
                    }
                }
            }
        }
        return null;
    }

    /** "Usar" o item da mão no bloco, como o jogador faria (a fogueira põe a comida). True se o jogo aceitou. */
    private static boolean useOnBlock(Minecraft client, int hand, net.minecraft.world.phys.BlockHitResult hit) {
        if (client.player == null || client.gameMode == null) {
            return false;
        }
        var interactionHand = hand == 0 ? net.minecraft.world.InteractionHand.MAIN_HAND : net.minecraft.world.InteractionHand.OFF_HAND;
        return NoShift.run(client, () -> client.gameMode.useItemOn(client.player, interactionHand, hit)).consumesAction();
    }

    /**
     * Onde a peça de armadura se veste, se a ponta da mão está na parte do corpo dela: o capacete na cabeça, o peitoral
     * (ou o élitro) no peito, as calças na cintura e as botas abaixo dos joelhos, sempre na frente do corpo (os coldres
     * ficam dos lados e atrás). Retorna o espaço do menu do jogador, ou -1.
     */
    private static int bodyArmorSlot(LocalPlayer p, ItemStack stack, Vec3 tip) {
        VrBridge.Pose head = VrBridge.head();
        if (stack.isEmpty() || tip == null || head == null) {
            return -1;
        }
        net.minecraft.world.entity.EquipmentSlot slot = p.getEquipmentSlotForItem(stack);
        if (slot.getType() != net.minecraft.world.entity.EquipmentSlot.Type.HUMANOID_ARMOR) {
            return -1;
        }
        double s = VrBridge.worldScale();
        Vec3 h = head.position(), d = tip.subtract(h);
        Vec3 look = head.direction();
        Vec3 forward = new Vec3(look.x, 0, look.z).lengthSqr() < 1.0e-6 ? new Vec3(0, 0, 1) : new Vec3(look.x, 0, look.z).normalize();
        double horizontal = Math.sqrt(d.x * d.x + d.z * d.z), front = d.x * forward.x + d.z * forward.z;
        boolean in = switch (slot) {
            case HEAD -> tip.distanceTo(h) < 0.25 * s && d.y > -0.12 * s;
            case CHEST -> horizontal < 0.4 * s && front > 0.03 * s && d.y <= -0.2 * s && d.y > -0.62 * s;
            case LEGS -> horizontal < 0.45 * s && front > 0.03 * s && d.y <= -0.62 * s && d.y > -1.05 * s;
            case FEET -> horizontal < 0.55 * s && d.y <= -1.05 * s;
            default -> false;
        };
        if (!in) {
            return -1;
        }
        return InvOps.ARMOR_START + switch (slot) {
            case HEAD -> 0;
            case CHEST -> 1;
            case LEGS -> 2;
            default -> 3;
        };
    }

    /** O baú aberto: o espaço sob cada mão (vibra ao entrar). */
    private void chestTick(Minecraft client) {
        chest.tick(client);
        for (int hand = 0; hand < 2; hand++) {
            if (chest.lidActive(hand)) {
                int moved = chest.lidTick(client, hand, VrBridge.isVRActive() ? VrBridge.pointer(hand) : null, GripState.isHeld(hand));
                if (moved != 0) {
                    VrBridge.pulse(hand, 0.03f);
                    action((moved > 0 ? "abriu" : "fechou") + " a tampa do baú");
                }
            }
            int target = backpack.holder() == hand || !VrBridge.isVRActive() ? ChestStation.NONE : chest.slotAt(VrBridge.pointer(hand));
            if (chest.updateHover(hand, target)) {
                VrBridge.pulse(hand, 0.005f);
            }
            if (target != ChestStation.NONE && chest.isSuspended() && InvOps.ready(client) && !table.isOpen() && craftJob == null
                    && chest.resume(client)) {
                action("a mão voltou ao baú: reabriu");
            }
        }
    }

    /** O suporte de poções: abre sozinho o que o jogador olha, o lugar sob cada mão e, com um item na mão, abre na hora. */
    private void brewingTick(Minecraft client) {
        LocalPlayer p = client.player;
        boolean canAuto = InvOps.ready(client) && p.containerMenu == p.inventoryMenu && !table.isOpen() && !table.isWaiting()
                && !furnace.isWaiting() && !chest.isWaiting() && craftJob == null && !grabbingOutsideMenus();
        brewing.tick(client, canAuto);
        for (int hand = 0; hand < 2; hand++) {
            int target = backpack.holder() == hand || !VrBridge.isVRActive() ? BrewingStation.NONE
                    : brewing.targetAt(VrBridge.pointer(hand));
            if (brewing.updateHover(hand, target)) {
                VrBridge.pulse(hand, 0.005f);
            }
            ItemStack held = hand == 0 ? p.getMainHandItem() : p.getOffhandItem();
            if (target >= 0 && (grabs.isGrabbing(hand) || brewing.likelyAccepts(client, target, held) || chest.isOpen() && !brewing.isOpen()) && InvOps.ready(client) && !table.isOpen() && !grabbingOutsideMenus()) {
                if (p.containerMenu != p.inventoryMenu) {
                    InvOps.releaseHidden(client); // uma fornalha ou um baú aberto cede o menu
                }
                brewing.ensureOpen(client, true);
            }
        }
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
        if (target == RadialMenu.DROP) {
            LocalPlayer p = client.player;
            if (p != null && !(hand == 0 ? p.getMainHandItem() : p.getOffhandItem()).isEmpty()) {
                InvOps.throwFrom(client, InvOps.handSlot(p, hand), true);
                VrBridge.pulse(hand, 0.03f);
                action("radial: mão " + hand + " jogou fora a pilha");
            } else {
                refuse(hand);
            }
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
        dragTick(client);
        furnaceTick(client);
        chestTick(client);
        brewingTick(client);
        dipTick(client);
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
            if (target != FurnaceStation.NONE && chest.isOpen() && InvOps.ready(client) && !table.isOpen() && !grabbingOutsideMenus()) {
                InvOps.releaseHidden(client); // o baú aberto cede o menu (fica suspenso e volta com a mão nele)
                furnace.ensureOpen(client, true);
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
