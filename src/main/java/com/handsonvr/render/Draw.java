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


    // ------------------------------------------------------------------ estilo do inventário do Minecraft

    /** Cores da interface do jogo (inventory.png e slot.png). */
    public static final int MC_PANEL = 0xFFC6C6C6, MC_PANEL_LIGHT = 0xFFFFFFFF, MC_PANEL_DARK = 0xFF555555,
            MC_OUTLINE = 0xFF000000, MC_SLOT = 0xFF8B8B8B, MC_SLOT_DARK = 0xFF373737, MC_SLOT_LIGHT = 0xFFFFFFFF,
            MC_LABEL = 0xFF404040;
    /** Espaço sob o cursor: o branco 0x60 + 0x20 do jogo (slot_highlight_back/front) sobre o cinza do espaço. */
    public static final int MC_SLOT_HOVER = 0xFFC1C1C1;

    /**
     * Um espaço do inventário (como slot.png, 18x18): fundo cinza, linha escura em cima e à esquerda, clara embaixo e
     * à direita. {@code size} é o lado em metros (18 pixels do jogo).
     */
    public void mcSlot(float cx, float cy, float size, float z, boolean hovered) {
        float h = size / 2f, t = size / 18f;
        float x0 = cx - h, x1 = cx + h, y0 = cy - h, y1 = cy + h;
        rect(x0, y0, x1, y1, z, MC_SLOT);
        List<Vector3f> dark = new ArrayList<>(8), light = new ArrayList<>(8);
        addRect(dark, x0, y1 - t, x1 - t, y1, z + 0.0004f);      // cima
        addRect(dark, x0, y0 + t, x0 + t, y1 - t, z + 0.0004f);  // esquerda
        addRect(light, x0 + t, y0, x1, y0 + t, z + 0.0004f);     // baixo
        addRect(light, x1 - t, y0 + t, x1, y1 - t, z + 0.0004f); // direita
        quads(dark, MC_SLOT_DARK);
        quads(light, MC_SLOT_LIGHT);
        if (hovered) {
            rect(x0 + t, y0 + t, x1 - t, y1 - t, z + 0.0006f, MC_SLOT_HOVER);
        }
    }

    /**
     * Janela no estilo do jogo (como inventory.png): contorno preto de cantos cortados, relevo branco em cima e à
     * esquerda, cinza escuro embaixo e à direita e fundo cinza claro. {@code px} = um pixel do jogo, em metros.
     */
    public void mcWindow(float x0, float y0, float x1, float y1, float px, float z) {
        rect(x0 + px, y0 + px, x1 - px, y1 - px, z, MC_PANEL);
        List<Vector3f> outline = new ArrayList<>(16), light = new ArrayList<>(8), dark = new ArrayList<>(8);
        float zz = z + 0.0004f;
        addRect(outline, x0 + 2 * px, y1 - px, x1 - 2 * px, y1, zz); // cima
        addRect(outline, x0 + 2 * px, y0, x1 - 2 * px, y0 + px, zz); // baixo
        addRect(outline, x0, y0 + 2 * px, x0 + px, y1 - 2 * px, zz); // esquerda
        addRect(outline, x1 - px, y0 + 2 * px, x1, y1 - 2 * px, zz); // direita
        addRect(outline, x0 + px, y1 - 2 * px, x0 + 2 * px, y1 - px, zz); // cantos (um pixel na diagonal)
        addRect(outline, x1 - 2 * px, y1 - 2 * px, x1 - px, y1 - px, zz);
        addRect(outline, x0 + px, y0 + px, x0 + 2 * px, y0 + 2 * px, zz);
        addRect(outline, x1 - 2 * px, y0 + px, x1 - px, y0 + 2 * px, zz);
        addRect(light, x0 + 2 * px, y1 - 3 * px, x1 - 3 * px, y1 - px, zz); // relevo claro: cima
        addRect(light, x0 + px, y0 + 3 * px, x0 + 3 * px, y1 - 2 * px, zz); // e esquerda
        addRect(dark, x0 + 3 * px, y0 + px, x1 - 2 * px, y0 + 3 * px, zz);  // relevo escuro: baixo
        addRect(dark, x1 - 3 * px, y0 + 3 * px, x1 - px, y1 - 3 * px, zz);  // e direita
        quads(outline, MC_OUTLINE);
        quads(light, MC_PANEL_LIGHT);
        quads(dark, MC_PANEL_DARK);
    }

    /**
     * Desenha um {@link Sprite} pixel por pixel, como quadradinhos opacos ({@code left}/{@code top} = canto de cima à
     * esquerda; {@code px} = tamanho de um pixel em metros). Pixels semitransparentes são misturados com {@code over}
     * (a cor que está atrás). Pixels vizinhos da mesma cor viram um retângulo só.
     */
    public void sprite(Sprite s, float left, float top, float px, float z, int over) {
        if (s == null) {
            return;
        }
        java.util.Map<Integer, List<Vector3f>> byColor = new java.util.HashMap<>();
        for (int y = 0; y < s.height(); y++) {
            int x = 0;
            while (x < s.width()) {
                int c = s.pixel(x, y);
                if ((c >>> 24) == 0) {
                    x++;
                    continue;
                }
                int solid = blend(c, over);
                int end = x + 1;
                while (end < s.width() && (s.pixel(end, y) >>> 24) != 0 && blend(s.pixel(end, y), over) == solid) {
                    end++;
                }
                addRect(byColor.computeIfAbsent(solid, k -> new ArrayList<>()), left + x * px, top - (y + 1) * px,
                        left + end * px, top - y * px, z);
                x = end;
            }
        }
        byColor.forEach((color, corners) -> quads(corners, color));
    }

    /** Sprite centrado em (cx, cy) com {@code size} metros de largura. */
    public void spriteCentered(Sprite s, float cx, float cy, float size, float z, int over) {
        if (s == null) {
            return;
        }
        float px = size / s.width();
        sprite(s, cx - size / 2f, cy + px * s.height() / 2f, px, z, over);
    }

    /** {@code argb} misturada sobre {@code base} (opaca), alfa cheio. */
    private static int blend(int argb, int base) {
        float a = ((argb >>> 24) & 0xFF) / 255f;
        int r = mix((argb >> 16) & 0xFF, (base >> 16) & 0xFF, a);
        int g = mix((argb >> 8) & 0xFF, (base >> 8) & 0xFF, a);
        int b = mix(argb & 0xFF, base & 0xFF, a);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Texto de {@code height} metros de altura, centrado (ou alinhado à esquerda) em (x, y). */
    public void text(String s, float x, float y, float z, float height, int argb, boolean centered) {
        text(s, x, y, z, height, argb, centered, false);
    }

    /** Texto com a sombra do jogo (como a quantidade dos itens no inventário). */
    public void text(String s, float x, float y, float z, float height, int argb, boolean centered, boolean shadow) {
        Font font = Minecraft.getInstance().font;
        ms.pushPose();
        ms.translate(x, y, z);
        float k = height / 8f;
        ms.scale(k, -k, k);
        float w = font.width(s);
        FormattedCharSequence seq = FormattedCharSequence.forward(s, Style.EMPTY);
        out.submitText(ms, centered ? -w / 2f : 0f, -4f, seq, shadow, Font.DisplayMode.NORMAL, FULL_LIGHT, argb, 0, 0);
        ms.popPose();
    }

    /** Item como ícone (estilo inventário) de {@code size} metros, centrado em (x, y, z). */
    public void item(ItemStack stack, float x, float y, float z, float size) {
        // Achatado na profundidade: blocos 3D não saem do painel (nem cobrem a quantidade) e a frente fica igual.
        item(stack, ItemDisplayContext.GUI, x, y, z, size, FULL_LIGHT, 0.2f);
    }

    /** Item com o contexto e a luz escolhidos (coldres usam a luz do lugar). */
    public void item(ItemStack stack, ItemDisplayContext context, float x, float y, float z, float size, int light) {
        item(stack, context, x, y, z, size, light, 1f);
    }

    /** Item com a profundidade multiplicada por {@code depth}. */
    public void item(ItemStack stack, ItemDisplayContext context, float x, float y, float z, float size, int light,
            float depth) {
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
        ms.scale(size, size, size * depth);
        itemState.submit(ms, out, light, OverlayTexture.NO_OVERLAY, 0);
        ms.popPose();
    }

    /**
     * Quantidade como no inventário do jogo: os números da fonte de bitmap do jogo, brancos com a sombra cinza escura
     * um pixel para baixo e para a direita, no canto de baixo à direita do espaço ({@code size} = lado do espaço, 18
     * pixels do jogo). Desenhada opaca, pixel a pixel: o texto translúcido com sombra se embaralha no Minecraft 26.
     */
    public void mcCount(ItemStack stack, float cx, float cy, float size, float z) {
        if (stack.getCount() <= 1) {
            return;
        }
        String s = Integer.toString(stack.getCount());
        float u = size / 18f;
        Sprite[] glyphs = new Sprite[s.length()];
        float advance = 0; // em pixels da fonte (8 por célula), com 1 de espaço depois de cada número
        for (int i = 0; i < s.length(); i++) {
            glyphs[i] = Sprite.glyph(s.charAt(i));
            if (glyphs[i] == null) { // fonte diferente: texto normal, sem sombra
                text(s, cx + size / 2f - u, cy - size / 2f + 4.5f * u, z, 8f * u, 0xFFFFFFFF, false);
                return;
            }
            advance += glyphs[i].width() * 8f / glyphs[i].height() + 1f;
        }
        // Como no jogo: termina na coluna 16 do espaço, começa na linha 10.
        float x = cx - size / 2f + (18f - advance) * u, top = cy + size / 2f - 10f * u;
        for (Sprite g : glyphs) {
            float gp = u * 8f / g.height();
            spriteSolid(g, x + u, top - u, gp, z, 0xFF3F3F3F);
            spriteSolid(g, x, top, gp, z + 0.0006f, 0xFFFFFFFF);
            x += (g.width() * 8f / g.height() + 1f) * u;
        }
    }

    /** Os pixels visíveis do sprite, todos numa cor só (texto e sombra). */
    public void spriteSolid(Sprite s, float left, float top, float px, float z, int argb) {
        List<Vector3f> corners = new ArrayList<>();
        for (int y = 0; y < s.height(); y++) {
            int x = 0;
            while (x < s.width()) {
                if ((s.pixel(x, y) >>> 24) < 0x80) {
                    x++;
                    continue;
                }
                int end = x + 1;
                while (end < s.width() && (s.pixel(end, y) >>> 24) >= 0x80) {
                    end++;
                }
                addRect(corners, left + x * px, top - (y + 1) * px, left + end * px, top - y * px, z);
                x = end;
            }
        }
        quads(corners, argb);
    }

    /** Quantidade (quando maior que 1) no canto de baixo à direita do espaço. */
    public void count(ItemStack stack, float cx, float cy, float r, float z) {
        if (stack.getCount() > 1) {
            text(Integer.toString(stack.getCount()), cx + r * 0.55f, cy - r * 0.62f, z, r * 0.42f, 0xFFFFFFFF, true, true);
        }
    }
}
