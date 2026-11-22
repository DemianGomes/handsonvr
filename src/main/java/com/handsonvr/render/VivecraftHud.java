package com.handsonvr.render;

import com.handsonvr.HandsOnConfig;
import com.handsonvr.vr.VrBridge;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * O HUD do Vivecraft (no pulso) é o HUD do jogo desenhado num painel. Em VR, com
 * {@link HandsOnConfig#hideVivecraftHud}, as partes que o HandsOn já mostra (o relógio do pulso e o radial) somem dele:
 * hotbar, vida, armadura, fome, bolhas, XP, efeitos e o nome do item. O resto continua: o minimapa do Xaero e o
 * HUD de outros mods, o chat, os títulos. Sem VR, o HUD do jogo é o de sempre. Não grava nada no arquivo do Vivecraft.
 */
public final class VivecraftHud {
    private VivecraftHud() {}

    private static final Identifier[] HIDDEN = {VanillaHudElements.HOTBAR, VanillaHudElements.ARMOR_BAR,
            VanillaHudElements.HEALTH_BAR, VanillaHudElements.FOOD_BAR, VanillaHudElements.AIR_BAR,
            VanillaHudElements.MOUNT_HEALTH, VanillaHudElements.INFO_BAR, VanillaHudElements.EXPERIENCE_LEVEL,
            VanillaHudElements.HELD_ITEM_TOOLTIP, VanillaHudElements.MOB_EFFECTS};

    /** Uma vez, ao iniciar: cada parte escondida só se desenha quando não está em VR (ou a opção está desligada). */
    public static void register() {
        for (Identifier id : HIDDEN) {
            HudElementRegistry.replaceElement(id, original -> (graphics, delta) -> {
                if (!hidesBars()) {
                    original.extractRenderState(graphics, delta);
                }
            });
        }
    }

    /** Em VR, com a opção ligada: as barras do jogo saem do HUD do Vivecraft. */
    public static boolean hidesBars() {
        return VrBridge.isVRActive() && HandsOnConfig.get().hideVivecraftHud;
    }

    /** (Até a 0.8.4 a opção escondia o HUD inteiro, como o F1. Hoje não há nada para aplicar na hora.) */
    public static void apply(Minecraft client) {
    }
}
