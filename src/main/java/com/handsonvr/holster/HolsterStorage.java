package com.handsonvr.holster;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.handsonvr.HandsOnVR;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.level.storage.LevelResource;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * O que cada coldre guarda, por mundo/servidor: config/handsonvr/holsters/&lt;mundo&gt;.json. O item continua no
 * inventário do jogador (o coldre só lembra qual é e em que espaço do inventário ele fica) — assim funciona em
 * qualquer servidor, sem nada instalado nele.
 */
public final class HolsterStorage {
    private HolsterStorage() {}

    /** Item guardado: id do item, nome personalizado (null se não tiver) e espaço do inventário (0-35, 40). */
    public record Binding(String item, String name, int slot) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<Map<String, Binding>>() {}.getType();

    /** Nome do arquivo deste mundo (null fora de um mundo). */
    public static String worldKey(Minecraft client) {
        try {
            if (client.getSingleplayerServer() != null) {
                Path root = client.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
                return "sp_" + sanitize(root.getFileName().toString());
            }
            ServerData server = client.getCurrentServer();
            if (server != null) {
                return "mp_" + sanitize(server.ip);
            }
        } catch (Exception e) {
            HandsOnVR.LOGGER.debug("[HandsOn VR] mundo sem nome", e);
        }
        return null;
    }

    private static String sanitize(String s) {
        String clean = s.replaceAll("[^A-Za-z0-9._-]", "_");
        return clean.length() > 80 ? clean.substring(0, 80) : clean;
    }

    private static Path file(String key) {
        return FabricLoader.getInstance().getConfigDir().resolve("handsonvr").resolve("holsters").resolve(key + ".json");
    }

    public static EnumMap<Holster, Binding> load(String key) {
        EnumMap<Holster, Binding> result = new EnumMap<>(Holster.class);
        if (key == null) {
            return result;
        }
        Path f = file(key);
        try {
            if (Files.exists(f)) {
                try (Reader reader = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    Map<String, Binding> raw = GSON.fromJson(reader, TYPE);
                    if (raw != null) {
                        raw.forEach((name, binding) -> {
                            try {
                                if (binding != null && binding.item() != null) {
                                    result.put(Holster.valueOf(name), binding);
                                }
                            } catch (IllegalArgumentException ignored) {
                            }
                        });
                    }
                }
            }
        } catch (Exception e) {
            HandsOnVR.LOGGER.warn("[HandsOn VR] Não consegui ler {}", f, e);
        }
        return result;
    }

    public static void save(String key, EnumMap<Holster, Binding> bindings) {
        if (key == null) {
            return;
        }
        Path f = file(key);
        try {
            Files.createDirectories(f.getParent());
            Map<String, Binding> raw = new HashMap<>();
            bindings.forEach((h, b) -> raw.put(h.name(), b));
            try (Writer writer = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                GSON.toJson(raw, TYPE, writer);
            }
        } catch (Exception e) {
            HandsOnVR.LOGGER.warn("[HandsOn VR] Não consegui salvar {}", f, e);
        }
    }
}
