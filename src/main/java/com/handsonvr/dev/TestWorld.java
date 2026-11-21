package com.handsonvr.dev;

import com.handsonvr.HandsOnVR;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.AABB;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Ferramenta de desenvolvimento: monta um mundo de testes para jogar em VR ("HandsOn VR - Testes") e fecha o jogo.
 * Só liga com {@code -Dhandsonvr.testworld=true} ({@code gradlew prodClientWorld}); o mundo fica em
 * {@code run-prod/saves} para copiar para o perfil do jogador.
 *
 * <p>O que tem (o jogador nasce no centro, olhando para o sul):
 * <ul>
 *   <li>à frente, a oficina: baú para fabricar, mesa de trabalho, fornalha, alto-forno, defumador, baú para queimar,
 *       fogueira e suporte de poções;</li>
 *   <li>à esquerda, o arsenal: as ferramentas e a armadura de diamante em molduras, na altura da mão;</li>
 *   <li>à direita, o combate: arco, besta, escudo e tridente em molduras, um baú com flechas e arremessáveis, e dois
 *       botões: um zumbi perto (para a espada) e um longe, no fim de uma pista de tiro com um alvo.</li>
 * </ul>
 * Sempre de dia, sem chuva e sem monstros aparecendo sozinhos; os zumbis têm capacete (não queimam no sol). O
 * inventário fica ao morrer.
 */
public final class TestWorld {
    private TestWorld() {}

    private static final boolean ENABLED = Boolean.getBoolean("handsonvr.testworld");
    public static final String NAME = "HandsOn VR - Testes";

    private static int step = 0, ticks = 0;
    private static BlockPos origin;
    private static volatile boolean built = false;
    private static final List<String> report = new ArrayList<>();
    private static String nearCommand, farCommand;
    private static BlockPos nearButton, farButton;

    public static void register() {
        if (!ENABLED) {
            return;
        }
        HandsOnVR.LOGGER.info("[TESTWORLD] montando o mundo de testes");
        ClientTickEvents.END_CLIENT_TICK.register(TestWorld::tick);
    }

    private static void log(String line) {
        report.add(line);
        HandsOnVR.LOGGER.info("[TESTWORLD] {}", line);
    }

    private static void tick(Minecraft client) {
        ticks++;
        try {
            run(client);
        } catch (Throwable t) {
            log("ERRO no passo " + step + ": " + t);
            HandsOnVR.LOGGER.error("[TESTWORLD] erro", t);
            finish(client);
        }
    }

    private static void next() {
        step++;
        ticks = 0;
    }

    private static void run(Minecraft client) throws IOException {
        if (step >= 1 && client.gui.screen() != null && client.player != null) {
            client.gui.setScreen(null); // telas de boas-vindas de outros mods
        }
        switch (step) {
            case 0 -> {
                client.options.pauseOnLostFocus = false;
                if ((client.gui.screen() instanceof TitleScreen && client.gui.overlay() == null && ticks > 40) || ticks > 600) {
                    deleteWorld(client);
                    LevelSettings settings = new LevelSettings(NAME, GameType.SURVIVAL,
                            new LevelSettings.DifficultySettings(Difficulty.NORMAL, false, false), true, WorldDataConfiguration.DEFAULT);
                    client.createWorldOpenFlows().createFreshLevel(NAME, settings, new WorldOptions(20261121L, false, false),
                            registries -> registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value()
                                    .createWorldDimensions(),
                            client.gui.screen());
                    next();
                }
            }
            case 1 -> {
                if (client.level == null || client.player == null) {
                    ticks = 0;
                } else if (ticks == 60) {
                    origin = client.player.blockPosition();
                    server(client, TestWorld::build);
                } else if (ticks > 60 && built) {
                    next();
                }
            }
            case 2 -> {
                if (ticks == 20) {
                    server(client, (server, level, player) -> {
                        // Os dois botões de verdade: o zumbi aparece? Com o capacete?
                        BlockState near = level.getBlockState(nearButton), far = level.getBlockState(farButton);
                        ((ButtonBlock) near.getBlock()).press(near, level, nearButton, player);
                        ((ButtonBlock) far.getBlock()).press(far, level, farButton, player);
                    });
                }
                if (ticks == 40) {
                    server(client, (server, level, player) -> {
                        List<Zombie> zombies = level.getEntitiesOfClass(Zombie.class, new AABB(origin).inflate(60));
                        long helmets = zombies.stream().filter(z -> z.getItemBySlot(EquipmentSlot.HEAD).is(Items.LEATHER_HELMET)).count();
                        log((zombies.size() == 2 && helmets == 2 ? "OK" : "FALHA") + ": os botões chamaram " + zombies.size()
                                + " zumbi(s), " + helmets + " com capacete");
                        zombies.forEach(z -> z.discard());
                    });
                }
                if (ticks == 50) {
                    next();
                }
            }
            case 3 -> photos(client);
            case 4 -> {
                if (ticks == 1) {
                    server(client, (server, level, player) -> {
                        player.teleportTo(level, origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, Set.of(), 0f, 0f, false);
                        player.getInventory().clearContent();
                        player.setGameMode(GameType.SURVIVAL);
                        player.setHealth(player.getMaxHealth());
                    });
                }
                if (ticks == 20) {
                    server(client, (server, level, player) -> {
                        boolean saved = server.saveEverything(false, true, true);
                        log((saved ? "OK" : "FALHA") + ": mundo salvo em saves/" + NAME);
                    });
                }
                if (ticks == 40) {
                    finish(client);
                }
            }
            default -> { }
        }
    }

    private interface ServerAction {
        void run(MinecraftServer server, ServerLevel level, ServerPlayer player);
    }

    private static void server(Minecraft client, ServerAction action) {
        MinecraftServer server = client.getSingleplayerServer();
        java.util.UUID id = client.player.getUUID();
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) {
                try {
                    action.run(server, player.level(), player);
                } catch (Throwable t) {
                    log("ERRO no servidor: " + t);
                    HandsOnVR.LOGGER.error("[TESTWORLD] erro no servidor", t);
                }
            }
        });
    }

    // ------------------------------------------------------------------ a construção

    private static ServerLevel level;

    /** Bloco relativo à origem: x para o leste, y para cima (0 = onde se pisa), z para o sul (à frente). */
    private static BlockPos at(int x, int y, int z) {
        return origin.offset(x, y, z);
    }

    private static void set(int x, int y, int z, BlockState state) {
        level.setBlock(at(x, y, z), state, 3);
    }

    private static void fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                    set(x, y, z, state);
                }
            }
        }
    }

    private static void build(MinecraftServer server, ServerLevel lvl, ServerPlayer player) {
        level = lvl;
        GameRules rules = server.getGameRules();
        rules.set(GameRules.ADVANCE_TIME, false, server);
        rules.set(GameRules.ADVANCE_WEATHER, false, server);
        rules.set(GameRules.SPAWN_MOBS, false, server);
        rules.set(GameRules.SPAWN_MONSTERS, false, server);
        rules.set(GameRules.SPAWN_PHANTOMS, false, server);
        rules.set(GameRules.SPAWN_PATROLS, false, server);
        rules.set(GameRules.SPAWN_WANDERING_TRADERS, false, server);
        rules.set(GameRules.KEEP_INVENTORY, true, server);
        rules.set(GameRules.COMMAND_BLOCK_OUTPUT, false, server);
        rules.set(GameRules.RESPAWN_RADIUS, 0, server);
        command(server, "time set noon");
        command(server, "weather clear");
        command(server, "setworldspawn " + origin.getX() + " " + origin.getY() + " " + origin.getZ());

        // --- o chão da praça e a pista de tiro
        fill(-7, -1, -5, 9, -1, 8, Blocks.POLISHED_ANDESITE.defaultBlockState());
        fill(-7, 0, -5, 9, 5, 8, Blocks.AIR.defaultBlockState());
        fill(-42, -1, 1, -8, -1, 3, Blocks.DIRT_PATH.defaultBlockState());
        fill(-41, 0, 0, -8, 0, 0, Blocks.STONE_BRICKS.defaultBlockState());
        fill(-41, 0, 4, -8, 0, 4, Blocks.STONE_BRICKS.defaultBlockState());
        fill(-43, 0, -1, -43, 4, 5, Blocks.STONE_BRICKS.defaultBlockState());
        set(-43, 1, 2, Blocks.TARGET.defaultBlockState());
        set(-43, 2, 2, Blocks.TARGET.defaultBlockState());
        fill(-42, 0, 1, -42, 1, 1, Blocks.HAY_BLOCK.defaultBlockState());
        fill(-42, 0, 3, -42, 1, 3, Blocks.HAY_BLOCK.defaultBlockState());

        buildWorkshop();
        buildArmory();
        buildCombat(server);
        built = true;
        log("OK: mundo montado (origem " + origin.toShortString() + ")");
    }

    private static void command(MinecraftServer server, String cmd) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd);
    }

    /** À frente (sul): a parede de madeira com as placas e os blocos de trabalho virados para o jogador. */
    private static void buildWorkshop() {
        fill(-6, 0, 7, 6, 3, 7, Blocks.SPRUCE_PLANKS.defaultBlockState());
        fill(-6, 3, 7, 6, 3, 7, Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState());
        Direction toPlayer = Direction.NORTH;
        chest(-4, 0, 6, toPlayer, craftingChest());
        set(-3, 0, 6, Blocks.CRAFTING_TABLE.defaultBlockState());
        set(-2, 0, 6, Blocks.FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, toPlayer));
        set(-1, 0, 6, Blocks.BLAST_FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, toPlayer));
        set(0, 0, 6, Blocks.SMOKER.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, toPlayer));
        chest(1, 0, 6, toPlayer, smeltingChest());
        set(3, 0, 6, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.FACING, toPlayer));
        set(4, 0, 6, Blocks.BREWING_STAND.defaultBlockState());
        chest(5, 0, 6, toPlayer, brewingChest());
        sign(-4, 1, 6, toPlayer, "Para", "fabricar");
        sign(-3, 1, 6, toPlayer, "Mesa de", "trabalho");
        sign(-2, 1, 6, toPlayer, "Fornalha");
        sign(-1, 1, 6, toPlayer, "Alto-forno");
        sign(0, 1, 6, toPlayer, "Defumador");
        sign(1, 1, 6, toPlayer, "Para", "queimar");
        sign(3, 1, 6, toPlayer, "Fogueira");
        sign(4, 1, 6, toPlayer, "Poções");
        sign(5, 1, 6, toPlayer, "Para", "poções");
        sign(0, 2, 6, toPlayer, "OFICINA");
        // Lanternas nos cantos
        set(-6, 4, 7, Blocks.LANTERN.defaultBlockState());
        set(6, 4, 7, Blocks.LANTERN.defaultBlockState());
    }

    /** À esquerda (leste): a parede de pedra com as ferramentas e a armadura de diamante em molduras, na altura da mão. */
    private static void buildArmory() {
        fill(8, 0, -4, 8, 3, 6, Blocks.STONE_BRICKS.defaultBlockState());
        fill(8, 3, -4, 8, 3, 6, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
        Item[] gear = {Items.DIAMOND_SWORD, Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE, Items.DIAMOND_SHOVEL, Items.DIAMOND_HOE,
                Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS};
        for (int i = 0; i < gear.length; i++) {
            frame(7, 1, -3 + i, Direction.WEST, new ItemStack(gear[i]));
        }
        sign(7, 2, 1, Direction.WEST, "ARSENAL", "de diamante");
        sign(7, 0, -3, Direction.WEST, "Bata na", "moldura", "para pegar");
    }

    /** À direita (oeste): molduras de combate, o baú de flechas e arremessáveis e os botões dos zumbis. */
    private static void buildCombat(MinecraftServer server) {
        fill(-6, 0, -4, -2, 2, -4, Blocks.STONE_BRICKS.defaultBlockState());
        frame(-6, 1, -3, Direction.SOUTH, new ItemStack(Items.BOW));
        frame(-5, 1, -3, Direction.SOUTH, new ItemStack(Items.CROSSBOW));
        frame(-4, 1, -3, Direction.SOUTH, new ItemStack(Items.SHIELD));
        frame(-3, 1, -3, Direction.SOUTH, new ItemStack(Items.TRIDENT));
        sign(-5, 2, -3, Direction.SOUTH, "COMBATE");
        chest(-2, 0, -3, Direction.SOUTH, combatChest());
        sign(-2, 1, -3, Direction.SOUTH, "Flechas e", "arremessáveis");

        // Botões: um pilar de pedra (2 blocos) com o botão na frente; o bloco de comando fica embaixo do chão.
        int nearZ = 0, farZ = 4;
        double nearX = origin.getX() - 12 + 0.5, farX = origin.getX() - 40 + 0.5, laneZ = origin.getZ() + 2 + 0.5;
        String zombie = " {equipment:{head:{id:\"minecraft:leather_helmet\",count:1}},drop_chances:{head:0.0f},"
                + "PersistenceRequired:1b}";
        nearCommand = "summon minecraft:zombie " + nearX + " " + origin.getY() + " " + laneZ + zombie;
        farCommand = "summon minecraft:zombie " + farX + " " + origin.getY() + " " + laneZ + zombie;
        nearButton = button(-7, nearZ, nearCommand, "Zumbi", "PERTO", "(espada)");
        farButton = button(-7, farZ, farCommand, "Zumbi", "LONGE", "(arco)");
        // Um arco de pedra sobre a entrada da pista, com a placa e uma lanterna.
        fill(-7, 2, 0, -7, 2, 4, Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
        set(-7, 3, 2, Blocks.LANTERN.defaultBlockState());
        sign(-6, 2, 2, Direction.EAST, "PISTA", "DE TIRO", "alvo a 36", "blocos");
    }

    /** Pilar com o botão virado para o leste; o bloco de comando embaixo dele. Retorna onde está o botão. */
    private static BlockPos button(int x, int z, String command, String... label) {
        fill(x, 0, z, x, 1, z, Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
        set(x, -1, z, Blocks.COMMAND_BLOCK.defaultBlockState());
        if (level.getBlockEntity(at(x, -1, z)) instanceof CommandBlockEntity cb) {
            cb.getCommandBlock().setCommand(command);
            cb.getCommandBlock().setTrackOutput(false);
            cb.setChanged();
        }
        set(x + 1, 0, z, Blocks.POLISHED_BLACKSTONE_BUTTON.defaultBlockState()
                .setValue(ButtonBlock.FACE, AttachFace.WALL).setValue(ButtonBlock.FACING, Direction.EAST));
        sign(x + 1, 1, z, Direction.EAST, label);
        return at(x + 1, 0, z);
    }

    private static void frame(int x, int y, int z, Direction facing, ItemStack item) {
        ItemFrame frame = new ItemFrame(level, at(x, y, z), facing);
        frame.setItem(item);
        level.addFreshEntity(frame);
    }

    private static void sign(int x, int y, int z, Direction facing, String... lines) {
        BlockState state = Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, facing);
        set(x, y, z, state);
        if (level.getBlockEntity(at(x, y, z)) instanceof SignBlockEntity sign) {
            List<Component> messages = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                messages.add(Component.literal(i < lines.length ? lines[i] : ""));
            }
            sign.setText(new SignText(messages, messages, DyeColor.BLACK, false), SignTextSlot.FRONT);
            sign.setChanged();
            level.sendBlockUpdated(at(x, y, z), state, state, 3);
        }
    }

    private static void chest(int x, int y, int z, Direction facing, List<ItemStack> items) {
        set(x, y, z, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing));
        if (level.getBlockEntity(at(x, y, z)) instanceof ChestBlockEntity chest) {
            for (int i = 0; i < items.size() && i < chest.getContainerSize(); i++) {
                chest.setItem(i, items.get(i));
            }
            chest.setChanged();
        }
    }

    private static ItemStack of(Item item, int count) {
        return new ItemStack(item, count);
    }

    private static List<ItemStack> craftingChest() {
        return List.of(of(Items.OAK_LOG, 64), of(Items.OAK_LOG, 64), of(Items.BIRCH_LOG, 64), of(Items.SPRUCE_LOG, 64),
                of(Items.OAK_PLANKS, 64), of(Items.STICK, 64), of(Items.COBBLESTONE, 64), of(Items.COBBLESTONE, 64),
                of(Items.STONE, 64), of(Items.IRON_INGOT, 64), of(Items.GOLD_INGOT, 32), of(Items.DIAMOND, 32),
                of(Items.COPPER_INGOT, 32), of(Items.REDSTONE, 64), of(Items.COAL, 64), of(Items.STRING, 32),
                of(Items.FEATHER, 32), of(Items.FLINT, 32), of(Items.LEATHER, 32), of(Items.WOOL.white(), 32),
                of(Items.SUGAR_CANE, 32), of(Items.WHEAT, 32), of(Items.GLASS, 32), of(Items.SAND, 64),
                of(Items.OBSIDIAN, 16), of(Items.BOOK, 8), of(Items.SLIME_BALL, 16));
    }

    private static List<ItemStack> smeltingChest() {
        return List.of(of(Items.RAW_IRON, 64), of(Items.RAW_GOLD, 64), of(Items.RAW_COPPER, 64), of(Items.IRON_ORE, 32),
                of(Items.GOLD_ORE, 16), of(Items.SAND, 64), of(Items.COBBLESTONE, 64), of(Items.CLAY_BALL, 64),
                of(Items.OAK_LOG, 64), of(Items.CACTUS, 16), of(Items.KELP, 32), of(Items.POTATO, 32), of(Items.BEEF, 32),
                of(Items.PORKCHOP, 32), of(Items.CHICKEN, 32), of(Items.MUTTON, 32), of(Items.COD, 16), of(Items.SALMON, 16),
                of(Items.COAL, 64), of(Items.COAL, 64), of(Items.CHARCOAL, 32), of(Items.COAL_BLOCK, 16),
                of(Items.BLAZE_ROD, 8), of(Items.LAVA_BUCKET, 1), of(Items.DRIED_KELP_BLOCK, 8), of(Items.WET_SPONGE, 4),
                of(Items.NETHERRACK, 32));
    }

    private static List<ItemStack> combatChest() {
        List<ItemStack> items = new ArrayList<>(List.of(of(Items.ARROW, 64), of(Items.ARROW, 64), of(Items.SPECTRAL_ARROW, 32),
                of(Items.BOW, 1), of(Items.IRON_SWORD, 1), of(Items.SHIELD, 1), of(Items.ENDER_PEARL, 16),
                of(Items.SNOWBALL, 16), of(Items.SNOWBALL, 16), of(Items.EGG, 16), of(Items.WIND_CHARGE, 32),
                of(Items.FIRE_CHARGE, 16), of(Items.TORCH, 64), of(Items.COOKED_BEEF, 32), of(Items.BREAD, 32),
                of(Items.GOLDEN_APPLE, 8), of(Items.FISHING_ROD, 1), of(Items.FLINT_AND_STEEL, 1)));
        items.add(PotionContents.createItemStack(Items.SPLASH_POTION, Potions.HEALING).copyWithCount(1));
        items.add(PotionContents.createItemStack(Items.SPLASH_POTION, Potions.HEALING).copyWithCount(1));
        items.add(PotionContents.createItemStack(Items.SPLASH_POTION, Potions.SWIFTNESS).copyWithCount(1));
        items.add(PotionContents.createItemStack(Items.LINGERING_POTION, Potions.REGENERATION).copyWithCount(1));
        items.add(PotionContents.createItemStack(Items.POTION, Potions.STRENGTH).copyWithCount(1));
        return items;
    }

    private static List<ItemStack> brewingChest() {
        List<ItemStack> items = new ArrayList<>(List.of(of(Items.BLAZE_POWDER, 32), of(Items.NETHER_WART, 32),
                of(Items.GLASS_BOTTLE, 16), of(Items.REDSTONE, 32), of(Items.GLOWSTONE_DUST, 32), of(Items.GUNPOWDER, 32),
                of(Items.SUGAR, 32), of(Items.GLISTERING_MELON_SLICE, 16), of(Items.SPIDER_EYE, 16),
                of(Items.FERMENTED_SPIDER_EYE, 16), of(Items.MAGMA_CREAM, 16), of(Items.GHAST_TEAR, 8),
                of(Items.RABBIT_FOOT, 8), of(Items.GOLDEN_CARROT, 16), of(Items.PHANTOM_MEMBRANE, 8), of(Items.WATER_BUCKET, 1)));
        for (int i = 0; i < 3; i++) {
            items.add(PotionContents.createItemStack(Items.POTION, Potions.WATER).copyWithCount(1));
        }
        return items;
    }

    // ------------------------------------------------------------------ fotos e fim

    /** Três vistas, sem o HUD: a oficina, o arsenal e a pista de tiro (para conferir antes de entregar). */
    private static void photos(Minecraft client) {
        var p = client.player;
        double[][] views = {
                {0.5, -2.5, 0f, 12f}, // oficina, de frente
                {1.5, 1.5, -90f, 8f}, // arsenal (leste)
                {-3.5, 2.5, 90f, 4f}, // botões e pista (oeste)
                {-1.5, 1.5, 135f, 10f}, // combate (noroeste)
        };
        String[] names = {"mundo-oficina", "mundo-arsenal", "mundo-pista", "mundo-combate"};
        if (ticks == 1 && !client.gui.hud.isHidden()) {
            client.gui.hud.toggle();
        }
        for (int i = 0; i < views.length; i++) {
            if (ticks == 2 + i * 12) {
                p.setPos(origin.getX() + views[i][0], origin.getY(), origin.getZ() + views[i][1]);
                p.setYRot((float) views[i][2]);
                p.setXRot((float) views[i][3]);
            }
            if (ticks == 12 + i * 12) {
                Screenshot.grab(client.gameDirectory, names[i] + ".png", client.gameRenderer.mainRenderTarget(), 1, text -> { });
                log("foto " + names[i]);
            }
        }
        if (ticks == 14 + views.length * 12) {
            client.gui.hud.toggle();
            next();
        }
    }

    private static void finish(Minecraft client) {
        step = 99;
        try {
            Files.write(client.gameDirectory.toPath().resolve("handsonvr-testworld.txt"), report, StandardCharsets.UTF_8);
        } catch (IOException e) {
            HandsOnVR.LOGGER.error("[TESTWORLD] relatório", e);
        }
        Thread exit = new Thread(() -> {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException ignored) {
                return;
            }
            Runtime.getRuntime().halt(0);
        }, "handsonvr-testworld-exit");
        exit.setDaemon(true);
        exit.start();
        client.stop();
    }

    private static void deleteWorld(Minecraft client) throws IOException {
        Path dir = client.gameDirectory.toPath().resolve("saves").resolve(NAME);
        if (Files.exists(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }
}
