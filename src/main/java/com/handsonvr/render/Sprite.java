package com.handsonvr.render;

import com.handsonvr.HandsOnVR;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Uma imagem pequena em pixels (ARGB), para desenhar ícones do jogo no mundo como quadradinhos opacos — o mesmo
 * desenho do inventário do Minecraft, sem depender de camadas translúcidas (o OIT do Minecraft 26 mistura camadas
 * próximas). Os ícones do jogo (ex.: {@code container/slot/helmet}) vêm das texturas carregadas, então um resource
 * pack que os mude também muda a mochila.
 */
public final class Sprite {
    private static final Map<String, Optional<Sprite>> CACHE = new HashMap<>();

    private final int width, height;
    private final int[] argb;

    private Sprite(int width, int height, int[] argb) {
        this.width = width;
        this.height = height;
        this.argb = argb;
    }

    /** Sprite desenhado no código: {@code rows} com um caractere por pixel; {@code ' '} é transparente. */
    public static Sprite of(String[] rows, Map<Character, Integer> colors) {
        int h = rows.length, w = 0;
        for (String r : rows) {
            w = Math.max(w, r.length());
        }
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < rows[y].length(); x++) {
                Integer c = colors.get(rows[y].charAt(x));
                px[y * w + x] = c == null ? 0 : c;
            }
        }
        return new Sprite(w, h, px);
    }

    /**
     * Um sprite da interface do jogo ({@code textures/gui/sprites/<path>.png}), lido uma vez e guardado. Null se não
     * existir (o desenho usa um substituto).
     */
    public static Sprite gui(String path) {
        return CACHE.computeIfAbsent("gui:" + path,
                k -> read(Identifier.withDefaultNamespace("textures/gui/sprites/" + path + ".png"), 64)).orElse(null);
    }

    /**
     * Um caractere da fonte de bitmap do jogo ({@code textures/font/ascii.png}, 16x16 células), recortado na largura
     * dele, como o jogo faz. A altura do sprite é a da célula (8 pixels na fonte padrão). Null se não houver.
     */
    public static Sprite glyph(char c) {
        if (c > 0xFF) {
            return null;
        }
        return CACHE.computeIfAbsent("glyph:" + c, k -> cutGlyph(c)).orElse(null);
    }

    private static Optional<Sprite> cutGlyph(char c) {
        Sprite sheet = CACHE.computeIfAbsent("font:ascii",
                k -> read(Identifier.withDefaultNamespace("textures/font/ascii.png"), 2048)).orElse(null);
        if (sheet == null || sheet.width % 16 != 0) {
            return Optional.empty();
        }
        int cell = sheet.width / 16, x0 = (c % 16) * cell, y0 = (c / 16) * cell, w = 0;
        for (int x = 0; x < cell; x++) {
            for (int y = 0; y < cell; y++) {
                if ((sheet.pixel(x0 + x, y0 + y) >>> 24) != 0) {
                    w = x + 1;
                }
            }
        }
        if (w == 0) {
            return Optional.empty();
        }
        int[] px = new int[w * cell];
        for (int y = 0; y < cell; y++) {
            for (int x = 0; x < w; x++) {
                px[y * w + x] = sheet.pixel(x0 + x, y0 + y);
            }
        }
        return Optional.of(new Sprite(w, cell, px));
    }

    /** Esquece os sprites lidos (troca de resource pack). */
    public static void clearCache() {
        CACHE.clear();
    }

    private static Optional<Sprite> read(Identifier id, int maxSize) {
        try {
            Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(id);
            if (resource.isEmpty()) {
                return Optional.empty();
            }
            try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
                int w = image.getWidth(), h = image.getHeight();
                if (w > maxSize || h > maxSize) {
                    return Optional.empty(); // animado ou estranho: usa o substituto
                }
                int[] px = new int[w * h];
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        px[y * w + x] = image.getPixel(x, y);
                    }
                }
                return Optional.of(new Sprite(w, h, px));
            }
        } catch (Exception e) {
            HandsOnVR.LOGGER.warn("[HandsOn VR] Não deu para ler o ícone {}: {}", id, e.toString());
            return Optional.empty();
        }
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Cor ARGB do pixel (x para a direita, y para baixo). */
    public int pixel(int x, int y) {
        return argb[y * width + x];
    }
}
