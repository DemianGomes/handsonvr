package com.handsonvr.backpack;

import com.handsonvr.HandsOnConfig;
import com.handsonvr.hands.HandsController;
import com.handsonvr.holster.BodyFrame;
import com.handsonvr.holster.Holster;
import com.handsonvr.inv.InvOps;
import com.handsonvr.render.Draw;
import com.handsonvr.render.Sprite;
import com.handsonvr.vr.Panel;
import com.handsonvr.vr.VrBridge;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.Map;

/**
 * Desenha a mochila. Fechada: uma bolsa de lona nas costas, atrás do ombro esquerdo. Aberta: o painel na mão, uma
 * janela no padrão do inventário do Minecraft (cinza, com relevo) dentro da borda de lona da mochila, com a alça em
 * cima. Os espaços são quadrados como no jogo; a armadura vazia mostra o ícone do jogo (capacete, peitoral...), o
 * crafting tem a seta cinza do inventário e as setas de página são as do livro de receitas. O espaço sob a outra mão
 * clareia, como o cursor no inventário.
 *
 * <p>Tudo é desenhado opaco (inclusive os ícones, pixel a pixel): o translúcido do Minecraft 26 mistura camadas a
 * milímetros umas das outras.
 */
public final class BackpackRenderer {
    private BackpackRenderer() {}

    // Lona e couro da mochila.
    private static final int CANVAS = 0xFF46512F, CANVAS_SIDE = 0xFF38412A, CANVAS_TOP = 0xFF55623A,
            CANVAS_DARK = 0xFF2E3521, STITCH = 0xFF65744A;
    private static final int LEATHER = 0xFF6B4423, LEATHER_DARK = 0xFF4A2E17, BUCKLE = 0xFFD2AE4C;

    /** Um pixel do jogo na moldura da janela e na seta do crafting (m). */
    private static final float WINDOW_PX = 0.003f;

    /** Ícones dos espaços de armadura vazios (os do jogo): cabeça, peito, pernas, pés. */
    private static final String[] ARMOR_ICONS = {
            "container/slot/helmet", "container/slot/chestplate", "container/slot/leggings", "container/slot/boots"
    };

    /** A seta cinza do crafting do inventário (16x13, recortada de inventory.png). */
    private static final Sprite CRAFT_ARROW = Sprite.of(new String[] {
            "         #      ",
            "         ##     ",
            "         ###    ",
            "         ####   ",
            "         #####  ",
            "############### ",
            "################",
            "############### ",
            "         #####  ",
            "         ####   ",
            "         ###    ",
            "         ##     ",
            "         #      ",
    }, Map.of('#', Draw.MC_SLOT));

    /** Ícone do descarte (16x16): seta para baixo sobre o chão, no traço dos ícones do jogo. */
    public static final Sprite DROP_ICON = Sprite.of(new String[] {
            "                ",
            "      oooo      ",
            "      orro      ",
            "      orro      ",
            "      orro      ",
            "   ooooRRoooo   ",
            "   orrrRRrrro   ",
            "    orrRRrro    ",
            "     orRRro     ",
            "      orro      ",
            "       oo       ",
            "                ",
            "  gggggggggggg  ",
            "  dddddddddddd  ",
            "  dddddddddddd  ",
            "                ",
    }, Map.of('o', 0xFF3B1212, 'r', 0xFFD9443A, 'R', 0xFFF06A55, 'g', 0xFF5B8C33, 'd', 0xFF79553A));

    public static void render(LevelRenderContext context, HandsController hands) {
        Minecraft client = Minecraft.getInstance();
        HandsOnConfig config = HandsOnConfig.get();
        if (!config.backpack || client.player == null || client.level == null || !VrBridge.isVRActive()) {
            return;
        }
        PoseStack ms = context.poseStack();
        if (ms == null || context.submitNodeCollector() == null) {
            return;
        }
        Draw d = new Draw(ms, context.submitNodeCollector(), Draw.MC_PANEL);
        Vec3 camera = context.levelState().cameraRenderState.pos;
        Backpack bp = hands.backpack();
        if (bp.isOpen()) {
            renderOpen(client.player, hands, bp, d, camera);
        } else if (config.renderHolstered) {
            renderOnBack(hands, d, camera, config);
        }
    }

    // ------------------------------------------------------------------ aberta (painel na mão)

    private static void renderOpen(LocalPlayer p, HandsController hands, Backpack bp, Draw d, Vec3 camera) {
        Panel panel = bp.computePanel();
        if (panel == null) {
            return;
        }
        PoseStack ms = d.pose();
        ms.pushPose();
        Vec3 o = panel.origin();
        ms.translate(o.x - camera.x, o.y - camera.y, o.z - camera.z);
        ms.rotate(panel.rotation());
        float s = (float) panel.scale();
        ms.scale(s, s, s);
        float w = (float) (Backpack.WIDTH / 2), h = (float) Backpack.HEIGHT, f = (float) Backpack.FRAME;

        // A mochila aberta: borda de lona com costura, a alça de couro e, dentro, a janela do inventário.
        d.rect(-w, -h, w, 0, 0f, CANVAS);
        d.frame(-w + 0.004f, -h + 0.004f, w - 0.004f, -0.004f, 0.0015f, 0.0005f, STITCH);
        // Alça de couro em arco, onde a mão segura: dois pés presos na lona e a barra em cima.
        float handle = (float) Backpack.HANDLE, barTop = handle + 0.014f, barBottom = handle - 0.004f;
        for (float sx : new float[] {-1f, 1f}) {
            d.rect(sx * 0.055f, -0.008f, sx * 0.073f, barTop, 0.0005f, LEATHER);
            d.rect(sx * 0.058f, -0.005f, sx * 0.070f, 0.007f, 0.001f, BUCKLE); // rebite
        }
        d.rect(-0.073f, barBottom, 0.073f, barTop, 0.0008f, LEATHER);
        d.frame(-0.073f, barBottom, 0.073f, barTop, 0.002f, 0.0012f, LEATHER_DARK);
        d.mcWindow(-w + f, -h + f, w - f, -f, WINDOW_PX, 0.001f);

        int free = 1 - bp.holder();
        Backpack.Element hover = bp.hover(free);
        for (Backpack.Element e : bp.layout()) {
            boolean hovered = (hover != null && hover.kind() == e.kind() && hover.handler() == e.handler() && hover.x() == e.x())
                    || ((e.kind() == Backpack.Kind.SLOT || e.kind() == Backpack.Kind.CRAFT_IN)
                    && hands.dragMarked(HandsController.DragKind.BACKPACK, e.handler())); // marcado pelo arrastar
            drawElement(p, hands, d, e, hovered);
        }

        // "Fabricação" acima do crafting (o mesmo texto do inventário do jogo).
        d.text(Component.translatable("container.crafting").getString(), -0.170f, -0.034f, 0.002f, 0.011f,
                Draw.MC_LABEL, false);
        // A seta do crafting para o resultado.
        float arrowPx = WINDOW_PX;
        d.spriteCentered(CRAFT_ARROW, (float) Backpack.ARROW_X, (float) Backpack.CRAFT_MID_Y, 16 * arrowPx, 0.002f,
                Draw.MC_PANEL);

        // Entre as setas: "Página X/3" e "Espaços N/27".
        int used = 0;
        for (int i = InvOps.MAIN_START; i < InvOps.HOTBAR_START; i++) {
            if (!hands.displayStack(p, i).isEmpty()) {
                used++;
            }
        }
        float tx = (float) Backpack.PAGE_TEXT_X, by = (float) Backpack.BOTTOM_Y;
        d.text(Component.translatable("backpack.handsonvr.page", bp.page() + 1, Backpack.PAGES).getString(),
                tx, by + 0.010f, 0.002f, 0.014f, Draw.MC_LABEL, true);
        d.text(Component.translatable("backpack.handsonvr.slots", used, 27).getString(),
                tx, by - 0.011f, 0.002f, 0.011f, 0xFF5A5A5A, true);
        ms.popPose();
    }

    private static void drawElement(LocalPlayer p, HandsController hands, Draw d, Backpack.Element e, boolean hovered) {
        float x = (float) e.x(), y = (float) e.y(), size = (float) e.half() * 2f;
        switch (e.kind()) {
            case SLOT, CRAFT_IN, CRAFT_OUT, ARMOR -> {
                d.mcSlot(x, y, size, 0.002f, hovered);
                ItemStack stack = hands.displayStack(p, e.handler());
                if (stack.isEmpty() && e.kind() == Backpack.Kind.ARMOR) {
                    // O ícone do espaço vazio, como no inventário (16 pixels dentro dos 18 do espaço).
                    Sprite icon = Sprite.gui(ARMOR_ICONS[e.handler() - InvOps.ARMOR_START]);
                    d.spriteCentered(icon, x, y, size * 16f / 18f, 0.003f, hovered ? Draw.MC_SLOT_HOVER : Draw.MC_SLOT);
                }
                float itemSize = size * 0.85f;
                // O item é achatado (profundidade x0,2): um bloco 3D vai de z - 0,1 a z + 0,1 do tamanho. A quantidade
                // fica logo à frente dele, como no jogo (senão a quina do bloco cobre os números).
                float itemZ = 0.004f + itemSize * 0.1f;
                d.item(stack, x, y, itemZ, itemSize);
                // No resultado, o total que o "fabricar tudo" (grip + gatilho) dá, não só o de uma receita.
                ItemStack shown = e.kind() == Backpack.Kind.CRAFT_OUT && !stack.isEmpty() ? stack.copyWithCount(InvOps.craftTotal(stack,
                        InvOps.stack(p, 1), InvOps.stack(p, 2), InvOps.stack(p, 3), InvOps.stack(p, 4))) : stack;
                d.mcCount(shown, x, y, size, itemZ + itemSize * 0.13f);
            }
            case DROP -> {
                d.mcSlot(x, y, size, 0.002f, hovered);
                d.spriteCentered(DROP_ICON, x, y, size * 16f / 18f, 0.003f, hovered ? Draw.MC_SLOT_HOVER : Draw.MC_SLOT);
            }
            case PREV, NEXT -> {
                // As setas de página do livro de receitas (12x17); destacadas sob a mão.
                String name = e.kind() == Backpack.Kind.PREV ? "recipe_book/page_backward" : "recipe_book/page_forward";
                Sprite arrow = Sprite.gui(hovered ? name + "_highlighted" : name);
                if (arrow == null) {
                    arrow = Sprite.gui(name);
                }
                if (arrow != null) {
                    float px = 0.0035f;
                    d.spriteCentered(arrow, x, y, arrow.width() * px, 0.003f, Draw.MC_PANEL);
                }
            }
        }
    }

    // ------------------------------------------------------------------ fechada (nas costas)

    /** Fechada: uma bolsa de lona nas costas, atrás do ombro esquerdo (fica mais clara com a mão na zona). */
    private static void renderOnBack(HandsController hands, Draw d, Vec3 camera, HandsOnConfig config) {
        BodyFrame.Frame frame = hands.holsters().body().compute();
        if (frame == null) {
            return;
        }
        boolean hover = hands.holsters().zoneOf(0) == Holster.LEFT_SHOULDER || hands.holsters().zoneOf(1) == Holster.LEFT_SHOULDER;
        Vec3 anchor = frame.anchor(Holster.LEFT_SHOULDER, config);
        // Pendurada atrás do ombro: um pouco para trás e para baixo do ponto de pegar.
        Vec3 at = anchor.subtract(frame.forward().scale(0.12 * frame.scale())).add(0, -0.16 * frame.scale(), 0)
                .add(frame.right().scale(0.06 * frame.scale()));
        PoseStack ms = d.pose();
        ms.pushPose();
        ms.translate(at.x - camera.x, at.y - camera.y, at.z - camera.z);
        ms.rotate(Axis.YP.rotationDegrees(-frame.yaw()));
        float s = (float) frame.scale();
        ms.scale(s, s, s);
        float k = hover ? 1.22f : 1f;
        // -Z: o lado de fora (aba, bolso, fivela); +Z: o lado das costas (alças).
        box(d, 0, 0, 0, 0.12f, 0.15f, 0.05f, shade(CANVAS, k), shade(CANVAS_SIDE, k), shade(CANVAS_TOP, k));
        // Tampa por cima, um pouco maior que o corpo.
        box(d, 0, 0.135f, 0, 0.125f, 0.03f, 0.056f, shade(CANVAS_SIDE, k), shade(CANVAS_DARK, k), shade(CANVAS, k));
        // Lingueta de couro da tampa com a fivela.
        box(d, 0, 0.075f, -0.058f, 0.028f, 0.045f, 0.003f, shade(LEATHER, k), shade(LEATHER_DARK, k), shade(LEATHER, k));
        box(d, 0, 0.045f, -0.062f, 0.018f, 0.012f, 0.002f, shade(BUCKLE, k), shade(BUCKLE, k * 0.8f), shade(BUCKLE, k));
        // Bolso da frente com costura.
        box(d, 0, -0.065f, -0.058f, 0.085f, 0.055f, 0.008f, shade(CANVAS_SIDE, k), shade(CANVAS_DARK, k), shade(CANVAS, k));
        d.frame(-0.08f, -0.115f, 0.08f, -0.015f, 0.003f, -0.0665f, shade(STITCH, k));
        // Alça de mão em cima e as alças dos ombros atrás.
        box(d, 0, 0.175f, 0, 0.04f, 0.007f, 0.008f, shade(LEATHER, k), shade(LEATHER_DARK, k), shade(LEATHER, k));
        for (float sx : new float[] {-0.065f, 0.065f}) {
            box(d, sx, 0.0f, 0.054f, 0.014f, 0.16f, 0.004f, shade(LEATHER, k), shade(LEATHER_DARK, k), shade(LEATHER, k));
        }
        ms.popPose();
    }

    /** Cor mais clara ({@code k} > 1) ou mais escura. */
    private static int shade(int argb, float k) {
        int r = Math.min(255, Math.round(((argb >> 16) & 0xFF) * k));
        int g = Math.min(255, Math.round(((argb >> 8) & 0xFF) * k));
        int b = Math.min(255, Math.round((argb & 0xFF) * k));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * Caixa com centro (cx, cy, cz) e meias-medidas (hx, hy, hz): frente e trás com {@code face}, lados e baixo com
     * {@code side}, e o topo com {@code top} (luz de cima, como os blocos do jogo).
     */
    private static void box(Draw d, float cx, float cy, float cz, float hx, float hy, float hz, int face, int side, int top) {
        Vector3f[] c = new Vector3f[8];
        for (int i = 0; i < 8; i++) {
            c[i] = new Vector3f(cx + ((i & 1) == 0 ? -hx : hx), cy + ((i & 2) == 0 ? -hy : hy), cz + ((i & 4) == 0 ? -hz : hz));
        }
        d.quad(c[4], c[5], c[7], c[6], face); // +Z
        d.quad(c[0], c[2], c[3], c[1], face); // -Z
        d.quad(c[1], c[3], c[7], c[5], side); // +X
        d.quad(c[0], c[4], c[6], c[2], side); // -X
        d.quad(c[2], c[6], c[7], c[3], top);  // +Y
        d.quad(c[0], c[1], c[5], c[4], shade(side, 0.8f)); // -Y
    }
}
