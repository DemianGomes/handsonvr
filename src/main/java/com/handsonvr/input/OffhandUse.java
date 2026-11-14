package com.handsonvr.input;

import com.handsonvr.vr.VrBridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

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

    public static void use(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.interactionManager == null || client.currentScreen != null || player.isSpectator()) {
            last = "sem jogador/tela aberta";
            return;
        }
        VrBridge.Pose hand = VrBridge.hand(1);
        if (hand == null) {
            last = "sem a mão esquerda";
            return;
        }
        Vec3d from = hand.position();
        double reach = player.getBlockInteractionRange();
        Vec3d to = from.add(hand.direction().multiply(reach));

        BlockHitResult block = player.getWorld().raycast(new RaycastContext(from, to, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, player));
        double blockDistance = block.getType() == HitResult.Type.MISS ? Double.MAX_VALUE : from.squaredDistanceTo(block.getPos());
        double entityReach = Math.min(player.getEntityInteractionRange(), reach);
        Vec3d entityTo = from.add(hand.direction().multiply(entityReach));
        EntityHitResult entity = ProjectileUtil.raycast(player, from, entityTo, new Box(from, entityTo).expand(1.0),
                e -> !e.isSpectator() && e.canHit() && e != player, entityReach * entityReach);

        if (entity != null && from.squaredDistanceTo(entity.getPos()) < blockDistance) {
            ActionResult result = client.interactionManager.interactEntityAtLocation(player, entity.getEntity(), entity, Hand.OFF_HAND);
            if (!result.isAccepted()) {
                result = client.interactionManager.interactEntity(player, entity.getEntity(), Hand.OFF_HAND);
            }
            if (result.isAccepted()) {
                swing(player, result);
                last = "entidade";
                return;
            }
        }
        if (block.getType() == HitResult.Type.BLOCK) {
            ActionResult result = client.interactionManager.interactBlock(player, Hand.OFF_HAND, block);
            if (result.isAccepted()) {
                swing(player, result);
                last = "bloco";
                return;
            }
        }
        if (!player.getOffHandStack().isEmpty()) {
            ActionResult result = client.interactionManager.interactItem(player, Hand.OFF_HAND);
            if (result.isAccepted()) {
                swing(player, result);
                last = "item";
                return;
            }
        }
        last = "nada";
    }

    private static void swing(ClientPlayerEntity player, ActionResult result) {
        if (result.shouldSwingHand()) {
            player.swingHand(Hand.OFF_HAND);
        }
    }
}
