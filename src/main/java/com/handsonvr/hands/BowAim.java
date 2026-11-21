package com.handsonvr.hands;

import com.handsonvr.HandsOnConfig;
import com.handsonvr.vr.VrBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A mira opcional do arco (desligada por padrão): onde a flecha vai acertar se a corda for solta agora.
 *
 * <p>A conta é a do jogo com o arco do Vivecraft (lido no bytecode do 1.3.18):
 * <ul>
 *   <li>a flecha sai da mão da flecha (a que puxa a corda), na direção da mão da flecha para a mão do arco;</li>
 *   <li>a velocidade é a do arco do jogo ({@code BowItem}: força pelo tempo puxando, até 1 em 20 ticks, vezes 3) vezes
 *       o quanto a corda está puxada (o Vivecraft multiplica pela fração do puxão);</li>
 *   <li>a cada tick a flecha anda, perde 1% da velocidade (o ar) e cai 0,05 (a gravidade).</li>
 * </ul>
 * O ponto é o primeiro bloco ou criatura no caminho. Não conta a pequena imprecisão aleatória do arco nem o vento do
 * movimento do jogador: é uma ajuda, não uma garantia.
 */
public final class BowAim {
    private BowAim() {}

    /** Teste automático: um tiro "de mentira" (sem o Vivecraft puxando a corda). */
    public static Shot debugShot = null;

    /** Ticks simulados (uns 10 s de voo: bem mais que o alcance do arco). */
    private static final int MAX_TICKS = 200;
    private static final double DRAG = 0.99, GRAVITY = 0.05;

    /** Onde a flecha vai acertar agora, ou null (mira desligada, corda solta, nada no caminho). */
    public static Vec3 predict(Minecraft client) {
        LocalPlayer p = client.player;
        if (!HandsOnConfig.get().bowAim || p == null || client.level == null || !VrBridge.isVRActive()) {
            return null;
        }
        if (debugShot == null && (!VrBridge.isVivecraftLoaded() || !BowHand.isBow(p.getMainHandItem()))) {
            return null;
        }
        Shot shot;
        try {
            shot = debugShot != null ? debugShot : Viv.shot(client);
        } catch (Throwable t) {
            return null;
        }
        return shot == null ? null : trace(client.level, p, shot.from(), shot.velocity());
    }

    /** A flecha que sairia agora: de onde e com que velocidade (blocos por tick). */
    public record Shot(Vec3 from, Vec3 velocity) {}

    /** O caminho da flecha, tick a tick, até o primeiro bloco ou criatura. */
    public static Vec3 trace(Level level, LocalPlayer shooter, Vec3 from, Vec3 velocity) {
        Vec3 pos = from, v = velocity;
        for (int i = 0; i < MAX_TICKS; i++) {
            Vec3 next = pos.add(v);
            BlockHitResult block = level.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                    shooter));
            Vec3 end = block.getType() == HitResult.Type.MISS ? next : block.getLocation();
            EntityHitResult entity = ProjectileUtil.getEntityHitResult(shooter, pos, end, new AABB(pos, end).inflate(1.0),
                    e -> e != shooter && !e.isSpectator() && e.isPickable(), pos.distanceToSqr(end) + 1.0e-4);
            if (entity != null) {
                return entity.getLocation();
            }
            if (block.getType() != HitResult.Type.MISS) {
                return block.getLocation();
            }
            pos = next;
            v = v.scale(DRAG).subtract(0, GRAVITY, 0);
            if (pos.y < level.getMinY() - 16) {
                return null;
            }
        }
        return null;
    }

    /** A força do arco do jogo pelo tempo puxando ({@code BowItem.getPowerForTime}). */
    public static float power(int ticks) {
        float f = ticks / 20f;
        f = (f * f + f * 2f) / 3f;
        return Math.min(f, 1f);
    }

    /** O que vem do Vivecraft (API interna) fica aqui: sem o Vivecraft, esta classe nunca é carregada. */
    private static final class Viv {
        static Shot shot(Minecraft client) {
            var tracker = org.vivecraft.client_vr.ClientDataHolderVR.getInstance().bowTracker;
            if (tracker == null || !tracker.isActive(client.player) || !tracker.isDrawing()) {
                return null;
            }
            float draw = tracker.getDrawPercent();
            int ticks = (int) ((net.minecraft.util.Util.getMillis() - tracker.startDrawTime) / 50L);
            float speed = power(ticks) * 3f * draw;
            if (draw <= 0f || power(ticks) < 0.1f) {
                return null; // o jogo não atira com o arco tão pouco puxado
            }
            // A mão da flecha é a oposta à do arco (o "Reverse Bow" põe o arco na mão principal).
            int arrowHand = Boolean.TRUE.equals(BowHand.reverse()) ? 1 : 0;
            VrBridge.Pose arrow = VrBridge.hand(arrowHand), bow = VrBridge.hand(1 - arrowHand);
            if (arrow == null || bow == null) {
                return null;
            }
            Vec3 dir = bow.position().subtract(arrow.position());
            if (dir.lengthSqr() < 1.0e-6) {
                return null;
            }
            return new Shot(arrow.position(), dir.normalize().scale(speed));
        }
    }
}
