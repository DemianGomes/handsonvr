package com.handsonvr.compat;

import com.handsonvr.HandsOnVR;
import com.handsonvr.render.Draw;
import com.handsonvr.render.Sprite;
import com.handsonvr.render.WristHudRenderer;
import com.handsonvr.vr.Panel;
import com.handsonvr.vr.VrBridge;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;

/**
 * O FTB Ultimine em VR, pelo relógio do pulso esquerdo.
 *
 * <p>No computador, o Ultimine funciona segurando uma tecla (o "`") enquanto quebra um bloco, e a forma (sem forma,
 * túnel, 3x3...) muda com a roda do mouse segurando a tecla. Em VR não há teclado, então abaixo do relógio ficam três
 * botões, com as texturas do jogo, que a ponta da mão direita aperta encostando:
 * <ul>
 *   <li><b>picareta</b> no meio: liga e desliga o Ultimine (fica clara enquanto ligado; é como segurar a tecla). Com
 *       ele ligado, quebrar um bloco quebra o grupo, como no computador, e o próprio Ultimine contorna o grupo;</li>
 *   <li><b>setas</b> dos lados: forma anterior e próxima (ligam o Ultimine se estava desligado).</li>
 * </ul>
 * Por baixo é a tecla do próprio Ultimine (segurada pelo HandsOn) e o pedido de troca de forma dele: nada do Ultimine
 * muda, e o servidor (que precisa ter o Ultimine) decide tudo, como no computador. Sem o Ultimine instalado, nada aparece.
 */
public final class UltimineVr {
    private UltimineVr() {}

    public static final int NONE = -1, PREV = 0, TOGGLE = 1, NEXT = 2;
    /** Lado de um botão (m) e o espaço entre eles. */
    private static final float SLOT = 0.026f, GAP = 0.006f;
    /** Ticks com a ponta no botão para apertar (evita apertar só de passar a mão). */
    private static final int PRESS_TICKS = 2;
    private static final String KEY_NAME = "key.ftbultimine.default.ftbultimine";

    private static Boolean loaded = null;
    private static KeyMapping key;
    private static boolean active = false;
    private static int touching = NONE, touchTicks = 0;
    private static int pendingCycle = 0;
    private static String last = "-";

    public static boolean isLoaded() {
        if (loaded == null) {
            loaded = FabricLoader.getInstance().isModLoaded("ftbultimine");
        }
        return loaded;
    }

    public static boolean isActive() {
        return active;
    }

    public static String last() {
        return last;
    }

    /** Teste automático. */
    public static int touching() {
        return touching;
    }

    /** O centro de um botão no plano do relógio (x, y locais). */
    public static float[] buttonCenter(int button) {
        Minecraft client = Minecraft.getInstance();
        float below = client.player == null ? 0.03f : WristHudRenderer.halfHeight(client.player);
        return new float[] {(button - 1) * (SLOT + GAP), -(below + GAP + SLOT / 2f)};
    }

    /** O botão sob a ponta da mão, ou {@link #NONE}. */
    public static int buttonAt(Vec3 tip) {
        Panel panel = tip == null ? null : WristHudRenderer.wristPanel();
        if (panel == null) {
            return NONE;
        }
        Vec3 l = panel.local(tip);
        float s = VrBridge.worldScale();
        if (l.z < -0.015 * s || l.z > 0.05 * s) {
            return NONE;
        }
        for (int b = PREV; b <= NEXT; b++) {
            float[] c = buttonCenter(b);
            if (Math.abs(l.x - c[0] * s) <= SLOT * 0.6 * s && Math.abs(l.y - c[1] * s) <= SLOT * 0.6 * s) {
                return b;
            }
        }
        return NONE;
    }

    /** A cada tick: o toque da mão direita nos botões e a tecla do Ultimine segurada enquanto ligado. */
    public static void tick(Minecraft client) {
        if (!isLoaded() || client.player == null) {
            return;
        }
        if (key == null) {
            for (KeyMapping k : client.options.keyMappings) {
                if (KEY_NAME.equals(k.getName())) {
                    key = k;
                }
            }
        }
        if (!VrBridge.isVRActive()) {
            if (active) {
                setActive(false);
            }
            touching = NONE;
            return;
        }
        int b = buttonAt(VrBridge.pointer(0));
        if (b != touching) {
            touching = b;
            touchTicks = 0;
        }
        if (b != NONE && ++touchTicks == PRESS_TICKS) {
            press(b);
        }
        if (active && key != null && client.gui.screen() == null) {
            key.setDown(true); // como segurar a tecla (o jogo solta as teclas ao abrir uma tela)
        }
        if (pendingCycle != 0 && active) {
            cycle(pendingCycle > 0);
            pendingCycle = 0;
        }
    }

    private static void press(int b) {
        VrBridge.pulse(0, 0.02f);
        if (b == TOGGLE) {
            setActive(!active);
        } else {
            if (!active) {
                setActive(true);
            }
            pendingCycle = b == NEXT ? 1 : -1; // no próximo tick, com a tecla já segurada para o Ultimine
        }
    }

    private static void setActive(boolean on) {
        active = on;
        if (key != null) {
            key.setDown(on);
        }
        last = on ? "ligado" : "desligado";
    }

    /** Pede ao servidor a forma anterior ou a próxima, como a roda do mouse do Ultimine. */
    private static void cycle(boolean next) {
        try {
            Class<?> packet = Class.forName("dev.ftb.mods.ftbultimine.net.ModeChangedPacket");
            Object payload = packet.getConstructor(boolean.class).newInstance(next);
            Class<?> net = Class.forName("dev.ftb.mods.ftblibrary.platform.network.Play2ServerNetworking");
            for (Method m : net.getMethods()) {
                if (m.getName().equals("send") && m.getParameterCount() == 1) {
                    m.invoke(null, payload);
                    last = next ? "próxima forma" : "forma anterior";
                    return;
                }
            }
            last = "não achei como trocar a forma";
        } catch (Throwable t) {
            last = "troca de forma falhou: " + t;
            HandsOnVR.LOGGER.debug("[HandsOn VR] Ultimine: troca de forma", t);
        }
    }

    /** Os três botões, abaixo do relógio (no plano dele, já posicionado). */
    public static void draw(Draw d) {
        if (!isLoaded() || !VrBridge.isVRActive()) {
            return;
        }
        float s = VrBridge.worldScale();
        for (int b = PREV; b <= NEXT; b++) {
            float[] c = buttonCenter(b);
            float x = c[0] * s, y = c[1] * s, size = SLOT * s;
            boolean lit = touching == b || (b == TOGGLE && active);
            d.mcSlot(x, y, size, 0.001f, lit);
            if (b == TOGGLE) {
                d.item(new ItemStack(Items.IRON_PICKAXE), x, y, 0.003f, size * 16f / 18f);
            } else {
                String name = b == PREV ? "recipe_book/page_backward" : "recipe_book/page_forward";
                Sprite arrow = Sprite.gui(touching == b ? name + "_highlighted" : name);
                if (arrow == null) {
                    arrow = Sprite.gui(name);
                }
                if (arrow != null) {
                    d.spriteCentered(arrow, x, y, size * 0.5f, 0.003f, Draw.MC_PANEL);
                }
            }
        }
    }
}
