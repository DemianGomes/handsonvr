package com.handsonvr.net;

import com.handsonvr.HandsOnVR;
import com.handsonvr.hands.HandsController;
import com.handsonvr.holster.Holster;
import com.handsonvr.vr.VrBridge;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * O que os outros jogadores veem do HandsOn: os itens nos coldres e a mochila aberta (uma bolsa, sem os itens).
 *
 * <p>O mod é só do cliente, então isso só existe onde o servidor também roda o HandsOn: o mundo aberto pelo próprio
 * jogo (singleplayer aberto para LAN, ou o Essential, que hospeda o mundo no jogo de quem convida). Lá, cada jogador
 * com o HandsOn manda o seu estado ({@link State}) a cada meio segundo, se mudou; o servidor guarda e repassa para os
 * outros jogadores que têm o HandsOn. Quem não tem o mod não recebe nada e não vê nada de diferente. Num servidor sem o
 * mod, nada é mandado.
 *
 * <p>O estado é só o que um jogador de computador também veria se olhasse o inventário do outro: nenhum item novo,
 * nenhuma ação.
 */
public final class PeerSync {
    private PeerSync() {}

    /** Coldres na ordem de {@link Holster}; {@code backpackHand}: a mão que segura a mochila aberta (-1 = fechada). */
    public record State(boolean vr, List<ItemStack> holsters, int backpackHand) {
        public static final State EMPTY = new State(false, List.of(), -1);
        static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, State::vr,
                ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list(8)), State::holsters,
                ByteBufCodecs.VAR_INT, State::backpackHand,
                State::new);

        boolean sameAs(State o) {
            if (o == null || vr != o.vr || backpackHand != o.backpackHand || holsters.size() != o.holsters.size()) {
                return false;
            }
            for (int i = 0; i < holsters.size(); i++) {
                if (!ItemStack.matches(holsters.get(i), o.holsters.get(i))) {
                    return false;
                }
            }
            return true;
        }
    }

    /** Do cliente para o servidor: o meu estado. */
    public record Mine(State state) implements CustomPacketPayload {
        public static final Type<Mine> TYPE = new Type<>(Identifier.fromNamespaceAndPath(HandsOnVR.MOD_ID, "state"));
        static final StreamCodec<RegistryFriendlyByteBuf, Mine> CODEC = State.CODEC.map(Mine::new, Mine::state);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Do servidor para o cliente: o estado de outro jogador. */
    public record Peer(UUID player, State state) implements CustomPacketPayload {
        public static final Type<Peer> TYPE = new Type<>(Identifier.fromNamespaceAndPath(HandsOnVR.MOD_ID, "peer"));
        static final StreamCodec<RegistryFriendlyByteBuf, Peer> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Peer::player, State.CODEC, Peer::state, Peer::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** No servidor (o do jogo de quem hospeda): o último estado de cada jogador. */
    private static final Map<UUID, State> SERVER = new ConcurrentHashMap<>();
    /** No cliente: o estado de cada outro jogador. */
    private static final Map<UUID, State> PEERS = new ConcurrentHashMap<>();
    private static State sent = null;
    private static long ticks = 0;

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(Mine.TYPE, Mine.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Peer.TYPE, Peer.CODEC);
        // Servidor: guarda e repassa para os outros que têm o HandsOn.
        ServerPlayNetworking.registerGlobalReceiver(Mine.TYPE, (payload, context) -> {
            ServerPlayer from = context.player();
            SERVER.put(from.getUUID(), payload.state());
            for (ServerPlayer other : from.level().getServer().getPlayerList().getPlayers()) {
                if (other != from && ServerPlayNetworking.canSend(other, Peer.TYPE)) {
                    ServerPlayNetworking.send(other, new Peer(from.getUUID(), payload.state()));
                }
            }
        });
        // Quem entra recebe o estado de quem já está; quem sai é esquecido.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer joined = handler.player;
            if (ServerPlayNetworking.canSend(joined, Peer.TYPE)) {
                SERVER.forEach((id, state) -> {
                    if (!id.equals(joined.getUUID())) {
                        ServerPlayNetworking.send(joined, new Peer(id, state));
                    }
                });
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID gone = handler.player.getUUID();
            SERVER.remove(gone);
            for (ServerPlayer other : server.getPlayerList().getPlayers()) {
                if (!other.getUUID().equals(gone) && ServerPlayNetworking.canSend(other, Peer.TYPE)) {
                    ServerPlayNetworking.send(other, new Peer(gone, State.EMPTY));
                }
            }
        });
        // Cliente: guarda o estado dos outros.
        ClientPlayNetworking.registerGlobalReceiver(Peer.TYPE, (payload, context) -> PEERS.put(payload.player(), payload.state()));
    }

    /** O estado de outro jogador (vazio se não se sabe). */
    public static State peer(UUID player) {
        return PEERS.getOrDefault(player, State.EMPTY);
    }

    /** Teste automático: o que o servidor guardou deste jogador. */
    public static State onServer(UUID player) {
        return SERVER.get(player);
    }

    public static void reset() {
        PEERS.clear();
        sent = null;
    }

    /** A cada tick no cliente: a cada meio segundo, manda o meu estado se ele mudou (só se o servidor tem o HandsOn). */
    public static void tick(Minecraft client, HandsController hands) {
        ticks++;
        LocalPlayer p = client.player;
        if (p == null || ticks % 10 != 0 || !ClientPlayNetworking.canSend(Mine.TYPE)) {
            return;
        }
        boolean vr = VrBridge.isVRActive();
        List<ItemStack> items = new ArrayList<>();
        for (Holster h : Holster.values()) {
            items.add(vr ? hands.holsters().displayStack(p, h).copy() : ItemStack.EMPTY);
        }
        State now = new State(vr, items, vr && hands.backpack().isOpen() ? hands.backpack().holder() : -1);
        if (!now.sameAs(sent)) {
            ClientPlayNetworking.send(new Mine(now));
            sent = now;
        }
        PEERS.keySet().removeIf(id -> client.level != null && client.level.getPlayerByUUID(id) == null && !Objects.equals(id, p.getUUID())
                && PEERS.get(id) == State.EMPTY);
    }
}
