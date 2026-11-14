package com.handsonvr.input;

import com.handsonvr.HandsOnConfig;
import com.handsonvr.HandsOnVR;
import net.minecraft.client.MinecraftClient;
import org.joml.Vector2fc;
import org.vivecraft.client.VivecraftVRMod;
import org.vivecraft.client_vr.provider.ControllerType;
import org.vivecraft.client_vr.provider.MCVR;
import org.vivecraft.client_vr.provider.openvr_lwjgl.VRInputAction;

/**
 * Os botões do controle chegam ao Minecraft só pelas "ações" do SteamVR que o Vivecraft cria (não há leitura direta
 * do botão). Aqui escutamos as ações padrão do Quest 2 (API interna do Vivecraft, com proteção contra erro):
 * <ul>
 *   <li>grip direito/esquerdo = "próximo/anterior da hotbar": numa zona de coldre, o grip saca/guarda e a hotbar não
 *       muda;</li>
 *   <li>gatilho esquerdo = "teleporte": usa o item da mão esquerda;</li>
 *   <li>analógico direito para os lados = "girar" (em saltos): troca o item da hotbar.</li>
 * </ul>
 * Um "ouvinte" que responde true consome o aperto: a ação original do Vivecraft não acontece. As ações são recriadas
 * quando o VR reinicia — por isso o registro é conferido a cada tick.
 */
public final class VivecraftInput {
    private VivecraftInput() {}

    private static VRInputAction gripMain, gripOff, teleport, rotate;
    private static boolean warned = false;

    public static void tick(MinecraftClient client) {
        try {
            MCVR mcvr = MCVR.get();
            if (mcvr == null) {
                gripMain = gripOff = teleport = rotate = null;
                return;
            }
            VivecraftVRMod mod = VivecraftVRMod.INSTANCE;
            gripMain = ensure(mcvr.getInputAction(mod.keyHotbarNext), gripMain, new GripListener(0));
            gripOff = ensure(mcvr.getInputAction(mod.keyHotbarPrev), gripOff, new GripListener(1));
            teleport = ensure(mcvr.getInputAction(mod.keyTeleport), teleport, new LeftTriggerListener());
            rotate = ensure(mcvr.getInputAction(mod.keyRotateAxis), rotate, new RightStickListener());
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

    /** Teste automático (VR nulo do Vivecraft): quantas das 4 ações estão sendo escutadas. */
    public static int debugListening() {
        return (gripMain != null ? 1 : 0) + (gripOff != null ? 1 : 0) + (teleport != null ? 1 : 0) + (rotate != null ? 1 : 0);
    }

    /**
     * Teste automático: o Vivecraft avisando os ouvintes de um aperto/soltura, como faz com o controle de verdade.
     * {@code which}: gripMain, gripOff, teleport ou rotate; {@code axisX}: posição do analógico (só para rotate).
     * Retorna true se algum ouvinte consumiu (a ação original do Vivecraft não aconteceria).
     */
    public static boolean debugNotify(String which, boolean pressed, float axisX) {
        VRInputAction action = switch (which) {
            case "gripMain" -> gripMain;
            case "gripOff" -> gripOff;
            case "teleport" -> teleport;
            default -> rotate;
        };
        if (action == null) {
            return false;
        }
        if (axisX != 0f && action.analogData.length > 0) {
            action.analogData[0].x = axisX;
        }
        ControllerType hand = which.equals("gripOff") || which.equals("teleport") ? ControllerType.LEFT : ControllerType.RIGHT;
        return action.notifyListeners(pressed, hand);
    }

    /** O grip da mão está apertado (dado cru da ação, lido pelo Vivecraft a cada quadro mesmo quando desativada). */
    public static boolean isGripHeld(int hand) {
        VRInputAction action = hand == 0 ? gripMain : gripOff;
        try {
            return action != null && action.digitalData.length > 0 && action.digitalData[0].state;
        } catch (Throwable t) {
            return false;
        }
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

    private static final class LeftTriggerListener implements VRInputAction.KeyListener {
        @Override
        public boolean onPressed(ControllerType controller) {
            if (!HandsOnConfig.get().leftTriggerOffhandUse) {
                return false;
            }
            HandsOnVR.guard("gatilho esquerdo", () -> OffhandUse.use(MinecraftClient.getInstance()));
            return true;
        }

        @Override
        public boolean onUnpressed(ControllerType controller) {
            return HandsOnConfig.get().leftTriggerOffhandUse;
        }

        @Override
        public int getPriority() {
            return 50;
        }
    }

    private static final class RightStickListener implements VRInputAction.KeyListener {
        @Override
        public boolean onPressed(ControllerType controller) {
            if (!HandsOnConfig.get().rightStickHotbar) {
                return false;
            }
            VRInputAction action = rotate;
            float x = 0f;
            if (action != null) {
                Vector2fc axis = action.getAxis2D(false);
                x = axis == null ? 0f : axis.x();
            }
            if (Math.abs(x) >= 0.3f) {
                int direction = x > 0 ? 1 : -1;
                HandsOnVR.guard("analógico direito", () -> HotbarScroll.scroll(MinecraftClient.getInstance(), direction));
            }
            return true; // sem girar a câmera
        }

        @Override
        public boolean onUnpressed(ControllerType controller) {
            return HandsOnConfig.get().rightStickHotbar;
        }

        @Override
        public int getPriority() {
            return 50;
        }
    }
}
