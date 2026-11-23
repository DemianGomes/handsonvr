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
 * <p><b>No computador</b> (VR desligado), como no Ultimine: segurar a tecla <b>'</b> liga enquanto ela está apertada, e
 * <b>Shift + ' + seta para cima/para baixo</b> troca a forma (o nome aparece no HUD enquanto a tecla está apertada).
 *
 * <p>Com o <b>FTB Ultimine</b> instalado, os botões são a tecla dele (segurada pelo HandsOn) e o pedido de troca de forma
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
    /** Teclas do computador: segurar liga; com Shift, as setas trocam a forma. */
    private static KeyMapping mineKey, prevKey, nextKey;
    /** A tecla do computador está segurada (ligada pelo teclado neste tick). */
    private static boolean keyHeld = false;

    /** Uma vez, ao iniciar: as teclas do computador (mudáveis em Controles). */
    public static void registerKeys(KeyMapping.Category category) {
        mineKey = net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(new KeyMapping("key.handsonvr.mine",
                com.mojang.blaze3d.platform.InputConstants.Type.KEYBOARD, com.mojang.blaze3d.platform.InputConstants.KEY_APOSTROPHE, category));
        prevKey = net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(new KeyMapping("key.handsonvr.mine_prev",
                com.mojang.blaze3d.platform.InputConstants.Type.KEYBOARD, com.mojang.blaze3d.platform.InputConstants.KEY_UP, category));
        nextKey = net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(new KeyMapping("key.handsonvr.mine_next",
                com.mojang.blaze3d.platform.InputConstants.Type.KEYBOARD, com.mojang.blaze3d.platform.InputConstants.KEY_DOWN, category));
    }

    /** A cópia do HandsOn está ligada agora (pelos botões do pulso em VR, ou pela tecla segurada no computador)? */
    public static boolean ownActive() {
        return !isLoaded() && (VrBridge.isVRActive() ? active : keyHeld);
    }

    /** Teste automático: segura ou solta a tecla do computador. */
    public static void debugHoldKey(boolean down) {
        if (mineKey != null) {
            mineKey.setDown(down);
        }
    }

    /** Teste automático: como Shift + ' + seta (para baixo = próxima). */
    public static void debugArrow(boolean next) {
        cycleOwn(next ? 1 : -1);
    }

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
        if (l.z < -0.03 * s || l.z > 0.06 * s) {
            return NONE;
        }
        for (int b = PREV; b <= NEXT; b++) {
            float[] c = buttonCenter(b);
            if (Math.abs(l.x - c[0] * s) <= SLOT * 0.75 * s && Math.abs(l.y - c[1] * s) <= SLOT * 0.75 * s) {
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
            // No computador: a tecla segurada liga; com Shift, as setas trocam a forma.
            keyHeld = !isLoaded() && mineKey != null && mineKey.isDown() && client.gui.screen() == null;
            boolean shift = client.hasShiftDown();
            while (prevKey != null && prevKey.consumeClick()) {
                if (keyHeld && shift) {
                    cycleOwn(-1);
                }
            }
            while (nextKey != null && nextKey.consumeClick()) {
                if (keyHeld && shift) {
                    cycleOwn(1);
                }
            }
        } else {
            keyHeld = false;
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
            MineServer.send(ownActive(), shape);
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
            cycleOwn(b == NEXT ? 1 : -1);
        }
    }

    private static void cycleOwn(int step) {
        shape = Math.floorMod(shape + step, MineShape.values().length);
        last = "forma: " + MineShape.of(shape).name();
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

    /** O HUD do computador: enquanto a tecla está segurada, o nome da forma e como trocar (só sem VR). */
    public static void registerHud() {
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                net.minecraft.resources.Identifier.fromNamespaceAndPath(HandsOnVR.MOD_ID, "mine_desktop"), (graphics, delta) -> {
                    if (!keyHeld || isLoaded() || VrBridge.isVRActive()) {
                        return;
                    }
                    Minecraft client = Minecraft.getInstance();
                    String title = Component.translatable("handsonvr.mine.title").getString() + ": "
                            + Component.translatable(shape().translationKey()).getString();
                    String hint = MineServer.serverSupports() ? Component.translatable("handsonvr.mine.hint").getString()
                            : Component.translatable("handsonvr.mine.no_server").getString();
                    graphics.text(client.font, title, 4, 4, 0xFFFFFFFF, true);
                    graphics.text(client.font, hint, 4, 15, 0xFFA0A0A0, true);
                });
    }

    /**
     * Onde a mão direita toca: com a ponta dela perto dos botões, a mira do jogo ({@code hud/crosshair}) fica no ponto
     * exato que aperta (ele fica um pouco à frente do controle).
     */
    public static void drawTouchPoint(Draw d, com.mojang.blaze3d.vertex.PoseStack ms, Vec3 camera) {
        Vec3 tip = VrBridge.pointer(0);
        Panel panel = WristHudRenderer.wristPanel();
        VrBridge.Pose head = VrBridge.head();
        Sprite cross = Sprite.gui("hud/crosshair");
        if (!showButtons() || tip == null || panel == null || head == null || cross == null) {
            return;
        }
        float s = VrBridge.worldScale();
        float[] c = buttonCenter(TOGGLE);
        Vec3 strip = panel.world(c[0] * s, c[1] * s, 0);
        if (tip.distanceTo(strip) > 0.18 * s) {
            return;
        }
        Panel p = Panel.facing(tip, head.position(), 0, 1.0);
        float size = 0.016f * s, px = size / cross.width();
        ms.pushPose();
        ms.translate(tip.x - camera.x, tip.y - camera.y, tip.z - camera.z);
        ms.rotate(p.rotation());
        float left = -size / 2f, top = px * cross.height() / 2f;
        d.spriteSolid(cross, left + px, top - px, px, 0f, 0xFF3F3F3F);
        d.spriteSolid(cross, left, top, px, px * 0.5f, 0xFFFFFFFF);
        ms.popPose();
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
