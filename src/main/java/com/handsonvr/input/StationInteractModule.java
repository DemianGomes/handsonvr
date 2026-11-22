package com.handsonvr.input;

import com.handsonvr.HandsOnVR;
import com.handsonvr.chest.ChestStation;
import com.handsonvr.vr.VrBridge;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.vivecraft.api.client.HeldInteractModule;
import org.vivecraft.client_vr.ClientDataHolderVR;

/**
 * O Vivecraft tem a ação "VR Interact" (no Quest, o grip): com a mão dentro de um bloco que se usa, ela faz o "usar"
 * do jogo nele (o BlockInteractionModule, prioridade 1500). No baú isso abria a janela antiga, e com o baú imersivo
 * aberto, o grip para pegar um item reabria a janela antiga e fechava o imersivo.
 *
 * <p>Mais: enquanto essa ação está ligada, o grip vai para ela e NÃO para a ação da hotbar que o HandsOn escuta. Por
 * isso este módulo vem antes (prioridade 1400) e é o próprio grip do HandsOn quando a mão está num bloco que o mod
 * usa com as mãos (baú, barril, baú do Ender, caixa de shulker, suporte de poções, fornalhas e mesa de trabalho):
 * apertar, segurar e soltar chamam o mesmo grip de sempre (pegar itens, o gesto da tampa, soltar num espaço). Se o
 * grip já começou fora do bloco (pela hotbar), o módulo não toma o botão no meio. Só existe em VR.
 */
public final class StationInteractModule implements HeldInteractModule {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("handsonvr", "stations");
    private static boolean registered = false;
    private static boolean warned = false;

    /** Registra uma vez no Vivecraft (chamado a cada tick até dar certo). */
    public static void ensureRegistered() {
        if (registered) {
            return;
        }
        try {
            ClientDataHolderVR dh = ClientDataHolderVR.getInstance();
            if (dh == null || dh.interactTracker == null) {
                return;
            }
            dh.interactTracker.registerModules(new StationInteractModule());
            registered = true;
            HandsOnVR.LOGGER.info("[HandsOn VR] Módulo de interação dos blocos registrado (o grip não abre mais a janela antiga)");
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                HandsOnVR.LOGGER.warn("[HandsOn VR] Não consegui registrar o módulo de interação no Vivecraft", t);
            }
        }
    }

    /** Teste automático: já está no Vivecraft? */
    public static boolean isRegistered() {
        return registered;
    }

    /** O bloco é um dos que o HandsOn usa com as mãos? */
    public static boolean isStation(BlockState state) {
        return ChestStation.isContainer(state) || state.is(Blocks.BREWING_STAND) || state.is(Blocks.CRAFTING_TABLE)
                || state.getBlock() instanceof AbstractFurnaceBlock;
    }

    /** A mão (o controle, ou a ponta dela) está num desses blocos? */
    public static boolean covers(Level level, int hand, Vec3 controller) {
        if (level == null) {
            return false;
        }
        if (controller != null && isStation(level.getBlockState(BlockPos.containing(controller)))) {
            return true;
        }
        Vec3 tip = VrBridge.pointer(hand);
        return (tip != null && isStation(level.getBlockState(BlockPos.containing(tip))))
                || ChestStation.containerAt(level, tip) != null;
    }

    @Override
    public int getPriority() {
        return 1400;
    }

    @Override
    public Identifier getId() {
        return ID;
    }

    /** O grip desta mão foi apertado por este módulo (e ainda não soltou)? */
    private final boolean[] held = new boolean[2];

    @Override
    public boolean isActive(LocalPlayer player, InteractionHand hand, Vec3 position) {
        int h = hand.ordinal();
        if (held[h]) {
            return true;
        }
        if (HandsOnVR.isGripDown(h)) {
            return false; // o grip começou fora do bloco (hotbar): não toma o botão no meio, senão soltaria o item
        }
        return HandsOnVR.call("módulo de interação (ativo)", () -> covers(player.level(), h, position));
    }

    @Override
    public boolean onPress(LocalPlayer player, InteractionHand hand) {
        int h = hand.ordinal();
        held[h] = true;
        HandsOnVR.onGripPressed(h); // o grip do HandsOn (pegar um item, a tampa do baú...)
        return false; // sem o balanço do braço e a vibração do Vivecraft (o HandsOn vibra quando faz algo)
    }

    @Override
    public boolean onHoldTick(LocalPlayer player, InteractionHand hand) {
        return held[hand.ordinal()]; // segura o módulo enquanto o botão está apertado, mesmo com a mão saindo do bloco
    }

    @Override
    public void onRelease(LocalPlayer player, InteractionHand hand) {
        int h = hand.ordinal();
        if (held[h]) {
            held[h] = false;
            HandsOnVR.onGripReleased(h);
        }
    }

    @Override
    public void reset(LocalPlayer player, InteractionHand hand) {
        // O Vivecraft reinicia os módulos a cada tick sem o botão apertado: a soltura vem por onRelease antes disso.
    }

    @Override
    public boolean swingsArm() {
        return false;
    }
}
