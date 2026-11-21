package com.handsonvr.radial;

import com.handsonvr.vr.Panel;
import com.handsonvr.vr.VrBridge;
import net.minecraft.world.phys.Vec3;

/**
 * Menu radial da hotbar (inspirado em Half-Life: Alyx): enquanto o botão (B na mão principal, X na secundária) está
 * apertado, os 9 espaços da hotbar ficam num círculo em volta de onde a mão estava, com a "mão vazia" no centro,
 * virados para o rosto. Passar a mão por cima destaca; soltar o botão escolhe.
 *
 * <p>O menu fica preso à área de jogo do Vivecraft: anda e gira junto quando o jogador anda ou vira com o analógico,
 * mas não foge quando ele mexe a cabeça ou se inclina para alcançar um espaço.
 *
 * <p>Só o estado e o layout ficam aqui; o que escolher faz fica no {@link com.handsonvr.hands.HandsController}.
 */
public final class RadialMenu {
    /** Índice do "mão vazia" (o centro); 0-8 são os espaços da hotbar. */
    public static final int CENTER = -1;
    /** Nenhum alvo. */
    public static final int NONE = -2;

    /** Um pixel do jogo no radial (m). Cada espaço é uma casa da hotbar: 22 pixels, como no jogo. */
    public static final double PX = 0.0034;
    public static final double CELL = 22 * PX, HALF = CELL / 2;
    /** Raio do círculo dos 9 espaços: as casas (quadradas) não se encostam em nenhum ângulo. */
    public static final double RING_RADIUS = 0.15;
    private static final double TOUCH_DEPTH = 0.12, TOUCH_SLACK = 0.02;

    private int opener = -1;
    /** O painel no mundo (o último calculado) e na área de jogo (null fora do VR de verdade/sem rastreio). */
    private Panel panel, roomPanel;
    private final int[] hover = {NONE, NONE};
    /** Nesta abertura um item pego já foi solto num espaço: soltar o botão não equipa nada. */
    private boolean assigned = false;

    public boolean isOpen() {
        return opener >= 0;
    }

    /** Mão que abriu (-1 = fechado). */
    public int opener() {
        return opener;
    }

    /** O painel no mundo agora: onde a área de jogo (e o jogador) está. Null fechado. */
    public Panel panel() {
        if (roomPanel != null) {
            VrBridge.RoomFrame frame = VrBridge.roomFrame();
            if (frame != null) {
                panel = roomPanel.toWorld(frame);
            }
        }
        return panel;
    }

    /** Abre em volta da ponta da mão, virado para o rosto. */
    public boolean open(int hand) {
        Vec3 at = VrBridge.pointer(hand);
        VrBridge.Pose head = VrBridge.head();
        if (at == null || head == null) {
            return false;
        }
        opener = hand;
        panel = Panel.facing(at, head.position(), 0.0, VrBridge.worldScale());
        VrBridge.RoomFrame frame = VrBridge.roomFrame();
        roomPanel = frame == null ? null : panel.toRoom(frame);
        hover[0] = hover[1] = NONE;
        assigned = false;
        VrBridge.pulse(hand, 0.02f);
        return true;
    }

    public void close() {
        opener = -1;
        panel = roomPanel = null;
        hover[0] = hover[1] = NONE;
    }

    public void markAssigned() {
        assigned = true;
    }

    public boolean wasAssigned() {
        return assigned;
    }

    /** Centro (x, y) do espaço {@code index} da hotbar (0 no topo, sentido horário) ou do centro. */
    public static double[] position(int index) {
        if (index == CENTER) {
            return new double[] {0, 0};
        }
        double angle = Math.toRadians(90 - index * 40);
        return new double[] {Math.cos(angle) * RING_RADIUS, Math.sin(angle) * RING_RADIUS};
    }

    /** O alvo sob a ponta da mão: 0-8, {@link #CENTER} ou {@link #NONE}. Os alvos são as casas (quadradas). */
    public int targetAt(Vec3 point) {
        Panel p = panel();
        if (p == null || point == null) {
            return NONE;
        }
        Vec3 l = p.local(point);
        if (Math.abs(l.z) > TOUCH_DEPTH) {
            return NONE;
        }
        int best = NONE;
        double bestDistance = Double.MAX_VALUE;
        for (int i = CENTER; i < 9; i++) {
            double[] c = position(i);
            double d = Math.max(Math.abs(l.x - c[0]), Math.abs(l.y - c[1]));
            if (d <= HALF + TOUCH_SLACK && d < bestDistance) {
                best = i;
                bestDistance = d;
            }
        }
        return best;
    }

    public int hover(int hand) {
        return hover[hand];
    }

    /** O alvo destacado: o da mão que abriu ou, se ela não está sobre nada, o da outra mão. */
    public int highlighted() {
        if (opener < 0) {
            return NONE;
        }
        return hover[opener] != NONE ? hover[opener] : hover[1 - opener];
    }

    /** Atualiza o alvo da mão; true se mudou para um alvo (vibração). */
    public boolean updateHover(int hand, int target) {
        int before = hover[hand];
        hover[hand] = target;
        return target != NONE && target != before;
    }
}
