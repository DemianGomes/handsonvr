package com.handsonvr.furnace;

import com.handsonvr.mixin.AbstractFurnaceBlockEntityAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * As fornalhas por perto (até {@link #RANGE} blocos), para mostrar o que tem em cada uma de longe.
 *
 * <p>O conteúdo de uma fornalha não chega ao cliente sozinho: só com o menu dela aberto, e o servidor só deixa abrir
 * uma, a uns 5 blocos ({@link FurnaceStation}). Então:
 * <ul>
 *   <li><b>No singleplayer</b>, o servidor é o do próprio jogo: a cada quarto de segundo o mod lê ali todas as
 *       fornalhas por perto (itens, chama e seta), na thread do servidor, e guarda uma cópia. Aparece tudo, ao vivo.</li>
 *   <li><b>Em servidor remoto</b>, a aberta aparece ao vivo e as outras com o que tinham na última vez que foram vistas
 *       abertas.</li>
 * </ul>
 */
public final class FurnaceWatcher {
    /** Até onde (m, dos olhos ao centro do bloco) as fornalhas mostram o que têm. */
    public static final double RANGE = 10.0;
    /** De quantos em quantos ticks procura as fornalhas e as lê no servidor do singleplayer. */
    private static final int EVERY = 5;

    /** A última vez que cada fornalha foi vista aberta (servidor remoto). */
    private final Map<BlockPos, FurnaceView> seen = new HashMap<>();
    /** O que o servidor do singleplayer tem agora (escrito na thread do servidor). */
    private final Map<BlockPos, FurnaceView> live = new ConcurrentHashMap<>();
    private List<BlockPos> nearby = List.of();
    private long ticks = 0;

    public void reset() {
        seen.clear();
        live.clear();
        nearby = List.of();
    }

    public void tick(Minecraft client, FurnaceStation station) {
        ticks++;
        if (client.level == null || client.player == null) {
            reset();
            return;
        }
        if (station.isOpen()) {
            FurnaceView open = station.view();
            if (open != null) {
                seen.put(open.pos(), open.withLive(false));
            }
        }
        if (ticks % EVERY != 0) {
            return;
        }
        Vec3 eye = client.player.getEyePosition();
        BlockPos center = BlockPos.containing(eye);
        int r = (int) Math.ceil(RANGE);
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos q : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
            if (Vec3.atCenterOf(q).distanceTo(eye) <= RANGE && FurnaceStation.Kind.of(client.level.getBlockState(q)) != null) {
                found.add(q.immutable());
            }
        }
        nearby = found;
        seen.keySet().removeIf(p -> client.level.isLoaded(p) && FurnaceStation.Kind.of(client.level.getBlockState(p)) == null);
        live.keySet().retainAll(found);
        MinecraftServer server = client.getSingleplayerServer();
        if (server != null && !found.isEmpty()) {
            ResourceKey<Level> dimension = client.level.dimension();
            List<BlockPos> list = List.copyOf(found);
            server.execute(() -> readOnServer(server, dimension, list));
        }
    }

    /** Na thread do servidor do singleplayer: uma cópia do que cada fornalha tem agora. */
    private void readOnServer(MinecraftServer server, ResourceKey<Level> dimension, List<BlockPos> list) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            return;
        }
        for (BlockPos p : list) {
            BlockState state = level.getBlockState(p);
            FurnaceStation.Kind kind = FurnaceStation.Kind.of(state);
            if (kind == null || !(level.getBlockEntity(p) instanceof AbstractFurnaceBlockEntity furnace)) {
                live.remove(p);
                continue;
            }
            ContainerData data = ((AbstractFurnaceBlockEntityAccessor) furnace).handsonvr$dataAccess();
            live.put(p, new FurnaceView(p, kind, state.getValue(AbstractFurnaceBlock.FACING), furnace.getItem(0).copy(),
                    furnace.getItem(1).copy(), furnace.getItem(2).copy(), data.get(0) > 0,
                    FurnaceView.litProgress(data.get(0), data.get(1)), FurnaceView.burnProgress(data.get(2), data.get(3)), true));
        }
    }

    /** O que desenhar agora: cada fornalha por perto, com o conteúdo mais novo que se tem (a aberta primeiro). */
    public List<FurnaceView> views(FurnaceStation station) {
        List<FurnaceView> out = new ArrayList<>();
        FurnaceView open = station.isOpen() ? station.view() : null;
        if (open != null) {
            out.add(open);
        }
        for (BlockPos p : nearby) {
            if (open != null && p.equals(open.pos())) {
                continue;
            }
            FurnaceView v = live.get(p);
            if (v == null) {
                v = seen.get(p);
            }
            if (v != null) {
                out.add(v);
            }
        }
        return out;
    }
}
