package com.handsonvr.backpack;

import com.handsonvr.inv.InvOps;
import com.handsonvr.vr.Panel;
import com.handsonvr.vr.VrBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A mochila do ombro esquerdo (inspirada em The Walking Dead: Saints &amp; Sinners): um painel preso à mão que a
 * segura, virado para o rosto, com o crafting 2x2 + resultado e a armadura no topo, uma página de 3x3 do inventário
 * principal (3 páginas = os 27 espaços) e, embaixo, as setas de página e a zona de descarte. Os espaços são
 * quadrados, no padrão do inventário do Minecraft.
 *
 * <p>Esta classe só cuida do estado (quem segura, página, para onde cada mão aponta) e do layout. O que os gestos fazem
 * fica no {@link com.handsonvr.hands.HandsController}.
 *
 * <p>Coordenadas do painel em metros (escala real): origem no meio da borda de cima (a alça, onde fica a mão), x para
 * a direita, y para cima (o painel vai de y = 0 a y = -{@link #HEIGHT}).
 */
public final class Backpack {
    public enum Kind { SLOT, CRAFT_IN, CRAFT_OUT, ARMOR, DROP, PREV, NEXT }

    /** Um alvo do painel: tipo, espaço do menu do jogador (-1 se não for espaço), centro (x, y) e meio lado (é quadrado). */
    public record Element(Kind kind, int handler, double x, double y, double half) {
        public boolean isSlotLike() {
            return kind == Kind.SLOT || kind == Kind.CRAFT_IN || kind == Kind.ARMOR;
        }
    }

    public static final double WIDTH = 0.40, HEIGHT = 0.56;
    /** A mão segura a alça um pouco acima da borda do painel. */
    public static final double HANDLE = 0.03;
    public static final int PAGES = 3;
    /** Distância máxima da ponta da mão ao plano do painel para tocar (m). */
    private static final double TOUCH_DEPTH = 0.10;
    /** Folga além da borda de cada alvo (m): o toque não precisa ser exato. */
    private static final double TOUCH_SLACK = 0.015;

    /**
     * Medidas (m). Os espaços são quadrados como no inventário do Minecraft (18 pixels do jogo): os do crafting e da
     * armadura menores, os da página 3x3 maiores (alvo confortável para a mão), lado a lado como no jogo.
     */
    public static final double SMALL_SLOT = 0.054, GRID_SLOT = 0.09, DROP_SLOT = 0.063;
    /** Borda de tecido da mochila em volta da janela do inventário. */
    public static final double FRAME = 0.012;
    public static final double TOP_ROW_Y = -0.073, GRID_TOP = -0.17, BOTTOM_Y = -0.495;
    /** Seta do crafting (centro) e o resultado. */
    public static final double ARROW_X = -0.034, CRAFT_MID_Y = TOP_ROW_Y - SMALL_SLOT / 2;
    public static final double PAGE_TEXT_X = -0.055;

    private int holder = -1;
    private int page = 0;
    private final Element[] hover = new Element[2];
    /** Ticks que a mão (segurando um item) está parada sobre uma seta: passa de página sozinha. */
    private final int[] dwell = new int[2];
    private Panel panel;

    // ------------------------------------------------------------------ estado

    public boolean isOpen() {
        return holder >= 0;
    }

    /** Mão que segura a mochila (-1 = fechada). */
    public int holder() {
        return holder;
    }

    public int page() {
        return page;
    }

    public void open(int hand) {
        holder = hand;
        hover[0] = hover[1] = null;
        dwell[0] = dwell[1] = 0;
        panel = computePanel();
        VrBridge.pulse(hand, 0.04f);
    }

    /** Fecha (o grip da mão que segura foi solto). Sobras do crafting voltam para o inventário. */
    public void close(Minecraft client) {
        if (holder < 0) {
            return;
        }
        int was = holder;
        holder = -1;
        hover[0] = hover[1] = null;
        panel = null;
        if (InvOps.ready(client)) {
            LocalPlayer p = client.player;
            for (int i = InvOps.CRAFT_START; i < InvOps.CRAFT_START + 4; i++) {
                if (!InvOps.stack(p, i).isEmpty()) {
                    InvOps.quickMove(client, i);
                }
            }
            InvOps.flushCursor(client);
        }
        VrBridge.pulse(was, 0.02f);
    }

    public void reset() {
        holder = -1;
        page = 0;
        hover[0] = hover[1] = null;
        panel = null;
    }

    public void flip(int direction) {
        page = Math.floorMod(page + direction, PAGES);
        dwell[0] = dwell[1] = 0;
    }

    // ------------------------------------------------------------------ geometria

    /** Painel atual (preso à mão que segura, virado para o rosto); null fechado ou sem rastreio. */
    public Panel panel() {
        return panel;
    }

    /** Recalcula o painel a partir da mão que segura e da cabeça (chamado a cada tick e a cada quadro). */
    public Panel computePanel() {
        if (holder < 0) {
            return null;
        }
        VrBridge.Pose hand = VrBridge.hand(holder);
        VrBridge.Pose head = VrBridge.head();
        if (hand == null || head == null) {
            return panel;
        }
        double scale = VrBridge.worldScale();
        Panel facing = Panel.facing(hand.position(), head.position(), HEIGHT / 2 + HANDLE, scale);
        // A alça fica na mão: a borda de cima do painel um pouco abaixo dela.
        Vec3 origin = hand.position().subtract(facing.up().scale(HANDLE * scale));
        panel = new Panel(origin, facing.right(), facing.up(), facing.normal(), scale);
        return panel;
    }

    /** Todos os alvos da página atual. */
    public List<Element> layout() {
        List<Element> out = new ArrayList<>(24);
        double small = SMALL_SLOT / 2;
        // Crafting 2x2 (topo à esquerda), a seta e o resultado, como no inventário do jogo.
        double[] cx = {-0.143, -0.089};
        double[] cy = {TOP_ROW_Y, TOP_ROW_Y - SMALL_SLOT};
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 2; col++) {
                out.add(new Element(Kind.CRAFT_IN, InvOps.CRAFT_START + row * 2 + col, cx[col], cy[row], small));
            }
        }
        out.add(new Element(Kind.CRAFT_OUT, InvOps.RESULT, 0.021, CRAFT_MID_Y, small));
        // Armadura (topo à direita): capacete, peitoral / calças, botas.
        double[] ax = {0.089, 0.143};
        for (int i = 0; i < 4; i++) {
            out.add(new Element(Kind.ARMOR, InvOps.ARMOR_START + i, ax[i % 2], cy[i / 2], small));
        }
        // Página 3x3, espaços lado a lado.
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int handler = InvOps.MAIN_START + page * 9 + row * 3 + col;
                out.add(new Element(Kind.SLOT, handler, (col - 1) * GRID_SLOT, GRID_TOP - GRID_SLOT / 2 - row * GRID_SLOT,
                        GRID_SLOT / 2));
            }
        }
        // Barra de baixo: setas de página e o descarte (canto inferior direito).
        out.add(new Element(Kind.PREV, -1, -0.150, BOTTOM_Y, 0.03));
        out.add(new Element(Kind.NEXT, -1, 0.040, BOTTOM_Y, 0.03));
        out.add(new Element(Kind.DROP, -1, 0.140, BOTTOM_Y, DROP_SLOT / 2));
        return Collections.unmodifiableList(out);
    }

    /** O alvo de um tipo (o primeiro). Para o teste e o desenho. */
    public Element element(Kind kind) {
        for (Element e : layout()) {
            if (e.kind() == kind) {
                return e;
            }
        }
        return null;
    }

    /** O alvo deste espaço do menu, se estiver na página atual. */
    public Element elementFor(int handler) {
        for (Element e : layout()) {
            if (e.handler() == handler && e.kind() != Kind.CRAFT_OUT && e.kind() != Kind.DROP) {
                return e;
            }
        }
        return null;
    }

    /** A ponta da mão está sobre o painel (perto do plano e dentro da borda)? */
    public boolean onPanel(Vec3 point) {
        if (panel == null || point == null) {
            return false;
        }
        Vec3 l = panel.local(point);
        return Math.abs(l.z) <= TOUCH_DEPTH && Math.abs(l.x) <= WIDTH / 2 + 0.03 && l.y <= 0.03 && l.y >= -HEIGHT - 0.03;
    }

    /**
     * O alvo sob a ponta da mão, ou null. Os alvos são quadrados: vale o que contém o ponto (com uma folga na borda);
     * entre vizinhos, o que tem o ponto mais para dentro.
     */
    public Element elementAt(Vec3 point) {
        if (!onPanel(point)) {
            return null;
        }
        Vec3 l = panel.local(point);
        Element best = null;
        double bestScore = Double.MAX_VALUE;
        for (Element e : layout()) {
            double dx = Math.abs(l.x - e.x()), dy = Math.abs(l.y - e.y());
            if (dx > e.half() + TOUCH_SLACK || dy > e.half() + TOUCH_SLACK) {
                continue;
            }
            double score = Math.max(dx, dy) / e.half(); // <= 1 dentro do quadrado
            if (score < bestScore) {
                best = e;
                bestScore = score;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ mãos

    /** Alvo em que a mão está (atualizado a cada tick). */
    public Element hover(int hand) {
        return hover[hand];
    }

    /** Atualiza o alvo da mão; retorna true se mudou para um alvo (vibração de "passou por cima"). */
    public boolean updateHover(int hand, Element e) {
        Element before = hover[hand];
        hover[hand] = e;
        boolean changed = e != null && (before == null || before.kind() != e.kind() || before.handler() != e.handler()
                || before.x() != e.x());
        if (e == null || (e.kind() != Kind.PREV && e.kind() != Kind.NEXT) || changed) {
            dwell[hand] = 0;
        }
        return changed;
    }

    /** Conta o tempo parado sobre uma seta; retorna true quando é hora de virar a página. */
    public boolean dwellTick(int hand) {
        Element e = hover[hand];
        if (e == null || (e.kind() != Kind.PREV && e.kind() != Kind.NEXT)) {
            dwell[hand] = 0;
            return false;
        }
        dwell[hand]++;
        if (dwell[hand] >= 10) {
            dwell[hand] = -5; // a próxima virada demora um pouco mais
            return true;
        }
        return false;
    }
}
