package com.handsonvr.screen;

import com.handsonvr.HandsOnConfig;
import com.handsonvr.holster.Holster;
import com.handsonvr.holster.HolsterManager;
import com.handsonvr.holster.HolsterStorage;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.function.Consumer;

/**
 * Ajustes do HandsOn VR: coldres, mochila, radial, cada coldre (ligar/desligar e esvaziar), mostrar os itens no corpo
 * e os controles extras. Aberta pelo botão "Coldres VR" do menu de pausa ou pela tecla "HandsOn VR: ajustes".
 */
public class HandsOnScreen extends Screen {
    private final Screen parent;
    private final HolsterManager holsters;

    public HandsOnScreen(Screen parent, HolsterManager holsters) {
        super(Component.translatable("screen.handsonvr.title"));
        this.parent = parent;
        this.holsters = holsters;
    }

    @Override
    protected void init() {
        HandsOnConfig config = HandsOnConfig.get();
        int w = 150, gap = 4, left = width / 2 - w - gap / 2, right = width / 2 + gap / 2, y = 28;

        toggle(left, y, w, "option.handsonvr.holsters", config.holsters, v -> config.holsters = v);
        toggle(right, y, w, "option.handsonvr.backpack_on", config.backpack, v -> config.backpack = v);
        y += 24;
        toggle(left, y, w, "option.handsonvr.radial", config.radialMenu, v -> config.radialMenu = v);
        toggle(right, y, w, "option.handsonvr.grip_hotbar", config.gripScrollsHotbar, v -> config.gripScrollsHotbar = v);
        y += 28;
        holsterRow(left, right, y, w, Holster.RIGHT_SHOULDER, config.rightShoulder, v -> config.rightShoulder = v);
        y += 24;
        if (!config.backpack) {
            holsterRow(left, right, y, w, Holster.LEFT_SHOULDER, config.leftShoulder, v -> config.leftShoulder = v);
            y += 24;
        }
        holsterRow(left, right, y, w, Holster.RIGHT_HIP, config.rightHip, v -> config.rightHip = v);
        y += 24;
        holsterRow(left, right, y, w, Holster.LEFT_HIP, config.leftHip, v -> config.leftHip = v);
        y += 28;
        toggle(left, y, w, "option.handsonvr.render", config.renderHolstered, v -> config.renderHolstered = v);
        toggle(right, y, w, "option.handsonvr.backpack", config.disableBackpackWithShoulders,
                v -> config.disableBackpackWithShoulders = v);
        y += 24;
        toggle(left, y, w, "option.handsonvr.left_trigger", config.leftTriggerOffhandUse, v -> config.leftTriggerOffhandUse = v);
        y += 30;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(width / 2 - 100, y, 200, 20).build());
    }

    private void holsterRow(int left, int right, int y, int w, Holster h, boolean value, Consumer<Boolean> setter) {
        toggle(left, y, w, h.translationKey(), value, setter);
        HolsterStorage.Binding binding = holsters.binding(h);
        Component label = binding == null
                ? Component.translatable("option.handsonvr.empty")
                : Component.translatable("option.handsonvr.clear", itemName(binding));
        Button clear = Button.builder(label, b -> {
            holsters.clear(h);
            rebuildWidgets();
        }).bounds(right, y, w, 20).build();
        clear.active = binding != null;
        addRenderableWidget(clear);
    }

    private static Component itemName(HolsterStorage.Binding binding) {
        if (binding.name() != null) {
            return Component.literal(binding.name());
        }
        Identifier id = Identifier.tryParse(binding.item());
        return id == null ? Component.literal(binding.item()) : BuiltInRegistries.ITEM.getValue(id).getDefaultInstance().getHoverName();
    }

    private void toggle(int x, int y, int w, String key, boolean value, Consumer<Boolean> setter) {
        addRenderableWidget(CycleButton.onOffBuilder(value).create(x, y, w, 20, Component.translatable(key), (button, v) -> {
            setter.accept(v);
            HandsOnConfig.get().save();
        }));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        HandsOnConfig.get().save();
        minecraft.gui.setScreen(parent);
    }
}
