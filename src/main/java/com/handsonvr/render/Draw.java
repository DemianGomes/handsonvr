package com.handsonvr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Desenho simples no mundo (painéis da mochila e do radial) no modelo do Minecraft 26: tudo vira "submit" para o
 * {@link SubmitNodeCollector}. Retângulos, discos e anéis (quads coloridos), texto e itens, sempre com luz cheia
 * (legível no escuro) e dos dois lados. Coordenadas em metros no espaço já posicionado na {@link PoseStack}.
 */
public final class Draw {
    public static final int FULL_LIGHT = LightCoordsUtil.FULL_BRIGHT;
    private static final int SEGMENTS = 28;

    /**
     * Textura com um texel branco puro (snow.png, texel 1,0): no modo opaco, a cor do vértice sai exata.
     */
    private static final Identifier WHITE = Identifier.withDefaultNamespace("textures/block/snow.png");
    private static final float WHITE_U = 1.5f / 16f, WHITE_V = 0.5f / 16f;

    private final PoseStack ms;
    private final SubmitNodeCollector out;
    /** Modo opaco: cor de fundo sobre a qual as cores semitransparentes são misturadas (null = translúcido). */
    private final Integer opaqueOver;

    /** Translúcido (o radial, solto no ar). */
    public Draw(PoseStack ms, SubmitNodeCollector out) {
        this(ms, out, null);
    }

    /**
     * {@code opaqueOver} != null: tudo opaco, com profundidade normal (painéis com várias camadas, como a mochila). O
     * Minecraft 26 desenha o translúcido com OIT (aproximado pela profundidade), que mistura camadas a milímetros umas
     * das outras; opaco, cada camada fica no lugar. As cores semitransparentes viram a mistura com esse fundo.
     */
    public Draw(PoseStack ms, SubmitNodeCollector out, Integer opaqueOver) {
        this.ms = ms;
        this.out = out;
        this.opaqueOver = opaqueOver;
    }

    public PoseStack pose() {
        return ms;
    }

    /** Cor final: no modo opaco, a mistura com o fundo (alfa cheio). */
    private int color(int argb) {
        if (opaqueOver == null) {
            return argb;
        }
        float a = ((argb >>> 24) & 0xFF) / 255f;
        int r = mix((argb >> 16) & 0xFF, (opaqueOver >> 16) & 0xFF, a);
        int g = mix((argb >> 8) & 0xFF, (opaqueOver >> 8) & 0xFF, a);
        int b = mix(argb & 0xFF, opaqueOver & 0xFF, a);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int mix(int top, int base, float a) {
        return Math.round(top * a + base * (1f - a));
    }

    /** Envia quads coloridos (cada um com 4 cantos), dos dois lados, num único submit. */
    private void quads(List<Vector3f> corners, int argb) {
        if (corners.isEmpty() || (opaqueOver != null && (argb >>> 24) == 0)) {
            return;
        }
        int c = color(argb);
        RenderType type = opaqueOver == null ? RenderTypes.debugQuads() : RenderTypes.beaconBeam(WHITE, false);
        out.submitCustomGeometry(ms, type, (pose, vc) -> {
            for (int i = 0; i + 3 < corners.size(); i += 4) {
                Vector3f a = corners.get(i), b = corners.get(i + 1), cc = corners.get(i + 2), d = corners.get(i + 3);
                v(vc, pose, a, c);
                v(vc, pose, b, c);
                v(vc, pose, cc, c);
                v(vc, pose, d, c);
                v(vc, pose, d, c);
                v(vc, pose, cc, c);
                v(vc, pose, b, c);
                v(vc, pose, a, c);
            }
        });
    }

    /** Um vértice com todos os campos (os que o formato da camada não usa são ignorados). */
    private static void v(VertexConsumer vc, PoseStack.Pose pose, Vector3f p, int argb) {
        vc.addVertex(pose, p.x, p.y, p.z).setColor(argb).setUv(WHITE_U, WHITE_V).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(FULL_LIGHT).setNormal(pose, 0f, 0f, 1f);
    }

    /** Quadrilátero qualquer. */
    public void quad(Vector3f a, Vector3f b, Vector3f c, Vector3f d, int argb) {
        quads(List.of(a, b, c, d), argb);
    }

    /** Retângulo no plano z. */
    public void rect(float x0, float y0, float x1, float y1, float z, int argb) {
        quad(new Vector3f(x0, y0, z), new Vector3f(x1, y0, z), new Vector3f(x1, y1, z), new Vector3f(x0, y1, z), argb);
    }

    /** Moldura retangular (só a borda, de espessura {@code t}). */
    public void frame(float x0, float y0, float x1, float y1, float t, float z, int argb) {
        List<Vector3f> c = new ArrayList<>(16);
        addRect(c, x0, y0, x1, y0 + t, z);
        addRect(c, x0, y1 - t, x1, y1, z);
        addRect(c, x0, y0 + t, x0 + t, y1 - t, z);
        addRect(c, x1 - t, y0 + t, x1, y1 - t, z);
        quads(c, argb);
    }

    private static void addRect(List<Vector3f> c, float x0, float y0, float x1, float y1, float z) {
        c.add(new Vector3f(x0, y0, z));
        c.add(new Vector3f(x1, y0, z));
        c.add(new Vector3f(x1, y1, z));
        c.add(new Vector3f(x0, y1, z));
    }

    /** Disco cheio. */
    public void disc(float cx, float cy, float r, float z, int argb) {
        ring(cx, cy, 0f, r, z, argb);
    }

    /** Anel entre os raios {@code inner} e {@code outer}. */
    public void ring(float cx, float cy, float inner, float outer, float z, int argb) {
        List<Vector3f> c = new ArrayList<>(SEGMENTS * 4);
        for (int i = 0; i < SEGMENTS; i++) {
            double a0 = Math.PI * 2 * i / SEGMENTS, a1 = Math.PI * 2 * (i + 1) / SEGMENTS;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            c.add(new Vector3f(cx + c0 * inner, cy + s0 * inner, z));
            c.add(new Vector3f(cx + c0 * outer, cy + s0 * outer, z));
            c.add(new Vector3f(cx + c1 * outer, cy + s1 * outer, z));
            c.add(new Vector3f(cx + c1 * inner, cy + s1 * inner, z));
        }
        quads(c, argb);
    }

    /** Texto de {@code height} metros de altura, centrado (ou alinhado à esquerda) em (x, y). */
    public void text(String s, float x, float y, float z, float height, int argb, boolean centered) {
        Font font = Minecraft.getInstance().font;
        ms.pushPose();
        ms.translate(x, y, z);
        float k = height / 8f;
        ms.scale(k, -k, k);
        float w = font.width(s);
        FormattedCharSequence seq = FormattedCharSequence.forward(s, Style.EMPTY);
        out.submitText(ms, centered ? -w / 2f : 0f, -4f, seq, false, Font.DisplayMode.NORMAL, FULL_LIGHT, argb, 0, 0);
        ms.popPose();
    }

    /** Item como ícone (estilo inventário) de {@code size} metros, centrado em (x, y, z). */
    public void item(ItemStack stack, float x, float y, float z, float size) {
        item(stack, ItemDisplayContext.GUI, x, y, z, size, FULL_LIGHT);
    }

    /** Item com o contexto e a luz escolhidos (coldres usam a luz do lugar). */
    public void item(ItemStack stack, ItemDisplayContext context, float x, float y, float z, float size, int light) {
        if (stack.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        // Um estado novo por item: o submit é desenhado depois, no fim do quadro.
        ItemStackRenderState itemState = new ItemStackRenderState();
        mc.getItemModelResolver().updateForTopItem(itemState, stack, context, mc.level, null, 0);
        if (itemState.isEmpty()) {
            return;
        }
        ms.pushPose();
        ms.translate(x, y, z);
        ms.scale(size, size, size);
        itemState.submit(ms, out, light, OverlayTexture.NO_OVERLAY, 0);
        ms.popPose();
    }

    /** Quantidade (quando maior que 1) no canto de baixo à direita do espaço. */
    public void count(ItemStack stack, float cx, float cy, float r, float z) {
        if (stack.getCount() > 1) {
            text(Integer.toString(stack.getCount()), cx + r * 0.55f, cy - r * 0.62f, z, r * 0.42f, 0xFFFFFFFF, true);
        }
    }
}
