package com.handsonvr.furnace;

import com.handsonvr.vr.Panel;
import com.handsonvr.vr.VrBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Uma fornalha e o que tem nela, para desenhar: a aberta (ao vivo, pelo menu), as outras por perto (no singleplayer, lidas
 * do servidor do próprio jogo) ou a última vez que cada uma foi vista aberta ({@link FurnaceWatcher}). Também é a
 * geometria dos lugares, da barra de progresso e do resultado de uma fornalha.
 *
 * @param live o conteúdo é de agora (menu aberto ou servidor do singleplayer), e não da última vez que foi vista
 */
public record FurnaceView(BlockPos pos, FurnaceStation.Kind kind, Direction facing, ItemStack input, ItemStack fuel,
                          ItemStack result, boolean lit, float litProgress, float burnProgress, boolean live) {

    /** Só a geometria (sem conteúdo). */
    public static FurnaceView shape(BlockPos pos, FurnaceStation.Kind kind, Direction facing) {
        return new FurnaceView(pos, kind, facing, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, false, 0f, 0f, false);
    }

    /** A chama da tela do jogo (AbstractFurnaceMenu.getLitProgress): o que ainda queima do combustível atual. */
    public static float litProgress(int litTime, int litDuration) {
        int duration = litDuration == 0 ? 200 : litDuration;
        return Math.max(0f, Math.min(1f, (float) litTime / duration));
    }

    /** A seta da tela do jogo (AbstractFurnaceMenu.getBurnProgress): o quanto o item atual já cozinhou. */
    public static float burnProgress(int cooking, int cookingTotal) {
        return cookingTotal != 0 && cooking != 0 ? Math.max(0f, Math.min(1f, (float) cooking / cookingTotal)) : 0f;
    }

    public FurnaceView withLive(boolean value) {
        return new FurnaceView(pos, kind, facing, input, fuel, result, lit, litProgress, burnProgress, value);
    }

    public FurnaceStation.Spot spot(int target) {
        return target == FurnaceStation.INPUT ? kind.input : kind.fuel;
    }

    public ItemStack stack(int target) {
        return switch (target) {
            case FurnaceStation.INPUT -> input;
            case FurnaceStation.FUEL -> fuel;
            case FurnaceStation.RESULT -> result;
            default -> ItemStack.EMPTY;
        };
    }

    /** Tem algo para mostrar (itens, fogo ou resultado)? */
    public boolean busy() {
        return lit || !input.isEmpty() || !fuel.isEmpty() || !result.isEmpty();
    }

    // ------------------------------------------------------------------ geometria

    public Vec3 center() {
        return Vec3.atCenterOf(pos);
    }

    public Vec3 topCenter() {
        return center().add(0, 0.5, 0);
    }

    /** O centro da frente (a face para onde a fornalha olha). */
    public Vec3 frontCenter() {
        return center().add(Vec3.atLowerCornerOf(facing.getUnitVec3i()).scale(0.5));
    }

    /** A direita de quem olha a frente (no plano). */
    public Vec3 frontRight() {
        Vec3 n = Vec3.atLowerCornerOf(facing.getUnitVec3i());
        return new Vec3(n.z, 0, -n.x);
    }

    /**
     * O plano de um lugar: origem no centro dele, x para a direita, y para cima do desenho e z para fora da face. No
     * topo, o "cima" do desenho aponta para longe do jogador.
     */
    public Panel spotPlane(int target) {
        FurnaceStation.Spot s = spot(target);
        double cx = ((s.x0() + s.x1()) / 2.0 - 8) / 16, cy = (8 - (s.y0() + s.y1()) / 2.0) / 16;
        if (s.face() == FurnaceStation.Face.FRONT) {
            Vec3 right = frontRight(), up = new Vec3(0, 1, 0);
            Vec3 origin = frontCenter().add(right.scale(cx)).add(up.scale(cy));
            return new Panel(origin, right, up, Vec3.atLowerCornerOf(facing.getUnitVec3i()), 1.0);
        }
        Vec3 away = away(), right = new Vec3(-away.z, 0, away.x);
        Vec3 origin = topCenter().add(right.scale(cx)).add(away.scale(cy));
        return new Panel(origin, right, away, new Vec3(0, 1, 0), 1.0);
    }

    /** Para longe do jogador, no plano (da cabeça para a fornalha). */
    public Vec3 away() {
        VrBridge.Pose head = VrBridge.head();
        if (head == null) {
            return new Vec3(0, 0, 1);
        }
        Vec3 d = center().subtract(head.position());
        Vec3 flat = new Vec3(d.x, 0, d.z);
        return flat.lengthSqr() < 1.0e-6 ? new Vec3(0, 0, 1) : flat.normalize();
    }

    /** A direita de quem olha a fornalha agora (a linha do progresso e do resultado acompanha o jogador). */
    public Vec3 viewRight() {
        Vec3 f = away();
        return new Vec3(-f.z, 0, f.x);
    }

    /** O centro da barra de progresso (à esquerda na linha acima do bloco). */
    public Vec3 panelCenter() {
        return topCenter().add(0, FurnaceStation.ROW_HEIGHT, 0).subtract(viewRight().scale(FurnaceStation.PANEL_SIDE));
    }

    /** Onde o resultado flutua (sem o balanço do desenho): à direita da seta. */
    public Vec3 resultCenter() {
        return topCenter().add(0, FurnaceStation.ROW_HEIGHT, 0).add(viewRight().scale(FurnaceStation.RESULT_SIDE));
    }
}
