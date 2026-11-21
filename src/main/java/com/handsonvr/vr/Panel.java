package com.handsonvr.vr;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Quaternionf;

/**
 * Um plano no mundo (mochila, menu radial): origem, direita, cima e normal (aponta para o rosto do jogador), em
 * metros vezes a escala do mundo do Vivecraft. Coordenadas "locais" em metros de escala real.
 */
public record Panel(Vec3 origin, Vec3 right, Vec3 up, Vec3 normal, double scale) {
    private static final Vec3 WORLD_UP = new Vec3(0, 1, 0);

    /**
     * Plano com a origem em {@code origin}, virado para {@code eye}. {@code centerBelow}: quanto o centro visual fica
     * abaixo da origem (a mochila pendura da mão), usado só para mirar a normal.
     */
    public static Panel facing(Vec3 origin, Vec3 eye, double centerBelow, double scale) {
        Vec3 center = origin.add(0, -centerBelow * scale, 0);
        Vec3 n = eye.subtract(center);
        if (n.lengthSqr() < 1.0e-6) {
            n = new Vec3(0, 0, 1);
        }
        n = n.normalize();
        Vec3 up = WORLD_UP.subtract(n.scale(n.dot(WORLD_UP)));
        if (up.lengthSqr() < 1.0e-4) {
            up = new Vec3(0, 0, 1); // olhando reto para cima/baixo
        }
        up = up.normalize();
        Vec3 right = up.cross(n).normalize();
        return new Panel(origin, right, up, n, scale);
    }

    /** Ponto do mundo -> (x direita, y cima, z para o rosto) em metros de escala real. */
    public Vec3 local(Vec3 world) {
        Vec3 d = world.subtract(origin);
        return new Vec3(d.dot(right) / scale, d.dot(up) / scale, d.dot(normal) / scale);
    }

    /** (x, y, z) locais -> ponto do mundo. */
    public Vec3 world(double x, double y, double z) {
        return origin.add(right.scale(x * scale)).add(up.scale(y * scale)).add(normal.scale(z * scale));
    }

    /** Este plano (no mundo) nas coordenadas da área de jogo, em metros (escala 1). */
    public Panel toRoom(VrBridge.RoomFrame f) {
        return new Panel(f.toRoom(origin), f.dirToRoom(right), f.dirToRoom(up), f.dirToRoom(normal), scale / f.scale());
    }

    /** Este plano (na área de jogo) de volta no mundo, onde a área de jogo está agora. */
    public Panel toWorld(VrBridge.RoomFrame f) {
        return new Panel(f.toWorld(origin), f.dirToWorld(right), f.dirToWorld(up), f.dirToWorld(normal), scale * f.scale());
    }

    /** Rotação que leva os eixos X/Y/Z do desenho para direita/cima/normal do plano. */
    public Quaternionf rotation() {
        Matrix3f m = new Matrix3f(
                (float) right.x, (float) right.y, (float) right.z,
                (float) up.x, (float) up.y, (float) up.z,
                (float) normal.x, (float) normal.y, (float) normal.z);
        return new Quaternionf().setFromNormalized(m);
    }
}
