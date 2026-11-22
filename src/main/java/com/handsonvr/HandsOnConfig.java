package com.handsonvr;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuração (config/handsonvr.json). Tudo ligado por padrão; a tela de ajustes do mod muda e salva aqui.
 * Campos públicos simples para o Gson.
 */
public final class HandsOnConfig {
    // ---- coldres
    public boolean holsters = true;
    public boolean rightShoulder = true;
    public boolean leftShoulder = true;
    public boolean rightHip = true;
    public boolean leftHip = true;
    /** Desenha os itens guardados nos coldres (no corpo). */
    public boolean renderHolstered = true;
    /** Vibra ao chegar com a mão num coldre. */
    public boolean zoneHaptics = true;
    /**
     * Com coldres nos ombros, a "troca pelas costas" do Vivecraft (backpack switching: mão atrás da cabeça troca o
     * item sozinha) fica desligada — a zona dela é a mesma dos coldres dos ombros.
     */
    public boolean disableBackpackWithShoulders = true;
    /** Ajuste fino da altura dos coldres (m; + sobe). */
    public double shoulderHeightOffset = 0.0;
    public double hipHeightOffset = 0.0;

    // ---- mochila e menu radial
    /** Mochila no ombro esquerdo (inventário, crafting 2x2, armadura e descarte sem tela). */
    public boolean backpack = true;
    /** B (mão direita) e X (mão esquerda) abrem o menu radial da hotbar no lugar do radial do Vivecraft/inventário. */
    public boolean radialMenu = true;
    /** Fora dos coldres, o grip troca a hotbar como no Vivecraft (desligado: o radial cuida da hotbar). */
    public boolean gripScrollsHotbar = false;

    /**
     * Itens que chegam (coletados, sobras do crafting) vão primeiro para o inventário principal; a hotbar só recebe
     * quando ele está cheio. Desligado: como no jogo (a hotbar primeiro).
     */
    public boolean pickupToMain = true;

    /**
     * Mira do arco: com a corda puxada, a mira do jogo aparece onde a flecha vai acertar. Ligada por padrão (0.8.4).
     */
    public boolean bowAim = true;

    /**
     * Arremesso com a mão: com um item arremessável seguro pelo grip, soltar o grip com a mão rápida joga o item na
     * direção do movimento (bola de neve, ovo, pérola, poções, frasco de experiência, carga de vento).
     */
    public boolean physicalThrow = true;

    /**
     * Força real do arremesso, só no singleplayer (o servidor é o do próprio jogo): a força do gesto muda a velocidade
     * do item. Em servidor remoto a velocidade é sempre a do jogo.
     */
    public boolean throwRealPower = true;

    /** Virando o pulso esquerdo para o rosto: vida, armadura, fome, XP e efeitos, como um relógio. */
    public boolean wristHud = true;

    /** A quantidade da pilha de cada mão, perto do dedão (só para o próprio jogador). */
    public boolean handCount = true;

    /**
     * Em VR, esconde o HUD do Vivecraft (a hotbar, os corações e a fome no pulso, o mesmo "esconder HUD" do F1): o
     * relógio do pulso e o radial do HandsOn mostram isso. Não muda o arquivo do Vivecraft; sem VR o HUD volta.
     */
    public boolean hideVivecraftHud = true;

    // ---- controles
    /** Gatilho esquerdo usa o item da mão esquerda (coloca tocha/bloco, usa) em vez do teleporte. */
    public boolean leftTriggerOffhandUse = true;
    // O analógico direito é sempre do Vivecraft (girar a câmera). Até a 0.2.4 ele podia trocar a hotbar
    // ("rightStickHotbar"); uma configuração antiga com esse campo é lida sem erro e o campo é ignorado.

    /**
     * Versão da configuração, para mudar um padrão de quem já tem o arquivo: 0 = antes da 0.8.4. A 2 liga a mira do
     * arco e o "esconder o HUD do Vivecraft" uma vez (o usuário pediu esses padrões).
     */
    public int configVersion = 0;
    private static final int CURRENT_VERSION = 2;

    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("handsonvr.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static HandsOnConfig instance;

    public static HandsOnConfig get() {
        if (instance == null) {
            instance = load();
            if (instance.configVersion < CURRENT_VERSION) {
                instance.bowAim = true;
                instance.hideVivecraftHud = true;
                instance.configVersion = CURRENT_VERSION;
                instance.save();
            }
        }
        return instance;
    }

    private static HandsOnConfig load() {
        try {
            if (Files.exists(FILE)) {
                try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
                    HandsOnConfig config = GSON.fromJson(reader, HandsOnConfig.class);
                    if (config != null) {
                        return config;
                    }
                }
            }
        } catch (Exception e) {
            HandsOnVR.LOGGER.warn("[HandsOn VR] Configuração inválida em {}, usando o padrão", FILE, e);
        }
        HandsOnConfig config = new HandsOnConfig();
        config.save();
        return config;
    }

    public void save() {
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (Exception e) {
            HandsOnVR.LOGGER.warn("[HandsOn VR] Não consegui salvar {}", FILE, e);
        }
    }
}
