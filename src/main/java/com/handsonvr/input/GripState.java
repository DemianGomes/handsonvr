package com.handsonvr.input;

import com.handsonvr.vr.SimulatedVr;
import com.handsonvr.vr.VrBridge;

/** Os botões "seguráveis" de cada mão estão apertados agora? (0 = principal, 1 = secundária.) */
public final class GripState {
    private GripState() {}

    public static boolean isHeld(int hand) {
        if (SimulatedVr.isEnabled()) {
            return SimulatedVr.gripHeld(hand);
        }
        return VrBridge.isVivecraftLoaded() && VivecraftInput.isGripHeld(hand);
    }

    /** Botão do menu radial: B na mão principal, X na secundária. */
    public static boolean isMenuButtonHeld(int hand) {
        if (SimulatedVr.isEnabled()) {
            return SimulatedVr.menuButtonHeld(hand);
        }
        return VrBridge.isVivecraftLoaded() && VivecraftInput.isMenuButtonHeld(hand);
    }
}
