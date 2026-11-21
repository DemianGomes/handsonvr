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
        return gui(path, 64);
    }

    /** Como {@link #gui(String)}, aceitando imagens de até {@code maxSize} pixels (a hotbar tem 182). */
    public static Sprite gui(String path, int maxSize) {
        return cached("gui:" + path,
                () -> read(Identifier.withDefaultNamespace("textures/gui/sprites/" + path + ".png"), maxSize));
    }

    /**
     * Uma casa da hotbar sozinha (22x22 pixels na textura padrão), recortada de {@code hud/hotbar}: a borda da
     * esquerda e a primeira casa, mais a borda da direita do fim da barra. É a casa que o jogo desenharia se a hotbar
     * tivesse um espaço só. Null se a textura não tiver o formato da do jogo (182x22, ou múltiplos dela).
     */
    public static Sprite hotbarCell() {
        return cached("hotbar_cell", () -> {
            Sprite bar = gui("hud/hotbar", 2048);
            if (bar == null || bar.height % 22 != 0 || bar.width != 182 * (bar.height / 22)) {
                return Optional.empty();
            }
            int u = bar.height / 22; // pixels da textura por pixel do jogo (resource packs em alta resolução)
            int left = 19 * u, right = 3 * u, w = left + right, h = bar.height;
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < left; x++) {
                    px[y * w + x] = bar.pixel(x, y);
                }
                for (int x = 0; x < right; x++) {
                    px[y * w + left + x] = bar.pixel(bar.width - right + x, y);
                }
            }
            return Optional.of(new Sprite(w, h, px));
        });
    }

    /**
     * Um caractere da fonte de bitmap do jogo ({@code textures/font/ascii.png}, 16x16 células), recortado na largura
     * dele, como o jogo faz. A altura do sprite é a da célula (8 pixels na fonte padrão). Null se não houver.
     */
    public static Sprite glyph(char c) {
        if (c > 0xFF) {
            return null;
        }
        return cached("glyph:" + c, () -> cutGlyph(c));
    }

    private static Optional<Sprite> cutGlyph(char c) {
        Sprite sheet = cached("font:ascii", () -> read(Identifier.withDefaultNamespace("textures/font/ascii.png"), 2048));
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

    /**
     * Um pedaço de uma textura de tela inteira (como {@code textures/gui/container/furnace.png}, de 256x256 no jogo),
     * em pixels da tela: {@code u, v, w, h}. Com um resource pack em alta resolução, o recorte acompanha a escala. Null
     * se a textura não existir ou não for quadrada e múltipla de 256.
     */
    public static Sprite guiCrop(String texture, int u, int v, int w, int h) {
        return cached("crop:" + texture + ":" + u + "," + v + "," + w + "," + h, () -> {
            Sprite sheet = cached("tex:" + texture, () -> read(Identifier.withDefaultNamespace(texture), 4096));
            if (sheet == null || sheet.width % 256 != 0 || sheet.height != sheet.width) {
                return Optional.empty();
            }
            int k = sheet.width / 256;
            return Optional.ofNullable(sheet.crop(u * k, v * k, w * k, h * k));
        });
    }

    /** Uma textura inteira qualquer ({@code textures/...png}, até 64 pixels), como os ícones de efeito. Null se não houver. */
    public static Sprite texture(String path) {
        return cached("img:" + path, () -> read(Identifier.withDefaultNamespace(path), 64));
    }

    /** Um pedaço deste sprite (x, y, largura, altura em pixels dele), ou null se ficar vazio. */
    public Sprite crop(int x, int y, int w, int h) {
        int x0 = Math.max(0, x), y0 = Math.max(0, y), x1 = Math.min(width, x + w), y1 = Math.min(height, y + h);
        if (x1 <= x0 || y1 <= y0) {
            return null;
        }
        int cw = x1 - x0, ch = y1 - y0;
        int[] px = new int[cw * ch];
        for (int j = 0; j < ch; j++) {
            for (int i = 0; i < cw; i++) {
                px[j * cw + i] = argb[(y0 + j) * width + x0 + i];
            }
        }
        return new Sprite(cw, ch, px);
    }

    /**
     * O sprite guardado com essa chave, lido na primeira vez. (Sem {@code computeIfAbsent}: ler um sprite pode ler
     * outro, e o HashMap não aceita mudar dentro do computeIfAbsent.)
     */
    private static Sprite cached(String key, java.util.function.Supplier<Optional<Sprite>> load) {
        Optional<Sprite> s = CACHE.get(key);
        if (s == null) {
            s = load.get();
            CACHE.put(key, s);
        }
        return s.orElse(null);
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
