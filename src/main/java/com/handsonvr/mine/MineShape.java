package com.handsonvr.mine;

/** As formas da mineração em grupo, as mesmas do FTB Ultimine. */
public enum MineShape {
    /** Os blocos iguais encostados (inclusive na diagonal), como um veio de minério. */
    SHAPELESS,
    /** Uma linha de 1x1 para dentro do bloco. */
    SMALL_TUNNEL,
    /** Um quadrado de 3x3 na face do bloco, com 1 de profundidade. */
    SMALL_SQUARE,
    /** Um túnel de 3x3 para dentro do bloco. */
    LARGE_TUNNEL,
    /** Um túnel da altura do jogador (1 de largura, 2 de altura). */
    MINING_TUNNEL,
    /** Uma escada para cima (1 de largura, 2 de altura, subindo 1 a cada bloco). */
    ESCAPE_TUNNEL;

    public static MineShape of(int index) {
        MineShape[] all = values();
        return all[Math.floorMod(index, all.length)];
    }

    public String translationKey() {
        return "handsonvr.mine.shape." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
