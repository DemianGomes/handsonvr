package com.handsonvr.compat;

import com.handsonvr.HandsOnVR;
import com.handsonvr.mine.MineServer;
import com.handsonvr.mine.MineShape;
import com.handsonvr.render.Draw;
import com.handsonvr.render.Sprite;
import com.handsonvr.render.WristHudRenderer;
import com.handsonvr.vr.Panel;
import com.handsonvr.vr.VrBridge;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;

/**
 * Mineração em grupo em VR, pelo relógio do pulso esquerdo: três botões, com as texturas do jogo, abaixo do relógio,
 * que a ponta da mão direita aperta encostando.
 * <ul>
 *   <li><b>picareta</b> no meio: liga e desliga (fica clara enquanto ligada);</li>
 *   <li><b>setas</b> dos lados: forma anterior e próxima (ligam se estava desligada). O nome da forma fica embaixo.</li>
 * </ul>
 * Com o <b>FTB Ultimine</b> instalado, os botões são a tecla dele (segurada pelo HandsOn) e o pedido de troca de forma
 * dele. Sem ele, controlam a cópia temporária do HandsOn ({@link MineServer}, as mesmas formas), que precisa do HandsOn
 * no servidor (singleplayer, LAN, Essential).
 */
public final class UltimineVr {
    private UltimineVr() {}

    public static final int NONE = -1, PREV = 0, TOGGLE = 1, NEXT = 2;
    /** Lado de um botão (m) e o espaço entre eles. */
    private static final float SLOT = 0.026f, GAP = 0.006f;
    /** Ticks com a ponta no botão para apertar (evita apertar só de passar a mão). */
    private static final int PRESS_TICKS = 2;
    private static final String KEY_NAME = "key.ftbultimine.default.ftbultimine";
    /** Fundo do nome da forma, como o do chat do jogo. */
    private static final int LABEL_BACKGROUND = 0x80000000;

    private static Boolean ftb = null;
    private static KeyMapping key;
    private static boolean active = false;
    private static int shape = 0;
    private static int touching = NONE, touchTicks = 0;
    private static int pendingCycle = 0;
    private static String last = "-";

    /** O FTB Ultimine está instalado (os botões são dele)? */
    public static boolean isLoaded() {
        if (ftb == null) {
            ftb = FabricLoader.getInstance().isModLoaded("ftbultimine");
        }
        return ftb;
    }

    /** Os botões aparecem (em VR, com o FTB Ultimine ou com a cópia do HandsOn)? */
    public static boolean showButtons() {
        return VrBridge.isVRActive();
    }

    public static boolean isActive() {
        return active;
    }

    /** A forma da cópia do HandsOn. */
    public static MineShape shape() {
        return MineShape.of(shape);
    }

    public static String last() {
        return last;
    }

    /** Teste automático. */
    public static int touching() {
        return touching;
    }

    /** O centro de um botão no plano do relógio (x, y locais, sem a escala do mundo). */
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

    /** A cada tick: o toque da mão direita nos botões; com o Ultimine, a tecla dele; sem ele, o estado no servidor. */
    public static void tick(Minecraft client) {
        if (client.player == null) {
            MineServer.resetClient();
            return;
        }
        if (isLoaded() && key == null) {
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
        } else {
            int b = buttonAt(VrBridge.pointer(0));
            if (b != touching) {
                touching = b;
                touchTicks = 0;
            }
            if (b != NONE && ++touchTicks == PRESS_TICKS) {
                press(b);
            }
        }
        if (isLoaded()) {
            if (active && key != null && client.gui.screen() == null) {
                key.setDown(true); // como segurar a tecla (o jogo solta as teclas ao abrir uma tela)
            }
            if (pendingCycle != 0 && active) {
                ftbCycle(pendingCycle > 0);
                pendingCycle = 0;
            }
        } else {
            MineServer.send(active, shape);
        }
    }

    private static void press(int b) {
        if (!isLoaded() && !MineServer.serverSupports()) {
            VrBridge.pulse(0, 0.08f);
            last = "o servidor não tem o HandsOn";
            return;
        }
        VrBridge.pulse(0, 0.02f);
        if (b == TOGGLE) {
            setActive(!active);
            return;
        }
        if (!active) {
            setActive(true);
        }
        if (isLoaded()) {
            pendingCycle = b == NEXT ? 1 : -1; // no próximo tick, com a tecla já segurada para o Ultimine
        } else {
            shape = Math.floorMod(shape + (b == NEXT ? 1 : -1), MineShape.values().length);
            last = "forma: " + MineShape.of(shape).name();
        }
    }

    private static void setActive(boolean on) {
        active = on;
        if (key != null) {
            key.setDown(on);
        }
        last = on ? "ligado" : "desligado";
    }

    /** Pede ao FTB Ultimine a forma anterior ou a próxima, como a roda do mouse dele. */
    private static void ftbCycle(boolean next) {
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

    /** Os três botões abaixo do relógio (no plano dele, já posicionado) e, na cópia do HandsOn, o nome da forma. */
    public static void draw(Draw d) {
        if (!showButtons()) {
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
        if (!isLoaded()) {
            // O nome da forma, logo abaixo dos botões (no FTB Ultimine, o próprio HUD dele mostra a lista de formas).
            float[] c = buttonCenter(TOGGLE);
            float y = (c[1] - SLOT / 2f - 0.009f) * s, h = 0.0065f * s, w = (3 * SLOT + 2 * GAP) * s;
            d.rect(-w / 2f, y - h, w / 2f, y + h, 0.001f, LABEL_BACKGROUND);
            Component name = Component.translatable(shape().translationKey());
            d.text(name, 0f, y, 0.002f, h * 1.1f, active ? 0xFFFFFFFF : 0xFFA0A0A0, true);
        }
    }
}
