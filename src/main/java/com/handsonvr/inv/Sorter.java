package com.handsonvr.inv;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.Comparator;

/**
 * "Organizar" (o botão da mochila e do baú VR, feito pelo HandsOn: funciona com ou sem o Inventory Profiles Next):
 * junta as pilhas iguais e põe em ordem (pelo item, depois o nome, a maior pilha primeiro, os vazios no fim), só com
 * cliques normais de pegar e pôr, como o jogador faria. Funciona em qualquer servidor.
 */
public final class Sorter {
    private Sorter() {}

    /** Os espaços a organizar: o item de cada um (como o cliente vê) e o clique de pegar/pôr nele. */
    public interface Slots {
        ItemStack get(int i);

        void pickup(int i);
    }

    private static final Comparator<ItemStack> ORDER = Comparator
            .comparing(ItemStack::isEmpty)
            .thenComparingInt(s -> BuiltInRegistries.ITEM.getId(s.getItem()))
            .thenComparing(s -> s.getHoverName().getString())
            .thenComparing(Comparator.comparingInt(ItemStack::getCount).reversed());

    /** Organiza os {@code n} espaços. Retorna quantos cliques fez. */
    public static int sort(int n, Slots s) {
        int clicks = 0;
        // 1) Junta: cada pilha incompleta puxa as iguais que vêm depois (a sobra volta para o lugar).
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                ItemStack a = s.get(i), b = s.get(j);
                if (a.isEmpty() || a.getCount() >= a.getMaxStackSize()) {
                    break;
                }
                if (!b.isEmpty() && ItemStack.isSameItemSameComponents(a, b)) {
                    s.pickup(j);
                    s.pickup(i);
                    s.pickup(j);
                    clicks += 3;
                }
            }
        }
        // 2) Ordena (seleção): o menor que falta vai para o lugar i, trocando de lugar com o que estava lá.
        for (int i = 0; i < n; i++) {
            int best = i;
            for (int k = i + 1; k < n; k++) {
                if (ORDER.compare(s.get(k), s.get(best)) < 0) {
                    best = k;
                }
            }
            if (best != i && !s.get(best).isEmpty()) {
                boolean filled = !s.get(i).isEmpty();
                s.pickup(best);
                s.pickup(i);
                clicks += 2;
                if (filled) {
                    s.pickup(best);
                    clicks++;
                }
            }
        }
        return clicks;
    }
}
