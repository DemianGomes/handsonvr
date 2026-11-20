package com.handsonvr.input;

import com.handsonvr.vr.VrBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Usar o item da mão ESQUERDA, mirando com o controle esquerdo (o Vivecraft mira o "usar" normal com o controle
 * direito): colocar a tocha/bloco onde a mão esquerda aponta, usar em uma entidade, ou usar o item no ar (bola de
 * neve, pérola...). Comer/beber: aproximar a comida da boca (o Vivecraft já faz isso).
 */
public final class OffhandUse {
    private OffhandUse() {}

    /** Resultado da última vez (teste automático). */
    private static String last = "-";

    public static String debugLast() {
        return last;
    }

    public static void use(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null || client.gui.screen() != null || player.isSpectator()) {
            last = "sem jogador/tela aberta";
            return;
        }
        VrBridge.Pose hand = VrBridge.hand(1);
        if (hand == null) {
            last = "sem a mão esquerda";
            return;
        }
        Vec3 from = hand.position();
        double reach = player.blockInteractionRange();
        Vec3 to = from.add(hand.direction().scale(reach));

        BlockHitResult block = player.level().clip(new ClipContext(from, to, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, player));
        double blockDistance = block.getType() == HitResult.Type.MISS ? Double.MAX_VALUE : from.distanceToSqr(block.getLocation());
        double entityReach = Math.min(player.entityInteractionRange(), reach);
        Vec3 entityTo = from.add(hand.direction().scale(entityReach));
        EntityHitResult entity = ProjectileUtil.getEntityHitResult(player, from, entityTo, new AABB(from, entityTo).inflate(1.0),
                e -> !e.isSpectator() && e.isPickable() && e != player, entityReach * entityReach);

        if (entity != null && from.distanceToSqr(entity.getLocation()) < blockDistance) {
            InteractionResult result = client.gameMode.interact(player, entity.getEntity(), entity, InteractionHand.OFF_HAND);
            if (result.consumesAction()) {
                swing(player, result);
                last = "entidade";
                return;
            }
        }
        if (block.getType() == HitResult.Type.BLOCK) {
            InteractionResult result = client.gameMode.useItemOn(player, InteractionHand.OFF_HAND, block);
            if (result.consumesAction()) {
                swing(player, result);
                last = "bloco";
                return;
            }
        }
        if (!player.getOffhandItem().isEmpty()) {
            InteractionResult result = client.gameMode.useItem(player, InteractionHand.OFF_HAND);
            if (result.consumesAction()) {
                swing(player, result);
                last = "item";
                return;
            }
        }
        last = "nada";
    }

    private static void swing(LocalPlayer player, InteractionResult result) {
        if (result instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.PREDICTED) {
            player.swing(InteractionHand.OFF_HAND, player.getOffhandItem().getInteractAnimation(), false);
        }
    }
}
