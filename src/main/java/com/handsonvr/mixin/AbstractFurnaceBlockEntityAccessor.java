package com.handsonvr.mixin;

import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * No singleplayer, o mod lê as fornalhas por perto direto do servidor do próprio jogo ({@code FurnaceWatcher}): os
 * números da chama e da seta são os mesmos que a tela da fornalha recebe (tempo de fogo, duração, cozimento, total).
 */
@Mixin(AbstractFurnaceBlockEntity.class)
public interface AbstractFurnaceBlockEntityAccessor {
    @Accessor("dataAccess")
    ContainerData handsonvr$dataAccess();
}
