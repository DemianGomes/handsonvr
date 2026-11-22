package com.handsonvr.input;

import com.handsonvr.HandsOnConfig;
import com.handsonvr.HandsOnVR;
import com.handsonvr.hands.BowHand;
import net.minecraft.client.Minecraft;
import org.vivecraft.client.VivecraftVRMod;
import org.vivecraft.client_vr.provider.ControllerType;
import org.vivecraft.client_vr.provider.MCVR;
import org.vivecraft.client_vr.provider.openvr_lwjgl.VRInputAction;

/**
 * Os botões do controle chegam ao Minecraft só pelas "ações" do SteamVR que o Vivecraft cria (não há leitura direta
 * do botão). Aqui escutamos as ações padrão do Quest 2 (API interna do Vivecraft, com proteção contra erro):
 * <ul>
 *   <li>grip direito/esquerdo = "próximo/anterior da hotbar": mochila, coldres e itens pegos;</li>
 *   <li>gatilho direito = "atacar" e esquerdo = "teleporte": um item por vez na mochila/radial; o esquerdo usa a mão
 *       esquerda;</li>
 *   <li>B = "menu radial" do Vivecraft e X = "inventário": o menu radial da hotbar de cada mão;</li>
 *   <li>A = "usar": bloqueado só na mão que segura a mochila.</li>
 * </ul>
 * O analógico direito ("girar") é sempre do Vivecraft: gira a câmera. Até a 0.2.4 ele trocava a hotbar; hoje a hotbar
 * é do menu radial.
 * Um "ouvinte" que responde true consome o aperto: a ação original do Vivecraft não acontece. As ações são recriadas
 * quando o VR reinicia — por isso o registro é conferido a cada tick.
 */
public final class VivecraftInput {
    private VivecraftInput() {}

    private static VRInputAction gripMain, gripOff, teleport, rotate, attack, use, radialMain, radialOff;
    private static boolean warned = false;

    public static void tick(Minecraft client) {
        try {
            StationInteractModule.ensureRegistered(); // o grip com a mão no baú não faz o "usar" do Vivecraft (só roda em VR)
            MCVR mcvr = MCVR.get();
            if (mcvr == null) {
                gripMain = gripOff = teleport = rotate = attack = use = radialMain = radialOff = null;
                return;
            }
            VivecraftVRMod mod = VivecraftVRMod.INSTANCE;
            gripMain = ensure(mcvr.getInputAction(mod.keyHotbarNext), gripMain, new GripListener(0));
            gripOff = ensure(mcvr.getInputAction(mod.keyHotbarPrev), gripOff, new GripListener(1));
            teleport = ensure(mcvr.getInputAction(mod.keyTeleport), teleport, new LeftTriggerListener());
            rotate = mcvr.getInputAction(mod.keyRotateAxis); // sem ouvinte: só para o teste conferir que é do Vivecraft
            attack = ensure(mcvr.getInputAction(client.options.keyAttack), attack, new RightTriggerListener());
            use = ensure(mcvr.getInputAction(client.options.keyUse), use, new UseListener());
            radialMain = ensure(mcvr.getInputAction(mod.keyRadialMenu), radialMain, new MenuButtonListener(0));
            radialOff = ensure(mcvr.getInputAction(client.options.keyInventory), radialOff, new MenuButtonListener(1));
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                HandsOnVR.LOGGER.warn("[HandsOn VR] Não consegui ligar os controles ao Vivecraft", t);
            }
        }
    }

    private static VRInputAction ensure(VRInputAction current, VRInputAction known, VRInputAction.KeyListener listener) {
        if (current != null && current != known) {
            current.registerListener(listener);
            HandsOnVR.LOGGER.info("[HandsOn VR] Escutando a ação {}", current.name);
        }
        return current;
    }

    /** Teste automático (VR nulo do Vivecraft): quantas das 7 ações estão sendo escutadas. */
    public static int debugListening() {
        int n = 0;
        for (VRInputAction a : new VRInputAction[] {gripMain, gripOff, teleport, attack, use, radialMain, radialOff}) {
            n += a != null ? 1 : 0;
        }
        return n;
    }

    /**
     * Teste automático: o Vivecraft avisando os ouvintes de um aperto/soltura, como faz com o controle de verdade.
     * {@code which}: gripMain, gripOff, teleport, rotate, attack, use, radialMain ou radialOff; {@code axisX}: posição
     * do analógico (só para rotate). Retorna true se algum ouvinte consumiu (a ação original não aconteceria).
     */
    public static boolean debugNotify(String which, boolean pressed, float axisX) {
        VRInputAction action = switch (which) {
            case "gripMain" -> gripMain;
            case "gripOff" -> gripOff;
            case "teleport" -> teleport;
            case "attack" -> attack;
            case "use" -> use;
            case "radialMain" -> radialMain;
            case "radialOff" -> radialOff;
            default -> rotate;
        };
        if (action == null) {
            return false;
        }
        if (axisX != 0f && action.analogData.length > 0) {
            action.analogData[0].x = axisX;
        }
        boolean left = which.equals("gripOff") || which.equals("teleport") || which.equals("radialOff");
        return action.notifyListeners(pressed, left ? ControllerType.LEFT : ControllerType.RIGHT);
    }

    /** Nome da ação (para o relatório do teste). */
    public static String debugName(String which) {
        VRInputAction a = switch (which) {
            case "radialMain" -> radialMain;
            case "radialOff" -> radialOff;
            case "attack" -> attack;
            case "rotate" -> rotate;
            default -> null;
        };
        return a == null ? "-" : a.name;
    }

    private static boolean held(VRInputAction action) {
        try {
            return action != null && action.digitalData.length > 0 && action.digitalData[0].state;
        } catch (Throwable t) {
            return false;
        }
    }

    /** O grip da mão está apertado (dado cru da ação, lido pelo Vivecraft a cada quadro mesmo quando desativada). */
    public static boolean isGripHeld(int hand) {
        return held(hand == 0 ? gripMain : gripOff);
    }

    /** O gatilho da mão (direito = atacar, esquerdo = teleporte) está apertado. */
    public static boolean isTriggerHeld(int hand) {
        return held(hand == 0 ? attack : teleport);
    }

    /** O botão do radial da mão (B principal, X secundária) está apertado. */
    public static boolean isMenuButtonHeld(int hand) {
        return held(hand == 0 ? radialMain : radialOff);
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    private record GripListener(int hand) implements VRInputAction.KeyListener {
        @Override
        public boolean onPressed(ControllerType controller) {
            return HandsOnVR.onGripPressed(hand);
        }

        @Override
        public boolean onUnpressed(ControllerType controller) {
            return HandsOnVR.onGripReleased(hand);
        }

        @Override
        public int getPriority() {
            return 50;
        }
    }

    private record MenuButtonListener(int hand) implements VRInputAction.KeyListener {
        @Override
        public boolean onPressed(ControllerType controller) {
            return HandsOnVR.call("botão do radial", () -> HandsOnVR.hands().onMenuButton(mc(), hand, true));
        }

        @Override
        public boolean onUnpressed(ControllerType controller) {
            return HandsOnVR.call("botão do radial (soltar)", () -> HandsOnVR.hands().onMenuButton(mc(), hand, false));
        }

        @Override
        public int getPriority() {
            return 50;
        }
    }

    private static final class RightTriggerListener implements VRInputAction.KeyListener {
        private boolean consumed = false;

        @Override
        public boolean onPressed(ControllerType controller) {
            consumed = HandsOnVR.call("gatilho direito", () -> HandsOnVR.hands().onTrigger(mc(), 0));
            return consumed;
        }

        @Override
        public boolean onUnpressed(ControllerType controller) {
            boolean was = consumed;
            consumed = false;
            boolean dragged = HandsOnVR.call("gatilho direito (soltar)", () -> HandsOnVR.hands().onTriggerReleased(mc(), 0));
            return was || dragged;
        }

        @Override
        public int getPriority() {
            return 50;
        }
    }

    private static final class UseListener implements VRInputAction.KeyListener {
        private boolean consumed = false;

        @Override
        public boolean onPressed(ControllerType controller) {
            consumed = HandsOnVR.call("usar (A)", () -> HandsOnVR.hands().onUse(mc()));
            return consumed;
        }

        @Override
        public boolean onUnpressed(ControllerType controller) {
            boolean was = consumed;
            consumed = false;
            return was;
        }

        @Override
        public int getPriority() {
            return 50;
        }
    }

    private static final class LeftTriggerListener implements VRInputAction.KeyListener {
        private boolean consumed = false;

        @Override
        public boolean onPressed(ControllerType controller) {
            consumed = press();
            return consumed;
        }

        private boolean press() {
            if (HandsOnVR.call("gatilho esquerdo", () -> HandsOnVR.hands().onTrigger(mc(), 1))) {
                return true;
            }
            // Com o arco na mão direita, a esquerda é a da flecha: o gatilho dela encaixa e puxa a flecha (Vivecraft).
            if (!HandsOnConfig.get().leftTriggerOffhandUse
                    || HandsOnVR.call("gatilho esquerdo (arco)", () -> BowHand.isArrowHand(mc(), 1))) {
                return false;
            }
            HandsOnVR.guard("gatilho esquerdo", () -> OffhandUse.use(mc()));
            return true;
        }

        @Override
        public boolean onUnpressed(ControllerType controller) {
            boolean was = consumed;
            consumed = false;
            boolean dragged = HandsOnVR.call("gatilho esquerdo (soltar)", () -> HandsOnVR.hands().onTriggerReleased(mc(), 1));
            return was || dragged;
        }

        @Override
        public int getPriority() {
            return 50;
        }
    }
}
