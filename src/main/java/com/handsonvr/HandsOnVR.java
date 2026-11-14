package com.handsonvr;

import com.handsonvr.dev.SelfTest;
import com.handsonvr.holster.HolsterManager;
import com.handsonvr.holster.HolsterRenderer;
import com.handsonvr.input.VivecraftInput;
import com.handsonvr.screen.HandsOnScreen;
import com.handsonvr.vr.VrBridge;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * HandsOn VR: complemento do Vivecraft com coldres físicos no corpo (ombros e quadris: mão no coldre + grip saca o
 * item, soltar o grip devolve) e controles melhores para o Quest 2 (gatilho esquerdo usa a mão esquerda, analógico
 * direito troca o item da hotbar). Só o cliente; funciona em qualquer servidor.
 */
public class HandsOnVR implements ClientModInitializer {
    public static final String MOD_ID = "handsonvr";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final HolsterManager HOLSTERS = new HolsterManager();
    private static KeyBinding settingsKey;

    @Override
    public void onInitializeClient() {
        HandsOnConfig.get();
        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.handsonvr.settings",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "category.handsonvr"));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                guard("entrar no mundo", () -> HOLSTERS.onJoin(client)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> HOLSTERS.onLeave());

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (VrBridge.isVivecraftLoaded()) {
                guard("controles do Vivecraft", () -> VivecraftInput.tick(client));
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            guard("coldres", () -> HOLSTERS.tick(client));
            while (settingsKey.wasPressed()) {
                client.setScreen(new HandsOnScreen(client.currentScreen, HOLSTERS));
            }
        });
        WorldRenderEvents.AFTER_ENTITIES.register(context ->
                guard("coldres (desenho)", () -> HolsterRenderer.render(context, HOLSTERS)));

        // Botão "Coldres VR" no menu de pausa (Y no controle): os ajustes sem precisar de tecla.
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof GameMenuScreen) {
                guard("botão no menu de pausa", () -> Screens.getButtons(screen).add(
                        ButtonWidget.builder(Text.translatable("button.handsonvr.settings"),
                                        b -> client.setScreen(new HandsOnScreen(screen, HOLSTERS)))
                                .dimensions(4, 4, 90, 20).build()));
            }
        });

        SelfTest.register();
        LOGGER.info("[HandsOn VR] Pronto (Vivecraft {})", VrBridge.isVivecraftLoaded() ? "presente" : "ausente");
    }

    public static HolsterManager holsters() {
        return HOLSTERS;
    }

    /** Grip apertado (VivecraftInput). true = o coldre agiu, o grip não troca a hotbar. */
    public static boolean onGripPressed(int hand) {
        try {
            return HandsOnConfig.get().holsters && HOLSTERS.press(MinecraftClient.getInstance(), hand);
        } catch (Throwable t) {
            report("grip (apertar)", t);
            return false;
        }
    }

    /** Grip solto. true = havia um item sacado, que voltou para o coldre. */
    public static boolean onGripReleased(int hand) {
        try {
            return HOLSTERS.releaseGrip(MinecraftClient.getInstance(), hand);
        } catch (Throwable t) {
            report("grip (soltar)", t);
            return false;
        }
    }

    /** Coldres dos ombros ligados (e a opção de trocar a "troca pelas costas" do Vivecraft por eles). */
    public static boolean shoulderHolstersReplaceBackpack() {
        HandsOnConfig config = HandsOnConfig.get();
        return config.holsters && config.disableBackpackWithShoulders && (config.rightShoulder || config.leftShoulder);
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

    private static void report(String what, Throwable t) {
        if (REPORTED.add(what)) {
            LOGGER.error("[HandsOn VR] Erro em '{}' (só o primeiro é mostrado)", what, t);
        }
    }
}
