package com.handsonvr.dev;

import com.handsonvr.HandsOnConfig;
import com.handsonvr.HandsOnVR;
import com.handsonvr.holster.BodyFrame;
import com.handsonvr.holster.Holster;
import com.handsonvr.holster.HolsterManager;
import com.handsonvr.holster.HolsterStorage;
import com.handsonvr.input.HotbarScroll;
import com.handsonvr.input.OffhandUse;
import com.handsonvr.vr.SimulatedVr;
import com.handsonvr.vr.VrBridge;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Teste automático de DESENVOLVIMENTO (só com -Dhandsonvr.selftest=true, usado pelo "gradlew prodClient"): cria um
 * mundo plano, finge o VR (mãos posicionadas pelo teste) e confere os coldres de ponta a ponta — guardar, sacar,
 * soltar, mão esquerda, persistência, desenho —, a mão esquerda usando o item e a hotbar pelo analógico. Relatório
 * em run-prod/handsonvr-selftest.txt e fotos em run-prod/screenshots/.
 */
public final class SelfTest {
    private SelfTest() {}

    private static final boolean ENABLED = Boolean.getBoolean("handsonvr.selftest");
    private static final String WORLD = "handsonvr-selftest";
    private static final int TIMEOUT_TICKS = 20 * 60 * 6;

    private static int step = 0, stepTicks = 0, totalTicks = 0;
    private static boolean finished = false;
    private static final List<String> report = new ArrayList<>();
    private static int swordSlot = -1;
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

    private static void check(boolean ok, String what) {
        if (ok) {
            pass(what);
        } else {
            fail(what);
        }
    }

    private static void tick(MinecraftClient client) {
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
            fail("exceção no passo " + step + ": " + t);
            HandsOnVR.LOGGER.error("[SELFTEST] exceção", t);
            finish(client);
        }
    }

    private static void finish(MinecraftClient client) {
        finished = true;
        SimulatedVr.setEnabled(false);
        long fails = report.stream().filter(s -> s.startsWith("FAIL")).count();
        report.add(0, "HandsOn VR self-test: " + (fails == 0 ? "TUDO OK" : fails + " falha(s)"));
        try {
            Files.write(client.runDirectory.toPath().resolve("handsonvr-selftest.txt"), report, StandardCharsets.UTF_8);
        } catch (IOException e) {
            HandsOnVR.LOGGER.error("[SELFTEST] não consegui gravar o relatório", e);
        }
        HandsOnVR.LOGGER.info("[SELFTEST] FIM ({} falhas)", fails);
        client.scheduleStop();
    }

    private static void screenshot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, name + ".png", client.getFramebuffer(), text -> { });
        info("print " + name);
    }

    private static void onServer(MinecraftClient client, Consumer<ServerPlayerEntity> action) {
        var server = client.getServer();
        UUID id = client.player.getUuid();
        server.execute(() -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);
            if (player != null) {
                action.accept(player);
            }
        });
    }

    private static HolsterManager holsters() {
        return HandsOnVR.holsters();
    }

    /** Põe a mão (0/1) bem no ponto do coldre, apontando para a frente do corpo. */
    private static Vec3d handAt(MinecraftClient client, int hand, Holster h) {
        BodyFrame.Frame frame = holsters().body().compute();
        Vec3d at = frame.anchor(h, HandsOnConfig.get());
        SimulatedVr.setHand(hand, at, frame.forward());
        return at;
    }

    private static void handAway(int hand) {
        SimulatedVr.setHand(hand, null, null);
    }

    private static String describe(ItemStack stack) {
        if (stack.isEmpty()) {
            return "vazio";
        }
        Text name = stack.get(DataComponentTypes.CUSTOM_NAME);
        return stack.getCount() + "x " + stack.getItem() + (name == null ? "" : " \"" + name.getString() + "\"");
    }

    private static int find(PlayerInventory inv, net.minecraft.item.Item item) {
        for (int i = 0; i < inv.size(); i++) {
            if (inv.getStack(i).isOf(item)) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ roteiro

    /**
     * Com -Dhandsonvr.selftest.nullvr=true o Vivecraft roda o VR de verdade com o "VR nulo" (sem óculos): as ações dos
     * controles existem, e o teste dispara os avisos de aperto que o controle dispararia (grip, gatilho esquerdo,
     * analógico direito) para conferir a ligação com o Vivecraft.
     */
    private static final boolean NULL_VR = Boolean.getBoolean("handsonvr.selftest.nullvr");

    private static void runStep(MinecraftClient client) throws Exception {
        if (NULL_VR && step >= 2) {
            runNullVr(client);
            return;
        }
        HolsterManager hm = holsters();
        PlayerInventory inv = client.player == null ? null : client.player.getInventory();
        switch (step) {
            case 0 -> { // tela inicial: mundo plano novo
                if ((client.currentScreen instanceof TitleScreen && client.getOverlay() == null && stepTicks > 40)
                        || stepTicks > 600) {
                    deleteWorld(client);
                    createWorld(client);
                    info("criando mundo de teste");
                    next();
                }
            }
            case 1 -> {
                if (client.world == null || client.player == null) {
                    stepTicks = 0;
                } else if (stepTicks > 60) {
                    pass("mod carregou e o mundo abriu (mixins aplicados)");
                    next();
                }
            }
            case 2 -> { // inventário de teste e VR simulado
                if (stepTicks == 1) {
                    onServer(client, player -> {
                        player.changeGameMode(GameMode.SURVIVAL);
                        PlayerInventory pinv = player.getInventory();
                        pinv.clear();
                        pinv.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
                        sword.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Excalibur"));
                        pinv.setStack(4, sword);
                        pinv.setStack(2, new ItemStack(Items.BREAD, 8));
                        pinv.setStack(40, new ItemStack(Items.TORCH, 16));
                        player.getServerWorld().setTimeOfDay(1000);
                    });
                    SimulatedVr.setEnabled(true);
                    client.player.setYaw(0);
                    client.player.setPitch(0);
                    handAway(0);
                    handAway(1);
                }
                if (stepTicks == 20) {
                    inv.selectedSlot = 4;
                }
                if (stepTicks == 30) {
                    info("inventário: mão " + describe(client.player.getMainHandStack()) + ", esquerda "
                            + describe(client.player.getOffHandStack()));
                    next();
                }
            }
            case 3 -> { // GUARDAR: espada na mão + grip no ombro direito
                if (stepTicks == 1) {
                    handAt(client, 0, Holster.RIGHT_SHOULDER);
                }
                if (stepTicks == 5) {
                    check(hm.zoneOf(0) == Holster.RIGHT_SHOULDER, "mão no ombro direito: está na zona do coldre");
                    check(hm.press(client, 0), "grip no ombro direito com a espada na mão: guardou");
                }
                if (stepTicks == 25) {
                    swordSlot = find(inv, Items.DIAMOND_SWORD);
                    HolsterStorage.Binding b = hm.binding(Holster.RIGHT_SHOULDER);
                    info("depois de guardar: mão " + describe(client.player.getMainHandStack()) + ", espada no espaço " + swordSlot);
                    check(client.player.getMainHandStack().isEmpty(), "guardar: a espada saiu da mão");
                    check(swordSlot >= 9 && swordSlot < 36, "guardar: a espada foi para o inventário (fora da hotbar)");
                    check(b != null && "minecraft:diamond_sword".equals(b.item()) && "Excalibur".equals(b.name()),
                            "guardar: o coldre do ombro direito lembra a espada \"Excalibur\"");
                    handAway(0);
                    next();
                }
            }
            case 4 -> { // SACAR com a picareta na mão; soltar devolve tudo
                if (stepTicks == 1) {
                    inv.selectedSlot = 0;
                }
                if (stepTicks == 5) {
                    handAt(client, 0, Holster.RIGHT_SHOULDER);
                }
                if (stepTicks == 8) {
                    SimulatedVr.setGrip(0, true);
                    check(hm.press(client, 0), "grip no ombro direito: sacou a espada");
                }
                if (stepTicks == 25) {
                    ItemStack hand = client.player.getMainHandStack();
                    info("sacada: mão " + describe(hand) + "; picareta no espaço " + find(inv, Items.STONE_PICKAXE));
                    check(hand.isOf(Items.DIAMOND_SWORD) && "Excalibur".equals(hand.getName().getString()),
                            "sacar: a espada está na mão (no espaço selecionado da hotbar)");
                    check(find(inv, Items.STONE_PICKAXE) == swordSlot, "sacar: a picareta foi para o lugar da espada");
                    check(hm.isDrawing(0), "sacar: segurando o grip, a espada continua na mão");
                    // A mão pode ir para qualquer lugar enquanto segura o grip.
                    SimulatedVr.setHand(0, client.player.getEyePos().add(0.3, -0.3, 0.5), new Vec3d(0, 0, 1));
                }
                if (stepTicks == 40) {
                    check(hm.isDrawing(0) && client.player.getMainHandStack().isOf(Items.DIAMOND_SWORD),
                            "sacar: com a mão longe do ombro (grip ainda apertado), a espada continua na mão");
                    SimulatedVr.setGrip(0, false);
                }
                if (stepTicks == 60) {
                    info("soltou: mão " + describe(client.player.getMainHandStack()) + ", espada no espaço "
                            + find(inv, Items.DIAMOND_SWORD));
                    check(!hm.isDrawing(0), "soltar o grip: a espada voltou para o coldre");
                    check(client.player.getMainHandStack().isOf(Items.STONE_PICKAXE) && inv.selectedSlot == 0,
                            "soltar o grip: a mão voltou a segurar a picareta");
                    check(find(inv, Items.DIAMOND_SWORD) == swordSlot, "soltar o grip: a espada voltou para o mesmo lugar");
                    handAway(0);
                    next();
                }
            }
            case 5 -> { // GUARDAR com a mão esquerda: tochas no quadril esquerdo
                if (stepTicks == 1) {
                    handAt(client, 1, Holster.LEFT_HIP);
                }
                if (stepTicks == 5) {
                    check(hm.zoneOf(1) == Holster.LEFT_HIP, "mão esquerda no quadril esquerdo: está na zona do coldre");
                    check(hm.press(client, 1), "grip no quadril esquerdo com as tochas na mão esquerda: guardou");
                }
                if (stepTicks == 25) {
                    HolsterStorage.Binding b = hm.binding(Holster.LEFT_HIP);
                    int torches = find(inv, Items.TORCH);
                    info("tochas guardadas no espaço " + torches + "; mão esquerda " + describe(client.player.getOffHandStack()));
                    check(client.player.getOffHandStack().isEmpty() && torches >= 9 && torches < 36,
                            "guardar (mão esquerda): as tochas saíram da mão e foram para o inventário");
                    check(b != null && "minecraft:torch".equals(b.item()), "guardar (mão esquerda): o quadril esquerdo lembra as tochas");
                    handAway(1);
                    next();
                }
            }
            case 6 -> { // SACAR na mão esquerda e colocar uma tocha com o gatilho esquerdo
                if (stepTicks == 1) {
                    handAt(client, 1, Holster.LEFT_HIP);
                }
                if (stepTicks == 5) {
                    SimulatedVr.setGrip(1, true);
                    check(hm.press(client, 1), "grip no quadril esquerdo: sacou as tochas");
                }
                if (stepTicks == 20) {
                    check(client.player.getOffHandStack().isOf(Items.TORCH),
                            "sacar (mão esquerda): as tochas estão na mão esquerda");
                    // Mão esquerda apontando para o chão 2 blocos à frente.
                    Vec3d feet = client.player.getPos();
                    torchAt = BlockPos.ofFloored(feet.x, feet.y, feet.z + 2);
                    Vec3d hand = client.player.getEyePos().add(-0.2, -0.5, 0.3);
                    Vec3d target = new Vec3d(torchAt.getX() + 0.5, feet.y - 0.001, torchAt.getZ() + 0.5);
                    SimulatedVr.setHand(1, hand, target.subtract(hand));
                }
                if (stepTicks == 22) {
                    OffhandUse.use(client); // o mesmo que o gatilho esquerdo faz
                    info("gatilho esquerdo: " + OffhandUse.debugLast());
                }
                if (stepTicks == 40) {
                    boolean placed = client.world.getBlockState(torchAt).isOf(Blocks.TORCH);
                    info("tocha em " + torchAt.toShortString() + ": " + client.world.getBlockState(torchAt).getBlock()
                            + "; mão esquerda " + describe(client.player.getOffHandStack()));
                    check(placed, "gatilho esquerdo: colocou a tocha onde a mão esquerda aponta");
                    check(client.player.getOffHandStack().getCount() == 15, "gatilho esquerdo: gastou uma tocha (16 -> 15)");
                    SimulatedVr.setGrip(1, false);
                }
                if (stepTicks == 60) {
                    int torches = find(inv, Items.TORCH);
                    check(client.player.getOffHandStack().isEmpty() && torches >= 9 && torches < 36
                                    && inv.getStack(torches).getCount() == 15,
                            "soltar o grip esquerdo: as tochas (15) voltaram para o coldre");
                    handAway(1);
                    next();
                }
            }
            case 7 -> { // GUARDAR o pão no quadril direito
                if (stepTicks == 1) {
                    inv.selectedSlot = 2;
                }
                if (stepTicks == 5) {
                    handAt(client, 0, Holster.RIGHT_HIP);
                }
                if (stepTicks == 8) {
                    check(hm.press(client, 0), "grip no quadril direito com o pão na mão: guardou");
                }
                if (stepTicks == 25) {
                    HolsterStorage.Binding b = hm.binding(Holster.RIGHT_HIP);
                    check(b != null && "minecraft:bread".equals(b.item()) && client.player.getMainHandStack().isEmpty(),
                            "guardar: o pão saiu da mão e o quadril direito lembra dele");
                    handAway(0);
                    inv.selectedSlot = 0;
                    next();
                }
            }
            case 8 -> { // ZONAS: cada coldre no lugar certo, sem misturar; virar só a cabeça não leva os coldres
                Holster[] all = Holster.values();
                int i = (stepTicks - 1) / 4;
                if (i < all.length && (stepTicks - 1) % 4 == 0) {
                    handAt(client, 0, all[i]);
                }
                if (i < all.length && (stepTicks - 1) % 4 == 3) {
                    check(hm.zoneOf(0) == all[i], "zona: mão no " + all[i] + " -> " + hm.zoneOf(0));
                }
                if (stepTicks == all.length * 4 + 1) {
                    SimulatedVr.setHand(0, client.player.getEyePos().add(0, -0.3, 0.6), new Vec3d(0, 0, 1));
                }
                if (stepTicks == all.length * 4 + 4) {
                    check(hm.zoneOf(0) == null, "zona: mão na frente do corpo -> fora dos coldres");
                    BodyFrame.Frame before = hm.body().compute();
                    client.player.setYaw(20); // só a cabeça (dentro da zona morta do tronco)
                    BodyFrame.Frame after = hm.body().compute();
                    double moved = before.anchor(Holster.RIGHT_HIP, HandsOnConfig.get())
                            .distanceTo(after.anchor(Holster.RIGHT_HIP, HandsOnConfig.get()));
                    info(String.format(java.util.Locale.ROOT, "virando a cabeça 20°: o quadril direito andou %.3f m", moved));
                    check(moved < 0.06, "virar só a cabeça um pouco não leva os coldres junto");
                    client.player.setYaw(0);
                    handAway(0);
                    next();
                }
            }
            case 9 -> { // FOTOS dos itens no corpo
                if (stepTicks == 1) {
                    client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
                    client.player.setPitch(0);
                }
                if (stepTicks == 25) {
                    screenshot(client, "01-coldres-frente");
                    client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                }
                if (stepTicks == 50) {
                    screenshot(client, "02-coldres-costas");
                    client.options.setPerspective(Perspective.FIRST_PERSON);
                    client.player.setPitch(70);
                }
                if (stepTicks == 75) {
                    screenshot(client, "03-olhando-para-baixo");
                    // Mão no quadril direito: o pão fica um pouco maior (sinal de "pode pegar").
                    handAt(client, 0, Holster.RIGHT_HIP);
                }
                if (stepTicks == 95) {
                    screenshot(client, "04-mao-no-quadril");
                    handAway(0);
                    client.player.setPitch(0);
                    next();
                }
            }
            case 10 -> { // persistência, mixin do Vivecraft, hotbar pelo analógico, vibração
                if (stepTicks == 5) {
                    EnumMap<Holster, HolsterStorage.Binding> saved = HolsterStorage.load(HolsterStorage.worldKey(client));
                    info("salvo em disco: " + saved);
                    check(saved.size() == 3 && saved.containsKey(Holster.RIGHT_SHOULDER) && saved.containsKey(Holster.LEFT_HIP)
                            && saved.containsKey(Holster.RIGHT_HIP), "os coldres ficam salvos para este mundo");
                    if (VrBridge.isVivecraftLoaded()) {
                        Class<?> backpack = Class.forName("org.vivecraft.client_vr.gameplay.trackers.BackpackTracker");
                        boolean mixed = Stream.of(backpack.getDeclaredMethods()).anyMatch(m -> m.getName().contains("handsonvr$"));
                        check(mixed, "Vivecraft: a troca pelas costas fica desligada com coldres nos ombros (mixin aplicado)");
                    } else {
                        info("Vivecraft ausente: mixin da troca pelas costas não conferido");
                    }
                    inv.selectedSlot = 0;
                    HotbarScroll.scroll(client, 1);
                    boolean forward = inv.selectedSlot == 1;
                    HotbarScroll.scroll(client, -1);
                    HotbarScroll.scroll(client, -1);
                    check(forward && inv.selectedSlot == 8, "analógico direito: próximo/anterior da hotbar (dando a volta)");
                    inv.selectedSlot = 0;
                    check(SimulatedVr.pulseCount(0) > 0 && SimulatedVr.pulseCount(1) > 0,
                            "vibração nas duas mãos ao guardar/sacar (" + SimulatedVr.pulseCount(0) + "/" + SimulatedVr.pulseCount(1) + ")");
                }
                if (stepTicks == 10) {
                    client.setScreen(new net.minecraft.client.gui.screen.GameMenuScreen(true));
                }
                if (stepTicks == 20) {
                    // O botão "Coldres VR" no menu de pausa abre os ajustes.
                    var button = client.currentScreen == null ? null : client.currentScreen.children().stream()
                            .filter(e -> e instanceof net.minecraft.client.gui.widget.ButtonWidget b
                                    && b.getMessage().getString().equals(Text.translatable("button.handsonvr.settings").getString()))
                            .map(e -> (net.minecraft.client.gui.widget.ButtonWidget) e).findFirst().orElse(null);
                    check(button != null, "menu de pausa: tem o botão dos coldres");
                    if (button != null) {
                        button.onPress();
                    }
                }
                if (stepTicks == 30) {
                    check(client.currentScreen instanceof com.handsonvr.screen.HandsOnScreen,
                            "o botão do menu de pausa abre os ajustes dos coldres");
                    screenshot(client, "05-ajustes");
                }
                if (stepTicks == 40) {
                    client.setScreen(null);
                }
                if (stepTicks == 50) {
                    finish(client);
                }
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------ VR nulo: ligação com os controles do Vivecraft

    private static void runNullVr(MinecraftClient client) {
        HolsterManager hm = holsters();
        PlayerInventory inv = client.player.getInventory();
        switch (step) {
            case 2 -> { // o VR (nulo) ligou e as 4 ações estão sendo escutadas
                int listening = com.handsonvr.input.VivecraftInput.debugListening();
                if (listening == 4) {
                    pass("VR nulo do Vivecraft ligado: escutando grip direito, grip esquerdo, gatilho esquerdo e analógico direito");
                    next();
                } else if (stepTicks > 400) {
                    fail("VR nulo: ações escutadas " + listening + "/4 (VR ativo: "
                            + org.vivecraft.api.client.VRClientAPI.instance().isVRActive() + ")");
                    finish(client);
                }
            }
            case 3 -> {
                if (stepTicks == 1) {
                    onServer(client, player -> {
                        player.changeGameMode(GameMode.SURVIVAL);
                        PlayerInventory pinv = player.getInventory();
                        pinv.clear();
                        pinv.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
                        sword.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Excalibur"));
                        pinv.setStack(4, sword);
                        pinv.setStack(40, new ItemStack(Items.TORCH, 16));
                    });
                    SimulatedVr.setEnabled(true); // poses das mãos pelo teste (o resto é o Vivecraft de verdade)
                }
                if (stepTicks == 20) {
                    inv.selectedSlot = 4;
                    handAt(client, 0, Holster.RIGHT_SHOULDER);
                }
                if (stepTicks == 25) {
                    check(com.handsonvr.input.VivecraftInput.debugNotify("gripMain", true, 0f),
                            "Vivecraft: grip direito no ombro foi consumido pelo coldre (a hotbar não troca)");
                    com.handsonvr.input.VivecraftInput.debugNotify("gripMain", false, 0f);
                }
                if (stepTicks == 40) {
                    check(client.player.getMainHandStack().isEmpty() && hm.binding(Holster.RIGHT_SHOULDER) != null,
                            "Vivecraft: o grip direito guardou a espada no ombro");
                    inv.selectedSlot = 0;
                }
                if (stepTicks == 45) {
                    SimulatedVr.setGrip(0, true);
                    check(com.handsonvr.input.VivecraftInput.debugNotify("gripMain", true, 0f) && hm.isDrawing(0),
                            "Vivecraft: grip direito no ombro sacou a espada");
                }
                if (stepTicks == 60) {
                    check(client.player.getMainHandStack().isOf(Items.DIAMOND_SWORD), "Vivecraft: a espada está na mão");
                    SimulatedVr.setGrip(0, false);
                    check(com.handsonvr.input.VivecraftInput.debugNotify("gripMain", false, 0f),
                            "Vivecraft: soltar o grip direito devolve a espada");
                }
                if (stepTicks == 75) {
                    check(client.player.getMainHandStack().isOf(Items.STONE_PICKAXE), "Vivecraft: a picareta voltou para a mão");
                    // Fora dos coldres o grip continua sendo do Vivecraft (troca a hotbar).
                    SimulatedVr.setHand(0, client.player.getEyePos().add(0, -0.3, 0.6), new Vec3d(0, 0, 1));
                }
                if (stepTicks == 80) {
                    check(!com.handsonvr.input.VivecraftInput.debugNotify("gripMain", true, 0f),
                            "Vivecraft: fora dos coldres o grip não é consumido (continua trocando a hotbar)");
                    com.handsonvr.input.VivecraftInput.debugNotify("gripMain", false, 0f);
                    handAway(0);
                    next();
                }
            }
            case 4 -> { // gatilho esquerdo: usa a mão esquerda
                if (stepTicks == 1) {
                    Vec3d feet = client.player.getPos();
                    torchAt = BlockPos.ofFloored(feet.x, feet.y, feet.z + 2);
                    Vec3d hand = client.player.getEyePos().add(-0.2, -0.5, 0.3);
                    Vec3d target = new Vec3d(torchAt.getX() + 0.5, feet.y - 0.001, torchAt.getZ() + 0.5);
                    SimulatedVr.setHand(1, hand, target.subtract(hand));
                }
                if (stepTicks == 5) {
                    check(com.handsonvr.input.VivecraftInput.debugNotify("teleport", true, 0f),
                            "Vivecraft: o gatilho esquerdo foi consumido (sem teleporte)");
                    com.handsonvr.input.VivecraftInput.debugNotify("teleport", false, 0f);
                }
                if (stepTicks == 25) {
                    check(client.world.getBlockState(torchAt).isOf(Blocks.TORCH),
                            "Vivecraft: o gatilho esquerdo colocou a tocha com a mão esquerda (" + OffhandUse.debugLast() + ")");
                    handAway(1);
                    next();
                }
            }
            case 5 -> { // analógico direito: hotbar
                if (stepTicks == 1) {
                    inv.selectedSlot = 0;
                    boolean consumed = com.handsonvr.input.VivecraftInput.debugNotify("rotate", true, 0.9f);
                    com.handsonvr.input.VivecraftInput.debugNotify("rotate", false, 0.9f);
                    check(consumed && inv.selectedSlot == 1,
                            "Vivecraft: analógico direito para a direita = próximo da hotbar (sem girar a câmera)");
                    com.handsonvr.input.VivecraftInput.debugNotify("rotate", true, -0.9f);
                    com.handsonvr.input.VivecraftInput.debugNotify("rotate", false, -0.9f);
                    check(inv.selectedSlot == 0, "Vivecraft: analógico direito para a esquerda = anterior da hotbar");
                }
                if (stepTicks == 5) {
                    boolean backpack = org.vivecraft.client_vr.ClientDataHolderVR.getInstance().backpackTracker
                            .isActive(client.player);
                    check(!backpack, "Vivecraft: a troca pelas costas está desligada (coldres nos ombros)");
                    next();
                }
            }
            default -> finish(client);
        }
    }

    // ------------------------------------------------------------------ mundo

    private static void deleteWorld(MinecraftClient client) throws IOException {
        Path dir = client.runDirectory.toPath().resolve("saves").resolve(WORLD);
        if (Files.exists(dir)) {
            try (Stream<Path> paths = Files.walk(dir)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
        // E os coldres salvos do teste anterior.
        Path saved = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("handsonvr")
                .resolve("holsters").resolve("sp_" + WORLD + ".json");
        Files.deleteIfExists(saved);
    }

    private static void createWorld(MinecraftClient client) {
        LevelInfo info = new LevelInfo(WORLD, GameMode.SURVIVAL, false, Difficulty.PEACEFUL, true, new GameRules(),
                DataConfiguration.SAFE_MODE);
        GeneratorOptions options = new GeneratorOptions(20261004L, false, false);
        client.createIntegratedServerLoader().createAndStart(WORLD, info, options,
                registries -> registries.get(RegistryKeys.WORLD_PRESET).entryOf(WorldPresets.FLAT).value()
                        .createDimensionsRegistryHolder(),
                client.currentScreen);
    }
}
