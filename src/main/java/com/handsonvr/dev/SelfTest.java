package com.handsonvr.dev;

import com.handsonvr.HandsOnConfig;
import com.handsonvr.HandsOnVR;
import com.handsonvr.backpack.Backpack;
import com.handsonvr.hands.BowHand;
import com.handsonvr.hands.GrabSystem;
import com.handsonvr.hands.HandsController;
import com.handsonvr.holster.BodyFrame;
import com.handsonvr.holster.Holster;
import com.handsonvr.holster.HolsterManager;
import com.handsonvr.holster.HolsterStorage;
import com.handsonvr.input.OffhandUse;
import com.handsonvr.input.VivecraftInput;
import com.handsonvr.inv.InvOps;
import com.handsonvr.inv.PickupOrder;
import com.handsonvr.radial.RadialMenu;
import com.handsonvr.vr.Panel;
import com.handsonvr.vr.SimulatedVr;
import com.handsonvr.vr.VrBridge;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.CameraType;
import net.minecraft.client.Screenshot;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.LevelSettings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Teste automático de DESENVOLVIMENTO (só com -Dhandsonvr.selftest=true, usado pelo "gradlew prodClient"): cria um
 * mundo plano, finge o VR (mãos e botões posicionados/apertados pelo teste) e confere de ponta a ponta:
 * coldres (zonas amplas, guardar, sacar, soltar, mão esquerda), mochila (abrir, pegar, mover, juntar, um por vez,
 * descartar, crafting, armadura, páginas), menu radial (equipar e esvaziar as duas mãos, mochila &lt;-&gt; hotbar),
 * o inventário 100% cheio, guardar o item da mão (na mochila, por cima do ombro, nos coldres), persistência,
 * mixins e desenho. Relatório em run-prod/handsonvr-selftest.txt e fotos em
 * run-prod/screenshots/.
 */
public final class SelfTest {
    private SelfTest() {}

    private static final boolean ENABLED = Boolean.getBoolean("handsonvr.selftest");
    private static final String WORLD = "handsonvr-selftest";
    private static final int TIMEOUT_TICKS = 20 * 60 * 10;

    private static int step = 0, stepTicks = 0, totalTicks = 0;
    private static boolean finished = false;
    private static final List<String> report = new ArrayList<>();
    private static int swordSlot = -1, counted = 0, emptySelected = -1;
    private static int craftSelected = 0;
    private static BlockPos torchAt;

    public static void register() {
        if (!ENABLED) {
            return;
        }
        HandsOnVR.LOGGER.info("[SELFTEST] teste automático LIGADO");
        ClientTickEvents.END_CLIENT_TICK.register(SelfTest::tick);
    }

    // ------------------------------------------------------------------ infraestrutura

    private static void next() {
        step++;
        stepTicks = 0;
    }

    private static void pass(String what) {
        report.add("PASS  " + what);
        HandsOnVR.LOGGER.info("[SELFTEST] PASS {}", what);
    }

    private static void fail(String what) {
        report.add("FAIL  " + what);
        HandsOnVR.LOGGER.warn("[SELFTEST] FAIL {}", what);
    }

    private static void info(String what) {
        report.add("INFO  " + what);
        HandsOnVR.LOGGER.info("[SELFTEST] {}", what);
    }

    private static void section(String what) {
        report.add("");
        report.add("== " + what);
    }

    private static void check(boolean ok, String what) {
        if (ok) {
            pass(what);
        } else {
            fail(what);
        }
    }

    private static boolean at(int t) {
        return stepTicks == t;
    }

    private static void tick(Minecraft client) {
        if (finished) {
            return;
        }
        totalTicks++;
        stepTicks++;
        if (totalTicks > TIMEOUT_TICKS) {
            fail("tempo esgotado no passo " + step);
            finish(client);
            return;
        }
        try {
            runStep(client);
        } catch (Throwable t) {
            fail("exceção no passo " + step + " (tick " + stepTicks + "): " + t);
            HandsOnVR.LOGGER.error("[SELFTEST] exceção", t);
            finish(client);
        }
    }

    private static void finish(Minecraft client) {
        finished = true;
        SimulatedVr.setEnabled(false);
        long fails = report.stream().filter(s -> s.startsWith("FAIL")).count();
        long passes = report.stream().filter(s -> s.startsWith("PASS")).count();
        report.add(0, "HandsOn VR self-test: " + (fails == 0 ? "TUDO OK" : fails + " falha(s)") + " (" + passes + " verificações OK)");
        try {
            Files.write(client.gameDirectory.toPath().resolve("handsonvr-selftest.txt"), report, StandardCharsets.UTF_8);
        } catch (IOException e) {
            HandsOnVR.LOGGER.error("[SELFTEST] não consegui gravar o relatório", e);
        }
        HandsOnVR.LOGGER.info("[SELFTEST] FIM ({} falhas)", fails);
        // O driver de vídeo às vezes trava o jogo ao fechar. O relatório já está gravado: se o jogo não fechar em 30 s,
        // sai à força (só no teste).
        Thread exit = new Thread(() -> {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException ignored) {
                return;
            }
            HandsOnVR.LOGGER.warn("[SELFTEST] o jogo não fechou em 30 s: saindo à força");
            Runtime.getRuntime().halt(0);
        }, "handsonvr-selftest-exit");
        exit.setDaemon(true);
        exit.start();
        client.stop();
    }

    private static void screenshot(Minecraft client, String name) {
        Screenshot.grab(client.gameDirectory, name + ".png", client.gameRenderer.mainRenderTarget(), 1, text -> { });
        info("print " + name);
    }

    private static void onServer(Minecraft client, Consumer<ServerPlayer> action) {
        var server = client.getSingleplayerServer();
        UUID id = client.player.getUUID();
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) {
                action.accept(player);
            }
        });
    }

    /** Some com os itens jogados no chão (para não voltarem ao inventário e bagunçar o próximo passo). */
    private static void clearDrops(Minecraft client) {
        onServer(client, player -> player.level()
                .getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(16), e -> true)
                .forEach(ItemEntity::discard));
    }

    private static HandsController hands() {
        return HandsOnVR.hands();
    }

    private static HolsterManager holsters() {
        return HandsOnVR.holsters();
    }

    // ------------------------------------------------------------------ mãos e botões simulados

    /** Grip apertado/solto, como o Vivecraft avisaria. Retorna se foi consumido. */
    private static boolean grip(Minecraft client, int hand, boolean pressed) {
        SimulatedVr.setGrip(hand, pressed);
        return pressed ? hands().onGripPressed(client, hand) : hands().onGripReleased(client, hand);
    }

    /** Botão do radial (B = 0, X = 1) apertado/solto. Retorna se foi consumido. */
    private static boolean menuButton(Minecraft client, int hand, boolean pressed) {
        SimulatedVr.setMenuButton(hand, pressed);
        return hands().onMenuButton(client, hand, pressed);
    }

    /** Põe a mão (0/1) bem no ponto do coldre, apontando para a frente do corpo. */
    private static Vec3 handAt(int hand, Holster h) {
        BodyFrame.Frame frame = holsters().body().compute();
        Vec3 at = frame.anchor(h, HandsOnConfig.get());
        SimulatedVr.setHand(hand, at, frame.forward());
        return at;
    }

    /** Mão no coldre deslocada no referencial do tronco (direita, cima, frente), em metros. */
    private static void handNear(int hand, Holster h, double right, double up, double forward) {
        BodyFrame.Frame frame = holsters().body().compute();
        Vec3 at = frame.anchor(h, HandsOnConfig.get()).add(frame.right().scale(right)).add(0, up, 0)
                .add(frame.forward().scale(forward));
        SimulatedVr.setHand(hand, at, frame.forward());
    }

    private static void handAway(int hand) {
        SimulatedVr.setHand(hand, null, null);
    }

    /** Põe a PONTA da mão no ponto, entrando na direção {@code into}. */
    private static void pointAt(int hand, Vec3 point, Vec3 into) {
        Vec3 dir = into.normalize();
        SimulatedVr.setHand(hand, point.subtract(dir.scale(VrBridge.POINTER_OFFSET)), dir);
    }

    private static void pointPanel(int hand, Panel panel, double x, double y) {
        pointAt(hand, panel.world(x, y, 0.01), panel.normal().reverse());
    }

    /** Ponta da mão sobre um alvo da mochila aberta. */
    private static void pointBackpack(int hand, Backpack.Element e) {
        pointPanel(hand, hands().backpack().computePanel(), e.x(), e.y());
    }

    private static void pointBackpackSlot(int hand, int handler) {
        pointBackpack(hand, hands().backpack().elementFor(handler));
    }

    /** Ponta da mão sobre um espaço do radial (ou o centro). */
    private static void pointRadial(int hand, int index) {
        double[] c = RadialMenu.position(index);
        pointPanel(hand, hands().radial().panel(), c[0], c[1]);
    }

    /** Mão segurando a mochila na frente do rosto (a mochila pendura dela, virada para o rosto). */
    private static void holdBackpackInFront(Minecraft client, int hand) {
        Vec3 eye = client.player.getEyePosition();
        SimulatedVr.setHand(hand, eye.add(0.03, -0.08, 0.42), new Vec3(0, 0, 1));
    }

    /** Mão longe de tudo (na frente, à direita): soltar aqui devolve o item. */
    private static void pointAway(Minecraft client, int hand) {
        Vec3 eye = client.player.getEyePosition();
        SimulatedVr.setHand(hand, eye.add(-0.45, -0.25, 0.55), new Vec3(0, 0, 1));
    }

    /** Abre a mochila com a mão (mão no ombro esquerdo + grip) e a traz para a frente. */
    private static boolean openBackpack(Minecraft client, int hand) {
        handAt(hand, Holster.LEFT_SHOULDER);
        holsters().refreshZone(hand);
        boolean consumed = grip(client, hand, true);
        holdBackpackInFront(client, hand);
        hands().backpack().computePanel();
        return consumed && hands().backpack().isOpen();
    }

    private static String describe(ItemStack stack) {
        if (stack.isEmpty()) {
            return "vazio";
        }
        Component name = stack.get(DataComponents.CUSTOM_NAME);
        return stack.getCount() + "x " + stack.getItem() + (name == null ? "" : " \"" + name.getString() + "\"");
    }

    private static int find(Inventory inv, Item item) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(item)) {
                return i;
            }
        }
        return -1;
    }

    private static int total(Inventory inv, Item item) {
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(item)) {
                n += inv.getItem(i).getCount();
            }
        }
        return n;
    }

    private static ItemStack slot(LocalPlayer p, int handler) {
        return InvOps.stack(p, handler);
    }

    private static int droppedNearby(Minecraft client, Item item) {
        AABB box = client.player.getBoundingBox().inflate(12);
        return client.level.getEntitiesOfClass(ItemEntity.class, box, e -> e.getItem().is(item)).stream()
                .mapToInt(e -> e.getItem().getCount()).sum();
    }

    // ------------------------------------------------------------------ roteiro

    /**
     * Com -Dhandsonvr.selftest.nullvr=true o Vivecraft roda o VR de verdade com o "VR nulo" (sem óculos): as ações dos
     * controles existem, e o teste dispara os avisos de aperto que o controle dispararia (grips, gatilhos, A, B e X)
     * para conferir a ligação com o Vivecraft (e que o analógico direito ficou com ele).
     */
    private static final boolean NULL_VR = Boolean.getBoolean("handsonvr.selftest.nullvr");

    /** Telas que mods do modpack abrem sozinhas (boas-vindas, configuração inicial...) já registradas. */
    private static final java.util.Set<String> strayScreens = new java.util.HashSet<>();
    /** O próprio teste abre o menu de pausa e os ajustes (último passo): essas telas ficam abertas. */
    private static boolean testOpensScreens = false;

    /**
     * Com o modpack inteiro, algum mod pode abrir uma tela por cima do jogo (boas-vindas, configuração inicial). O
     * HandsOn ignora as mãos com tela aberta (como deve), então o teste fecha essas telas como o jogador faria —
     * menos as que o próprio teste abre (menu de pausa e ajustes, no último passo).
     */
    private static void closeStrayScreen(Minecraft client) {
        var screen = client.gui.screen();
        if (screen == null || client.player == null || step < 2 || (testOpensScreens
                && (screen instanceof net.minecraft.client.gui.screens.PauseScreen
                || screen instanceof com.handsonvr.screen.HandsOnScreen))
                || (allowCraftingScreen && screen instanceof net.minecraft.client.gui.screens.inventory.CraftingScreen)
                || (allowFurnaceScreen && screen instanceof net.minecraft.client.gui.screens.inventory.AbstractFurnaceScreen<?>)) {
            return;
        }
        if (strayScreens.add(screen.getClass().getName())) {
            info("tela aberta por outro mod fechada para o teste: " + screen.getClass().getName());
        }
        client.gui.setScreen(null);
    }

    private static void runStep(Minecraft client) throws Exception {
        closeStrayScreen(client);
        if (NULL_VR && step >= 2) {
            runNullVr(client);
            return;
        }
        switch (step) {
            case 0 -> { // tela inicial: mundo plano novo
                client.options.pauseOnLostFocus = false; // outra janela em foco não pausa o teste
                client.options.tutorialStep = net.minecraft.client.tutorial.TutorialSteps.NONE; // prints sem o tutorial
                if ((client.gui.screen() instanceof TitleScreen && client.gui.overlay() == null && stepTicks > 40)
                        || stepTicks > 600) {
                    deleteWorld(client);
                    createWorld(client);
                    info("criando mundo de teste");
                    next();
                }
            }
            case 1 -> {
                if (client.level == null || client.player == null) {
                    stepTicks = 0;
                } else if (stepTicks > 60) {
                    pass("mod carregou e o mundo abriu (mixins aplicados)");
                    next();
                }
            }
            case 2 -> setupHolsterInventory(client);
            case 3 -> holsterStoreSword(client);
            case 4 -> holsterDrawSword(client);
            case 5 -> holsterStoreTorches(client);
            case 6 -> holsterDrawTorchesAndPlace(client);
            case 7 -> holsterStoreBread(client);
            case 8 -> holsterZones(client);
            case 9 -> holsterPhotos(client);
            case 10 -> backpackBasics(client);
            case 11 -> backpackStacksAndDrop(client);
            case 12 -> backpackCraftingArmorPages(client);
            case 13 -> radialEquip(client);
            case 14 -> radialWithBackpack(client);
            case 15 -> fullInventory(client);
            case 16 -> photos(client);
            case 17 -> handItemStore(client);
            case 18 -> persistenceAndMisc(client);
            case 19 -> pickupOrder(client);
            case 20 -> bowHands(client);
            case 21 -> tableCrafting(client);
            case 22 -> furnaces(client);
            case 23 -> throwing(client);
            case 24 -> dragAndHalf(client);
            case 25 -> bowAimMarker(client);
            default -> { }
        }
    }

    // ------------------------------------------------------------------ coldres

    private static void setupHolsterInventory(Minecraft client) {
        Inventory inv = client.player.getInventory();
        if (at(1)) {
            section("Coldres");
            client.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
            onServer(client, player -> {
                player.setGameMode(GameType.SURVIVAL);
                Inventory pinv = player.getInventory();
                pinv.clearContent();
                pinv.setItem(0, new ItemStack(Items.STONE_PICKAXE));
                ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
                sword.set(DataComponents.CUSTOM_NAME, Component.literal("Excalibur"));
                pinv.setItem(4, sword);
                pinv.setItem(2, new ItemStack(Items.BREAD, 8));
                pinv.setItem(40, new ItemStack(Items.TORCH, 16));
            });
            SimulatedVr.setEnabled(true);
            client.player.setYRot(0);
            client.player.setXRot(0);
            handAway(0);
            handAway(1);
        }
        if (at(20)) {
            inv.setSelectedSlot(4);
        }
        if (at(30)) {
            info("inventário: mão " + describe(client.player.getMainHandItem()) + ", esquerda "
                    + describe(client.player.getOffhandItem()));
            next();
        }
    }

    private static void holsterStoreSword(Minecraft client) { // GUARDAR: espada na mão + grip no ombro direito
        Inventory inv = client.player.getInventory();
        HolsterManager hm = holsters();
        if (at(1)) {
            handAt(0, Holster.RIGHT_SHOULDER);
        }
        if (at(5)) {
            check(hm.zoneOf(0) == Holster.RIGHT_SHOULDER, "mão no ombro direito: está na zona do coldre");
            check(grip(client, 0, true), "grip no ombro direito com a espada na mão: guardou");
            SimulatedVr.setGrip(0, false);
        }
        if (at(25)) {
            swordSlot = find(inv, Items.DIAMOND_SWORD);
            HolsterStorage.Binding b = hm.binding(Holster.RIGHT_SHOULDER);
            info("depois de guardar: mão " + describe(client.player.getMainHandItem()) + ", espada no espaço " + swordSlot);
            check(client.player.getMainHandItem().isEmpty(), "guardar: a espada saiu da mão");
            check(swordSlot >= 9 && swordSlot < 36, "guardar: a espada foi para o inventário (fora da hotbar)");
            check(b != null && "minecraft:diamond_sword".equals(b.item()) && "Excalibur".equals(b.name()),
                    "guardar: o coldre do ombro direito lembra a espada \"Excalibur\"");
            handAway(0);
            next();
        }
    }

    private static void holsterDrawSword(Minecraft client) { // SACAR com a picareta na mão; soltar devolve tudo
        Inventory inv = client.player.getInventory();
        HolsterManager hm = holsters();
        if (at(1)) {
            inv.setSelectedSlot(0);
        }
        if (at(5)) {
            handAt(0, Holster.RIGHT_SHOULDER);
        }
        if (at(8)) {
            check(grip(client, 0, true) && hm.isDrawing(0), "grip no ombro direito: sacou a espada");
        }
        if (at(25)) {
            ItemStack hand = client.player.getMainHandItem();
            info("sacada: mão " + describe(hand) + "; picareta no espaço " + find(inv, Items.STONE_PICKAXE));
            check(hand.is(Items.DIAMOND_SWORD) && "Excalibur".equals(hand.getHoverName().getString()),
                    "sacar: a espada está na mão (no espaço selecionado da hotbar)");
            check(find(inv, Items.STONE_PICKAXE) == swordSlot, "sacar: a picareta foi para o lugar da espada");
            check(hm.isDrawing(0), "sacar: segurando o grip, a espada continua na mão");
            SimulatedVr.setHand(0, client.player.getEyePosition().add(0.3, -0.3, 0.5), new Vec3(0, 0, 1));
        }
        if (at(40)) {
            check(hm.isDrawing(0) && client.player.getMainHandItem().is(Items.DIAMOND_SWORD),
                    "sacar: com a mão longe do ombro (grip ainda apertado), a espada continua na mão");
            SimulatedVr.setGrip(0, false);
        }
        if (at(60)) {
            info("soltou: mão " + describe(client.player.getMainHandItem()) + ", espada no espaço " + find(inv, Items.DIAMOND_SWORD));
            check(!hm.isDrawing(0), "soltar o grip: a espada voltou para o coldre");
            check(client.player.getMainHandItem().is(Items.STONE_PICKAXE) && inv.getSelectedSlot() == 0,
                    "soltar o grip: a mão voltou a segurar a picareta");
            check(find(inv, Items.DIAMOND_SWORD) == swordSlot, "soltar o grip: a espada voltou para o mesmo lugar");
            handAway(0);
            next();
        }
    }

    private static void holsterStoreTorches(Minecraft client) { // GUARDAR com a mão esquerda: tochas no quadril esquerdo
        Inventory inv = client.player.getInventory();
        HolsterManager hm = holsters();
        if (at(1)) {
            handAt(1, Holster.LEFT_HIP);
        }
        if (at(5)) {
            check(hm.zoneOf(1) == Holster.LEFT_HIP, "mão esquerda no quadril esquerdo: está na zona do coldre");
            check(grip(client, 1, true), "grip no quadril esquerdo com as tochas na mão esquerda: guardou");
            SimulatedVr.setGrip(1, false);
        }
        if (at(25)) {
            HolsterStorage.Binding b = hm.binding(Holster.LEFT_HIP);
            int torches = find(inv, Items.TORCH);
            info("tochas guardadas no espaço " + torches + "; mão esquerda " + describe(client.player.getOffhandItem()));
            check(client.player.getOffhandItem().isEmpty() && torches >= 9 && torches < 36,
                    "guardar (mão esquerda): as tochas saíram da mão e foram para o inventário");
            check(b != null && "minecraft:torch".equals(b.item()), "guardar (mão esquerda): o quadril esquerdo lembra as tochas");
            handAway(1);
            next();
        }
    }

    private static void holsterDrawTorchesAndPlace(Minecraft client) { // SACAR na mão esquerda e colocar uma tocha
        Inventory inv = client.player.getInventory();
        if (at(1)) {
            handAt(1, Holster.LEFT_HIP);
        }
        if (at(5)) {
            check(grip(client, 1, true), "grip no quadril esquerdo: sacou as tochas");
        }
        if (at(20)) {
            check(client.player.getOffhandItem().is(Items.TORCH), "sacar (mão esquerda): as tochas estão na mão esquerda");
            Vec3 feet = client.player.position();
            torchAt = BlockPos.containing(feet.x, feet.y, feet.z + 2);
            Vec3 hand = client.player.getEyePosition().add(-0.2, -0.5, 0.3);
            Vec3 target = new Vec3(torchAt.getX() + 0.5, feet.y - 0.001, torchAt.getZ() + 0.5);
            SimulatedVr.setHand(1, hand, target.subtract(hand));
        }
        if (at(22)) {
            check(!hands().onTrigger(client, 1), "gatilho esquerdo longe da mochila/radial não é do menu (usa o item)");
            OffhandUse.use(client); // o mesmo que o gatilho esquerdo faz em seguida
            info("gatilho esquerdo: " + OffhandUse.debugLast());
        }
        if (at(40)) {
            boolean placed = client.level.getBlockState(torchAt).is(Blocks.TORCH);
            info("tocha em " + torchAt.toShortString() + ": " + client.level.getBlockState(torchAt).getBlock()
                    + "; mão esquerda " + describe(client.player.getOffhandItem()));
            check(placed, "gatilho esquerdo: colocou a tocha onde a mão esquerda aponta");
            check(client.player.getOffhandItem().getCount() == 15, "gatilho esquerdo: gastou uma tocha (16 -> 15)");
            SimulatedVr.setGrip(1, false);
        }
        if (at(60)) {
            int torches = find(inv, Items.TORCH);
            check(client.player.getOffhandItem().isEmpty() && torches >= 9 && torches < 36
                    && inv.getItem(torches).getCount() == 15, "soltar o grip esquerdo: as tochas (15) voltaram para o coldre");
            handAway(1);
            next();
        }
    }

    private static void holsterStoreBread(Minecraft client) { // GUARDAR o pão no quadril direito
        Inventory inv = client.player.getInventory();
        if (at(1)) {
            inv.setSelectedSlot(2);
        }
        if (at(5)) {
            handAt(0, Holster.RIGHT_HIP);
        }
        if (at(8)) {
            check(grip(client, 0, true), "grip no quadril direito com o pão na mão: guardou");
            SimulatedVr.setGrip(0, false);
        }
        if (at(25)) {
            HolsterStorage.Binding b = holsters().binding(Holster.RIGHT_HIP);
            check(b != null && "minecraft:bread".equals(b.item()) && client.player.getMainHandItem().isEmpty(),
                    "guardar: o pão saiu da mão e o quadril direito lembra dele");
            handAway(0);
            inv.setSelectedSlot(0);
            next();
        }
    }

    /** Pontos de borda de cada zona (fora da antiga esfera de 14-16 cm, dentro do novo elipsoide). */
    private static final Object[][] WIDE = {
            {Holster.RIGHT_SHOULDER, 0.0, 0.19, 0.0, "acima do ombro (19 cm)"},
            {Holster.RIGHT_SHOULDER, 0.0, 0.0, -0.16, "atrás do ombro (16 cm)"},
            {Holster.RIGHT_SHOULDER, 0.14, 0.0, 0.0, "por fora do ombro (14 cm)"},
            {Holster.RIGHT_SHOULDER, 0.08, 0.12, -0.09, "na diagonal, por cima e por trás"},
            {Holster.LEFT_SHOULDER, 0.0, 0.17, -0.08, "mochila: por cima e por trás do ombro esquerdo"},
            {Holster.LEFT_SHOULDER, -0.13, 0.05, 0.0, "mochila: por fora do ombro esquerdo"},
            {Holster.RIGHT_HIP, 0.0, -0.15, 0.0, "abaixo do quadril direito (15 cm)"},
            {Holster.RIGHT_HIP, 0.0, 0.0, 0.13, "à frente do quadril direito (13 cm)"},
            {Holster.RIGHT_HIP, 0.12, 0.0, 0.0, "por fora do quadril direito (12 cm)"},
            {Holster.LEFT_HIP, -0.12, 0.0, 0.0, "por fora do quadril esquerdo (12 cm)"},
            {Holster.LEFT_HIP, 0.0, 0.0, -0.14, "atrás do quadril esquerdo (14 cm)"},
    };

    private static void holsterZones(Minecraft client) { // ZONAS amplas, sem misturar; virar a cabeça não leva
        HolsterManager hm = holsters();
        Holster[] all = Holster.values();
        int i = (stepTicks - 1) / 4;
        if (at(1)) {
            section("Zonas dos coldres e da mochila");
        }
        if (i < all.length && (stepTicks - 1) % 4 == 0) {
            handAt(0, all[i]);
        }
        if (i < all.length && (stepTicks - 1) % 4 == 3) {
            check(hm.zoneOf(0) == all[i], "zona: mão no centro do " + all[i] + " -> " + hm.zoneOf(0));
            handAway(0);
        }
        int base = all.length * 4 + 1;
        int j = (stepTicks - base) / 4;
        if (stepTicks >= base && j < WIDE.length) {
            Object[] w = WIDE[j];
            Holster h = (Holster) w[0];
            if ((stepTicks - base) % 4 == 0) {
                handNear(0, h, (double) w[1], (double) w[2], (double) w[3]);
            }
            if ((stepTicks - base) % 4 == 3) {
                check(hm.zoneOf(0) == h, "zona ampla: " + w[4] + " -> " + hm.zoneOf(0));
                handAway(0);
            }
        }
        int end = base + WIDE.length * 4;
        if (at(end)) {
            SimulatedVr.setHand(0, client.player.getEyePosition().add(0, -0.3, 0.6), new Vec3(0, 0, 1));
        }
        if (at(end + 3)) {
            check(hm.zoneOf(0) == null, "zona: mão na frente do corpo -> fora de tudo");
            BodyFrame.Frame before = hm.body().compute();
            client.player.setYRot(20);
            BodyFrame.Frame after = hm.body().compute();
            double moved = before.anchor(Holster.RIGHT_HIP, HandsOnConfig.get()).distanceTo(after.anchor(Holster.RIGHT_HIP, HandsOnConfig.get()));
            info(String.format(Locale.ROOT, "virando a cabeça 20°: o quadril direito andou %.3f m", moved));
            check(moved < 0.06, "virar só a cabeça um pouco não leva os coldres junto");
            client.player.setYRot(0);
            handAway(0);
            next();
        }
    }

    private static void holsterPhotos(Minecraft client) {
        if (at(1)) {
            client.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
            client.player.setXRot(0);
        }
        if (at(25)) {
            screenshot(client, "01-coldres-frente");
            client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        }
        if (at(50)) {
            screenshot(client, "02-coldres-e-mochila-costas");
            client.options.setCameraType(CameraType.FIRST_PERSON);
            client.player.setXRot(70);
        }
        if (at(75)) {
            screenshot(client, "03-olhando-para-baixo");
            handAt(0, Holster.RIGHT_HIP);
        }
        if (at(95)) {
            screenshot(client, "04-mao-no-quadril");
            handAway(0);
            client.player.setXRot(0);
            next();
        }
    }

    // ------------------------------------------------------------------ mochila

    /** Inventário da mochila: espada na mão (hotbar 0), tocha na esquerda, itens nas 2 primeiras páginas. */
    private static void setupBackpackInventory(Minecraft client) {
        onServer(client, player -> {
            Inventory pinv = player.getInventory();
            pinv.clearContent();
            pinv.setItem(0, new ItemStack(Items.STONE_SWORD));
            pinv.setItem(2, new ItemStack(Items.STONE_PICKAXE));
            pinv.setItem(4, new ItemStack(Items.BREAD, 8));
            pinv.setItem(40, new ItemStack(Items.TORCH, 16));
            pinv.setItem(9, new ItemStack(Items.BOW));
            pinv.setItem(10, new ItemStack(Items.SAND, 32));
            pinv.setItem(11, new ItemStack(Items.OAK_PLANKS, 16));
            pinv.setItem(12, new ItemStack(Items.IRON_HELMET));
            pinv.setItem(13, new ItemStack(Items.SAND, 20));
            pinv.setItem(14, new ItemStack(Items.DIRT, 64));
            pinv.setItem(18, new ItemStack(Items.ARROW, 16));
        });
    }

    private static void backpackBasics(Minecraft client) {
        LocalPlayer p = client.player;
        Inventory inv = p.getInventory();
        HandsController hc = hands();
        Backpack bp = hc.backpack();
        if (at(1)) {
            section("Mochila: abrir, pegar e soltar");
            setupBackpackInventory(client);
        }
        if (at(15)) {
            inv.setSelectedSlot(0);
            handAt(1, Holster.LEFT_SHOULDER);
        }
        if (at(19)) {
            check(holsters().zoneOf(1) == Holster.LEFT_SHOULDER, "mão esquerda no ombro esquerdo: zona da mochila");
            check(openBackpack(client, 1), "grip no ombro esquerdo: a mochila abriu na mão esquerda");
            check(bp.holder() == 1 && hc.isHandHidden(1) && !hc.isHandHidden(0),
                    "mochila na mão esquerda: a tocha some (só a mochila aparece nessa mão)");
            check(HandsOnVR.blockSwing(), "mochila aberta: o golpe com o braço do Vivecraft fica desligado");
            check(hc.onTrigger(client, 1), "gatilho da mão que segura a mochila não usa o item escondido");
        }
        if (at(22)) {
            pointBackpackSlot(0, InvOps.MAIN_START); // o arco
        }
        if (at(25)) {
            Backpack.Element h = bp.hover(0);
            check(h != null && h.handler() == InvOps.MAIN_START, "mão direita sobre o 1º espaço: destacado (" + h + ")");
            check(grip(client, 0, true), "grip no arco: pegou");
        }
        if (at(32)) {
            info("pegou: mão " + describe(p.getMainHandItem()) + "; espaço 9 tem " + describe(slot(p, 9)));
            check(p.getMainHandItem().is(Items.BOW), "o arco está na mão direita (a espada sumiu)");
            check(hc.displayStack(p, 9).isEmpty(), "o espaço do arco aparece vazio na mochila (o arco está na mão)");
            SimulatedVr.setGrip(1, false); // solta a mochila, continua com o arco
        }
        if (at(38)) {
            check(!bp.isOpen() && !hc.isHandHidden(1), "soltar o grip esquerdo: a mochila some e a tocha volta");
            check(p.getMainHandItem().is(Items.BOW) && p.getOffhandItem().is(Items.TORCH),
                    "sem a mochila, o arco continua na mão direita (e a tocha na esquerda)");
            pointAway(client, 0);
            SimulatedVr.setGrip(0, false);
        }
        if (at(45)) {
            check(p.getMainHandItem().is(Items.STONE_SWORD) && slot(p, 9).is(Items.BOW),
                    "soltar o grip direito: o arco volta para a mochila e a espada volta para a mão");
            next();
        }
    }

    private static void backpackStacksAndDrop(Minecraft client) {
        LocalPlayer p = client.player;
        HandsController hc = hands();
        Backpack bp = hc.backpack();
        if (at(1)) {
            section("Mochila: mover, juntar, descartar");
            check(openBackpack(client, 1), "abriu a mochila de novo");
        }
        if (at(4)) {
            pointBackpackSlot(0, 10); // 32 areia
        }
        if (at(6)) {
            grip(client, 0, true);
        }
        if (at(9)) {
            check(p.getMainHandItem().is(Items.SAND) && p.getMainHandItem().getCount() == 32, "pegou 32 de areia");
            pointBackpackSlot(0, 13); // 20 areia
        }
        if (at(12)) {
            SimulatedVr.setGrip(0, false);
        }
        if (at(20)) {
            info("juntou: espaço 13 = " + describe(slot(p, 13)) + ", espaço 10 = " + describe(slot(p, 10)));
            check(slot(p, 13).is(Items.SAND) && slot(p, 13).getCount() == 52 && slot(p, 10).isEmpty(),
                    "soltar sobre a pilha igual: juntou (32 + 20 = 52)");
            check(p.getMainHandItem().is(Items.STONE_SWORD), "depois de colocar, a espada volta para a mão");
            pointBackpackSlot(0, 9); // o arco
        }
        if (at(22)) {
            grip(client, 0, true);
            pointBackpackSlot(0, 12); // o capacete: troca de lugar
        }
        if (at(25)) {
            SimulatedVr.setGrip(0, false);
        }
        if (at(32)) {
            check(slot(p, 12).is(Items.BOW) && slot(p, 9).is(Items.IRON_HELMET),
                    "soltar sobre um item diferente: os dois trocam de lugar (arco <-> capacete)");
            pointBackpackSlot(0, 13);
        }
        if (at(34)) {
            grip(client, 0, true); // 52 areia
            pointBackpack(0, bp.element(Backpack.Kind.DROP));
        }
        if (at(37)) {
            check(bp.hover(0) != null && bp.hover(0).kind() == Backpack.Kind.DROP, "mão sobre a zona de descarte: destacada");
            SimulatedVr.setGrip(0, false);
        }
        if (at(50)) {
            int dropped = droppedNearby(client, Items.SAND);
            info("areia no chão: " + dropped);
            check(slot(p, 13).isEmpty() && dropped == 52, "soltar na zona de descarte: a pilha inteira caiu no chão (52)");
            check(p.getMainHandItem().is(Items.STONE_SWORD), "descartou e a mão voltou para a espada");
            clearDrops(client);
            pointBackpackSlot(0, 14); // 64 terra
        }
        if (at(53)) {
            grip(client, 0, true);
            pointBackpack(0, bp.element(Backpack.Kind.DROP));
        }
        if (at(55) || at(57) || at(59)) {
            check(hc.onTrigger(client, 0), "gatilho com o grip na zona de descarte: um item (" + (stepTicks - 53) / 2 + "/3)");
        }
        if (at(66)) {
            check(p.getMainHandItem().is(Items.DIRT) && p.getMainHandItem().getCount() == 61,
                    "três gatilhos: soltou 3 de terra, 61 continuam na mão (" + describe(p.getMainHandItem()) + ")");
            check(droppedNearby(client, Items.DIRT) == 3, "três itens de terra no chão");
            pointAway(client, 0);
            SimulatedVr.setGrip(0, false);
        }
        if (at(74)) {
            check(slot(p, 14).is(Items.DIRT) && slot(p, 14).getCount() == 61, "soltar longe: as 61 de terra voltaram para o lugar");
            clearDrops(client);
            SimulatedVr.setGrip(1, false);
        }
        if (at(80)) {
            next();
        }
    }

    private static void backpackCraftingArmorPages(Minecraft client) {
        LocalPlayer p = client.player;
        HandsController hc = hands();
        Backpack bp = hc.backpack();
        if (at(1)) {
            section("Mochila: crafting, armadura e páginas");
            openBackpack(client, 1);
            pointBackpackSlot(0, 11); // 16 tábuas
        }
        if (at(3)) {
            grip(client, 0, true);
        }
        for (int k = 0; k < 4; k++) { // uma tábua em cada espaço do crafting (gatilho com o grip)
            if (at(6 + k * 4)) {
                pointBackpackSlot(0, InvOps.CRAFT_START + k);
            }
            if (at(8 + k * 4)) {
                check(hc.onTrigger(client, 0), "gatilho no crafting " + (k + 1) + ": colocou uma tábua");
            }
        }
        if (at(24)) {
            boolean oneEach = true;
            for (int k = 0; k < 4; k++) {
                oneEach &= slot(p, InvOps.CRAFT_START + k).is(Items.OAK_PLANKS) && slot(p, InvOps.CRAFT_START + k).getCount() == 1;
            }
            check(oneEach, "crafting 2x2: uma tábua em cada espaço");
            check(p.getMainHandItem().getCount() == 12, "na mão ficaram 12 tábuas");
            pointAway(client, 0);
            SimulatedVr.setGrip(0, false);
        }
        if (at(34)) {
            check(slot(p, 11).getCount() == 12 && p.getMainHandItem().is(Items.STONE_SWORD), "soltar longe: as 12 tábuas voltaram");
            check(slot(p, InvOps.RESULT).is(Items.CRAFTING_TABLE), "o resultado mostra a mesa de trabalho");
            pointBackpack(0, bp.element(Backpack.Kind.CRAFT_OUT));
        }
        if (at(36)) {
            craftSelected = p.getInventory().getSelectedSlot();
            grip(client, 0, true); // grip sozinho no resultado: fabrica uma vez
        }
        if (at(37)) {
            check(hc.resultHeld(0) == HandsController.ResultSource.BACKPACK, "grip no resultado: a mão pegou o resultado");
        }
        if (at(46)) {
            GrabSystem.Grab g = hc.grabs().get(0);
            check(!hc.isCrafting() && p.getMainHandItem().is(Items.CRAFTING_TABLE) && slot(p, InvOps.HOTBAR_START + craftSelected).is(Items.STONE_SWORD),
                    "grip no resultado: fabricou uma vez e a mesa de trabalho foi para a mão direita (a espada ficou na hotbar)");
            check(g != null && g.origin() == GrabSystem.Origin.HAND, "com o grip ainda apertado, a mão segura o resultado (soltar decide)");
            int pulses = SimulatedVr.pulseCount(0);
            check(hc.onTrigger(client, 0) && !hc.isCrafting() && SimulatedVr.pulseCount(0) > pulses && hc.grabs().get(0) == g,
                    "gatilho no resultado com a grade vazia: nada mais a fabricar (vibra e a mão continua segurando)");
            boolean emptyGrid = true;
            for (int k = 0; k < 4; k++) {
                emptyGrid &= slot(p, InvOps.CRAFT_START + k).isEmpty();
            }
            check(emptyGrid, "o crafting gastou as 4 tábuas");
            pointAway(client, 0);
            SimulatedVr.setGrip(0, false);
            grip(client, 0, false);
            check(p.getMainHandItem().is(Items.CRAFTING_TABLE), "soltar longe: a mesa de trabalho continua na mão");
            p.getInventory().setSelectedSlot(craftSelected); // de volta à espada (o resto do teste conta com ela)
            pointBackpackSlot(0, 9); // capacete (trocado de lugar antes)
        }
        if (at(48)) {
            grip(client, 0, true);
            pointBackpackSlot(0, InvOps.ARMOR_START); // espaço do capacete
        }
        if (at(51)) {
            SimulatedVr.setGrip(0, false);
        }
        if (at(60)) {
            check(p.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET) && slot(p, 9).isEmpty(),
                    "soltar o capacete no espaço da cabeça: vestiu");
            pointBackpack(0, bp.element(Backpack.Kind.NEXT));
        }
        if (at(62)) {
            grip(client, 0, true);
            SimulatedVr.setGrip(0, false);
            grip(client, 0, false);
            check(bp.page() == 1, "grip na seta: página 2");
            check(bp.elementFor(18) != null, "página 2 mostra os espaços 18-26");
        }
        if (at(64)) {
            pointBackpackSlot(0, 18); // flechas
        }
        if (at(66)) {
            grip(client, 0, true);
            pointBackpack(0, bp.element(Backpack.Kind.PREV)); // parado sobre a seta com o item
        }
        if (at(82)) {
            check(bp.page() == 0, "segurando o item parado sobre a seta: voltou para a página 1");
            pointBackpackSlot(0, 17);
        }
        if (at(85)) {
            SimulatedVr.setGrip(0, false);
        }
        if (at(92)) {
            check(slot(p, 17).is(Items.ARROW) && slot(p, 18).isEmpty(), "levou as flechas da página 2 para a página 1");
            pointBackpackSlot(0, 11);
        }
        if (at(94)) { // tábua no crafting e fecha: volta para o inventário
            grip(client, 0, true);
            pointBackpackSlot(0, InvOps.CRAFT_START);
        }
        if (at(97)) {
            hc.onTrigger(client, 0);
            pointAway(client, 0);
            SimulatedVr.setGrip(0, false);
        }
        if (at(103)) {
            check(slot(p, InvOps.CRAFT_START).getCount() == 1, "uma tábua ficou no crafting");
            SimulatedVr.setGrip(1, false);
        }
        if (at(112)) {
            check(!bp.isOpen() && slot(p, InvOps.CRAFT_START).isEmpty() && total(p.getInventory(), Items.OAK_PLANKS) == 12,
                    "fechar a mochila: a sobra do crafting voltou para o inventário (12 tábuas)");
        }
        // Grip na armadura vestida: ela vem para a mão (#15). A mão tem a espada, que não pode ir para a cabeça: a mão
        // principal usa um espaço vazio da hotbar enquanto segura o capacete.
        if (at(115)) {
            section("Mochila: grip na armadura vestida e no crafting");
            check(openBackpack(client, 1), "mochila aberta de novo");
            armorSelected = p.getInventory().getSelectedSlot();
            pointBackpackSlot(0, InvOps.ARMOR_START);
        }
        if (at(118)) {
            check(grip(client, 0, true) && p.getMainHandItem().is(Items.IRON_HELMET)
                            && p.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
                            && slot(p, InvOps.HOTBAR_START + armorSelected).is(Items.STONE_SWORD),
                    "grip no capacete vestido: ele vem para a mão (a espada espera no espaço dela)");
            check(hc.displayStack(p, InvOps.ARMOR_START).isEmpty(), "o espaço da cabeça aparece vazio enquanto a mão segura");
            pointAway(client, 0);
        }
        if (at(120)) {
            SimulatedVr.setGrip(0, false);
        }
        if (at(127)) {
            check(p.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET) && p.getMainHandItem().is(Items.STONE_SWORD)
                            && p.getInventory().getSelectedSlot() == armorSelected,
                    "soltar longe: o capacete volta para a cabeça e a espada para a mão");
            armorTarget = InvOps.firstEmpty(p, InvOps.MAIN_START, InvOps.MAIN_START + 9);
            info("espaço vazio da página 1 para o capacete: " + armorTarget);
            pointBackpackSlot(0, InvOps.ARMOR_START);
        }
        if (at(129)) {
            grip(client, 0, true);
            pointBackpackSlot(0, armorTarget);
        }
        if (at(131)) {
            SimulatedVr.setGrip(0, false);
        }
        if (at(138)) {
            check(slot(p, armorTarget).is(Items.IRON_HELMET) && p.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
                            && p.getMainHandItem().is(Items.STONE_SWORD),
                    "soltar o capacete num espaço vazio da mochila: tirou e guardou ali");
            pointBackpackSlot(0, armorTarget);
        }
        if (at(140)) {
            grip(client, 0, true);
            pointBackpackSlot(0, InvOps.ARMOR_START);
        }
        if (at(142)) {
            SimulatedVr.setGrip(0, false);
        }
        if (at(149)) {
            check(p.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET), "e de volta para a cabeça");
            pointBackpackSlot(0, InvOps.ARMOR_START);
        }
        if (at(151)) {
            grip(client, 0, true);
            pointBackpackSlot(0, 11); // as tábuas: não podem ir para a cabeça
            armorPulses = SimulatedVr.pulseCount(0);
        }
        if (at(153)) {
            SimulatedVr.setGrip(0, false);
        }
        if (at(160)) {
            check(p.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET) && slot(p, 11).is(Items.OAK_PLANKS)
                            && slot(p, 11).getCount() == 12 && SimulatedVr.pulseCount(0) > armorPulses,
                    "soltar o capacete sobre as tábuas: recusado com vibração, nada saiu do lugar");
            pointBackpackSlot(0, 11);
        }
        // Grip no crafting: a tábua vem para a mão (antes ia direto para o inventário).
        if (at(162)) {
            grip(client, 0, true);
            pointBackpackSlot(0, InvOps.CRAFT_START);
        }
        if (at(165)) {
            hc.onTrigger(client, 0);
            pointAway(client, 0);
            SimulatedVr.setGrip(0, false);
        }
        if (at(172)) {
            check(slot(p, InvOps.CRAFT_START).getCount() == 1 && slot(p, 11).getCount() == 11, "uma tábua no crafting");
            pointBackpackSlot(0, InvOps.CRAFT_START);
        }
        if (at(174)) {
            check(grip(client, 0, true) && p.getMainHandItem().is(Items.OAK_PLANKS) && p.getMainHandItem().getCount() == 1,
                    "grip no crafting: a tábua vem para a mão");
            pointBackpackSlot(0, 11);
        }
        if (at(176)) {
            SimulatedVr.setGrip(0, false);
        }
        if (at(183)) {
            check(slot(p, InvOps.CRAFT_START).isEmpty() && slot(p, 11).getCount() == 12 && p.getMainHandItem().is(Items.STONE_SWORD),
                    "soltar sobre as tábuas: juntou (12 de novo)");
            SimulatedVr.setGrip(1, false);
        }
        if (at(192)) {
            check(!bp.isOpen(), "mochila fechada");
            next();
        }
    }

    private static int armorSelected, armorTarget, armorPulses;

    // ------------------------------------------------------------------ radial

    private static void handInFront(Minecraft client, int hand) {
        Vec3 eye = client.player.getEyePosition();
        SimulatedVr.setHand(hand, eye.add(hand == 0 ? -0.12 : 0.12, -0.18, 0.38), new Vec3(0, 0, 1));
    }

    private static void radialEquip(Minecraft client) {
        LocalPlayer p = client.player;
        Inventory inv = p.getInventory();
        HandsController hc = hands();
        RadialMenu radial = hc.radial();
        if (at(1)) {
            section("Menu radial");
            inv.setSelectedSlot(0);
            handInFront(client, 0);
            handInFront(client, 1);
        }
        if (at(3)) {
            check(menuButton(client, 0, true) && radial.isOpen() && radial.opener() == 0, "B: o radial abriu na mão direita");
            pointRadial(0, 2);
        }
        if (at(6)) {
            check(radial.hover(0) == 2, "mão sobre o espaço 3 do radial: destacado");
            int pulses = SimulatedVr.pulseCount(0);
            menuButton(client, 0, false);
            check(!radial.isOpen() && inv.getSelectedSlot() == 2 && p.getMainHandItem().is(Items.STONE_PICKAXE),
                    "soltar o B: a picareta fica na mão direita (sem grip)");
            check(SimulatedVr.pulseCount(0) > pulses, "escolher no radial vibra");
        }
        if (at(10)) {
            menuButton(client, 0, true);
            pointRadial(0, RadialMenu.CENTER);
        }
        if (at(13)) {
            menuButton(client, 0, false);
            emptySelected = inv.getSelectedSlot();
            check(p.getMainHandItem().isEmpty(), "radial: mão vazia na direita (foi para um espaço vazio da hotbar " + (emptySelected + 1) + ")");
        }
        if (at(16)) {
            check(menuButton(client, 1, true) && radial.opener() == 1, "X: o radial abriu na mão esquerda");
            pointRadial(1, 0); // a espada
        }
        if (at(19)) {
            menuButton(client, 1, false);
            check(p.getOffhandItem().is(Items.STONE_SWORD) && hc.offSource() == 0,
                    "radial esquerdo: a espada foi para a mão esquerda (lembra que veio do espaço 1)");
            check(hc.displayStack(p, InvOps.HOTBAR_START).is(Items.STONE_SWORD),
                    "o radial mostra a espada no espaço 1 (marcado como mão esquerda)");
        }
        if (at(22)) {
            menuButton(client, 1, true);
            pointRadial(1, 4); // o pão
        }
        if (at(25)) {
            menuButton(client, 1, false);
            check(p.getOffhandItem().is(Items.BREAD) && slot(p, InvOps.HOTBAR_START).is(Items.STONE_SWORD) && hc.offSource() == 4,
                    "trocar o item da esquerda pelo radial: o pão vem, a espada volta para o espaço 1");
        }
        if (at(28)) {
            menuButton(client, 1, true);
            pointRadial(1, RadialMenu.CENTER);
        }
        if (at(31)) {
            menuButton(client, 1, false);
            check(p.getOffhandItem().isEmpty() && slot(p, InvOps.HOTBAR_START + 4).is(Items.BREAD)
                            && find(inv, Items.TORCH) >= 0 && hc.offSource() == -1,
                    "radial esquerdo, mão vazia: o pão volta para o espaço 5 e a tocha vai para o inventário");
        }
        if (at(34)) { // passar a picareta da direita para a esquerda e voltar
            menuButton(client, 1, true);
            pointRadial(1, 2);
        }
        if (at(37)) {
            menuButton(client, 1, false);
            check(p.getOffhandItem().is(Items.STONE_PICKAXE), "radial esquerdo no espaço 3: a picareta vai para a esquerda");
            menuButton(client, 0, true);
            pointRadial(0, 2);
        }
        if (at(40)) {
            menuButton(client, 0, false);
            check(p.getMainHandItem().is(Items.STONE_PICKAXE) && p.getOffhandItem().isEmpty() && hc.offSource() == -1,
                    "radial direito no mesmo espaço: a picareta passa da esquerda para a direita");
        }
        // O radial acompanha o jogador: abrir, andar 1,5 bloco e ainda escolher (o VR de mentira põe a área de jogo no
        // jogador, como o Vivecraft faz ao andar com o analógico).
        if (at(43)) {
            check(menuButton(client, 0, true) && radial.isOpen(), "B de novo: o radial abriu");
            followStart = p.position();
            followPanel = radial.panel().origin();
            p.setPos(p.getX() + 1.5, p.getY(), p.getZ());
        }
        if (at(46)) {
            Vec3 moved = p.position().subtract(followStart);
            Vec3 panelMoved = radial.panel().origin().subtract(followPanel);
            check(moved.length() > 1.0 && panelMoved.distanceTo(moved) < 1.0e-3,
                    "andando com o radial aberto, ele vai junto (o jogador andou " + fmt(moved.length())
                            + " m, o radial " + fmt(panelMoved.length()) + " m)");
            handInFront(client, 0);
            pointRadial(0, 6);
        }
        if (at(49)) {
            check(radial.hover(0) == 6, "depois de andar, a mão sobre o espaço 7 ainda o destaca");
            pointRadial(0, 2);
        }
        if (at(52)) {
            menuButton(client, 0, false);
            check(!radial.isOpen() && inv.getSelectedSlot() == 2, "e escolher continua funcionando (a picareta, espaço 3)");
            p.setPos(followStart.x, followStart.y, followStart.z);
            next();
        }
    }

    private static Vec3 followStart, followPanel;

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private static void radialWithBackpack(Minecraft client) {
        LocalPlayer p = client.player;
        Inventory inv = p.getInventory();
        HandsController hc = hands();
        if (at(1)) {
            section("Radial + mochila: levar itens para a hotbar e de volta");
            inv.setSelectedSlot(0);
            check(openBackpack(client, 1), "mochila aberta na mão esquerda");
        }
        if (at(3)) {
            pointBackpackSlot(0, 12); // o arco
        }
        if (at(5)) {
            grip(client, 0, true);
            check(p.getMainHandItem().is(Items.BOW), "pegou o arco da mochila");
            check(menuButton(client, 0, true) && hc.radial().isOpen(), "com o arco no grip, B abre o radial");
            pointRadial(0, 6);
        }
        if (at(8)) {
            SimulatedVr.setGrip(0, false); // solta o arco sobre o espaço 7 do radial
        }
        if (at(11)) {
            menuButton(client, 0, false);
            check(slot(p, InvOps.HOTBAR_START + 6).is(Items.BOW) && slot(p, 12).isEmpty(),
                    "soltar o arco sobre o espaço 7 do radial: o arco foi para a hotbar");
            check(inv.getSelectedSlot() == 0 && p.getMainHandItem().is(Items.STONE_SWORD),
                    "soltar o B depois de pôr na hotbar não troca o item da mão (a espada continua)");
        }
        if (at(14)) {
            handInFront(client, 0);
            menuButton(client, 0, true);
            pointRadial(0, 6);
        }
        if (at(17)) {
            check(grip(client, 0, true) && p.getMainHandItem().is(Items.BOW), "grip no espaço 7 do radial: pegou o arco");
            menuButton(client, 0, false);
            check(!hc.radial().isOpen() && p.getMainHandItem().is(Items.BOW), "soltar o B com o arco no grip: radial fecha, arco na mão");
            pointBackpackSlot(0, 12);
        }
        if (at(20)) {
            SimulatedVr.setGrip(0, false);
        }
        if (at(27)) {
            check(slot(p, 12).is(Items.BOW) && slot(p, InvOps.HOTBAR_START + 6).isEmpty() && inv.getSelectedSlot() == 0,
                    "soltar o arco na mochila: saiu da hotbar e voltou para a mochila");
            SimulatedVr.setGrip(1, false);
        }
        if (at(33)) {
            next();
        }
    }

    // ------------------------------------------------------------------ inventário cheio

    private static void fullInventory(Minecraft client) {
        LocalPlayer p = client.player;
        Inventory inv = p.getInventory();
        HandsController hc = hands();
        if (at(1)) {
            section("Inventário 100% cheio");
            onServer(client, player -> {
                Inventory pinv = player.getInventory();
                pinv.clearContent();
                for (int i = 0; i < 36; i++) {
                    pinv.setItem(i, new ItemStack(Items.DIRT, 64));
                }
                pinv.setItem(0, new ItemStack(Items.IRON_SWORD));
                pinv.setItem(9, new ItemStack(Items.BOW));
                pinv.setItem(40, new ItemStack(Items.DIRT, 64));
            });
        }
        if (at(15)) {
            inv.setSelectedSlot(0);
            counted = total(inv, Items.DIRT);
            info("terra no inventário: " + counted + " (todos os 37 espaços ocupados)");
            check(!hc.canEmpty(p, 0) && !hc.canEmpty(p, 1), "sem espaço livre: a opção \"mão vazia\" fica apagada nas duas mãos");
            handInFront(client, 0);
            menuButton(client, 0, true);
            pointRadial(0, RadialMenu.CENTER);
        }
        if (at(18)) {
            int pulses = SimulatedVr.pulseCount(0);
            menuButton(client, 0, false);
            check(p.getMainHandItem().is(Items.IRON_SWORD) && inv.getSelectedSlot() == 0,
                    "radial \"mão vazia\" sem espaço: recusado, a espada continua na mão");
            check(SimulatedVr.pulseCount(0) > pulses, "recusa vibra o controle");
            check(openBackpack(client, 1), "mochila abre com o inventário cheio");
        }
        if (at(21)) {
            pointBackpackSlot(0, 9);
        }
        if (at(23)) {
            check(grip(client, 0, true) && p.getMainHandItem().is(Items.BOW) && slot(p, 9).is(Items.IRON_SWORD),
                    "inventário cheio: pegou o arco (troca com a espada, sem precisar de espaço)");
            pointAway(client, 0);
            SimulatedVr.setGrip(0, false);
        }
        if (at(30)) {
            check(p.getMainHandItem().is(Items.IRON_SWORD) && slot(p, 9).is(Items.BOW),
                    "inventário cheio: soltou e tudo voltou para o lugar");
            SimulatedVr.setGrip(1, false);
        }
        if (at(34)) { // coldre: guardar com o inventário cheio (fica no espaço) e sacar com terra na mão
            handAt(0, Holster.RIGHT_HIP);
        }
        if (at(37)) {
            grip(client, 0, true);  // segura a espada dentro do coldre
            grip(client, 0, false); // e solta ali: guarda
            check("minecraft:iron_sword".equals(holsters().binding(Holster.RIGHT_HIP).item()),
                    "inventário cheio: guardar no quadril direito lembra a espada (ela fica no mesmo espaço)");
            inv.setSelectedSlot(1);
        }
        if (at(41)) {
            check(grip(client, 0, true) && p.getMainHandItem().is(Items.IRON_SWORD) && inv.getSelectedSlot() == 0,
                    "inventário cheio: sacou a espada do quadril com terra na mão");
            SimulatedVr.setGrip(0, false);
        }
        if (at(48)) {
            check(inv.getSelectedSlot() == 1 && p.getMainHandItem().is(Items.DIRT), "soltou: a terra voltou para a mão");
            check(total(inv, Items.DIRT) == counted && total(inv, Items.IRON_SWORD) == 1 && total(inv, Items.BOW) == 1
                            && droppedNearby(client, Items.DIRT) == 0,
                    "inventário cheio: nada sumiu, nada duplicou, nada caiu no chão");
            handAway(0);
            next();
        }
    }

    // ------------------------------------------------------------------ fotos da mochila e do radial

    private static void photos(Minecraft client) {
        LocalPlayer p = client.player;
        if (at(1)) {
            section("Fotos");
            setupBackpackInventory(client);
            client.options.setCameraType(CameraType.FIRST_PERSON);
            p.setXRot(32);
        }
        if (at(15)) {
            p.getInventory().setSelectedSlot(0);
            openBackpack(client, 1);
        }
        if (at(17)) {
            pointBackpackSlot(0, 11); // tábuas: põe uma no crafting para a foto
            grip(client, 0, true);
        }
        if (at(20)) {
            pointBackpackSlot(0, InvOps.CRAFT_START);
        }
        if (at(22)) {
            hands().onTrigger(client, 0);
            pointBackpackSlot(0, InvOps.CRAFT_START + 1);
        }
        if (at(24)) {
            hands().onTrigger(client, 0);
            pointAway(client, 0);
            SimulatedVr.setGrip(0, false);
        }
        if (at(30)) {
            pointBackpackSlot(0, 10); // mão sobre a areia (destacada)
        }
        if (at(45)) {
            screenshot(client, "05-mochila-aberta");
            pointBackpack(0, hands().backpack().element(Backpack.Kind.DROP));
            p.setXRot(58); // a parte de baixo: setas, página e descarte
        }
        if (at(60)) {
            screenshot(client, "06-mochila-descarte");
            SimulatedVr.setGrip(1, false);
            p.setXRot(18);
            handInFront(client, 0);
        }
        if (at(66)) {
            menuButton(client, 0, true);
            pointRadial(0, 2);
        }
        if (at(80)) {
            screenshot(client, "07-radial");
            pointRadial(0, RadialMenu.CENTER);
        }
        if (at(92)) {
            screenshot(client, "08-radial-mao-vazia");
            menuButton(client, 0, false);
            p.setXRot(0);
            handAway(0);
            handAway(1);
        }
        if (at(100)) {
            next();
        }
    }

    // ------------------------------------------------------------------ resto

    // ------------------------------------------------------------------ guardar o item da mão (issues #8 e #9)

    /** Ponta da mão sobre o fundo da mochila aberta (acima do crafting, sem nenhum alvo embaixo). */
    private static void pointBackpackBackground(int hand) {
        pointPanel(hand, hands().backpack().computePanel(), 0.0, -0.030);
    }

    private static void handItemStore(Minecraft client) {
        Inventory inv = client.player.getInventory();
        LocalPlayer p = client.player;
        HandsController hc = hands();
        HolsterManager hm = holsters();
        Backpack bp = hc.backpack();
        if (at(1)) {
            section("Guardar o item da mão (mochila e coldres)");
            HandsOnConfig.get().gripScrollsHotbar = false;
            clearDrops(client);
            onServer(client, player -> {
                Inventory pinv = player.getInventory();
                pinv.clearContent();
                pinv.setItem(0, new ItemStack(Items.IRON_SWORD));
                pinv.setItem(1, new ItemStack(Items.OAK_PLANKS, 16));
                pinv.setItem(2, new ItemStack(Items.IRON_HELMET));
                pinv.setItem(3, new ItemStack(Items.OAK_PLANKS, 10));
                pinv.setItem(4, new ItemStack(Items.COBBLESTONE, 20));
                pinv.setItem(5, new ItemStack(Items.IRON_PICKAXE));
                pinv.setItem(9, new ItemStack(Items.OAK_PLANKS, 5));
                pinv.setItem(40, new ItemStack(Items.TORCH, 8));
            });
            handAway(0);
            handAway(1);
        }
        // --- mochila aberta: grip num espaço vazio com a espada na mão, soltar no mesmo espaço
        if (at(15)) {
            inv.setSelectedSlot(0);
            check(openBackpack(client, 1), "mochila aberta na mão esquerda (mundo novo: inventário quase vazio)");
            pointBackpackSlot(0, 12); // vazio
        }
        if (at(18)) {
            check(grip(client, 0, true), "mochila: grip num espaço vazio com a espada na mão é aceito");
            GrabSystem.Grab g = hc.grabs().get(0);
            check(g != null && g.origin() == GrabSystem.Origin.HAND && p.getMainHandItem().is(Items.IRON_SWORD),
                    "mochila: o grip segura a espada da própria mão (ela continua na mão)");
        }
        if (at(21)) {
            grip(client, 0, false);
        }
        if (at(24)) {
            check(slot(p, 12).is(Items.IRON_SWORD) && p.getMainHandItem().isEmpty(),
                    "mochila: soltar no espaço vazio guardou a espada ali e a mão ficou vazia (" + hc.lastAction() + ")");
        }
        // --- mochila aberta: grip no fundo do painel com as tábuas, arrastar e soltar sobre as tábuas da mochila
        if (at(27)) {
            inv.setSelectedSlot(1);
            pointBackpackBackground(0);
        }
        if (at(30)) {
            check(bp.elementAt(VrBridge.pointer(0)) == null && bp.onPanel(VrBridge.pointer(0)),
                    "mochila: a mão está no fundo do painel (fora dos espaços)");
            check(grip(client, 0, true) && hc.grabs().get(0) != null, "mochila: grip no fundo segura o item da mão");
            pointBackpackSlot(0, 9); // 5 tábuas
        }
        if (at(33)) {
            grip(client, 0, false);
        }
        if (at(36)) {
            check(slot(p, 9).is(Items.OAK_PLANKS) && slot(p, 9).getCount() == 21 && p.getMainHandItem().isEmpty(),
                    "mochila: soltar sobre as tábuas juntou a pilha (5 + 16 = " + slot(p, 9).getCount() + ")");
        }
        // --- o item da mão no descarte: grip no descarte e soltar joga a pilha no chão
        if (at(39)) {
            inv.setSelectedSlot(4); // pedregulho
            pointBackpack(0, bp.element(Backpack.Kind.DROP));
        }
        if (at(42)) {
            grip(client, 0, true);
        }
        if (at(45)) {
            grip(client, 0, false);
        }
        if (at(55)) {
            check(p.getMainHandItem().isEmpty() && droppedNearby(client, Items.COBBLESTONE) == 20,
                    "mochila: o item da mão solto no descarte foi jogado no chão (20 pedregulhos)");
            clearDrops(client);
            grip(client, 1, false); // fecha a mochila
        }
        // --- por cima do ombro: segurar o capacete na frente, levar ao ombro esquerdo e soltar
        if (at(60)) {
            check(!bp.isOpen(), "mochila fechada");
            inv.setSelectedSlot(2); // capacete de ferro
            pointAway(client, 0);
        }
        if (at(63)) {
            check(grip(client, 0, true) && hc.grabs().get(0) != null && p.getMainHandItem().is(Items.IRON_HELMET),
                    "fora das zonas: o grip segura o capacete da mão");
            handAt(0, Holster.LEFT_SHOULDER);
        }
        if (at(66)) {
            check(!bp.isOpen(), "levar o item seguro ao ombro esquerdo não abre a mochila");
            grip(client, 0, false);
        }
        if (at(69)) {
            int helmet = find(inv, Items.IRON_HELMET);
            check(helmet >= 9 && helmet < 36 && p.getMainHandItem().isEmpty(),
                    "por cima do ombro: o capacete foi para a mochila (espaço " + helmet + ") e a mão ficou vazia");
            check(slot(p, InvOps.ARMOR_START).isEmpty(), "por cima do ombro: o capacete não foi vestido");
            handAway(0);
        }
        // --- por cima do ombro: tábuas juntam com a pilha da mochila
        if (at(72)) {
            inv.setSelectedSlot(3); // 10 tábuas
            pointAway(client, 0);
        }
        if (at(75)) {
            grip(client, 0, true);
            handAt(0, Holster.LEFT_SHOULDER);
        }
        if (at(78)) {
            grip(client, 0, false);
        }
        if (at(81)) {
            check(slot(p, 9).getCount() == 31 && total(inv, Items.OAK_PLANKS) == 31 && p.getMainHandItem().isEmpty(),
                    "por cima do ombro: as tábuas juntaram com a pilha da mochila (21 + 10 = " + slot(p, 9).getCount() + ")");
            handAway(0);
        }
        // --- por cima do ombro com a mochila cheia: vibra e o item fica na mão
        if (at(84)) {
            onServer(client, player -> {
                Inventory pinv = player.getInventory();
                for (int i = 9; i < 36; i++) {
                    if (pinv.getItem(i).isEmpty()) {
                        pinv.setItem(i, new ItemStack(Items.DIRT, 64));
                    }
                }
                pinv.setItem(6, new ItemStack(Items.GLASS, 3));
            });
        }
        if (at(94)) {
            inv.setSelectedSlot(6);
            pointAway(client, 0);
        }
        if (at(97)) {
            grip(client, 0, true);
            handAt(0, Holster.LEFT_SHOULDER);
        }
        if (at(100)) {
            counted = SimulatedVr.pulseCount(0);
            grip(client, 0, false);
        }
        if (at(103)) {
            check(p.getMainHandItem().is(Items.GLASS) && p.getMainHandItem().getCount() == 3,
                    "mochila cheia: o vidro continua na mão (" + hc.lastAction() + ")");
            check(hc.lastAction().contains("cheia") && SimulatedVr.pulseCount(0) > counted,
                    "mochila cheia: o controle vibrou em sinal de \"negado\"");
            handAway(0);
            onServer(client, player -> {
                Inventory pinv = player.getInventory();
                for (int i = 9; i < 36; i++) {
                    if (pinv.getItem(i).is(Items.DIRT)) {
                        pinv.setItem(i, ItemStack.EMPTY);
                    }
                }
                pinv.setItem(6, ItemStack.EMPTY);
            });
        }
        // --- coldre: segurar a picareta na frente, levar ao quadril direito e soltar
        if (at(113)) {
            for (Holster h : Holster.values()) {
                hm.clear(h);
            }
            inv.setSelectedSlot(5); // picareta de ferro
            pointAway(client, 0);
        }
        if (at(116)) {
            check(grip(client, 0, true) && hc.grabs().get(0) != null, "coldre: o grip segura a picareta na frente do corpo");
            handAt(0, Holster.RIGHT_HIP);
        }
        if (at(119)) {
            check(hm.zoneOf(0) == Holster.RIGHT_HIP, "coldre: a mão (com a picareta segura) chegou ao quadril direito");
            grip(client, 0, false);
        }
        if (at(122)) {
            HolsterStorage.Binding b = hm.binding(Holster.RIGHT_HIP);
            int pick = find(inv, Items.IRON_PICKAXE);
            check(b != null && "minecraft:iron_pickaxe".equals(b.item()), "coldre: soltar no quadril guardou a picareta");
            check(p.getMainHandItem().isEmpty() && pick >= 9 && pick < 36,
                    "coldre: a picareta saiu da hotbar (espaço " + pick + ") e a mão ficou vazia");
            handAway(0);
        }
        // --- coldre ocupado: soltar outro item ali troca o item do coldre
        if (at(125)) {
            int sword = find(inv, Items.IRON_SWORD);
            if (sword >= 9) {
                onServer(client, player -> {
                    Inventory pinv = player.getInventory();
                    pinv.setItem(7, pinv.getItem(sword).copy());
                    pinv.setItem(sword, ItemStack.EMPTY);
                });
            }
        }
        if (at(135)) {
            inv.setSelectedSlot(7); // espada de ferro
            pointAway(client, 0);
        }
        if (at(138)) {
            grip(client, 0, true);
            handAt(0, Holster.RIGHT_HIP);
        }
        if (at(141)) {
            grip(client, 0, false);
        }
        if (at(144)) {
            HolsterStorage.Binding b = hm.binding(Holster.RIGHT_HIP);
            check(b != null && "minecraft:iron_sword".equals(b.item()) && p.getMainHandItem().isEmpty(),
                    "coldre ocupado: soltar a espada ali trocou o item do coldre");
            check(find(inv, Items.IRON_PICKAXE) >= 0, "coldre ocupado: a picareta antiga continua no inventário");
            handAway(0);
        }
        // --- coldre: grip e soltar dentro do coldre vazio (o gesto antigo) ainda guarda
        if (at(147)) {
            onServer(client, player -> player.getInventory().setItem(8, new ItemStack(Items.SHEARS)));
        }
        if (at(157)) {
            inv.setSelectedSlot(8);
            handAt(0, Holster.RIGHT_SHOULDER);
        }
        if (at(160)) {
            check(grip(client, 0, true), "coldre vazio: grip com a tesoura na mão dentro do coldre");
            check(hm.binding(Holster.RIGHT_SHOULDER) == null, "coldre vazio: segurando o grip, ainda não guardou");
        }
        if (at(163)) {
            grip(client, 0, false);
        }
        if (at(166)) {
            HolsterStorage.Binding b = hm.binding(Holster.RIGHT_SHOULDER);
            check(b != null && "minecraft:shears".equals(b.item()) && p.getMainHandItem().isEmpty(),
                    "coldre vazio: soltar o grip ali guardou a tesoura");
            handAway(0);
        }
        // --- mão esquerda: tochas da mão secundária para o quadril esquerdo
        if (at(169)) {
            pointAway(client, 1);
        }
        if (at(172)) {
            check(grip(client, 1, true) && hc.grabs().get(1) != null, "mão esquerda: o grip segura as tochas");
            handAt(1, Holster.LEFT_HIP);
        }
        if (at(175)) {
            grip(client, 1, false);
        }
        if (at(178)) {
            HolsterStorage.Binding b = hm.binding(Holster.LEFT_HIP);
            check(b != null && "minecraft:torch".equals(b.item()) && p.getOffhandItem().isEmpty(),
                    "mão esquerda: soltar no quadril esquerdo guardou as tochas");
            handAway(1);
        }
        // --- soltar fora de qualquer zona: nada muda
        if (at(181)) {
            onServer(client, player -> player.getInventory().setItem(0, new ItemStack(Items.APPLE, 4)));
        }
        if (at(191)) {
            inv.setSelectedSlot(0);
            pointAway(client, 0);
        }
        if (at(194)) {
            grip(client, 0, true);
        }
        if (at(197)) {
            grip(client, 0, false);
        }
        if (at(200)) {
            check(p.getMainHandItem().is(Items.APPLE) && p.getMainHandItem().getCount() == 4 && inv.getSelectedSlot() == 0,
                    "soltar fora das zonas: a maçã continua na mão");
        }
        // --- trocar de item (a hotbar mudou) segurando o grip: o item antigo não é guardado depois
        if (at(203)) {
            grip(client, 0, true);
            inv.setSelectedSlot((inv.getSelectedSlot() + 1) % 9);
        }
        if (at(206)) {
            check(hc.grabs().get(0) == null, "trocar de item com o grip apertado: o grip solta o item antigo");
            handAt(0, Holster.RIGHT_HIP);
            grip(client, 0, false);
        }
        if (at(209)) {
            check(find(inv, Items.APPLE) == 0 && "minecraft:iron_sword".equals(hm.binding(Holster.RIGHT_HIP).item()),
                    "trocar de item com o grip apertado: nada foi guardado ao soltar no coldre");
            inv.setSelectedSlot(0);
            handAway(0);
        }
        // --- a opção "grip troca a hotbar" ligada: fora das zonas o grip volta a ser do Vivecraft
        if (at(212)) {
            HandsOnConfig.get().gripScrollsHotbar = true;
            pointAway(client, 0);
        }
        if (at(215)) {
            check(!grip(client, 0, true) && hc.grabs().get(0) == null,
                    "com \"grip troca a hotbar\" ligado, o grip fora das zonas fica com o Vivecraft");
            SimulatedVr.setGrip(0, false);
            HandsOnConfig.get().gripScrollsHotbar = false;
            handAway(0);
        }
        if (at(225)) {
            next();
        }
    }

    private static void persistenceAndMisc(Minecraft client) throws Exception {
        Inventory inv = client.player.getInventory();
        if (at(5)) {
            section("Persistência, Vivecraft, ajustes");
            EnumMap<Holster, HolsterStorage.Binding> saved = HolsterStorage.load(HolsterStorage.worldKey(client));
            info("salvo em disco: " + saved);
            check(saved.containsKey(Holster.RIGHT_SHOULDER) && saved.containsKey(Holster.LEFT_HIP) && saved.containsKey(Holster.RIGHT_HIP),
                    "os coldres ficam salvos para este mundo");
            check(!saved.containsKey(Holster.LEFT_SHOULDER), "o ombro esquerdo não é coldre (é a mochila)");
            if (VrBridge.isVivecraftLoaded()) {
                check(hasMixin("org.vivecraft.client_vr.gameplay.trackers.BackpackTracker"),
                        "Vivecraft: a troca pelas costas fica desligada (mixin aplicado)");
                check(hasMixin("org.vivecraft.client_vr.gameplay.trackers.SwingTracker"),
                        "Vivecraft: golpe com o braço desligável durante a mochila/radial (mixin aplicado)");
                check(hasMixin("org.vivecraft.client_vr.render.helpers.VRArmHelper"),
                        "Vivecraft: o item da mão que segura a mochila some (mixin aplicado)");
            } else {
                info("Vivecraft ausente: mixins do Vivecraft não conferidos");
            }
            inv.setSelectedSlot(0);
            check(SimulatedVr.pulseCount(0) > 0 && SimulatedVr.pulseCount(1) > 0,
                    "vibração nas duas mãos (" + SimulatedVr.pulseCount(0) + "/" + SimulatedVr.pulseCount(1) + ")");
        }
        if (at(9)) {
            testOpensScreens = true;
        }
        if (at(10)) {
            client.gui.setScreen(new net.minecraft.client.gui.screens.PauseScreen(true));
        }
        if (at(20)) {
            var button = client.gui.screen() == null ? null : client.gui.screen().children().stream()
                    .filter(e -> e instanceof net.minecraft.client.gui.components.Button b
                            && b.getMessage().getString().equals(Component.translatable("button.handsonvr.settings").getString()))
                    .map(e -> (net.minecraft.client.gui.components.Button) e).findFirst().orElse(null);
            check(button != null, "menu de pausa: tem o botão dos coldres");
            if (button != null) {
                button.onPress(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN, 0, 0));
            }
        }
        if (at(30)) {
            check(client.gui.screen() instanceof com.handsonvr.screen.HandsOnScreen, "o botão do menu de pausa abre os ajustes");
            screenshot(client, "09-ajustes");
        }
        if (at(40)) {
            client.gui.setScreen(null);
        }
        if (at(50)) {
            next();
        }
    }

    // ------------------------------------------------------------------ coleta: inventário principal primeiro

    private static int pickupMovedBefore = 0;

    /** Item jogado no chão bem nos pés do jogador, sem espera para coletar. */
    private static void dropAtFeet(ServerPlayer player, ItemStack stack) {
        ItemEntity e = new ItemEntity(player.level(), player.getX(), player.getY() + 0.2, player.getZ(), stack, 0, 0, 0);
        e.setNoPickUpDelay();
        player.level().addFreshEntity(e);
    }

    private static void pickupOrder(Minecraft client) {
        LocalPlayer p = client.player;
        Inventory inv = p.getInventory();
        if (at(1)) {
            section("Coleta: inventário principal primeiro");
            check(hasMixin(Inventory.class), "mixin do inventário aplicado (escolha do espaço livre)");
            clearDrops(client);
            inv.setSelectedSlot(1);
            onServer(client, player -> {
                Inventory pinv = player.getInventory();
                pinv.clearContent();
                pinv.setItem(0, new ItemStack(Items.IRON_SWORD));
                pinv.setItem(2, new ItemStack(Items.TORCH, 10));
                pinv.add(new ItemStack(Items.OAK_LOG, 5));
                pinv.add(new ItemStack(Items.TORCH, 5));
            });
        }
        if (at(10)) {
            boolean hotbarFree = true;
            for (int i : new int[] {1, 3, 4, 5, 6, 7, 8}) {
                hotbarFree &= inv.getItem(i).isEmpty();
            }
            check(inv.getItem(9).is(Items.OAK_LOG) && inv.getItem(9).getCount() == 5 && hotbarFree,
                    "item recebido vai para o inventário principal, com a hotbar livre (" + describe(inv.getItem(9)) + " no espaço 9)");
            check(inv.getItem(2).is(Items.TORCH) && inv.getItem(2).getCount() == 15 && total(inv, Items.TORCH) == 15,
                    "item igual a uma pilha da hotbar se junta a ela (15 tochas no espaço 2)");
            onServer(client, player -> dropAtFeet(player, new ItemStack(Items.COBBLESTONE, 3)));
        }
        if (at(50)) {
            int at = find(inv, Items.COBBLESTONE);
            check(at == 10 && inv.getItem(1).isEmpty(), "coletar do chão: o pedregulho foi para o inventário principal (espaço " + at
                    + "), não para a mão vazia");
            onServer(client, player -> {
                Inventory pinv = player.getInventory();
                for (int i = 9; i < 36; i++) {
                    pinv.setItem(i, new ItemStack(Items.DIRT));
                }
                pinv.add(new ItemStack(Items.APPLE, 2));
            });
        }
        if (at(60)) {
            check(inv.getItem(1).is(Items.APPLE), "inventário principal cheio: a maçã vai para a hotbar (" + find(inv, Items.APPLE) + ")");
            HandsOnConfig.get().pickupToMain = false;
            onServer(client, player -> {
                Inventory pinv = player.getInventory();
                for (int i = 9; i < 36; i++) {
                    pinv.setItem(i, ItemStack.EMPTY);
                }
                pinv.add(new ItemStack(Items.BREAD, 3));
            });
        }
        if (at(70)) {
            check(find(inv, Items.BREAD) == 3, "opção desligada: como no jogo, o pão vai para a hotbar (" + find(inv, Items.BREAD) + ")");
            HandsOnConfig.get().pickupToMain = true;
            // Servidor de outra pessoa: sem o mixin, o servidor põe na hotbar e a correção pelo cliente leva ao inventário.
            PickupOrder.debugRemote = true;
            pickupMovedBefore = PickupOrder.debugMoved();
            onServer(client, player -> player.getInventory().add(new ItemStack(Items.CARROT, 4)));
        }
        if (at(85)) {
            int at = find(inv, Items.CARROT);
            check(at >= 9 && at < 36 && PickupOrder.debugMoved() > pickupMovedBefore,
                    "servidor remoto: a cenoura que caiu na hotbar foi levada para o inventário principal (espaço " + at + ")");
            InvOps.moveStack(client, InvOps.fromInventory(at), InvOps.HOTBAR_START + 5); // o jogador a leva para a hotbar
        }
        if (at(100)) {
            check(find(inv, Items.CARROT) == 5, "servidor remoto: um item que o jogador levou para a hotbar fica lá");
            PickupOrder.debugRemote = false;
            inv.setSelectedSlot(0);
            clearDrops(client);
        }
        if (at(105)) {
            next();
        }
    }

    // ------------------------------------------------------------------ arco na mão que o pegou

    private static Boolean reverseBefore;

    private static void bowHands(Minecraft client) {
        LocalPlayer p = client.player;
        Inventory inv = p.getInventory();
        HandsController hc = hands();
        if (at(1)) {
            section("Arco: na mão que o pegou, a flecha na outra");
            onServer(client, player -> {
                Inventory pinv = player.getInventory();
                pinv.clearContent();
                pinv.setItem(0, new ItemStack(Items.IRON_SWORD));
                pinv.setItem(9, new ItemStack(Items.BOW));
                pinv.setItem(10, new ItemStack(Items.ARROW, 16));
            });
            inv.setSelectedSlot(0);
            handAway(0);
            handAway(1);
            reverseBefore = BowHand.reverse();
            info("Reverse Bow do Vivecraft antes: " + reverseBefore);
        }
        // --- mochila na mão direita, a esquerda pega o arco
        if (at(8)) {
            check(openBackpack(client, 0), "mochila aberta na mão direita");
            pointBackpackSlot(1, 9);
        }
        if (at(10)) {
            grip(client, 1, true);
        }
        if (at(12)) {
            GrabSystem.Grab g = hc.grabs().get(1);
            check(g != null && g.itemHand() == 0 && p.getMainHandItem().is(Items.BOW) && p.getOffhandItem().isEmpty(),
                    "mão esquerda pega o arco: ele vai para o espaço da mão principal (o Vivecraft usa as flechas do inventário)");
            check(slot(p, 9).is(Items.IRON_SWORD) && hc.displayStack(p, 9).isEmpty(),
                    "a espada da mão direita espera no lugar do arco (o espaço aparece vazio)");
        }
        if (at(14)) {
            check(BowHand.bowHand() == 1 && Boolean.FALSE.equals(BowHand.reverse()) && BowHand.isArrowHand(client, 0),
                    "arco na mão esquerda: Reverse Bow desligado, a direita é a da flecha (" + BowHand.reverse() + ")");
            pointAway(client, 1);
            grip(client, 1, false);
        }
        if (at(17)) {
            check(slot(p, 9).is(Items.BOW) && p.getMainHandItem().is(Items.IRON_SWORD) && inv.getSelectedSlot() == 0
                            && hc.grabs().get(1) == null,
                    "soltar longe: o arco volta para a mochila e a espada para a mão direita");
        }
        if (at(19)) {
            check(java.util.Objects.equals(BowHand.reverse(), reverseBefore) && BowHand.bowHand() == -1,
                    "sem o arco na mão, o Reverse Bow volta à escolha do jogador");
            grip(client, 0, false); // fecha a mochila
        }
        // --- mochila na mão esquerda, a direita pega o arco
        if (at(23)) {
            check(openBackpack(client, 1), "mochila aberta na mão esquerda");
            pointBackpackSlot(0, 9);
        }
        if (at(25)) {
            grip(client, 0, true);
        }
        if (at(28)) {
            GrabSystem.Grab g = hc.grabs().get(0);
            check(g != null && g.itemHand() == 0 && p.getMainHandItem().is(Items.BOW) && BowHand.bowHand() == 0
                            && Boolean.TRUE.equals(BowHand.reverse()) && BowHand.isArrowHand(client, 1),
                    "mão direita pega o arco: ele fica nela (Reverse Bow ligado) e a esquerda é a da flecha");
            pointAway(client, 0);
            grip(client, 0, false);
            grip(client, 1, false);
        }
        if (at(31)) {
            check(slot(p, 9).is(Items.BOW) && p.getMainHandItem().is(Items.IRON_SWORD) && !hc.backpack().isOpen(),
                    "soltar longe: o arco volta, a espada volta para a mão e a mochila fecha");
        }
        if (at(33)) {
            check(java.util.Objects.equals(BowHand.reverse(), reverseBefore), "o Reverse Bow volta à escolha do jogador");
            InvOps.moveStack(client, 9, InvOps.HOTBAR_START + 4); // o arco na hotbar (espaço 5)
            handInFront(client, 0);
            handInFront(client, 1);
        }
        // --- radial: X põe o arco na mão esquerda, B na direita
        if (at(36)) {
            menuButton(client, 1, true);
            pointRadial(1, 4);
        }
        if (at(39)) {
            menuButton(client, 1, false);
            check(inv.getSelectedSlot() == 4 && p.getMainHandItem().is(Items.BOW) && p.getOffhandItem().isEmpty()
                            && BowHand.bowHand() == 1,
                    "X no arco: ele vai para o espaço da mão principal e fica na mão esquerda");
        }
        if (at(41)) {
            check(Boolean.FALSE.equals(BowHand.reverse()), "X no arco: Reverse Bow desligado (arco na esquerda)");
            menuButton(client, 0, true);
            pointRadial(0, 4);
        }
        if (at(44)) {
            menuButton(client, 0, false);
            check(inv.getSelectedSlot() == 4 && BowHand.bowHand() == 0, "B no arco: ele passa para a mão direita");
        }
        if (at(46)) {
            check(Boolean.TRUE.equals(BowHand.reverse()), "B no arco: Reverse Bow ligado (arco na direita)");
            menuButton(client, 0, true);
            pointRadial(0, 0);
        }
        if (at(49)) {
            menuButton(client, 0, false);
            check(p.getMainHandItem().is(Items.IRON_SWORD), "B na espada: o arco sai da mão");
        }
        if (at(51)) {
            check(java.util.Objects.equals(BowHand.reverse(), reverseBefore) && BowHand.bowHand() == -1,
                    "arco fora da mão: o Reverse Bow volta à escolha do jogador");
            handAway(0);
            handAway(1);
        }
        if (at(55)) {
            next();
        }
    }

    // ------------------------------------------------------------------ mesa de trabalho imersiva

    private static BlockPos tablePos;
    private static int plankSlot = -1;
    /** O teste abre a tela da mesa do jeito normal (o último passo): ela não é fechada como tela de outro mod. */
    private static boolean allowCraftingScreen = false;

    private static void pointTable(int hand, Vec3 point) {
        pointAt(hand, point, new Vec3(0, -1, 0)); // a mão por cima, apontando para a mesa
    }

    private static void tableCrafting(Minecraft client) {
        LocalPlayer p = client.player;
        Inventory inv = p.getInventory();
        HandsController hc = hands();
        com.handsonvr.table.CraftingTable table = hc.table();
        if (at(1)) {
            section("Mesa de trabalho: grade em cima da mesa, resultado flutuando");
            tablePos = BlockPos.containing(p.getX(), p.getY(), p.getZ()).offset(0, 0, 1);
            BlockPos at = tablePos;
            onServer(client, player -> {
                Inventory pinv = player.getInventory();
                pinv.clearContent();
                pinv.setItem(0, new ItemStack(Items.IRON_SWORD));
                pinv.setItem(9, new ItemStack(Items.OAK_LOG, 4));
                player.level().setBlock(at, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
                player.level().setBlock(at.above(), Blocks.AIR.defaultBlockState(), 3);
            });
            inv.setSelectedSlot(0);
            p.setYRot(0f);
            handAway(0);
            handAway(1);
        }
        if (at(8)) {
            check(tablePos.equals(table.pos()) && table.forward().z > 0.99,
                    "a mesa na frente é encontrada (" + table.pos() + "), a linha de cima da grade é a mais longe");
            check(openBackpack(client, 1), "mochila aberta na mão esquerda");
            pointBackpackSlot(0, 9);
        }
        if (at(10)) {
            grip(client, 0, true); // pega as toras
        }
        if (at(12)) {
            check(p.getMainHandItem().is(Items.OAK_LOG), "a mão direita pegou as toras da mochila");
            pointTable(0, table.cellCenter(4).add(0, 0.03, 0));
        }
        if (at(14)) {
            check(table.hover(0) == 4 && (table.isWaiting() || table.isOpen()),
                    "toras sobre a casa do meio: a casa é destacada e a mesa já está abrindo");
        }
        if (at(24)) {
            check(table.isOpen() && client.gui.screen() == null
                            && p.containerMenu instanceof net.minecraft.world.inventory.CraftingMenu,
                    "a mesa abriu por baixo, sem a tela do jogo (" + table.last() + ")");
            check(!InvOps.reachable(p, InvOps.ARMOR_START) && InvOps.reachable(p, InvOps.MAIN_START),
                    "com a mesa aberta, a mochila ainda usa o inventário (a armadura espera ela fechar)");
            grip(client, 0, false); // solta sobre a casa: a pilha inteira
        }
        if (at(30)) {
            check(table.cellStack(4).is(Items.OAK_LOG) && table.cellStack(4).getCount() == 4 && total(inv, Items.OAK_LOG) == 0
                            && p.getMainHandItem().is(Items.IRON_SWORD),
                    "soltar sobre a casa: as 4 toras ficam deitadas na mesa e a espada volta para a mão");
            check(table.resultStack().is(Items.OAK_PLANKS) && table.resultStack().getCount() == 4,
                    "a receita fecha: as tábuas flutuam acima da grade (" + describe(table.resultStack()) + " por vez)");
            check(table.resultTotal() == 16, "o resultado mostra o total que o grip + gatilho dá: 4 toras, " + table.resultTotal() + " tábuas");
            grip(client, 1, false); // fecha a mochila
            pointTable(0, table.resultCenter());
            p.setXRot(38f);
        }
        if (at(33)) {
            check(table.hover(0) == com.handsonvr.table.CraftingTable.RESULT, "a mão no resultado flutuando: destacado");
        }
        if (at(38)) {
            screenshot(client, "10-mesa");
        }
        if (at(40)) {
            grip(client, 0, true); // grip sozinho no resultado: fabrica uma vez
        }
        if (at(48)) {
            GrabSystem.Grab g = hc.grabs().get(0);
            check(!hc.isCrafting() && hc.resultHeld(0) == HandsController.ResultSource.TABLE
                            && p.getMainHandItem().is(Items.OAK_PLANKS) && p.getMainHandItem().getCount() == 4
                            && table.cellStack(4).is(Items.OAK_LOG) && table.cellStack(4).getCount() == 3
                            && slot(p, InvOps.HOTBAR_START).is(Items.IRON_SWORD),
                    "grip sozinho no resultado: um craft só, 4 tábuas na mão direita e 3 toras ficam na mesa (a espada ficou na hotbar)");
            check(g != null && g.origin() == GrabSystem.Origin.HAND, "com o grip ainda apertado, a mão segura as 4 tábuas");
            check(table.resultTotal() == 12, "o total que ainda dá para fabricar: " + table.resultTotal() + " tábuas");
            check(hc.onTrigger(client, 0), "grip + gatilho no resultado: fabrica o resto");
        }
        if (at(64)) {
            GrabSystem.Grab g = hc.grabs().get(0);
            check(!hc.isCrafting() && p.getMainHandItem().is(Items.OAK_PLANKS) && p.getMainHandItem().getCount() == 16
                            && table.cellStack(4).isEmpty() && slot(p, InvOps.HOTBAR_START).is(Items.IRON_SWORD)
                            && total(inv, Items.OAK_PLANKS) == 16,
                    "grip + gatilho: o resto das toras virou tábuas, juntando na mesma mão (16 tábuas)");
            check(g != null && g.origin() == GrabSystem.Origin.HAND, "com o grip ainda apertado, a mão segura as tábuas");
            pointAway(client, 0);
            grip(client, 0, false);
            check(p.getMainHandItem().is(Items.OAK_PLANKS) && table.resultStack().isEmpty(),
                    "soltar longe: as tábuas continuam na mão e nada mais flutua");
        }
        // --- gatilho: uma tábua por casa (a mão segura as próprias tábuas com o grip)
        if (at(66)) {
            pointTable(0, table.cellCenter(1).add(0, 0.03, 0)); // linha de cima, meio
        }
        if (at(68)) {
            grip(client, 0, true); // casa vazia: a mão segura o próprio item
        }
        if (at(70)) {
            check(hands().onTrigger(client, 0), "gatilho sobre a casa de cima: é da mesa");
            pointTable(0, table.cellCenter(4).add(0, 0.03, 0));
        }
        if (at(72)) {
            hands().onTrigger(client, 0);
        }
        if (at(78)) {
            check(table.cellStack(1).is(Items.OAK_PLANKS) && table.cellStack(1).getCount() == 1
                            && table.cellStack(4).getCount() == 1 && p.getMainHandItem().getCount() == 14,
                    "grip + gatilho sobre as casas: uma tábua por casa e a mão continua com as outras 14");
            check(table.resultStack().is(Items.STICK) && table.resultStack().getCount() == 4,
                    "tábua em cima de tábua (de longe para perto): as varetas flutuam (" + describe(table.resultStack()) + ")");
            pointAway(client, 0);
            grip(client, 0, false);
        }
        // --- grip numa casa ocupada: o item volta para a mão
        if (at(80)) {
            pointTable(0, table.cellCenter(1).add(0, 0.03, 0));
        }
        if (at(82)) {
            grip(client, 0, true);
        }
        if (at(84)) {
            GrabSystem.Grab g = hc.grabs().get(0);
            check(g != null && g.origin() == GrabSystem.Origin.TABLE && p.getMainHandItem().is(Items.OAK_PLANKS)
                            && p.getMainHandItem().getCount() == 15 && table.cellStack(1).isEmpty(),
                    "grip numa casa ocupada: a tábua volta para a mão (junto com as outras)");
            pointAway(client, 0);
            grip(client, 0, false);
        }
        if (at(88)) {
            // Uma tábua sozinha também é uma receita do jogo: o botão de madeira.
            check(table.cellStack(4).is(Items.OAK_PLANKS) && table.resultStack().is(Items.OAK_BUTTON)
                            && total(inv, Items.OAK_PLANKS) == 15,
                    "a receita muda com a grade: uma tábua sozinha vira um botão (" + describe(table.resultStack())
                            + "); 15 tábuas na mão e 1 na mesa");
            check(table.take(client, 4) >= 0 && table.gridEmpty(), "a última tábua volta para o inventário");
            onServer(client, player -> {
                player.getInventory().setItem(11, new ItemStack(Items.COAL, 2));
                player.getInventory().setItem(12, new ItemStack(Items.STICK, 2));
            });
        }
        if (at(92)) {
            // Carvão em cima das varetas: a tocha (a vareta é um item achatado, deitado na casa).
            table.place(client, 0, 11, 1, true, true);  // a pilha toda (2 carvões)
            table.place(client, 0, 12, 4, true, false); // uma vareta (gatilho)
            table.place(client, 0, 12, 4, true, false); // outra
            pointTable(0, table.cellCenter(4).add(0, 0.03, 0));
        }
        if (at(96)) {
            check(table.cellStack(1).is(Items.COAL) && table.cellStack(1).getCount() == 2 && table.cellStack(4).is(Items.STICK)
                            && table.cellStack(4).getCount() == 2 && table.resultStack().is(Items.TORCH)
                            && table.resultStack().getCount() == 4,
                    "carvão em cima das varetas: as tochas flutuam (" + describe(table.resultStack()) + ")");
            check(table.resultTotal() == 8, "total das tochas: 2 carvões e 2 varetas fabricam duas vezes (" + table.resultTotal() + ")");
        }
        if (at(100)) {
            screenshot(client, "11-mesa-tocha");
        }
        if (at(102)) {
            SimulatedVr.setTrigger(0, true); // o gatilho já apertado
            pointTable(0, table.resultCenter());
        }
        if (at(104)) {
            grip(client, 0, true); // e depois o grip: fabrica tudo de uma vez
        }
        if (at(122)) {
            check(!hc.isCrafting() && p.getMainHandItem().is(Items.TORCH) && p.getMainHandItem().getCount() == 8
                            && table.gridEmpty() && total(inv, Items.OAK_PLANKS) == 16,
                    "gatilho apertado e depois o grip: fabricou tudo, 8 tochas na mão direita (as tábuas ficaram na hotbar) ["
                            + describe(p.getMainHandItem()) + ", tábuas " + total(inv, Items.OAK_PLANKS) + ", tochas "
                            + total(inv, Items.TORCH) + ", " + hc.lastAction() + ", " + table.last() + "]");
            pointAway(client, 0);
            grip(client, 0, false);
            SimulatedVr.setTrigger(0, false);
        }
        if (at(124)) {
            int planks = InvOps.fromInventory(find(inv, Items.OAK_PLANKS));
            table.place(client, 0, planks, 0, true, false); // uma tábua para conferir que volta ao se afastar
        }
        if (at(126)) {
            check(table.cellStack(0).is(Items.OAK_PLANKS), "uma tábua na casa de cima à esquerda (" + table.last() + ")");
            handAway(0);
            inv.setSelectedSlot(0);
        }
        if (at(128)) {
            p.setPos(p.getX(), p.getY(), p.getZ() - 6); // afasta-se da mesa
        }
        if (at(143)) {
            check(!table.isOpen() && p.containerMenu == p.inventoryMenu && total(inv, Items.OAK_PLANKS) == 16
                            && total(inv, Items.TORCH) == 8 && total(inv, Items.COAL) == 0 && total(inv, Items.STICK) == 0,
                    "afastar-se: a mesa fecha e a tábua volta para o inventário (" + table.last() + ")");
            p.setPos(p.getX(), p.getY(), p.getZ() + 6);
            p.setXRot(0f);
        }
        if (at(153)) {
            allowCraftingScreen = true;
            client.gameMode.useItemOn(p, net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.phys.BlockHitResult(
                    Vec3.atCenterOf(tablePos).add(0, 0.5, 0), net.minecraft.core.Direction.UP, tablePos, false));
        }
        if (at(163)) {
            check(client.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.CraftingScreen && !table.isOpen(),
                    "usar a mesa do jeito normal ainda abre a tela do jogo");
            p.closeContainer();
            allowCraftingScreen = false;
            handAway(0);
            handAway(1);
        }
        if (at(168)) {
            next();
        }
    }

    // ------------------------------------------------------------------ fornalhas imersivas

    private static BlockPos furnacePos, farFurnacePos;
    /** O teste abre a tela da fornalha do jeito normal: ela não é fechada como tela de outro mod. */
    private static boolean allowFurnaceScreen = false;

    /** Ponta da mão num lugar da fornalha (entrada ou combustível), um pouco fora da face, entrando nela. */
    private static void pointFurnace(int hand, int target) {
        Panel plane = hands().furnace().spotPlane(target);
        pointAt(hand, plane.world(0, 0, 0.05), plane.normal().reverse());
    }

    /**
     * Foto para a documentação: sem o HUD (como o F1), sem avisos e um passo para trás, para caber o bloco, a barra de
     * progresso e o resultado. {@code false} volta ao normal.
     */
    private static void photoPose(Minecraft client, boolean on) {
        LocalPlayer p = client.player;
        if (client.gui.hud.isHidden() != on) {
            client.gui.hud.toggle();
        }
        if (on) {
            client.gui.toastManager().clear();
            client.gui.hud.getChat().clearMessages(false);
        }
        p.setPos(p.getX(), p.getY(), p.getZ() + (on ? -1 : 1));
        p.setXRot(on ? 30f : 45f);
    }

    private static void setFurnaceBlock(Minecraft client, net.minecraft.world.level.block.Block block) {
        BlockPos at = furnacePos;
        onServer(client, player -> player.level().setBlock(at, block.defaultBlockState()
                .setValue(net.minecraft.world.level.block.AbstractFurnaceBlock.FACING, net.minecraft.core.Direction.NORTH), 3));
    }

    private static void furnaces(Minecraft client) {
        LocalPlayer p = client.player;
        Inventory inv = p.getInventory();
        HandsController hc = hands();
        com.handsonvr.furnace.FurnaceStation f = hc.furnace();
        int input = com.handsonvr.furnace.FurnaceStation.INPUT, fuel = com.handsonvr.furnace.FurnaceStation.FUEL;
        if (at(1)) {
            section("Fornalhas: fornalha, alto-forno e defumador");
            furnacePos = BlockPos.containing(p.getX(), p.getY(), p.getZ()).offset(0, 0, 1); // onde estava a mesa
            BlockPos at = furnacePos;
            onServer(client, player -> {
                Inventory pinv = player.getInventory();
                pinv.clearContent();
                pinv.setItem(0, new ItemStack(Items.IRON_SWORD));
                pinv.setItem(9, new ItemStack(Items.RAW_IRON, 8));
                pinv.setItem(10, new ItemStack(Items.COAL, 4));
                pinv.setItem(11, new ItemStack(Items.BEEF, 2));
                pinv.setItem(12, new ItemStack(Items.DIRT, 3));
                player.level().setBlock(at.above(), Blocks.AIR.defaultBlockState(), 3);
            });
            setFurnaceBlock(client, Blocks.FURNACE); // de frente para o jogador
            // Um defumador a uns 7 blocos, já com carne e carvão: longe para abrir, perto para mostrar (até 10 blocos).
            farFurnacePos = furnacePos.offset(5, 0, 5);
            BlockPos far = farFurnacePos;
            onServer(client, player -> {
                player.level().setBlock(far, Blocks.SMOKER.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.AbstractFurnaceBlock.FACING, net.minecraft.core.Direction.NORTH), 3);
                if (player.level().getBlockEntity(far) instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity be) {
                    be.setItem(0, new ItemStack(Items.BEEF, 5));
                    be.setItem(1, new ItemStack(Items.COAL, 3));
                }
            });
            inv.setSelectedSlot(0);
            p.setYRot(0f);
            p.setXRot(45f); // olhando para a fornalha, embaixo à frente
            handAway(0);
            handAway(1);
        }
        if (at(12)) {
            check(furnacePos.equals(f.pos()) && f.kind() == com.handsonvr.furnace.FurnaceStation.Kind.FURNACE && f.isOpen()
                            && client.gui.screen() == null && p.containerMenu instanceof net.minecraft.world.inventory.FurnaceMenu,
                    "olhar para a fornalha perto: ela abre por baixo, sem a tela do jogo (" + f.last() + ")");
            check(InvOps.reachable(p, InvOps.ARMOR_START) && InvOps.reachable(p, InvOps.CRAFT_START),
                    "com a fornalha aberta, a armadura e o crafting 2x2 da mochila continuam valendo (ela cede o menu)");
            check(openBackpack(client, 1), "mochila aberta na mão esquerda");
            pointBackpackSlot(0, 9); // minério de ferro
        }
        if (at(14)) {
            grip(client, 0, true);
        }
        if (at(16)) {
            check(p.getMainHandItem().is(Items.RAW_IRON), "a mão direita pegou o minério da mochila");
            pointFurnace(0, input);
        }
        if (at(18)) {
            check(f.hover(0) == input, "minério na abertura de cima: o lugar da entrada é destacado");
            grip(client, 0, false); // solta: a pilha inteira
        }
        if (at(24)) {
            check(f.stack(input).is(Items.RAW_IRON) && f.stack(input).getCount() == 8 && total(inv, Items.RAW_IRON) == 0
                            && p.getMainHandItem().is(Items.IRON_SWORD),
                    "soltar na abertura de cima: os 8 minérios entram para queimar e a espada volta para a mão");
            pointBackpackSlot(0, 10); // carvão
        }
        if (at(26)) {
            grip(client, 0, true);
        }
        if (at(28)) {
            pointFurnace(0, fuel);
        }
        if (at(30)) {
            check(f.hover(0) == fuel && hc.onTrigger(client, 0), "carvão na abertura de baixo: o gatilho é da fornalha");
        }
        if (at(32)) {
            hc.onTrigger(client, 0);
        }
        if (at(34)) {
            com.handsonvr.furnace.FurnaceView farView = hc.furnaceWatcher().views(f).stream()
                    .filter(v -> v.pos().equals(farFurnacePos)).findFirst().orElse(null);
            check(farView != null && farView.live() && farView.kind() == com.handsonvr.furnace.FurnaceStation.Kind.SMOKER
                            && farView.input().is(Items.BEEF) && farView.fuel().is(Items.COAL) && farView.lit(),
                    "um defumador a " + String.format(Locale.ROOT, "%.1f", Vec3.atCenterOf(farFurnacePos).distanceTo(p.getEyePosition()))
                            + " blocos, longe para abrir: mostra ao vivo a carne, o carvão e o fogo (lido do servidor do singleplayer)");
            check(!f.isOpen() || !farFurnacePos.equals(f.pos()), "ele não é aberto (só a fornalha da frente, de perto)");
        }
        if (at(36)) {
            // O primeiro carvão já começou a queimar (o jogo tira um da pilha ao acender).
            check(f.stack(fuel).is(Items.COAL) && f.stack(fuel).getCount() + (f.isLit() ? 1 : 0) == 2
                            && p.getMainHandItem().getCount() == 2,
                    "grip + gatilho no combustível: um carvão por vez (2 na fornalha, um já queimando; 2 na mão)");
            pointAway(client, 0);
            grip(client, 0, false);
        }
        if (at(40)) {
            check(total(inv, Items.COAL) == 2, "soltar longe: os outros 2 carvões voltam para a mochila");
            check(!f.place(client, 0, 12, fuel, true) && !f.place(client, 0, 12, input, false) && total(inv, Items.DIRT) == 3,
                    "terra não queima nem cozinha: a fornalha recusa nos dois lugares");
            grip(client, 1, false); // fecha a mochila
        }
        if (at(64)) {
            check(f.isLit() && f.litProgress() > 0f && f.burnProgress() > 0f && f.burnProgress() < 1f,
                    String.format(Locale.ROOT, "a fornalha acende e cozinha: chama %.2f, seta %.2f", f.litProgress(), f.burnProgress()));
            BlockPos at = furnacePos;
            onServer(client, player -> {
                if (player.level().getBlockEntity(at) instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity be) {
                    be.setItem(2, new ItemStack(Items.IRON_INGOT, 3)); // como se já tivesse cozinhado 3
                }
            });
        }
        if (at(66)) {
            photoPose(client, true);
        }
        if (at(70)) {
            check(f.resultStack().is(Items.IRON_INGOT) && f.resultStack().getCount() == 3,
                    "o que ficou pronto flutua à direita da seta (" + describe(f.resultStack()) + ")");
            screenshot(client, "13-fornalha");
        }
        if (at(71)) {
            photoPose(client, false);
            pointAt(0, f.resultCenter(), new Vec3(0, 0, 1));
        }
        if (at(73)) {
            check(f.hover(0) == com.handsonvr.furnace.FurnaceStation.RESULT, "a mão no resultado: destacado");
            grip(client, 0, true);
        }
        if (at(80)) {
            GrabSystem.Grab g = hc.grabs().get(0);
            check(!hc.isCrafting() && p.getMainHandItem().is(Items.IRON_INGOT) && p.getMainHandItem().getCount() == 3
                            && f.resultStack().isEmpty() && slot(p, InvOps.HOTBAR_START).is(Items.IRON_SWORD),
                    "grip no resultado: os 3 lingotes vão direto para a mão direita (a espada ficou na hotbar)");
            check(g != null && g.origin() == GrabSystem.Origin.HAND, "com o grip ainda apertado, a mão segura os lingotes");
            pointAway(client, 0);
            grip(client, 0, false);
            check(p.getMainHandItem().is(Items.IRON_INGOT), "soltar longe: os lingotes continuam na mão");
            inv.setSelectedSlot(0);
            pointFurnace(0, input);
        }
        if (at(82)) {
            grip(client, 0, true); // grip na entrada com minério: volta para a mão
        }
        if (at(86)) {
            GrabSystem.Grab g = hc.grabs().get(0);
            check(g != null && p.getMainHandItem().is(Items.RAW_IRON) && f.stack(input).isEmpty(),
                    "grip na entrada: o minério volta para a mão (" + describe(p.getMainHandItem()) + ")");
            pointAway(client, 0);
            grip(client, 0, false);
        }
        if (at(90)) {
            check(total(inv, Items.RAW_IRON) >= 7 && p.getMainHandItem().is(Items.IRON_SWORD),
                    "soltar longe: o minério fica na mochila");
            // O crafting 2x2 da mochila com a fornalha aberta: ela cede o menu na hora.
            check(InvOps.placeOne(client, 12, InvOps.CRAFT_START) && slot(p, InvOps.CRAFT_START).is(Items.DIRT)
                            && !f.isOpen() && p.containerMenu == p.inventoryMenu,
                    "pôr terra no crafting 2x2 com a fornalha aberta: a fornalha fecha e a terra entra (" + f.last() + ")");
            InvOps.quickMove(client, InvOps.CRAFT_START);
        }
        if (at(96)) {
            check(slot(p, InvOps.CRAFT_START).isEmpty() && total(inv, Items.DIRT) == 3 && !f.isOpen(),
                    "a terra volta e a fornalha espera um pouco antes de abrir de novo sozinha");
            setFurnaceBlock(client, Blocks.SMOKER);
            clearDrops(client);
        }
        // --- defumador: a comida em cima da chaminé
        if (at(100)) {
            check(openBackpack(client, 1), "mochila aberta de novo");
            pointBackpackSlot(0, 11); // carne
        }
        if (at(102)) {
            grip(client, 0, true);
        }
        if (at(104)) {
            pointFurnace(0, input);
        }
        if (at(106)) {
            check(f.kind() == com.handsonvr.furnace.FurnaceStation.Kind.SMOKER && f.hover(0) == input
                            && (f.isOpen() || f.isWaiting()),
                    "carne sobre a chaminé do defumador: o lugar é destacado e ele abre na hora (" + f.last() + ")");
        }
        if (at(114)) {
            grip(client, 0, false);
        }
        if (at(120)) {
            check(f.stack(input).is(Items.BEEF) && f.stack(input).getCount() == 2, "soltar: a carne fica deitada em cima do defumador");
            check(!f.accepts(input, new ItemStack(Items.RAW_IRON)) && f.accepts(fuel, new ItemStack(Items.COAL)),
                    "o defumador não aceita minério na entrada");
            check(f.place(client, 0, InvOps.fromInventory(find(inv, Items.COAL)), fuel, false), "um carvão no fogo da frente");
            grip(client, 1, false);
            handAway(0);
        }
        if (at(136)) {
            photoPose(client, true);
        }
        if (at(140)) {
            check(f.isLit(), "o defumador acende com o carvão no fogo da frente");
            screenshot(client, "14-defumador");
        }
        if (at(141)) {
            photoPose(client, false);
        }
        // --- alto-forno: o minério por cima
        if (at(142)) {
            setFurnaceBlock(client, Blocks.BLAST_FURNACE);
            clearDrops(client);
        }
        if (at(150)) {
            check(f.kind() == com.handsonvr.furnace.FurnaceStation.Kind.BLAST_FURNACE, "o alto-forno no lugar do defumador é reconhecido");
            // Sem a mão: o lugar pede o menu e o item entra quando ele abre.
            check(f.place(client, 0, InvOps.fromInventory(find(inv, Items.RAW_IRON)), input, true), "minério por cima do alto-forno");
        }
        if (at(160)) {
            check(f.isOpen() && f.stack(input).is(Items.RAW_IRON), "o alto-forno abriu e o minério ficou deitado no topo");
            check(!f.accepts(input, new ItemStack(Items.BEEF)), "o alto-forno não aceita carne");
            f.place(client, 0, InvOps.fromInventory(find(inv, Items.COAL)), fuel, false);
        }
        if (at(172)) {
            photoPose(client, true);
        }
        if (at(176)) {
            check(f.isLit(), "o alto-forno acende com o carvão na grade da frente");
            screenshot(client, "15-alto-forno");
        }
        if (at(177)) {
            photoPose(client, false);
            p.setYRot(180f); // olha para o outro lado
        }
        if (at(270)) {
            check(!f.isOpen() && p.containerMenu == p.inventoryMenu, "olhar para o outro lado: o alto-forno fecha (" + f.last() + ")");
            p.setYRot(0f);
        }
        if (at(290)) {
            check(f.isOpen() && f.stack(input).is(Items.RAW_IRON), "olhar de novo: abre sozinho e o minério continua lá");
            allowFurnaceScreen = true;
            client.gameMode.useItemOn(p, net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.phys.BlockHitResult(
                    f.frontCenter(), net.minecraft.core.Direction.NORTH, furnacePos, false));
        }
        if (at(300)) {
            check(client.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractFurnaceScreen<?> && !f.isOpen(),
                    "usar o alto-forno do jeito normal ainda abre a tela do jogo");
            p.closeContainer();
            allowFurnaceScreen = false;
            BlockPos at = furnacePos;
            BlockPos far = farFurnacePos;
            onServer(client, player -> {
                player.level().setBlock(at, Blocks.AIR.defaultBlockState(), 3);
                player.level().setBlock(far, Blocks.AIR.defaultBlockState(), 3);
            });
            clearDrops(client);
            p.setXRot(0f);
            handAway(0);
            handAway(1);
        }
        if (at(306)) {
            next();
        }
    }

    // ------------------------------------------------------------------ arremesso com a mão

    private static Vec3 probeVel = null, throwFrom = null, throwDir = null;
    private static int probeCount = 0, probeTicks = 0;

    /** No servidor: os itens arremessados pelo jogador agora há pouco (quantos e a velocidade do último). */
    private static void probeThrown(Minecraft client) {
        probeVel = null;
        probeCount = 0;
        onServer(client, player -> {
            for (var e : player.level().getEntitiesOfClass(net.minecraft.world.entity.projectile.Projectile.class,
                    player.getBoundingBox().inflate(48), x -> x.getOwner() == player && x.tickCount <= 10)) {
                probeCount++;
                if (probeVel == null || e.tickCount < probeTicks) {
                    probeVel = e.getDeltaMovement();
                    probeTicks = e.tickCount;
                }
            }
        });
    }

    /** Arremesso simulado: a mão começa em {@code from} (com o grip) e anda {@code perTick} por tick, 3 ticks; solta no 4º. */
    private static void swing(Minecraft client, int t0, int hand, Vec3 from, Vec3 perTick) {
        swing(client, t0, hand, from, perTick, null);
    }

    /** Como {@link #swing(Minecraft, int, int, Vec3, Vec3)}, com o controle apontando para {@code pointing} (null: para onde vai). */
    private static void swing(Minecraft client, int t0, int hand, Vec3 from, Vec3 perTick, Vec3 pointing) {
        Vec3 dir = pointing != null ? pointing : perTick.lengthSqr() < 1.0e-9 ? new Vec3(0, 0, 1) : perTick;
        if (at(t0)) {
            SimulatedVr.setHand(hand, from, dir);
            grip(client, hand, true);
        }
        for (int k = 1; k <= 3; k++) {
            if (at(t0 + k)) {
                SimulatedVr.setHand(hand, from.add(perTick.scale(k)), dir);
            }
        }
        if (at(t0 + 4)) {
            com.handsonvr.hands.ThrowGesture.last = null;
            com.handsonvr.hands.ThrowPower.lastVelocity = -1f;
            grip(client, hand, false);
        }
        if (at(t0 + 5)) {
            probeThrown(client);
        }
        if (at(t0 + 7) && probeCount == 0) {
            probeThrown(client); // o servidor ainda não tinha lançado: confere de novo
        }
    }

    private static String speedOf(com.handsonvr.hands.ThrowGesture.Throw t) {
        return t == null ? "sem arremesso" : String.format(Locale.ROOT, "mão a %.1f m/s, força %.2f, velocidade %.2f",
                t.speed(), t.power(), com.handsonvr.hands.ThrowPower.lastVelocity);
    }

    private static void throwing(Minecraft client) {
        LocalPlayer p = client.player;
        Inventory inv = p.getInventory();
        HandsController hc = hands();
        HandsOnConfig config = HandsOnConfig.get();
        Vec3 eye = p.getEyePosition();
        Vec3 right = eye.add(-0.35, -0.3, 0.35), left = eye.add(0.35, -0.3, 0.35);
        var gesture = com.handsonvr.hands.ThrowGesture.last;
        if (at(1)) {
            section("Arremesso com a mão");
            check(com.handsonvr.hands.ThrowGesture.isThrowable(new ItemStack(Items.SNOWBALL))
                            && com.handsonvr.hands.ThrowGesture.isThrowable(new ItemStack(Items.EGG))
                            && com.handsonvr.hands.ThrowGesture.isThrowable(new ItemStack(Items.ENDER_PEARL))
                            && com.handsonvr.hands.ThrowGesture.isThrowable(new ItemStack(Items.SPLASH_POTION))
                            && com.handsonvr.hands.ThrowGesture.isThrowable(new ItemStack(Items.LINGERING_POTION))
                            && com.handsonvr.hands.ThrowGesture.isThrowable(new ItemStack(Items.EXPERIENCE_BOTTLE))
                            && com.handsonvr.hands.ThrowGesture.isThrowable(new ItemStack(Items.WIND_CHARGE))
                            && !com.handsonvr.hands.ThrowGesture.isThrowable(new ItemStack(Items.POTION))
                            && !com.handsonvr.hands.ThrowGesture.isThrowable(new ItemStack(Items.ARROW)),
                    "arremessáveis: bola de neve, ovo, pérola, poção arremessável e persistente, frasco de experiência e carga de vento (poção de beber e flecha não)");
            float[] potion = com.handsonvr.hands.ThrowGesture.rotationFor(new Vec3(0, 0, 1), -20f);
            check(Math.abs(potion[1] - 20f) < 0.1f && Math.abs(potion[0]) < 0.1f,
                    "poção para a frente: a mira vai 20° para baixo, porque o jogo a levanta 20°");
            onServer(client, player -> {
                Inventory pinv = player.getInventory();
                pinv.clearContent();
                pinv.setItem(0, new ItemStack(Items.IRON_SWORD));
                pinv.setItem(1, new ItemStack(Items.SNOWBALL, 16));
                pinv.setItem(11, new ItemStack(Items.SNOWBALL, 8));
                pinv.setItem(Inventory.SLOT_OFFHAND, net.minecraft.world.item.alchemy.PotionContents.createItemStack(
                        Items.SPLASH_POTION, net.minecraft.world.item.alchemy.Potions.HEALING).copyWithCount(2));
            });
            p.setYRot(0f);
            p.setXRot(0f);
            handAway(0);
            handAway(1);
            config.physicalThrow = true;
            config.throwRealPower = true;
            com.handsonvr.hands.ThrowGesture.debugTickTime = true; // a mão anda a cada tick: velocidade exata
        }
        if (at(5)) {
            inv.setSelectedSlot(1); // bolas de neve na mão direita
        }
        // --- 1) forte, para a frente: 0,3 m por tick (6 m/s)
        swing(client, 8, 0, right, new Vec3(0, 0.02, 0.3));
        if (at(9)) {
            GrabSystem.Grab g = hc.grabs().get(0);
            check(g != null && g.origin() == GrabSystem.Origin.HAND, "grip com a bola de neve: a mão segura o próprio item");
        }
        if (at(16)) {
            check(gesture != null && p.getMainHandItem().is(Items.SNOWBALL) && p.getMainHandItem().getCount() == 15
                            && hc.grabs().get(0) == null,
                    "soltar o grip com a mão rápida: jogou UMA bola de neve e as outras 15 ficaram na mão (" + speedOf(gesture) + ")");
            check(gesture != null && gesture.direction().z > 0.95, "o arremesso vai para onde a mão ia: para a frente");
            check(probeCount == 1 && probeVel != null && probeVel.normalize().z > 0.9,
                    "o servidor lançou a bola de neve nessa direção (" + fmt3(probeVel) + ")");
            check(gesture != null && Math.abs(com.handsonvr.hands.ThrowPower.lastVelocity - 1.5f * gesture.power()) < 0.01f,
                    "força real no singleplayer: velocidade = a do jogo (1,5) x a força do gesto (" + speedOf(gesture) + ")");
        }
        // --- 2) devagar (0,8 m/s): não arremessa
        swing(client, 20, 0, right, new Vec3(0, 0, 0.04));
        if (at(28)) {
            check(gesture == null && probeCount == 0 && p.getMainHandItem().getCount() == 15,
                    "mão devagar: não arremessa, a bola continua na mão");
        }
        // --- 3) da mochila, para a esquerda
        if (at(30)) {
            inv.setSelectedSlot(0); // espada na mão
            check(openBackpack(client, 1), "mochila aberta na mão esquerda");
            pointBackpackSlot(0, 11);
        }
        if (at(32)) {
            throwFrom = VrBridge.hand(0).position();
            throwDir = VrBridge.hand(0).direction(); // a ponta da mão continua no espaço da mochila
        }
        if (throwFrom != null && stepTicks >= 34 && stepTicks <= 39) {
            swing(client, 34, 0, throwFrom, new Vec3(0.3, 0.02, 0), throwDir);
        }
        if (at(35)) {
            GrabSystem.Grab g = hc.grabs().get(0);
            check(g != null && g.origin() == GrabSystem.Origin.BACKPACK && p.getMainHandItem().is(Items.SNOWBALL),
                    "grip nas bolas de neve da mochila: vêm para a mão");
        }
        if (at(42)) {
            check(gesture != null && gesture.direction().x > 0.9 && slot(p, 11).is(Items.SNOWBALL) && slot(p, 11).getCount() == 7
                            && p.getMainHandItem().is(Items.IRON_SWORD),
                    "da mochila, jogando para a esquerda: saiu uma e as outras 7 voltaram para a mochila (" + speedOf(gesture) + ")");
            check(probeCount == 1 && probeVel != null && probeVel.normalize().x > 0.85,
                    "o servidor lançou para a esquerda (" + fmt3(probeVel) + ")");
            grip(client, 1, false); // fecha a mochila
            throwFrom = null;
        }
        // --- 4) mão esquerda, poção arremessável na horizontal: sai reta (sem os 20° para cima do jogo)
        swing(client, 46, 1, left, new Vec3(0, 0, 0.3));
        if (at(53)) {
            check(gesture != null && Math.abs(gesture.xRot() - 20f) < 4f && p.getOffhandItem().getCount() == 1,
                    "mão esquerda: jogou uma poção e a mira mandada foi " + (gesture == null ? "-" : String.format(Locale.ROOT, "%.1f°", gesture.xRot()))
                            + " para baixo, que o jogo levanta para a horizontal");
            // A velocidade de saída, desfazendo os ticks de voo (a poção perde 1% por tick e cai 0,05 por tick).
            double vy0 = probeVel == null ? 1 : probeVel.y / Math.pow(0.99, probeTicks) + 0.05 * probeTicks;
            check(probeVel != null && probeVel.z > 0.1 && Math.abs(vy0) < 0.06,
                    String.format(Locale.ROOT, "o servidor lançou a poção reta, sem subir (saída vertical %.3f; sem o desconto seria %.3f)",
                            vy0, 0.5 * (gesture == null ? 1 : gesture.power()) * Math.sin(Math.toRadians(20))));
            check(gesture != null && Math.abs(com.handsonvr.hands.ThrowPower.lastVelocity - 0.5f * gesture.power()) < 0.01f,
                    "a poção também ganha a força real (0,5 x " + (gesture == null ? "-" : String.format(Locale.ROOT, "%.2f", gesture.power())) + ")");
            inv.setSelectedSlot(1);
        }
        // --- 5) arremesso fraco (3 m/s): a bola sai mais devagar
        swing(client, 56, 0, right, new Vec3(0, 0.01, 0.17));
        if (at(64)) {
            float v = com.handsonvr.hands.ThrowPower.lastVelocity;
            check(gesture != null && v > 0.5f && v < 1.2f, "arremesso fraco: a bola sai mais devagar e cai perto (" + speedOf(gesture) + ")");
            config.throwRealPower = false;
        }
        // --- 6) força real desligada (como em servidor): sempre a velocidade do jogo
        swing(client, 66, 0, right, new Vec3(0, 0.01, 0.17));
        if (at(74)) {
            check(gesture != null && Math.abs(com.handsonvr.hands.ThrowPower.lastVelocity - 1.5f) < 0.001f,
                    "força real desligada: a velocidade é a do jogo, qualquer que seja o gesto (" + speedOf(gesture) + ")");
            config.throwRealPower = true;
            config.physicalThrow = false;
        }
        // --- 7) arremesso desligado: soltar rápido não joga nada
        swing(client, 76, 0, right, new Vec3(0, 0.02, 0.3));
        if (at(84)) {
            check(gesture == null && probeCount == 0 && p.getMainHandItem().is(Items.SNOWBALL) && p.getMainHandItem().getCount() == 13,
                    "com o arremesso desligado, soltar o grip rápido não joga nada (13 bolas na mão)");
            config.physicalThrow = true;
            com.handsonvr.hands.ThrowGesture.debugTickTime = false;
            inv.setSelectedSlot(0);
            handAway(0);
            handAway(1);
        }
        if (at(88)) {
            next();
        }
    }

    // ------------------------------------------------------------------ distribuir arrastando e pegar metade

    private static boolean cellIs(com.handsonvr.table.CraftingTable table, int cell, Item item, int count) {
        return table.cellStack(cell).is(item) && table.cellStack(cell).getCount() == count;
    }

    private static void dragAndHalf(Minecraft client) {
        LocalPlayer p = client.player;
        Inventory inv = p.getInventory();
        HandsController hc = hands();
        com.handsonvr.table.CraftingTable table = hc.table();
        HandsController.DragKind TABLE = HandsController.DragKind.TABLE, BACKPACK = HandsController.DragKind.BACKPACK;
        if (at(1)) {
            section("Distribuir arrastando e pegar metade");
            tablePos = BlockPos.containing(p.getX(), p.getY(), p.getZ()).offset(0, 0, 1);
            BlockPos at = tablePos;
            onServer(client, player -> {
                Inventory pinv = player.getInventory();
                pinv.clearContent();
                pinv.setItem(0, new ItemStack(Items.IRON_SWORD));
                pinv.setItem(9, new ItemStack(Items.SUGAR_CANE, 9));
                pinv.setItem(10, new ItemStack(Items.OAK_PLANKS, 7));
                pinv.setItem(12, new ItemStack(Items.BREAD, 10));
                pinv.setItem(13, new ItemStack(Items.BIRCH_PLANKS, 8)); // outra madeira: não se junta às tábuas da mesa
                player.level().setBlock(at, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
                player.level().setBlock(at.above(), Blocks.AIR.defaultBlockState(), 3);
            });
            inv.setSelectedSlot(0);
            p.setYRot(0f);
            p.setXRot(38f);
            handAway(0);
            handAway(1);
        }
        // --- mochila: metade
        if (at(6)) {
            check(openBackpack(client, 1), "mochila aberta na mão esquerda");
            pointBackpackSlot(0, 12); // 10 pães
        }
        if (at(8)) {
            SimulatedVr.setTrigger(0, true);
            grip(client, 0, true); // gatilho apertado + grip: a metade
        }
        if (at(11)) {
            check(p.getMainHandItem().is(Items.BREAD) && p.getMainHandItem().getCount() == 5 && slot(p, 12).getCount() == 5,
                    "na mochila, gatilho apertado + grip em 10 pães: 5 vêm para a mão e 5 ficam");
            SimulatedVr.setTrigger(0, false);
            pointAway(client, 0);
            grip(client, 0, false);
        }
        // --- mochila: arrastar pelo crafting 2x2
        if (at(14)) {
            pointBackpackSlot(0, 13); // 8 tábuas
        }
        if (at(16)) {
            grip(client, 0, true);
        }
        if (at(18)) {
            pointBackpackSlot(0, InvOps.CRAFT_START);
        }
        if (at(20)) {
            SimulatedVr.setTrigger(0, true);
            check(hc.onTrigger(client, 0), "gatilho no crafting 2x2 com as tábuas: começa a arrastar");
        }
        for (int k = 1; k <= 3; k++) {
            if (at(20 + 2 * k)) {
                pointBackpackSlot(0, InvOps.CRAFT_START + k);
            }
        }
        if (at(28)) {
            boolean marked = true;
            for (int k = 0; k < 4; k++) {
                marked &= hc.dragMarked(BACKPACK, InvOps.CRAFT_START + k);
            }
            check(marked, "passando pelo crafting 2x2: os 4 espaços ficam marcados");
            SimulatedVr.setTrigger(0, false);
            hc.onTriggerReleased(client, 0);
        }
        if (at(32)) {
            boolean two = true;
            for (int k = 0; k < 4; k++) {
                two &= slot(p, InvOps.CRAFT_START + k).is(Items.BIRCH_PLANKS) && slot(p, InvOps.CRAFT_START + k).getCount() == 2;
            }
            check(two && slot(p, InvOps.RESULT).is(Items.CRAFTING_TABLE),
                    "soltar o gatilho: as 8 tábuas viram 2 em cada espaço e a mesa de trabalho aparece no resultado");
            pointAway(client, 0);
            grip(client, 0, false);
        }
        if (at(34)) {
            grip(client, 1, false); // fecha a mochila: o crafting 2x2 volta para o inventário
        }
        // --- mesa: 9 canas arrastadas pela linha de cima
        if (at(38)) {
            check(openBackpack(client, 1), "mochila aberta de novo");
            pointBackpackSlot(0, 9); // 9 canas
        }
        if (at(40)) {
            grip(client, 0, true);
        }
        if (at(42)) {
            pointTable(0, table.cellCenter(0).add(0, 0.03, 0));
        }
        if (at(52)) {
            check(table.isOpen() && p.getMainHandItem().is(Items.SUGAR_CANE), "canas sobre a mesa: ela abriu");
            SimulatedVr.setTrigger(0, true);
            check(hc.onTrigger(client, 0), "gatilho na casa de cima à esquerda: começa a arrastar");
        }
        if (at(54)) {
            pointTable(0, table.cellCenter(1).add(0, 0.03, 0));
        }
        if (at(56)) {
            pointTable(0, table.cellCenter(2).add(0, 0.03, 0));
        }
        if (at(58)) {
            check(hc.dragMarked(TABLE, 0) && hc.dragMarked(TABLE, 1) && hc.dragMarked(TABLE, 2) && !hc.dragMarked(TABLE, 4),
                    "passando pela linha de cima: as 3 casas ficam marcadas (e só elas)");
            SimulatedVr.setTrigger(0, false);
            hc.onTriggerReleased(client, 0);
        }
        if (at(62)) {
            check(cellIs(table, 0, Items.SUGAR_CANE, 3) && cellIs(table, 1, Items.SUGAR_CANE, 3) && cellIs(table, 2, Items.SUGAR_CANE, 3)
                            && total(inv, Items.SUGAR_CANE) == 0,
                    "soltar o gatilho: as 9 canas viram 3 em cada casa da linha de cima (" + table.last() + ")");
            check(table.resultStack().is(Items.PAPER) && table.resultTotal() == 9,
                    "e o papel aparece: " + table.resultTotal() + " no total");
            pointAway(client, 0);
            grip(client, 0, false);
        }
        // --- mesa: um toque = um; arrastar pelas 3 de baixo = divide o resto
        if (at(64)) {
            pointBackpackSlot(0, 10); // 7 tábuas
        }
        if (at(66)) {
            grip(client, 0, true);
        }
        if (at(68)) {
            pointTable(0, table.cellCenter(4).add(0, 0.03, 0));
        }
        if (at(70)) {
            SimulatedVr.setTrigger(0, true);
            hc.onTrigger(client, 0);
        }
        if (at(71)) {
            SimulatedVr.setTrigger(0, false);
            hc.onTriggerReleased(client, 0);
        }
        if (at(74)) {
            check(cellIs(table, 4, Items.OAK_PLANKS, 1) && p.getMainHandItem().is(Items.OAK_PLANKS) && p.getMainHandItem().getCount() == 6,
                    "um toque do gatilho, sem arrastar: uma tábua na casa do meio e 6 na mão (casa: " + describe(table.cellStack(4))
                            + ", mão: " + describe(p.getMainHandItem()) + ", " + hc.lastAction() + ")");
            pointTable(0, table.cellCenter(6).add(0, 0.03, 0));
        }
        if (at(76)) {
            SimulatedVr.setTrigger(0, true);
            hc.onTrigger(client, 0);
        }
        if (at(78)) {
            pointTable(0, table.cellCenter(7).add(0, 0.03, 0));
        }
        if (at(80)) {
            pointTable(0, table.cellCenter(8).add(0, 0.03, 0));
        }
        if (at(82)) {
            SimulatedVr.setTrigger(0, false);
            hc.onTriggerReleased(client, 0);
        }
        if (at(86)) {
            check(cellIs(table, 6, Items.OAK_PLANKS, 2) && cellIs(table, 7, Items.OAK_PLANKS, 2) && cellIs(table, 8, Items.OAK_PLANKS, 2)
                            && !p.getMainHandItem().is(Items.OAK_PLANKS),
                    "arrastando pelas 3 casas de baixo: as 6 tábuas da mão viram 2 em cada");
            pointAway(client, 0);
            grip(client, 0, false);
        }
        if (at(88)) {
            grip(client, 1, false); // fecha a mochila
            inv.setSelectedSlot(0);
            pointTable(0, table.cellCenter(0).add(0, 0.03, 0)); // 3 canas
        }
        // --- mesa: metade
        if (at(92)) {
            SimulatedVr.setTrigger(0, true);
            grip(client, 0, true);
        }
        if (at(95)) {
            GrabSystem.Grab g = hc.grabs().get(0);
            check(g != null && p.getMainHandItem().is(Items.SUGAR_CANE) && p.getMainHandItem().getCount() == 2
                            && cellIs(table, 0, Items.SUGAR_CANE, 1),
                    "na mesa, gatilho apertado + grip numa casa com 3 canas: 2 vêm para a mão (a metade, para cima) e 1 fica");
            SimulatedVr.setTrigger(0, false);
            pointAway(client, 0);
            grip(client, 0, false);
        }
        if (at(98)) {
            BlockPos at = tablePos;
            onServer(client, player -> player.level().setBlock(at, Blocks.AIR.defaultBlockState(), 3));
            clearDrops(client);
            p.setXRot(0f);
            handAway(0);
            handAway(1);
        }
        if (at(106)) {
            next();
        }
    }

    // ------------------------------------------------------------------ mira do arco (opcional)

    private static BlockPos wallAt;

    private static void bowAimMarker(Minecraft client) {
        LocalPlayer p = client.player;
        Vec3 eye = p.getEyePosition();
        if (at(1)) {
            section("Mira do arco (opcional)");
            check(!HandsOnConfig.get().bowAim && com.handsonvr.hands.BowAim.predict(client) == null,
                    "a mira do arco nasce desligada");
            check(com.handsonvr.hands.BowAim.power(0) == 0f && com.handsonvr.hands.BowAim.power(20) == 1f
                            && Math.abs(com.handsonvr.hands.BowAim.power(10) - 0.4167f) < 0.001f,
                    "força do arco pelo tempo puxando, como no jogo (0, 0,42 em meio segundo, 1 em um segundo)");
            p.setYRot(0f);
            p.setXRot(0f);
            // Uma parede de pedra 10 blocos à frente, na altura dos olhos.
            wallAt = BlockPos.containing(p.getX(), eye.y, p.getZ() + 10);
            BlockPos wall = wallAt;
            onServer(client, player -> {
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dy = -3; dy <= 2; dy++) {
                        player.level().setBlock(wall.offset(dx, dy, 0), Blocks.STONE.defaultBlockState(), 3);
                    }
                }
            });
        }
        if (at(10)) {
            Vec3 strong = com.handsonvr.hands.BowAim.trace(client.level, p, eye, new Vec3(0, 0, 3));
            check(strong != null && Math.abs(strong.z - wallAt.getZ()) < 0.01 && strong.y < eye.y && strong.y > eye.y - 0.6,
                    "puxão inteiro: a flecha acerta a parede a 10 blocos um pouco abaixo da mira (" + fmt3(strong) + ")");
            Vec3 weak = com.handsonvr.hands.BowAim.trace(client.level, p, eye, new Vec3(0, 0, 0.6));
            check(weak != null && Math.abs(weak.y - p.getY()) < 0.01 && weak.z < wallAt.getZ(),
                    "puxão fraco: a flecha cai no chão antes da parede (" + fmt3(weak) + ")");
            HandsOnConfig.get().bowAim = true;
            com.handsonvr.hands.BowAim.debugShot = new com.handsonvr.hands.BowAim.Shot(eye.add(0.1, -0.2, 0.3), new Vec3(0.05, 0.05, 2.4));
        }
        if (at(12)) {
            Vec3 hit = com.handsonvr.hands.BowAim.predict(client);
            check(hit != null && Math.abs(hit.z - wallAt.getZ()) < 0.01, "ligada e com a corda puxada: a mira aparece na parede (" + fmt3(hit) + ")");
        }
        if (at(16)) {
            screenshot(client, "12-mira-do-arco");
        }
        if (at(18)) {
            HandsOnConfig.get().bowAim = false;
            check(com.handsonvr.hands.BowAim.predict(client) == null, "desligada de novo: sem mira");
            com.handsonvr.hands.BowAim.debugShot = null;
            BlockPos wall = wallAt;
            onServer(client, player -> {
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dy = -3; dy <= 2; dy++) {
                        player.level().setBlock(wall.offset(dx, dy, 0), Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            });
        }
        if (at(22)) {
            finish(client);
        }
    }

    private static String fmt3(Vec3 v) {
        return v == null ? "nada" : String.format(Locale.ROOT, "%.2f, %.2f, %.2f", v.x, v.y, v.z);
    }

    private static boolean hasMixin(Class<?> c) {
        return Stream.of(c.getDeclaredMethods()).anyMatch(m -> m.getName().contains("handsonvr$"));
    }

    private static boolean hasMixin(String className) throws ClassNotFoundException {
        Class<?> c = Class.forName(className);
        return Stream.of(c.getDeclaredMethods()).anyMatch(m -> m.getName().contains("handsonvr$"));
    }

    // ------------------------------------------------------------------ VR nulo: ligação com os controles do Vivecraft

    private static void runNullVr(Minecraft client) {
        HolsterManager hm = holsters();
        HandsController hc = hands();
        LocalPlayer p = client.player;
        Inventory inv = p.getInventory();
        switch (step) {
            case 2 -> { // o VR (nulo) ligou e as 7 ações estão sendo escutadas
                int listening = VivecraftInput.debugListening();
                if (listening == 7) {
                    pass("VR nulo do Vivecraft ligado: escutando grips, gatilhos, A, B e X (7 ações)");
                    info("B = " + VivecraftInput.debugName("radialMain") + ", X = " + VivecraftInput.debugName("radialOff")
                            + ", gatilho direito = " + VivecraftInput.debugName("attack"));
                    next();
                } else if (stepTicks > 400) {
                    fail("VR nulo: ações escutadas " + listening + "/7 (VR ativo: "
                            + org.vivecraft.api.client.VRClientAPI.instance().isVRActive() + ")");
                    finish(client);
                }
            }
            case 3 -> {
                if (at(1)) {
                    onServer(client, player -> {
                        player.setGameMode(GameType.SURVIVAL);
                        Inventory pinv = player.getInventory();
                        pinv.clearContent();
                        pinv.setItem(0, new ItemStack(Items.STONE_PICKAXE));
                        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
                        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Excalibur"));
                        pinv.setItem(4, sword);
                        pinv.setItem(9, new ItemStack(Items.BOW));
                        pinv.setItem(40, new ItemStack(Items.TORCH, 16));
                    });
                    SimulatedVr.setEnabled(true); // poses das mãos pelo teste (o resto é o Vivecraft de verdade)
                }
                if (at(20)) {
                    inv.setSelectedSlot(4);
                    handAt(0, Holster.RIGHT_SHOULDER);
                }
                if (at(25)) {
                    check(VivecraftInput.debugNotify("gripMain", true, 0f),
                            "Vivecraft: grip direito no ombro foi consumido pelo coldre (a hotbar não troca)");
                    VivecraftInput.debugNotify("gripMain", false, 0f);
                }
                if (at(40)) {
                    check(p.getMainHandItem().isEmpty() && hm.binding(Holster.RIGHT_SHOULDER) != null,
                            "Vivecraft: o grip direito guardou a espada no ombro");
                    inv.setSelectedSlot(0);
                }
                if (at(45)) {
                    SimulatedVr.setGrip(0, true);
                    check(VivecraftInput.debugNotify("gripMain", true, 0f) && hm.isDrawing(0), "Vivecraft: grip direito no ombro sacou a espada");
                }
                if (at(60)) {
                    check(p.getMainHandItem().is(Items.DIAMOND_SWORD), "Vivecraft: a espada está na mão");
                    SimulatedVr.setGrip(0, false);
                    check(VivecraftInput.debugNotify("gripMain", false, 0f), "Vivecraft: soltar o grip direito devolve a espada");
                }
                if (at(75)) {
                    check(p.getMainHandItem().is(Items.STONE_PICKAXE), "Vivecraft: a picareta voltou para a mão");
                    SimulatedVr.setHand(0, p.getEyePosition().add(0, -0.3, 0.6), new Vec3(0, 0, 1));
                }
                if (at(80)) {
                    check(VivecraftInput.debugNotify("gripMain", true, 0f) && inv.getSelectedSlot() == 0,
                            "Vivecraft: fora dos coldres o grip é consumido e não troca mais a hotbar (o radial cuida dela)");
                    VivecraftInput.debugNotify("gripMain", false, 0f);
                    handAway(0);
                    next();
                }
            }
            case 4 -> { // gatilho esquerdo: usa a mão esquerda
                if (at(1)) {
                    Vec3 feet = p.position();
                    torchAt = BlockPos.containing(feet.x, feet.y, feet.z + 2);
                    Vec3 hand = p.getEyePosition().add(-0.2, -0.5, 0.3);
                    Vec3 target = new Vec3(torchAt.getX() + 0.5, feet.y - 0.001, torchAt.getZ() + 0.5);
                    SimulatedVr.setHand(1, hand, target.subtract(hand));
                }
                if (at(5)) {
                    check(VivecraftInput.debugNotify("teleport", true, 0f), "Vivecraft: o gatilho esquerdo foi consumido (sem teleporte)");
                    VivecraftInput.debugNotify("teleport", false, 0f);
                }
                if (at(25)) {
                    check(client.level.getBlockState(torchAt).is(Blocks.TORCH),
                            "Vivecraft: o gatilho esquerdo colocou a tocha com a mão esquerda (" + OffhandUse.debugLast() + ")");
                    handAway(1);
                    next();
                }
            }
            case 5 -> { // B e X: radial (sem o radial do Vivecraft nem a tela de inventário)
                if (at(1)) {
                    inv.setSelectedSlot(0);
                    handInFront(client, 0);
                    handInFront(client, 1);
                    SimulatedVr.setMenuButton(0, true);
                    check(VivecraftInput.debugNotify("radialMain", true, 0f) && hc.radial().isOpen() && hc.radial().opener() == 0,
                            "Vivecraft: B foi consumido e abriu o radial da hotbar na mão direita");
                    pointRadial(0, 4);
                }
                if (at(4)) {
                    SimulatedVr.setMenuButton(0, false);
                    VivecraftInput.debugNotify("radialMain", false, 0f);
                    check(!hc.radial().isOpen() && inv.getSelectedSlot() == 4 && p.getMainHandItem().isEmpty(),
                            "Vivecraft: soltar o B escolheu o espaço 5 (mão vazia, a espada está no coldre)");
                }
                if (at(8)) {
                    SimulatedVr.setMenuButton(1, true);
                    check(VivecraftInput.debugNotify("radialOff", true, 0f) && hc.radial().opener() == 1,
                            "Vivecraft: X foi consumido e abriu o radial na mão esquerda");
                    pointRadial(1, 0);
                }
                if (at(12)) {
                    check(client.gui.screen() == null, "Vivecraft: o X não abriu a tela de inventário");
                    SimulatedVr.setMenuButton(1, false);
                    VivecraftInput.debugNotify("radialOff", false, 0f);
                    check(p.getOffhandItem().is(Items.STONE_PICKAXE), "Vivecraft: soltar o X pôs a picareta na mão esquerda");
                }
                if (at(16)) {
                    next();
                }
            }
            case 6 -> { // grip esquerdo no ombro: mochila; gatilho direito no painel é da mochila
                if (at(1)) {
                    handAt(1, Holster.LEFT_SHOULDER);
                }
                if (at(4)) {
                    SimulatedVr.setGrip(1, true);
                    check(VivecraftInput.debugNotify("gripOff", true, 0f) && hc.backpack().isOpen(),
                            "Vivecraft: grip esquerdo no ombro esquerdo abriu a mochila");
                    holdBackpackInFront(client, 1);
                }
                if (at(7)) {
                    check(VivecraftInput.debugNotify("teleport", true, 0f), "Vivecraft: gatilho esquerdo da mão da mochila consumido");
                    VivecraftInput.debugNotify("teleport", false, 0f);
                    pointBackpackSlot(0, 9);
                }
                if (at(10)) {
                    SimulatedVr.setGrip(0, true);
                    check(VivecraftInput.debugNotify("gripMain", true, 0f) && p.getMainHandItem().is(Items.BOW),
                            "Vivecraft: grip direito no arco da mochila pegou o arco");
                    pointBackpack(0, hc.backpack().element(Backpack.Kind.DROP));
                }
                if (at(13)) {
                    check(VivecraftInput.debugNotify("attack", true, 0f), "Vivecraft: gatilho direito na zona de descarte é da mochila");
                    VivecraftInput.debugNotify("attack", false, 0f);
                    check(InvOps.stack(p, InvOps.handSlot(p, 0)).isEmpty(), "Vivecraft: soltou o arco (era 1)");
                    SimulatedVr.setGrip(0, false);
                    VivecraftInput.debugNotify("gripMain", false, 0f);
                    SimulatedVr.setGrip(1, false);
                    VivecraftInput.debugNotify("gripOff", false, 0f);
                }
                if (at(20)) {
                    check(!hc.backpack().isOpen(), "Vivecraft: soltar o grip esquerdo fechou a mochila");
                    pointAway(client, 0);
                }
                if (at(23)) {
                    check(!VivecraftInput.debugNotify("attack", true, 0f), "Vivecraft: longe da mochila o gatilho direito ataca normalmente");
                    VivecraftInput.debugNotify("attack", false, 0f);
                    next();
                }
            }
            case 7 -> { // analógico direito: é do Vivecraft (gira a câmera), não troca mais a hotbar
                if (at(1)) {
                    inv.setSelectedSlot(0);
                    boolean consumed = VivecraftInput.debugNotify("rotate", true, 0.9f);
                    VivecraftInput.debugNotify("rotate", false, 0.9f);
                    boolean consumedLeft = VivecraftInput.debugNotify("rotate", true, -0.9f);
                    VivecraftInput.debugNotify("rotate", false, -0.9f);
                    check(!"-".equals(VivecraftInput.debugName("rotate")) && !consumed && !consumedLeft && inv.getSelectedSlot() == 0,
                            "Vivecraft: o analógico direito não é do mod (gira a câmera; a hotbar não muda)");
                }
                if (at(5)) {
                    boolean backpack = org.vivecraft.client_vr.ClientDataHolderVR.getInstance().backpackTracker.isActive(p);
                    check(!backpack, "Vivecraft: a troca pelas costas está desligada (a mochila usa o ombro esquerdo)");
                    next();
                }
            }
            case 8 -> { // arco: na mão que o escolheu, e o gatilho da mão da flecha fica com o Vivecraft
                if (at(1)) {
                    onServer(client, player -> {
                        Inventory pinv = player.getInventory();
                        pinv.clearContent();
                        pinv.setItem(3, new ItemStack(Items.BOW));
                        pinv.setItem(20, new ItemStack(Items.ARROW, 16));
                    });
                    inv.setSelectedSlot(0);
                    handInFront(client, 0);
                    handInFront(client, 1);
                    reverseBefore = BowHand.reverse();
                }
                if (at(5)) {
                    SimulatedVr.setMenuButton(0, true);
                    VivecraftInput.debugNotify("radialMain", true, 0f);
                    pointRadial(0, 3);
                }
                if (at(8)) {
                    SimulatedVr.setMenuButton(0, false);
                    VivecraftInput.debugNotify("radialMain", false, 0f);
                    check(p.getMainHandItem().is(Items.BOW) && org.vivecraft.client_vr.gameplay.trackers.BowTracker.isHoldingBow(p,
                            net.minecraft.world.InteractionHand.MAIN_HAND), "Vivecraft: B no arco, o Vivecraft vê o arco na mão principal");
                }
                if (at(10)) {
                    check(Boolean.TRUE.equals(BowHand.reverse()), "Vivecraft: arco escolhido pela mão direita, Reverse Bow ligado");
                    check(!VivecraftInput.debugNotify("teleport", true, 0f),
                            "Vivecraft: o gatilho esquerdo (mão da flecha) fica com o Vivecraft, sem usar a mão esquerda");
                    VivecraftInput.debugNotify("teleport", false, 0f);
                    SimulatedVr.setMenuButton(1, true);
                    VivecraftInput.debugNotify("radialOff", true, 0f);
                    pointRadial(1, 3);
                }
                if (at(13)) {
                    SimulatedVr.setMenuButton(1, false);
                    VivecraftInput.debugNotify("radialOff", false, 0f);
                    check(p.getMainHandItem().is(Items.BOW) && p.getOffhandItem().isEmpty(),
                            "Vivecraft: X no arco, ele continua no espaço da mão principal");
                }
                if (at(15)) {
                    check(Boolean.FALSE.equals(BowHand.reverse()), "Vivecraft: arco escolhido pela mão esquerda, Reverse Bow desligado");
                    check(!VivecraftInput.debugNotify("attack", true, 0f), "Vivecraft: o gatilho direito (mão da flecha) fica com o Vivecraft");
                    VivecraftInput.debugNotify("attack", false, 0f);
                    inv.setSelectedSlot(0);
                }
                if (at(18)) {
                    check(java.util.Objects.equals(BowHand.reverse(), reverseBefore),
                            "Vivecraft: sem o arco na mão, o Reverse Bow volta ao que era (" + reverseBefore + ")");
                    handAway(0);
                    handAway(1);
                    next();
                }
            }
            case 9 -> { // arremesso: a direção do gesto chega ao servidor pela mira do Vivecraft, não a do controle
                Vec3 from = p.getEyePosition().add(-0.35, -0.3, 0.35);
                if (at(1)) {
                    onServer(client, player -> {
                        Inventory pinv = player.getInventory();
                        pinv.clearContent();
                        pinv.setItem(0, new ItemStack(Items.SNOWBALL, 16));
                    });
                    inv.setSelectedSlot(0);
                    com.handsonvr.hands.ThrowGesture.debugTickTime = true;
                    p.setYRot(0f);
                    p.setXRot(0f);
                }
                if (at(5)) {
                    SimulatedVr.setHand(0, from, new Vec3(1, 0, 0));
                    SimulatedVr.setGrip(0, true);
                    check(VivecraftInput.debugNotify("gripMain", true, 0f), "Vivecraft: grip com a bola de neve na mão é do mod (segura o item)");
                }
                for (int k = 1; k <= 3; k++) {
                    if (at(5 + k)) {
                        SimulatedVr.setHand(0, from.add(0.3 * k, 0.02 * k, 0), new Vec3(1, 0, 0));
                    }
                }
                if (at(9)) {
                    com.handsonvr.hands.ThrowGesture.last = null;
                    SimulatedVr.setGrip(0, false);
                    VivecraftInput.debugNotify("gripMain", false, 0f);
                }
                if (at(10)) {
                    probeThrown(client);
                }
                if (at(12) && probeCount == 0) {
                    probeThrown(client); // o servidor ainda não tinha lançado: confere de novo
                }
                if (at(16)) {
                    check(com.handsonvr.hands.ThrowGesture.last != null && probeVel != null && probeVel.normalize().x > 0.85
                                    && p.getMainHandItem().getCount() == 15,
                            "Vivecraft: o arremesso sai para onde a mão ia (a esquerda), não para a mira do controle ("
                                    + fmt3(probeVel) + "; mira do controle " + org.vivecraft.client.network.ClientNetworking.getActiveAimDir() + ")");
                    handAway(0);
                    next();
                }
            }
            default -> finish(client);
        }
    }

    // ------------------------------------------------------------------ mundo

    private static void deleteWorld(Minecraft client) throws IOException {
        Path dir = client.gameDirectory.toPath().resolve("saves").resolve(WORLD);
        if (Files.exists(dir)) {
            try (Stream<Path> paths = Files.walk(dir)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
        Path saved = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("handsonvr")
                .resolve("holsters").resolve("sp_" + WORLD + ".json");
        Files.deleteIfExists(saved);
    }

    private static void createWorld(Minecraft client) {
        LevelSettings settings = new LevelSettings(WORLD, GameType.SURVIVAL,
                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
        WorldOptions options = new WorldOptions(20261004L, false, false);
        client.createWorldOpenFlows().createFreshLevel(WORLD, settings, options,
                registries -> registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value()
                        .createWorldDimensions(),
                client.gui.screen());
    }
}
