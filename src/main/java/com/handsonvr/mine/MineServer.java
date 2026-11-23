package com.handsonvr.mine;

import com.handsonvr.HandsOnVR;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A mineração em grupo do HandsOn (uma cópia temporária do FTB Ultimine, até ele sair para o 26.3), do lado do servidor.
 *
 * <p>O cliente manda se ela está ligada e a forma ({@link Mode}). Quando o jogador quebra um bloco com ela ligada, o
 * servidor quebra também os outros blocos da forma ({@link MineShapes}), um a um, pela quebra normal do jogo
 * ({@code ServerPlayerGameMode.destroyBlock}): os itens caem como sempre, a ferramenta gasta, a fome gasta, e o que a
 * ferramenta não colhe não dá item (como quebrar à mão). Para antes de a ferramenta quebrar e quando a fome acaba.
 *
 * <p>Só existe onde o servidor roda o HandsOn: o singleplayer e o mundo aberto pelo jogo (LAN, Essential).
 */
public final class MineServer {
    private MineServer() {}

    /** Do cliente para o servidor: ligada e a forma. */
    public record Mode(boolean active, int shape) implements CustomPacketPayload {
        public static final Type<Mode> TYPE = new Type<>(Identifier.fromNamespaceAndPath(HandsOnVR.MOD_ID, "mine"));
        static final StreamCodec<RegistryFriendlyByteBuf, Mode> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, Mode::active, ByteBufCodecs.VAR_INT, Mode::shape, Mode::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Do cliente para o servidor: o bloco que ele começou a quebrar e a face batida (as formas seguem a face). */
    public record Aim(BlockPos pos, int face) implements CustomPacketPayload {
        public static final Type<Aim> TYPE = new Type<>(Identifier.fromNamespaceAndPath(HandsOnVR.MOD_ID, "mine_aim"));
        static final StreamCodec<RegistryFriendlyByteBuf, Aim> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Aim::pos, ByteBufCodecs.VAR_INT, Aim::face, Aim::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static final Map<UUID, Mode> MODES = new ConcurrentHashMap<>();
    private static final Map<UUID, Aim> AIMS = new ConcurrentHashMap<>();
    /** Quem está no meio de uma quebra em grupo (a quebra dos outros blocos não puxa outra). */
    private static final Set<UUID> BUSY = ConcurrentHashMap.newKeySet();
    /** Teste automático: quantos blocos a última quebra em grupo levou (além do primeiro). */
    public static volatile int lastExtra = -1;

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(Mode.TYPE, Mode.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Aim.TYPE, Aim.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Mode.TYPE, (payload, context) -> MODES.put(context.player().getUUID(), payload));
        ServerPlayNetworking.registerGlobalReceiver(Aim.TYPE, (payload, context) -> AIMS.put(context.player().getUUID(), payload));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            MODES.remove(handler.player.getUUID());
            AIMS.remove(handler.player.getUUID());
        });
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (player instanceof ServerPlayer sp) {
                afterBreak(sp, pos, state);
            }
        });
    }

    private static void afterBreak(ServerPlayer player, BlockPos pos, BlockState state) {
        Mode mode = MODES.get(player.getUUID());
        if (mode == null || !mode.active() || !BUSY.add(player.getUUID())) {
            return;
        }
        try {
            // A face batida (mandada pelo cliente ao começar a quebrar); sem ela, a face virada para o jogador.
            Aim aim = AIMS.get(player.getUUID());
            Direction face = aim != null && aim.pos().equals(pos) ? Direction.from3DDataValue(aim.face())
                    : Direction.getApproximateNearest(player.getEyePosition().subtract(net.minecraft.world.phys.Vec3.atCenterOf(pos)));
            List<BlockPos> blocks = MineShapes.blocks(player.level(), pos, state, MineShape.of(mode.shape()), face,
                    player.getDirection(), MineShapes.LIMIT);
            int extra = 0;
            for (BlockPos p : blocks.subList(1, blocks.size())) {
                if (!player.isCreative()) {
                    ItemStack tool = player.getMainHandItem();
                    if (tool.isDamageableItem() && tool.getMaxDamage() - tool.getDamageValue() <= 1) {
                        break; // não deixa a ferramenta quebrar
                    }
                    if (player.getFoodData().getFoodLevel() <= 0) {
                        break;
                    }
                }
                BlockState s = player.level().getBlockState(p);
                if (!MineShapes.breakable(player.level(), p, s)) {
                    continue;
                }
                if (player.gameMode.destroyBlock(p)) {
                    extra++;
                }
            }
            lastExtra = extra;
        } finally {
            BUSY.remove(player.getUUID());
        }
    }

    // ------------------------------------------------------------------ cliente

    private static Mode sent = null;

    /** O servidor tem o HandsOn (dá para minerar em grupo)? */
    public static boolean serverSupports() {
        try {
            return ClientPlayNetworking.canSend(Mode.TYPE);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Manda ao servidor se mudou. */
    public static void send(boolean active, int shape) {
        Mode now = new Mode(active, shape);
        if (now.equals(sent) || !serverSupports()) {
            return;
        }
        ClientPlayNetworking.send(now);
        sent = now;
    }

    private static Aim aimSent = null;

    /** O bloco que o jogador começou a quebrar e a face (só com a mineração em grupo ligada; manda se mudou). */
    public static void sendAim(BlockPos pos, Direction face) {
        Aim now = new Aim(pos.immutable(), face.get3DDataValue());
        if (now.equals(aimSent) || !serverSupports()) {
            return;
        }
        ClientPlayNetworking.send(now);
        aimSent = now;
    }

    /** Ao sair do mundo: o próximo servidor recebe o estado de novo. */
    public static void resetClient() {
        sent = null;
        aimSent = null;
    }
}
