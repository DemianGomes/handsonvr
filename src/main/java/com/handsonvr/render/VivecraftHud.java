package com.handsonvr.render;

import com.handsonvr.HandsOnConfig;
import com.handsonvr.vr.VrBridge;
import net.minecraft.client.Minecraft;

/**
 * O HUD do Vivecraft (a hotbar, os corações e a fome presos no pulso) é o HUD do jogo desenhado num painel. Com o
 * HandsOn, o relógio do pulso e o radial já mostram isso, então em VR ele fica escondido por padrão
 * ({@link HandsOnConfig#hideVivecraftHud}): é o mesmo "esconder HUD" do F1 (e da opção "hideGUI" do Vivecraft).
 *
 * <p>Só age quando o VR liga (ou ao entrar num mundo em VR) e desfaz quando o VR desliga, se foi ele que escondeu.
 * Não grava nada no arquivo do Vivecraft, e quem mostrar o HUD de novo pelo jogo (F1) não é contrariado.
 */
public final class VivecraftHud {
    private VivecraftHud() {}

    private static boolean wasVr = false, weHid = false;

    public static void tick(Minecraft client) {
        boolean vr = VrBridge.isVRActive() && client.level != null && client.player != null;
        if (vr && !wasVr && HandsOnConfig.get().hideVivecraftHud && !client.gui.hud.isHidden()) {
            client.gui.hud.toggle();
            weHid = true;
        }
        if (!vr && wasVr && weHid) {
            if (client.gui.hud.isHidden()) {
                client.gui.hud.toggle(); // sem VR, o jogo volta exatamente como era
            }
            weHid = false;
        }
        wasVr = vr;
    }

    /** A opção mudou na tela de ajustes: aplica já (em VR). */
    public static void apply(Minecraft client) {
        if (!VrBridge.isVRActive() || client.level == null) {
            return;
        }
        boolean hide = HandsOnConfig.get().hideVivecraftHud;
        if (hide != client.gui.hud.isHidden()) {
            client.gui.hud.toggle();
        }
        weHid = hide;
    }
}
