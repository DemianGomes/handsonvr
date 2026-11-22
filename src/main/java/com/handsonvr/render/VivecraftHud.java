package com.handsonvr.render;

import com.handsonvr.HandsOnVR;
import com.handsonvr.vr.VrBridge;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * O HUD do Vivecraft é o HUD do jogo desenhado num painel no pulso. Em VR, o HandsOn deixa nele só o que não tem outro
 * lugar: o chat, a lista de jogadores, os títulos, as mensagens da barra de ação, a barra do chefe, o placar do
 * servidor e o HUD de outros mods (o minimapa do Xaero's Minimap, quando ele está instalado). O resto sai, porque o
 * relógio do pulso e o radial do HandsOn já mostram: hotbar, vida, armadura, fome, bolhas, XP, efeitos, o nome do
 * item, a mira do jogo e as sobreposições de tela cheia.
 *
 * <p>Para isso o HUD precisa estar à vista e preso ao pulso esquerdo: quando o VR liga, o HandsOn mostra o HUD se ele
 * estava escondido (o F1, ou o "esconder HUD" do Vivecraft) e põe o HUD no pulso; quando o VR desliga, devolve como
 * estava. Só em memória: nenhum arquivo do Vivecraft ou do jogo é alterado. Sem VR, o HUD do jogo é o de sempre.
 */
public final class VivecraftHud {
    private VivecraftHud() {}

    private static final Identifier[] HIDDEN = {VanillaHudElements.HOTBAR, VanillaHudElements.ARMOR_BAR,
            VanillaHudElements.HEALTH_BAR, VanillaHudElements.FOOD_BAR, VanillaHudElements.AIR_BAR,
            VanillaHudElements.MOUNT_HEALTH, VanillaHudElements.INFO_BAR, VanillaHudElements.EXPERIENCE_LEVEL,
            VanillaHudElements.INFO_BAR_FOREGROUND, VanillaHudElements.HELD_ITEM_TOOLTIP, VanillaHudElements.MOB_EFFECTS,
            VanillaHudElements.CROSSHAIR, VanillaHudElements.MISC_OVERLAYS};

    private static boolean wasVr = false, weShowed = false;
    private static Object lockBefore = null;

    /** Uma vez, ao iniciar: cada parte tirada só se desenha fora do VR. */
    public static void register() {
        for (Identifier id : HIDDEN) {
            HudElementRegistry.replaceElement(id, original -> (graphics, delta) -> {
                if (!hidesBars()) {
                    original.extractRenderState(graphics, delta);
                }
            });
        }
    }

    /** Em VR, as partes que o HandsOn já mostra saem do HUD do Vivecraft. */
    public static boolean hidesBars() {
        return VrBridge.isVRActive();
    }

    /** A cada tick: ao ligar o VR, mostra o HUD e o prende ao pulso; ao desligar, devolve como estava. */
    public static void tick(Minecraft client) {
        boolean vr = VrBridge.isVRActive() && client.level != null && client.player != null;
        if (vr && !wasVr) {
            if (client.gui.hud.isHidden()) {
                client.gui.hud.toggle();
                weShowed = true;
            }
            lockBefore = setWristLock(true, null);
        }
        if (!vr && wasVr) {
            if (weShowed && !client.gui.hud.isHidden()) {
                client.gui.hud.toggle();
            }
            weShowed = false;
            setWristLock(false, lockBefore);
            lockBefore = null;
        }
        wasVr = vr;
    }

    /** Põe o HUD do Vivecraft no pulso (retorna o modo de antes) ou devolve o modo de antes. Em memória só. */
    private static Object setWristLock(boolean wrist, Object before) {
        if (!VrBridge.isVivecraftLoaded()) {
            return null;
        }
        try {
            var settings = org.vivecraft.client_vr.ClientDataHolderVR.getInstance().vrSettings;
            Object now = settings.vrHudLockMode;
            if (wrist) {
                settings.vrHudLockMode = org.vivecraft.client_vr.settings.VRSettings.HUDLock.WRIST;
            } else if (before instanceof org.vivecraft.client_vr.settings.VRSettings.HUDLock old) {
                settings.vrHudLockMode = old;
            }
            return now;
        } catch (Throwable t) {
            HandsOnVR.LOGGER.debug("[HandsOn VR] HUD do Vivecraft: não consegui mudar o lugar", t);
            return null;
        }
    }

    /** (A opção "Barras só no pulso" saiu na 0.8.6: o HUD é sempre assim em VR.) */
    public static void apply(Minecraft client) {
    }
}
