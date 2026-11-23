package com.handsonvr.mine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Quais blocos cada forma pega, do jeito do FTB Ultimine (mesmas regras, escritas pelo HandsOn). O mesmo cálculo serve
 * para o contorno (no cliente) e para a quebra (no servidor), então o que aparece contornado é o que quebra.
 *
 * <ul>
 *   <li>As formas seguem a <b>face batida</b> do bloco: "para dentro" é o lado oposto a ela. Nas escadas, se a face é a
 *       de cima ou a de baixo, vale a direção horizontal do jogador.</li>
 *   <li>Sem forma: os blocos iguais encostados (26 vizinhos), do mais perto para o mais longe. Contam como iguais as
 *       pedras do Overworld entre si (pedra, ardósia, granito...) e os minérios entre si, como no Ultimine.</li>
 *   <li>Com forma: qualquer bloco quebrável conta; a forma para no primeiro buraco (ar, líquido, inquebrável).</li>
 * </ul>
 */
public final class MineShapes {
    private MineShapes() {}

    /** No máximo tantos blocos por vez, como o padrão do FTB Ultimine. */
    public static final int LIMIT = 64;
    private static final TagKey<Block> ORES = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("c", "ores"));

    /** Dá para quebrar com a mineração em grupo: não é ar, nem líquido, nem inquebrável, nem guarda itens (baú...). */
    public static boolean breakable(BlockGetter level, BlockPos pos, BlockState state) {
        return !state.isAir() && !(state.getBlock() instanceof LiquidBlock) && state.getDestroySpeed(level, pos) >= 0
                && !state.hasBlockEntity();
    }

    /** "Sem forma": o bloco conta como igual ao de origem? */
    static boolean sameKind(BlockState origin, BlockState state) {
        if (origin.getBlock() == state.getBlock()) {
            return true;
        }
        return (origin.is(BlockTags.BASE_STONE_OVERWORLD) && state.is(BlockTags.BASE_STONE_OVERWORLD))
                || (origin.is(ORES) && state.is(ORES));
    }

    /**
     * Os blocos da forma, o bloco de origem primeiro. {@code originState}: o bloco de origem (o da hora da quebra, já
     * que no servidor ele já virou ar). {@code face}: a face batida. {@code facing}: a direção horizontal do jogador.
     */
    public static List<BlockPos> blocks(BlockGetter level, BlockPos origin, BlockState originState, MineShape shape,
            Direction face, Direction facing, int limit) {
        origin = origin.immutable();
        return switch (shape) {
            case SHAPELESS -> shapeless(level, origin, originState, limit);
            case SMALL_TUNNEL -> line(level, origin, face.getOpposite(), 0, limit);
            case SMALL_SQUARE -> layers(level, origin, face, 1, limit);
            case LARGE_TUNNEL -> layers(level, origin, face, Integer.MAX_VALUE, limit);
            case MINING_TUNNEL -> stairs(level, origin, face, facing, -1, limit);
            case ESCAPE_TUNNEL -> stairs(level, origin, face, facing, 1, limit);
        };
    }

    private static boolean check(BlockGetter level, BlockPos pos) {
        return breakable(level, pos, level.getBlockState(pos));
    }

    /** Uma linha de 1x1 para dentro, parando no primeiro buraco. */
    private static List<BlockPos> line(BlockGetter level, BlockPos origin, Direction into, int dy, int limit) {
        List<BlockPos> out = new ArrayList<>();
        out.add(origin);
        for (int i = 1; out.size() < limit; i++) {
            BlockPos p = origin.relative(into, i).above(dy * i);
            if (!check(level, p)) {
                break;
            }
            out.add(p);
        }
        return out;
    }

    /** Escada: a cada passo, 1 para dentro e 1 para baixo (mineração) ou para cima (fuga), até um buraco. */
    private static List<BlockPos> stairs(BlockGetter level, BlockPos origin, Direction face, Direction facing, int dy, int limit) {
        Direction into = face.getAxis().isVertical() ? facing : face.getOpposite();
        return line(level, origin, into, dy, limit);
    }

    /** Camadas de 3x3 na face, para dentro, até {@code maxDepth} camadas ou uma camada sem nenhum bloco. */
    private static List<BlockPos> layers(BlockGetter level, BlockPos origin, Direction face, int maxDepth, int limit) {
        List<BlockPos> out = new ArrayList<>();
        out.add(origin);
        BlockPos base = origin;
        for (int depth = 0; depth < maxDepth && out.size() < limit; depth++) {
            int before = out.size();
            for (int a = -1; a <= 1 && out.size() < limit; a++) {
                for (int b = -1; b <= 1 && out.size() < limit; b++) {
                    if (depth == 0 && a == 0 && b == 0) {
                        continue;
                    }
                    BlockPos p = switch (face.getAxis()) {
                        case X -> base.offset(0, a, b);
                        case Y -> base.offset(a, 0, b);
                        case Z -> base.offset(a, b, 0);
                    };
                    if (check(level, p)) {
                        out.add(p);
                    }
                }
            }
            if (depth > 0 && out.size() == before) {
                break; // nenhum bloco nesta camada: o túnel acabou
            }
            base = base.relative(face.getOpposite());
        }
        return out;
    }

    /** Os blocos iguais encostados (26 vizinhos), do mais perto para o mais longe. */
    private static List<BlockPos> shapeless(BlockGetter level, BlockPos origin, BlockState originState, int limit) {
        List<BlockPos> found = new ArrayList<>();
        found.add(origin);
        Set<BlockPos> seen = new HashSet<>(found);
        Deque<BlockPos> queue = new ArrayDeque<>(found);
        while (!queue.isEmpty() && found.size() < limit * 4) {
            BlockPos at = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos p = at.offset(dx, dy, dz);
                        if ((dx == 0 && dy == 0 && dz == 0) || !seen.add(p)) {
                            continue;
                        }
                        BlockState s = level.getBlockState(p);
                        if (sameKind(originState, s) && breakable(level, p, s)) {
                            found.add(p);
                            queue.add(p);
                        }
                    }
                }
            }
        }
        List<BlockPos> rest = new ArrayList<>(found.subList(1, found.size()));
        rest.sort(Comparator.comparingDouble(p -> p.distSqr(origin)));
        List<BlockPos> out = new ArrayList<>();
        out.add(origin);
        out.addAll(rest.subList(0, Math.min(rest.size(), limit - 1)));
        return out;
    }
}
