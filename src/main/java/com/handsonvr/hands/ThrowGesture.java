package com.handsonvr.hands;

import com.handsonvr.HandsOnConfig;
import com.handsonvr.vr.VrBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.EggItem;
import net.minecraft.world.item.EnderpearlItem;
import net.minecraft.world.item.ExperienceBottleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SnowballItem;
import net.minecraft.world.item.ThrowablePotionItem;
import net.minecraft.world.item.WindChargeItem;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;

/**
 * O arremesso com a mão: com um item arremessável seguro pelo grip (bola de neve, ovo, pérola do Ender, poção
 * arremessável ou persistente, frasco de experiência, carga de vento), soltar o grip com a mão rápida joga UM item na
 * direção em que a mão estava indo.
 *
 * <p><b>Como sai:</b> pelo "usar item" do jogo, com a mira trocada pela direção do gesto. No VR, a troca é a função do
 * próprio Vivecraft ({@code ClientNetworking.overrideAimDir}, a mesma do golpe de espada dele), que funciona com ou sem
 * o Vivecraft no servidor. O servidor recebe o mesmo pacote de um jogador que mirou com o mouse e clicou: a velocidade
 * e a gravidade são as do jogo. As poções e o frasco de experiência saem 20° acima da mira no jogo; o mod desconta
 * isso para o item sair na direção da mão.
 *
 * <p><b>A direção</b> é a velocidade da mão nos últimos instantes antes de soltar, medida no quarto (andar ou girar
 * com o analógico não entortam o arremesso). Amostrada a cada quadro (o VR desenha a 72-120 quadros por segundo) e a
 * cada tick.
 *
 * <p><b>Força real</b> (só no singleplayer, {@link ThrowPower}): a força do gesto muda a velocidade do item.
 */
public final class ThrowGesture {
    private ThrowGesture() {}

    /** Velocidade mínima da mão (m/s, no quarto) para soltar o grip virar arremesso. */
    public static final double MIN_SPEED = 2.0;
    /** Janela da velocidade (os últimos instantes antes de soltar), quanto guardar e o intervalo mínimo entre amostras. */
    private static final long WINDOW = 100_000_000L, KEEP = 500_000_000L, MIN_GAP = 2_000_000L, MIN_SPAN = 15_000_000L;
    /** Força real: a velocidade da mão que dá a força do jogo, e os limites (fraco cai perto; forte, um pouco além). */
    private static final double REF_SPEED = 6.0, MIN_POWER = 0.35, MAX_POWER = 1.3;

    private record Sample(long nanos, Vec3 room) {}

    @SuppressWarnings("unchecked")
    private static final ArrayDeque<Sample>[] HISTORY = new ArrayDeque[] {new ArrayDeque<Sample>(), new ArrayDeque<Sample>()};

    /** Teste automático: o último arremesso (velocidade da mão em m/s, direção no mundo e a mira mandada ao jogo). */
    public record Throw(double speed, Vec3 direction, float yRot, float xRot, float power) {}

    public static Throw last = null;
    /** Teste automático: o relógio das amostras anda 50 ms por tick (o teste mexe a mão a cada tick). */
    public static boolean debugTickTime = false;
    private static long ticks = 0;

    /** A cada tick (antes dos controles): conta o tick e guarda onde as mãos estão. */
    public static void tick() {
        ticks++;
        sample();
    }

    // ------------------------------------------------------------------ amostras da mão

    /** Guarda onde cada mão está agora, no quarto (chamado a cada quadro e a cada tick). */
    public static void sample() {
        long now = debugTickTime ? ticks * 50_000_000L : System.nanoTime();
        VrBridge.RoomFrame frame = VrBridge.roomFrame();
        for (int hand = 0; hand < 2; hand++) {
            ArrayDeque<Sample> h = HISTORY[hand];
            VrBridge.Pose pose = frame == null ? null : VrBridge.hand(hand);
            if (pose == null) {
                h.clear();
                continue;
            }
            Sample last = h.peekLast();
            if (last != null && now - last.nanos() < MIN_GAP) {
                continue;
            }
            h.addLast(new Sample(now, frame.toRoom(pose.position())));
            while (!h.isEmpty() && now - h.peekFirst().nanos() > KEEP) {
                h.removeFirst();
            }
        }
    }

    /** A velocidade da mão (m/s, no quarto) nos últimos instantes, ou null sem amostras suficientes. */
    public static Vec3 roomVelocity(int hand) {
        ArrayDeque<Sample> h = HISTORY[hand];
        Sample newest = h.peekLast();
        if (newest == null) {
            return null;
        }
        Sample oldest = newest;
        for (var it = h.descendingIterator(); it.hasNext(); ) {
            Sample s = it.next();
            if (newest.nanos() - s.nanos() > WINDOW) {
                break;
            }
            oldest = s;
        }
        long span = newest.nanos() - oldest.nanos();
        if (span < MIN_SPAN) {
            return null;
        }
        return newest.room().subtract(oldest.room()).scale(1e9 / span);
    }

    // ------------------------------------------------------------------ o arremesso

    /** É um item que o jogo arremessa ao usar? */
    public static boolean isThrowable(ItemStack stack) {
        var item = stack.getItem();
        return item instanceof SnowballItem || item instanceof EggItem || item instanceof EnderpearlItem
                || item instanceof ThrowablePotionItem || item instanceof ExperienceBottleItem || item instanceof WindChargeItem;
    }

    /** Quanto o jogo levanta a mira deste item ao arremessar (graus; as poções e o frasco de experiência: 20). */
    private static float pitchOffset(ItemStack stack) {
        var item = stack.getItem();
        return item instanceof ThrowablePotionItem || item instanceof ExperienceBottleItem ? -20f : 0f;
    }

    /** A força real para esta velocidade da mão (1 = a do jogo). */
    public static float power(double speed) {
        return (float) Math.max(MIN_POWER, Math.min(MAX_POWER, speed / REF_SPEED));
    }

    /**
     * A mira (yRot, xRot do jogo) que faz o item sair na direção {@code d}, sabendo que o jogo soma {@code offset} graus
     * à mira vertical ao arremessar (o jogo: x = -sen(y)·cos(p), y = -sen(p + offset), z = cos(y)·cos(p)).
     */
    public static float[] rotationFor(Vec3 d, float offset) {
        double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
        float yRot = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        double elevation = Math.atan2(d.y, horizontal);
        double lo = -89.9, hi = 89.9;
        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) / 2;
            double e = Math.atan2(-Math.sin(Math.toRadians(mid + offset)), Math.cos(Math.toRadians(mid)));
            if (e > elevation) {
                lo = mid; // sai alto demais: mira mais para baixo
            } else {
                hi = mid;
            }
        }
        return new float[] {yRot, (float) ((lo + hi) / 2)};
    }

    /** A direção da mira do jogo (sem o acréscimo do arremesso). */
    private static Vec3 lookOf(float yRot, float xRot) {
        double y = Math.toRadians(yRot), x = Math.toRadians(xRot);
        return new Vec3(-Math.sin(y) * Math.cos(x), -Math.sin(x), Math.cos(y) * Math.cos(x));
    }

    /**
     * Joga UM item da mão {@code hand} (o espaço dela) na direção da velocidade {@code roomVelocity} (no quarto).
     * Retorna true se o jogo aceitou (false: não é arremessável, está esperando a recarga, etc.).
     */
    public static boolean throwItem(Minecraft client, int hand, Vec3 roomVelocity) {
        LocalPlayer p = client.player;
        VrBridge.RoomFrame frame = VrBridge.roomFrame();
        if (p == null || client.gameMode == null || frame == null || roomVelocity.lengthSqr() < 1.0e-6) {
            return false;
        }
        InteractionHand interactionHand = hand == 0 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        ItemStack stack = p.getItemInHand(interactionHand);
        if (!isThrowable(stack) || p.getCooldowns().isOnCooldown(stack)) {
            return false;
        }
        double speed = roomVelocity.length();
        Vec3 direction = frame.dirToWorld(roomVelocity).normalize();
        float[] rotation = rotationFor(direction, pitchOffset(stack));
        float power = HandsOnConfig.get().throwRealPower ? power(speed) : 1f;
        ThrowPower.arm(p.getUUID(), power);
        float oldY = p.getYRot(), oldX = p.getXRot();
        p.setYRot(rotation[0]);
        p.setXRot(rotation[1]);
        // Com o Vivecraft, a mira dele é trocada pela do gesto (sem o VR rodando, ela não é usada: vale a rotação acima).
        boolean vivecraft = VrBridge.isVivecraftLoaded() && Viv.override(hand, lookOf(rotation[0], rotation[1]));
        InteractionResult result;
        try {
            result = client.gameMode.useItem(p, interactionHand);
        } finally {
            if (vivecraft) {
                Viv.reset();
            }
            p.setYRot(oldY);
            p.setXRot(oldX);
        }
        if (!result.consumesAction()) {
            ThrowPower.disarm();
            return false;
        }
        last = new Throw(speed, direction, rotation[0], rotation[1], power);
        return true;
    }

    /** A mira do Vivecraft (API interna, com proteção): sem o Vivecraft, esta classe nunca é carregada. */
    private static final class Viv {
        static boolean override(int hand, Vec3 aim) {
            try {
                org.vivecraft.client.network.ClientNetworking.sendActiveBodyPart(
                        hand == 0 ? org.vivecraft.api.data.VRBodyPart.MAIN_HAND : org.vivecraft.api.data.VRBodyPart.OFF_HAND, true);
                org.vivecraft.client.network.ClientNetworking.overrideAimDir(
                        new org.joml.Vector3f((float) aim.x, (float) aim.y, (float) aim.z));
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        static void reset() {
            try {
                org.vivecraft.client.network.ClientNetworking.resetAim(0);
            } catch (Throwable ignored) {
            }
        }
    }
}
