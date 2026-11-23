package com.handsonvr;

import com.handsonvr.backpack.BackpackRenderer;
import com.handsonvr.dev.SelfTest;
import com.handsonvr.hands.HandsController;
import com.handsonvr.holster.HolsterManager;
import com.handsonvr.holster.HolsterRenderer;
import com.handsonvr.input.VivecraftInput;
import com.handsonvr.radial.RadialRenderer;
import com.handsonvr.screen.HandsOnScreen;
import com.handsonvr.vr.VrBridge;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * HandsOn VR: complemento do Vivecraft que deixa o inventário com cara de jogo nativo de VR — coldres físicos no
 * corpo (ombro direito e quadris), a mochila no ombro esquerdo (inventário, crafting 2x2, armadura e descarte sem
 * tela), o menu radial da hotbar no B/X e a regra das mãos (o grip pega emprestado, o radial equipa). Só o cliente;
 * funciona em qualquer servidor. Design: docs/DESIGN.md.
 */
public class HandsOnVR implements ClientModInitializer {
    public static final String MOD_ID = "handsonvr";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final HandsController HANDS = new HandsController();
    private static KeyMapping settingsKey;

    @Override
    public void onInitializeClient() {
        HandsOnConfig.get();
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        settingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.handsonvr.settings",
                InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), category));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                guard("entrar no mundo", () -> {
                    com.handsonvr.render.Sprite.clearCache(); // ícones do jogo: relidos (o resource pack pode ter mudado)
                    HANDS.onJoin(client);
                    com.handsonvr.inv.PickupOrder.reset();
                    com.handsonvr.net.PeerSync.reset();
                }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> HANDS.onLeave());

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            guard("arremesso (amostra)", com.handsonvr.hands.ThrowGesture::tick);
            if (VrBridge.isVivecraftLoaded()) {
                guard("controles do Vivecraft", () -> VivecraftInput.tick(client));
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            guard("grip (soltura perdida)", HandsOnVR::gripWatch);
            guard("HUD do Vivecraft", () -> com.handsonvr.render.VivecraftHud.tick(client));
            guard("minimapa no pulso", com.handsonvr.render.MinimapWrist::tick);
            guard("FTB Ultimine (VR)", () -> com.handsonvr.compat.UltimineVr.tick(client));
            guard("mãos", () -> HANDS.tick(client));
            guard("coleta no inventário", () -> com.handsonvr.inv.PickupOrder.tick(client, HANDS));
            guard("estado para os outros jogadores", () -> com.handsonvr.net.PeerSync.tick(client, HANDS));
            while (settingsKey.consumeClick()) {
                client.gui.setScreen(new HandsOnScreen(client.gui.screen(), HANDS.holsters()));
            }
        });
        // Minecraft 26: o mundo é desenhado juntando "submits"; o nosso entra junto com o das entidades.
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            // A área de jogo deste quadro (o radial anda com ela), antes de desenhar.
            guard("área de jogo", VrBridge::captureFrame);
            guard("arremesso (amostra)", com.handsonvr.hands.ThrowGesture::sample);
            renderSafely(context.poseStack(), "coldres (desenho)", () -> HolsterRenderer.render(context, HANDS.holsters()));
            renderSafely(context.poseStack(), "mochila (desenho)", () -> BackpackRenderer.render(context, HANDS));
            renderSafely(context.poseStack(), "radial (desenho)", () -> RadialRenderer.render(context, HANDS));
            renderSafely(context.poseStack(), "mira do arco (desenho)", () -> com.handsonvr.render.BowAimRenderer.render(context));
            renderSafely(context.poseStack(), "mesa de trabalho (desenho)",
                    () -> com.handsonvr.table.CraftingTableRenderer.render(context, HANDS));
            renderSafely(context.poseStack(), "fornalha (desenho)",
                    () -> com.handsonvr.furnace.FurnaceRenderer.render(context, HANDS));
            renderSafely(context.poseStack(), "baú (desenho)", () -> com.handsonvr.chest.ChestRenderer.render(context, HANDS));
            renderSafely(context.poseStack(), "suporte de poções (desenho)",
                    () -> com.handsonvr.brewing.BrewingRenderer.render(context, HANDS));
            renderSafely(context.poseStack(), "fogueira (desenho)", () -> com.handsonvr.render.CampfireHint.render(context, HANDS));
            renderSafely(context.poseStack(), "minimapa no pulso (desenho)", () -> com.handsonvr.render.MinimapWrist.render(context, HANDS));
            renderSafely(context.poseStack(), "pulso e quantidade (desenho)",
                    () -> com.handsonvr.render.WristHudRenderer.render(context, HANDS));
            renderSafely(context.poseStack(), "outros jogadores (desenho)", () -> com.handsonvr.net.PeerRenderer.render(context));
        });

        // Botão "Opções HandsOn VR" no menu de pausa (Y no controle): os ajustes sem precisar de tecla.
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof PauseScreen) {
                guard("botão no menu de pausa", () -> Screens.getWidgets(screen).add(
                        Button.builder(Component.translatable("button.handsonvr.settings"),
                                        b -> client.gui.setScreen(new HandsOnScreen(screen, HANDS.holsters())))
                                .bounds(4, 4, 110, 20).build()));
            }
        });

        com.handsonvr.render.VivecraftHud.register(); // em VR, as barras do jogo saem do HUD do Vivecraft (o resto, como o minimapa, fica)
        com.handsonvr.net.PeerSync.register();
        SelfTest.register();
        LOGGER.info("[HandsOn VR] Pronto (Vivecraft {})", VrBridge.isVivecraftLoaded() ? "presente" : "ausente");
    }

    public static HandsController hands() {
        return HANDS;
    }

    public static HolsterManager holsters() {
        return HANDS.holsters();
    }

    /** Mixin do MenuScreens: o menu que o servidor abriu é o da mesa ou de uma fornalha imersiva? Então fica sem tela. */
    public static boolean openHiddenMenu(net.minecraft.world.inventory.MenuType<?> type, Minecraft client, int containerId) {
        return call("mesa de trabalho (abrir)", () -> HANDS.table().onOpenScreen(type, client, containerId))
                || call("fornalha (abrir)", () -> HANDS.furnace().onOpenScreen(type, client, containerId))
                || call("baú (abrir)", () -> HANDS.chest().onOpenScreen(type, client, containerId))
                || call("suporte de poções (abrir)", () -> HANDS.brewing().onOpenScreen(type, client, containerId));
    }

    /**
     * O grip chega por duas ações do Vivecraft no mesmo botão: a da hotbar (mão fora dos blocos) e o "VR Interact" (mão
     * dentro de um bloco do HandsOn, pelo {@link com.handsonvr.input.StationInteractModule}). Cada aperto conta uma vez
     * só, e uma soltura perdida é feita no tick ({@link #gripWatch}).
     */
    private static final boolean[] GRIP_DOWN = new boolean[2];
    private static final int[] GRIP_LOST = new int[2];

    public static boolean isGripDown(int hand) {
        return GRIP_DOWN[hand];
    }

    /** Grip apertado (VivecraftInput ou o módulo de interação). true = foi nosso, o grip não troca a hotbar. */
    public static boolean onGripPressed(int hand) {
        if (GRIP_DOWN[hand]) {
            return true;
        }
        GRIP_DOWN[hand] = true;
        GRIP_LOST[hand] = 0;
        return call("grip (apertar)", () -> HANDS.onGripPressed(Minecraft.getInstance(), hand));
    }

    /** Grip solto. true = foi nosso (fechou a mochila ou devolveu/colocou um item pego). */
    public static boolean onGripReleased(int hand) {
        if (!GRIP_DOWN[hand]) {
            return false;
        }
        GRIP_DOWN[hand] = false;
        return call("grip (soltar)", () -> HANDS.onGripReleased(Minecraft.getInstance(), hand));
    }

    /** O botão já não está apertado há 3 ticks e a soltura não chegou (a ação trocou no meio): solta agora. */
    private static void gripWatch() {
        for (int hand = 0; hand < 2; hand++) {
            if (GRIP_DOWN[hand] && !com.handsonvr.input.GripState.isHeld(hand)) {
                if (++GRIP_LOST[hand] >= 3) {
                    onGripReleased(hand);
                }
            } else {
                GRIP_LOST[hand] = 0;
            }
        }
    }

    /** Mixin do Vivecraft: esconder o item desta mão (ela segura a mochila). */
    public static boolean hideHandItem(int hand) {
        try {
            return HANDS.isHandHidden(hand);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Mixin do Vivecraft: desligar o golpe com o braço (mexendo na mochila ou no radial). */
    public static boolean blockSwing() {
        try {
            return HANDS.blocksSwing();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Coldres dos ombros ligados (e a opção de trocar a "troca pelas costas" do Vivecraft por eles). */
    public static boolean shoulderHolstersReplaceBackpack() {
        HandsOnConfig config = HandsOnConfig.get();
        return config.disableBackpackWithShoulders
                && (config.backpack || (config.holsters && (config.rightShoulder || config.leftShoulder)));
    }

    private static final Set<String> REPORTED = new HashSet<>();

    /** Roda algo do mod sem nunca derrubar o jogo; o primeiro erro de cada parte vai para o log. */
    public static void guard(String what, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            report(what, t);
        }
    }

    /**
     * Desenho protegido: um erro no meio nunca deixa a pilha de matrizes do mundo "aberta" (o Minecraft fecharia o
     * jogo com "Pose stack not empty").
     */
    private static void renderSafely(PoseStack ms, String what, Runnable action) {
        boolean wasEmpty = ms != null && ms.isEmpty();
        try {
            action.run();
        } catch (Throwable t) {
            report(what, t);
        } finally {
            if (wasEmpty) {
                while (!ms.isEmpty()) {
                    ms.popPose();
                }
            }
        }
    }

    /** Como {@link #guard}, para algo que responde sim/não (erro = não). */
    public static boolean call(String what, BooleanSupplier action) {
        try {
            return action.getAsBoolean();
        } catch (Throwable t) {
            report(what, t);
            return false;
        }
    }

    private static void report(String what, Throwable t) {
        if (REPORTED.add(what)) {
            LOGGER.error("[HandsOn VR] Erro em '{}' (só o primeiro é mostrado)", what, t);
        }
    }
}
