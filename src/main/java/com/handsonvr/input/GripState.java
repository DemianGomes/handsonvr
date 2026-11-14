package com.handsonvr.input;

import com.handsonvr.vr.SimulatedVr;
import com.handsonvr.vr.VrBridge;

/** O grip de cada mão está apertado agora? (0 = principal, 1 = secundária.) */
public final class GripState {
    private GripState() {}

    public static boolean isHeld(int hand) {
        if (SimulatedVr.isEnabled()) {
            return SimulatedVr.gripHeld(hand);
        }
        return VrBridge.isVivecraftLoaded() && VivecraftInput.isGripHeld(hand);
    }
}
