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

    // ---- controles
    /** Gatilho esquerdo usa o item da mão esquerda (coloca tocha/bloco, usa) em vez do teleporte. */
    public boolean leftTriggerOffhandUse = true;
    /** Analógico direito para os lados troca o item da hotbar (em vez de girar a câmera em saltos). */
    public boolean rightStickHotbar = true;

    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("handsonvr.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static HandsOnConfig instance;

    public static HandsOnConfig get() {
        if (instance == null) {
            instance = load();
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
