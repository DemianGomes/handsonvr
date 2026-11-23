package com.handsonvr.mine;

import com.handsonvr.compat.UltimineVr;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

import java.util.List;

/**
 * Com a mineração em grupo do HandsOn ligada, os blocos que vão quebrar junto com o bloco mirado ficam com o contorno
 * do jogo (as linhas pretas translúcidas do bloco mirado), cada um. É o mesmo cálculo da quebra ({@link MineShapes}).
 */
public final class MinePreview {
    private MinePreview() {}

    private static BlockPos cachedPos;
    private static MineShape cachedShape;
    private static long cachedTick = -1;
    private static net.minecraft.core.Direction cachedFace;
    private static List<BlockPos> cached = List.of();

    /** Os blocos que o contorno mostra agora (teste automático). */
    public static List<BlockPos> current() {
        return cached;
    }

    public static void render(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        PoseStack ms = context.poseStack();
        if (!UltimineVr.ownActive() || client.level == null || client.player == null || ms == null
                || context.submitNodeCollector() == null || !MineServer.serverSupports()) {
            cached = List.of();
            return;
        }
        HitResult hit = client.hitResult;
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) {
            cached = List.of();
            return;
        }
        BlockPos pos = bhr.getBlockPos();
        BlockState state = client.level.getBlockState(pos);
        if (!MineShapes.breakable(client.level, pos, state)) {
            cached = List.of();
            return;
        }
        long tick = client.level.getGameTime();
        if (!pos.equals(cachedPos) || UltimineVr.shape() != cachedShape || tick != cachedTick || bhr.getDirection() != cachedFace) {
            cachedFace = bhr.getDirection();
            cached = MineShapes.blocks(client.level, pos, state, UltimineVr.shape(), bhr.getDirection(), client.player.getDirection(), MineShapes.LIMIT);
            cachedPos = pos.immutable();
            cachedShape = UltimineVr.shape();
            cachedTick = tick;
        }
        Vec3 camera = context.levelState().cameraRenderState.pos;
        float width = client.getWindow().getAppropriateLineWidth();
        int color = ARGB.black(102); // o mesmo do contorno do bloco mirado
        for (BlockPos p : cached) {
            ms.pushPose();
            ms.translate(p.getX() - camera.x, p.getY() - camera.y, p.getZ() - camera.z);
            context.submitNodeCollector().submitShapeOutline(ms, Shapes.block(), RenderTypes.linesTranslucent(), color, width, false);
            ms.popPose();
        }
    }
}
