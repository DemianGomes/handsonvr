package com.handsonvr.mine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Quais blocos cada forma pega, a partir do bloco quebrado. O mesmo cálculo serve para o contorno (no cliente) e para a
 * quebra (no servidor), então o que aparece contornado é o que quebra.
 */
public final class MineShapes {
    private MineShapes() {}

    /** No máximo tantos blocos por vez, como o padrão do FTB Ultimine. */
    public static final int LIMIT = 64;

    /** Para onde a forma entra: o eixo mais forte do olho até o bloco (o túnel segue o olhar). */
    public static Direction into(Vec3 eye, BlockPos pos) {
        Vec3 d = Vec3.atCenterOf(pos).subtract(eye);
        return Direction.getApproximateNearest(d.x, d.y, d.z);
    }

    /** Dá para quebrar com a mineração em grupo: não é ar, nem líquido, nem inquebrável, nem guarda itens (baú...). */
    public static boolean breakable(BlockGetter level, BlockPos pos, BlockState state) {
        return !state.isAir() && !(state.getBlock() instanceof LiquidBlock) && state.getDestroySpeed(level, pos) >= 0
                && !state.hasBlockEntity();
    }

    /**
     * Os blocos da forma, o bloco de origem primeiro. {@code originState}: o bloco de origem (o da hora da quebra, já
     * que no servidor ele já virou ar).
     */
    public static List<BlockPos> blocks(BlockGetter level, BlockPos origin, BlockState originState, MineShape shape, Vec3 eye, int limit) {
        List<BlockPos> out = new ArrayList<>();
        out.add(origin.immutable());
        if (shape == MineShape.SHAPELESS) {
            shapeless(level, origin, originState.getBlock(), limit, out);
            return out;
        }
        Direction dir = into(eye, origin);
        Direction u, v;
        if (dir.getAxis() == Direction.Axis.Y) {
            u = Direction.EAST;
            v = Direction.SOUTH;
        } else {
            u = dir.getClockWise();
            v = Direction.UP;
        }
        if (shape == MineShape.ESCAPE_TUNNEL && dir.getAxis() == Direction.Axis.Y) {
            shape = MineShape.MINING_TUNNEL; // subir em escada só faz sentido olhando para os lados
        }
        int[][] slice;
        int depth;
        switch (shape) {
            case SMALL_SQUARE -> {
                slice = square();
                depth = 1;
            }
            case LARGE_TUNNEL -> {
                slice = square();
                depth = limit / 9;
            }
            case MINING_TUNNEL -> {
                // A altura do jogador: o bloco e o de baixo (bloco na altura da cabeça) ou o de cima (na altura dos pés).
                int extra = dir.getAxis() == Direction.Axis.Y ? 1 : (origin.getY() >= (int) Math.floor(eye.y) ? -1 : 1);
                slice = new int[][] {{0, 0}, {0, extra}};
                depth = limit / 2;
            }
            case ESCAPE_TUNNEL -> {
                slice = new int[][] {{0, 0}, {0, 1}};
                depth = limit / 2;
            }
            default -> {
                slice = new int[][] {{0, 0}};
                depth = limit;
            }
        }
        Set<BlockPos> seen = new HashSet<>(out);
        for (int k = 0; k < depth && out.size() < limit; k++) {
            BlockPos center = origin.relative(dir, k);
            if (shape == MineShape.ESCAPE_TUNNEL) {
                center = center.relative(Direction.UP, k);
            }
            for (int[] o : slice) {
                BlockPos p = center.relative(u, o[0]).relative(v, o[1]).immutable();
                if (out.size() >= limit || !seen.add(p)) {
                    continue;
                }
                if (breakable(level, p, level.getBlockState(p))) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    private static int[][] square() {
        int[][] s = new int[9][];
        int i = 0;
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                s[i++] = new int[] {a, b};
            }
        }
        return s;
    }

    /** Os blocos iguais ao de origem encostados (26 vizinhos), do mais perto para o mais longe. */
    private static void shapeless(BlockGetter level, BlockPos origin, Block block, int limit, List<BlockPos> out) {
        Set<BlockPos> seen = new HashSet<>(out);
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(origin.immutable());
        while (!queue.isEmpty() && out.size() < limit) {
            BlockPos at = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos p = at.offset(dx, dy, dz);
                        if (out.size() >= limit || !seen.add(p)) {
                            continue;
                        }
                        BlockState s = level.getBlockState(p);
                        if (s.is(block) && breakable(level, p, s)) {
                            out.add(p);
                            queue.add(p);
                        }
                    }
                }
            }
        }
    }
}
