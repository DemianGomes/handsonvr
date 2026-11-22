package com.handsonvr.render;

import com.handsonvr.HandsOnConfig;
import com.handsonvr.hands.HandsController;
import com.handsonvr.vr.Panel;
import com.handsonvr.vr.VrBridge;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * O que a tela do jogo mostra no HUD e o VR esconde, preso ao corpo (padrão em docs/VISUAL.md):
 * <ul>
 *   <li><b>No pulso esquerdo</b>, grudado na lateral interna do antebraço como o HUD "no pulso" do Vivecraft: a vida, a armadura, a fome, a
 *       barra de XP com o nível e os efeitos ativos, com os ícones do HUD do jogo, pequenos ({@code hud/heart/*},
 *       {@code hud/armor_*}, {@code hud/food_*}, {@code hud/experience_bar_*}, {@code textures/mob_effect/*}).</li>
 *   <li><b>Perto do dedão de cada mão</b>, a quantidade da pilha que ela segura, como num espaço do inventário (o que
 *       é sozinho, como a espada, não mostra nada).</li>
 * </ul>
 * Só o próprio jogador vê: é desenho do cliente, nada vai para o servidor.
 */
public final class WristHudRenderer {
    private WristHudRenderer() {}

    /** Um pixel do HUD (m): 81 pixels de largura (10 corações) dão uns 13 cm. */
    private static final float PX = 0.0016f;
    private static final int WIDTH = 81, ROW = 10;
    /**
     * Onde o relógio fica, no referencial do controle esquerdo (m), como o HUD "no pulso" do Vivecraft: na lateral
     * interna do antebraço (+x, o lado do corpo), afastado o bastante para ficar fora da segunda camada (mangas) das skins, atrás da mão (+z, para o cotovelo), deitado ao longo do braço.
     */
    private static final float WRIST_SIDE = 0.065f, WRIST_BACK = 0.15f, WRIST_UP = 0.0f;
    /** Fundo translúcido como o do chat do jogo. */
    private static final int BACKGROUND = 0x80000000;
    /** O verde do nível de XP do jogo. */
    private static final int XP_GREEN = 0xFF80FF20;
    /** Quantidade na mão: o "espaço" (m) e onde fica o dedão no referencial do controle (lado, cima, trás). */
    private static final float COUNT_SLOT = 0.05f, THUMB_SIDE = 0.035f, THUMB_UP = 0.03f, THUMB_BACK = 0.01f;


    private static Vec3 axis(Quaternionfc rotation, float x, float y, float z) {
        Vector3f v = new Vector3f(x, y, z);
        if (rotation != null) {
            rotation.transform(v);
        }
        return new Vec3(v.x, v.y, v.z);
    }

    /**
     * O plano do relógio, grudado no antebraço esquerdo: anda e gira com o controle, como o HUD "no pulso" do Vivecraft.
     * A leitura vai do cotovelo para a mão (x = para a frente do controle), o "cima" é o de cima do controle e a frente
     * do desenho fica virada para o lado interno do braço. Null sem a mão esquerda.
     */
    public static Panel wristPanel() {
        VrBridge.Pose hand = VrBridge.hand(1);
        if (hand == null) {
            return null;
        }
        float s = VrBridge.worldScale();
        Vec3 origin = hand.position().add(axis(hand.rotation(), WRIST_SIDE * s, WRIST_UP * s, WRIST_BACK * s));
        return new Panel(origin, axis(hand.rotation(), 0, 0, -1), axis(hand.rotation(), 0, 1, 0), axis(hand.rotation(), 1, 0, 0), 1.0);
    }

    public static void render(LevelRenderContext context, HandsController hands) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer p = client.player;
        PoseStack ms = context.poseStack();
        VrBridge.Pose head = VrBridge.head();
        if (p == null || ms == null || context.submitNodeCollector() == null || !VrBridge.isVRActive() || head == null) {
            return;
        }
        Vec3 camera = context.levelState().cameraRenderState.pos;
        Draw d = new Draw(ms, context.submitNodeCollector());
        HandsOnConfig config = HandsOnConfig.get();
        if (config.handCount) {
            for (int hand = 0; hand < 2; hand++) {
                if (!hands.isHandHidden(hand)) {
                    handCount(d, ms, camera, head.position(), hand, hand == 0 ? p.getMainHandItem() : p.getOffhandItem());
                }
            }
        }
        if (config.wristHud && !p.isCreative() && !p.isSpectator()) { // continua com a mochila na mão: só o item some, o relógio fica
            Panel panel = wristPanel();
            if (panel != null) {
                wrist(d, ms, camera, client, p, panel);
            }
        }
    }

    /** A quantidade da pilha da mão, perto do dedão, virada para o rosto. */
    private static void handCount(Draw d, PoseStack ms, Vec3 camera, Vec3 eye, int hand, ItemStack stack) {
        VrBridge.Pose pose = VrBridge.hand(hand);
        if (pose == null || stack.getCount() <= 1) {
            return;
        }
        float s = VrBridge.worldScale();
        // O dedão: à esquerda do controle direito, à direita do esquerdo.
        Vec3 at = pose.position().add(axis(pose.rotation(), (hand == 0 ? -THUMB_SIDE : THUMB_SIDE) * s, THUMB_UP * s, THUMB_BACK * s));
        Panel panel = Panel.facing(at, eye, 0, 1.0);
        ms.pushPose();
        ms.translate(at.x - camera.x, at.y - camera.y, at.z - camera.z);
        ms.rotate(panel.rotation());
        d.mcCount(stack, 0f, 0f, COUNT_SLOT * s, 0f);
        ms.popPose();
    }

    /** O "relógio": corações, armadura, fome, XP e efeitos, de cima para baixo. */
    private static void wrist(Draw d, PoseStack ms, Vec3 camera, Minecraft client, LocalPlayer p, Panel panel) {
        Vec3 at = panel.origin();
        float px = PX * VrBridge.worldScale();
        List<MobEffectInstance> effects = new ArrayList<>(p.getActiveEffects());
        int armor = p.getArmorValue();
        int rows = 2 + (armor > 0 ? 1 : 0) + 1 + (effects.isEmpty() ? 0 : 1); // corações, fome, XP (+ armadura, efeitos)
        float height = rows * ROW + 4;
        ms.pushPose();
        ms.translate(at.x - camera.x, at.y - camera.y, at.z - camera.z);
        ms.rotate(panel.rotation());
        float left = -(WIDTH + 4) * px / 2f, top = height * px / 2f;
        d.rect(left, -top, -left, top, 0f, BACKGROUND);
        float x0 = left + 2 * px;
        float y = top - 2 * px;
        // Vida: 10 corações (cada um vale 2), como a fileira do HUD.
        int health = (int) Math.ceil(p.getHealth());
        int hearts = Math.min(10, (int) Math.ceil(p.getMaxHealth() / 2f));
        for (int i = 0; i < hearts; i++) {
            icon(d, "hud/heart/container", x0 + i * 8 * px, y, px);
            int v = health - i * 2;
            if (v >= 2) {
                icon(d, "hud/heart/full", x0 + i * 8 * px, y, px);
            } else if (v == 1) {
                icon(d, "hud/heart/half", x0 + i * 8 * px, y, px);
            }
        }
        y -= ROW * px;
        if (armor > 0) {
            for (int i = 0; i < 10; i++) {
                int v = armor - i * 2;
                icon(d, v >= 2 ? "hud/armor_full" : v == 1 ? "hud/armor_half" : "hud/armor_empty", x0 + i * 8 * px, y, px);
            }
            y -= ROW * px;
        }
        int food = p.getFoodData().getFoodLevel();
        for (int i = 0; i < 10; i++) {
            int v = food - i * 2;
            icon(d, v >= 2 ? "hud/food_full" : v == 1 ? "hud/food_half" : "hud/food_empty", x0 + i * 8 * px, y, px);
        }
        y -= ROW * px;
        // XP: a barra do HUD (182 pixels) na largura dos corações, e o nível em verde.
        Sprite bar = Sprite.gui("hud/experience_bar_background", 256), fill = Sprite.gui("hud/experience_bar_progress", 256);
        if (bar != null && fill != null) {
            float k = px * WIDTH / bar.width();
            float barTop = y - 3 * px;
            d.sprite(bar, x0, barTop, k, 0.0005f, Draw.KEEP_ALPHA);
            int filled = Math.round(p.experienceProgress * fill.width());
            Sprite part = filled > 0 ? fill.crop(0, 0, filled, fill.height()) : null;
            if (part != null) {
                d.sprite(part, x0, barTop, k, 0.001f, Draw.KEEP_ALPHA);
            }
        }
        if (p.experienceLevel > 0) {
            number(d, Integer.toString(p.experienceLevel), x0 + WIDTH * px / 2f, y + 1 * px, px, XP_GREEN);
        }
        y -= ROW * px;
        // Efeitos: os ícones do jogo (18 pixels) na metade do tamanho, lado a lado.
        float ex = x0;
        for (MobEffectInstance effect : effects) {
            if (ex > x0 + (WIDTH - 9) * px) {
                break;
            }
            String path = effect.getEffect().unwrapKey().map(k -> k.identifier().getPath()).orElse(null);
            Sprite iconSprite = path == null ? null : Sprite.texture("textures/mob_effect/" + path + ".png");
            if (iconSprite != null) {
                d.sprite(iconSprite, ex, y, px * 9f / iconSprite.width(), 0.0005f, Draw.KEEP_ALPHA);
            }
            ex += 10 * px;
        }
        ms.popPose();
    }

    private static void icon(Draw d, String name, float left, float top, float px) {
        Sprite s = Sprite.gui(name);
        if (s != null) {
            d.sprite(s, left, top, px * 9f / s.width(), 0.0005f, Draw.KEEP_ALPHA);
        }
    }

    /** Um número com a fonte do jogo, centrado em {@code cx}, com a borda preta do nível de XP. */
    private static void number(Draw d, String s, float cx, float top, float px, int color) {
        float width = 0;
        List<Sprite> glyphs = new ArrayList<>();
        for (char c : s.toCharArray()) {
            Sprite g = Sprite.glyph(c);
            if (g == null) {
                return;
            }
            glyphs.add(g);
            width += (g.width() * 8f / g.height() + 1f) * px;
        }
        float x = cx - width / 2f;
        for (Sprite g : glyphs) {
            float gp = px * 8f / g.height();
            for (int[] o : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                d.spriteSolid(g, x + o[0] * px, top + o[1] * px, gp, 0.0012f, 0xFF000000);
            }
            d.spriteSolid(g, x, top, gp, 0.0018f, color);
            x += (g.width() * 8f / g.height() + 1f) * px;
        }
    }
}
