package com.handsonvr.render;

import com.handsonvr.HandsOnConfig;
import com.handsonvr.HandsOnVR;
import com.handsonvr.hands.HandsController;
import com.handsonvr.vr.Panel;
import com.handsonvr.vr.VrBridge;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.gui.ScreenArea;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

/**
 * O minimapa (Xaero's Minimap) no pulso interno direito.
 *
 * <p>O HUD do Vivecraft é o HUD do jogo desenhado numa textura e mostrado num painel no pulso esquerdo. O Xaero desenha
 * o minimapa nessa textura como parte do HUD. O HandsOn:
 * <ol>
 *   <li>anota onde o minimapa ficou na textura (o retângulo de tudo o que o Xaero desenhou neste quadro, inclusive as
 *       coordenadas embaixo dele), sem mudar nada no desenho dele;</li>
 *   <li>tira esse retângulo do painel do pulso esquerdo (o resto do HUD, como o chat, continua lá);</li>
 *   <li>mostra só esse pedaço da textura, do mesmo tamanho em pixels, grudado na parte de dentro do pulso direito, de pé
 *       quando a mão está levantada com a palma virada para o rosto (o norte do mapa aponta para os dedos).</li>
 * </ol>
 * Sem o Xaero, sem VR ou com a opção desligada, nada muda.
 */
public final class MinimapWrist {
    private MinimapWrist() {}

    private static final Identifier XAERO_HUD = Identifier.fromNamespaceAndPath("xaerohud", "hud");
    /** Onde fica, no referencial do controle direito (m): lado da palma (-x), atrás da mão (+z, para o cotovelo). */
    private static final float PALM_SIDE = 0.05f, BACK = 0.135f;
    /** Largura do mapa no pulso (m). */
    private static final float WIDTH = 0.085f;
    /** Folga (pixels do HUD) ao redor do que o Xaero desenhou. */
    private static final int MARGIN = 1;
    private static final int FULL_BRIGHT = 0xF000F0;

    private static boolean hooked = false;
    private static int hookTries = 0;
    /** Enquanto o Xaero desenha: junta o retângulo de cada coisa que ele põe no HUD. */
    private static boolean capturing = false;
    private static int cx0, cy0, cx1, cy1;
    /** O retângulo do desenho do mapa (fica de fora: é maior que o mapa, com bordas transparentes). */
    private static int pipX0, pipY0, pipX1, pipY1;
    /** O retângulo do último quadro (coordenadas do HUD) e o tamanho do HUD. */
    private static volatile int[] rect = null;
    private static volatile int guiW = 0, guiH = 0;
    private static volatile long rectFrame = -1;
    private static long frame = 0;
    /** Durante o desenho do painel do HUD pelo Vivecraft (para tirar o minimapa dele). */
    public static boolean hudPass = false;
    /** Teste automático: quantas vezes o painel foi desenhado sem o minimapa e o minimapa no pulso. */
    public static int debugCarved = 0, debugDrawn = 0;
    /** Teste automático: o que o Xaero pôs no HUD no último quadro (tipo e retângulo). */
    public static final java.util.List<String> debugParts = new java.util.ArrayList<>();

    public static boolean enabled() {
        return HandsOnConfig.get().minimapRightWrist && VrBridge.isVRActive();
    }

    /** A cada tick até conseguir: embrulha o desenho do Xaero (ele registra o HUD dele quando o jogo inicia). */
    public static void tick() {
        if (hooked || hookTries > 400 || !FabricLoader.getInstance().isModLoaded("xaerominimap")) {
            return;
        }
        hookTries++;
        try {
            HudElementRegistry.replaceElement(XAERO_HUD, original -> (graphics, delta) -> {
                if (!enabled()) {
                    original.extractRenderState(graphics, delta);
                    return;
                }
                debugParts.clear();
                capturing = true;
                cx0 = cy0 = Integer.MAX_VALUE;
                cx1 = cy1 = Integer.MIN_VALUE;
                pipX0 = pipY0 = Integer.MAX_VALUE;
                pipX1 = pipY1 = Integer.MIN_VALUE;
                try {
                    original.extractRenderState(graphics, delta);
                } finally {
                    capturing = false;
                }
                guiW = graphics.guiWidth();
                guiH = graphics.guiHeight();
                // O quadrado do mapa pelo Xaero; sem ele, o retângulo do desenho do mapa (maior, com bordas transparentes).
                int[] map = xaeroMapRect(guiW, guiH, Minecraft.getInstance().getWindow().getGuiScale());
                if (map != null && map[2] > 0 && map[3] > 0) {
                    grow(map[0], map[1], map[0] + map[2], map[1] + map[3]);
                } else if (pipX1 > pipX0) {
                    grow(pipX0, pipY0, pipX1, pipY1);
                }
                if (cx1 > cx0 && cy1 > cy0) {
                    rect = new int[] {Math.max(0, cx0 - MARGIN), Math.max(0, cy0 - MARGIN),
                            Math.min(guiW, cx1 + MARGIN), Math.min(guiH, cy1 + MARGIN)};
                    rectFrame = frame;
                } else {
                    rect = null;
                }
            });
            hooked = true;
            HandsOnVR.LOGGER.info("[HandsOn VR] minimapa do Xaero: pronto para o pulso direito");
        } catch (Throwable t) {
            // O Xaero ainda não registrou o HUD dele: tenta no próximo tick.
        }
    }

    /**
     * O quadrado do minimapa pelo próprio Xaero (a sessão do módulo do minimapa: posição efetiva e tamanho na escala do
     * HUD), ou null. O desenho do mapa ocupa uma área maior e transparente, então o retângulo dele não serve.
     */
    static int[] xaeroMapRect(int screenW, int screenH, double scale) {
        try {
            Object module = Class.forName("xaero.hud.minimap.BuiltInHudModules").getField("MINIMAP").get(null);
            Object session = module.getClass().getMethod("getCurrentSession").invoke(module);
            if (session == null) {
                return null;
            }
            Class<?> c = session.getClass();
            int x = (int) c.getMethod("getEffectiveX", int.class, double.class).invoke(session, screenW, scale);
            int y = (int) c.getMethod("getEffectiveY", int.class, double.class).invoke(session, screenH, scale);
            int w = (int) c.getMethod("getWidth", double.class).invoke(session, scale);
            int h = (int) c.getMethod("getHeight", double.class).invoke(session, scale);
            return new int[] {x, y, w, h};
        } catch (Throwable t) {
            return null;
        }
    }

    /** Chamado pelo {@code GuiRenderStateMixin} para cada coisa posta no HUD. */
    public static void capture(Object state) {
        if (!capturing || !(state instanceof ScreenArea area)) {
            return;
        }
        ScreenRectangle b = area.bounds();
        if (debugParts.size() < 40) {
            debugParts.add(state.getClass().getSimpleName() + (b == null ? " sem retângulo" : " " + b.left() + "," + b.top() + " " + b.width() + "x" + b.height()));
        }
        if (b == null || b.width() <= 0 || b.height() <= 0) {
            return;
        }
        if (state instanceof net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState) {
            pipX0 = Math.min(pipX0, b.left());
            pipY0 = Math.min(pipY0, b.top());
            pipX1 = Math.max(pipX1, b.right());
            pipY1 = Math.max(pipY1, b.bottom());
            return;
        }
        grow(b.left(), b.top(), b.right(), b.bottom());
    }

    private static void grow(int x0, int y0, int x1, int y1) {
        cx0 = Math.min(cx0, x0);
        cy0 = Math.min(cy0, y0);
        cx1 = Math.max(cx1, x1);
        cy1 = Math.max(cy1, y1);
    }

    /** O retângulo do minimapa no HUD agora, ou null (sem minimapa, ou ele não foi desenhado há pouco). */
    public static int[] currentRect() {
        int[] r = rect;
        return r == null || frame - rectFrame > 10 || guiW <= 0 || guiH <= 0 || !enabled() ? null : r;
    }

    // ------------------------------------------------------------------ o painel do HUD sem o minimapa

    /**
     * O painel do HUD do Vivecraft, sem o retângulo do minimapa: o mesmo quadrado que ele desenha ({@code
     * RenderHelper.submitSizedQuadWithLightmap}), em até 4 pedaços em volta do buraco. Retorna o próximo "order".
     */
    public static int carvedQuad(float width, float height, float size, int light, float[] color, PoseStack ms,
            RenderType type, boolean flipY, SubmitNodeCollector out, int order) {
        debugCarved++;
        int[] r = currentRect();
        float hw = size * 0.5f, hh = hw * height / width;
        float w = guiW, h = guiH;
        float[][] parts = {
                {0, 0, w, r[1]},          // em cima
                {0, r[3], w, h},          // embaixo
                {0, r[1], r[0], r[3]},    // à esquerda
                {r[2], r[1], w, r[3]}};   // à direita
        org.vivecraft.client_vr.render.helpers.RenderHelper.submitLateCustomGeometry(out.order(order), ms, type, (pose, vc) -> {
            for (float[] p : parts) {
                if (p[2] - p[0] < 0.5f || p[3] - p[1] < 0.5f) {
                    continue;
                }
                float x0 = -hw + 2 * hw * p[0] / w, x1 = -hw + 2 * hw * p[2] / w;
                float yTop = hh - 2 * hh * p[1] / h, yBottom = hh - 2 * hh * p[3] / h;
                float u0 = p[0] / w, u1 = p[2] / w, vTop = v(p[1] / h, flipY), vBottom = v(p[3] / h, flipY);
                vertex(vc, pose, x0, yBottom, u0, vBottom, color, light);
                vertex(vc, pose, x1, yBottom, u1, vBottom, color, light);
                vertex(vc, pose, x1, yTop, u1, vTop, color, light);
                vertex(vc, pose, x0, yTop, u0, vTop, color, light);
            }
        });
        return order + 1;
    }

    /** A textura do HUD: o topo do HUD (y = 0) é v = 1 (como o Vivecraft desenha, sem inverter). */
    private static float v(float yFraction, boolean flipY) {
        return flipY ? yFraction : 1f - yFraction;
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose pose, float x, float y, float u, float v, float[] color, int light) {
        vc.addVertex(pose, x, y, 0f).setColor(color[0], color[1], color[2], color[3]).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0f, 0f, 1f);
    }

    // ------------------------------------------------------------------ o minimapa no pulso direito

    private static Vec3 axis(Quaternionfc rotation, float x, float y, float z) {
        Vector3f v = new Vector3f(x, y, z);
        if (rotation != null) {
            rotation.transform(v);
        }
        return new Vec3(v.x, v.y, v.z);
    }

    /**
     * O plano do mapa, grudado no pulso direito: na parte de dentro (o lado da palma), atrás da mão. A frente fica para
     * o lado da palma; o "cima" do mapa aponta para os dedos (a frente do controle) e a direita para o dedão (o topo do
     * controle): com a mão levantada e a palma virada para o rosto, o mapa fica de pé. Null sem a mão direita.
     */
    public static Panel wristPanel() {
        VrBridge.Pose hand = VrBridge.hand(0);
        if (hand == null) {
            return null;
        }
        float s = VrBridge.worldScale();
        Vec3 origin = hand.position().add(axis(hand.rotation(), -PALM_SIDE * s, 0f, BACK * s));
        return new Panel(origin, axis(hand.rotation(), 0, 1, 0), axis(hand.rotation(), 0, 0, -1), axis(hand.rotation(), -1, 0, 0), 1.0);
    }

    public static void render(LevelRenderContext context, HandsController hands) {
        frame++;
        int[] r = currentRect();
        PoseStack ms = context.poseStack();
        if (r == null || ms == null || context.submitNodeCollector() == null || hands.isHandHidden(0)
                || org.vivecraft.client_vr.gameplay.screenhandlers.GuiHandler.GUI_FRAMEBUFFER == null
                || Minecraft.getInstance().gui.screen() != null) {
            return;
        }
        Panel panel = wristPanel();
        if (panel == null) {
            return;
        }
        float s = VrBridge.worldScale();
        float w = guiW, h = guiH;
        float mw = WIDTH * s, mh = mw * (r[3] - r[1]) / (float) (r[2] - r[0]);
        float u0 = r[0] / w, u1 = r[2] / w, vTop = v(r[1] / h, false), vBottom = v(r[3] / h, false);
        RenderType type = org.vivecraft.client_vr.render.rendertypes.VRRenderTypes.entityTranslucentNoCardinalLightLinear(
                org.vivecraft.client_vr.gameplay.screenhandlers.GuiHandler.GUI_FRAMEBUFFER.getColorTextureView(), false, true);
        Vec3 camera = context.levelState().cameraRenderState.pos;
        Vec3 o = panel.origin();
        float[] white = {1f, 1f, 1f, 1f};
        ms.pushPose();
        ms.translate(o.x - camera.x, o.y - camera.y, o.z - camera.z);
        ms.rotate(panel.rotation());
        context.submitNodeCollector().submitCustomGeometry(ms, type, (pose, vc) -> {
            vertex(vc, pose, -mw / 2, -mh / 2, u0, vBottom, white, FULL_BRIGHT);
            vertex(vc, pose, mw / 2, -mh / 2, u1, vBottom, white, FULL_BRIGHT);
            vertex(vc, pose, mw / 2, mh / 2, u1, vTop, white, FULL_BRIGHT);
            vertex(vc, pose, -mw / 2, mh / 2, u0, vTop, white, FULL_BRIGHT);
        });
        debugDrawn++;
        ms.popPose();
    }
}
