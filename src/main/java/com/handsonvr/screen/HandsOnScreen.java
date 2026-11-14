package com.handsonvr.screen;

import com.handsonvr.HandsOnConfig;
import com.handsonvr.holster.Holster;
import com.handsonvr.holster.HolsterManager;
import com.handsonvr.holster.HolsterStorage;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.function.Consumer;

/**
 * Ajustes do HandsOn VR: cada coldre (ligar/desligar e esvaziar), mostrar os itens no corpo e os controles extras.
 * Aberta pela tecla "HandsOn VR: ajustes" (sem tecla padrão: dá para pôr no menu radial do Vivecraft).
 */
public class HandsOnScreen extends Screen {
    private final Screen parent;
    private final HolsterManager holsters;

    public HandsOnScreen(Screen parent, HolsterManager holsters) {
        super(Text.translatable("screen.handsonvr.title"));
        this.parent = parent;
        this.holsters = holsters;
    }

    @Override
    protected void init() {
        HandsOnConfig config = HandsOnConfig.get();
        int w = 150, gap = 4, left = width / 2 - w - gap / 2, right = width / 2 + gap / 2, y = 32;

        toggle(left, y, w * 2 + gap, "option.handsonvr.holsters", config.holsters, v -> config.holsters = v);
        y += 24;
        holsterRow(left, right, y, w, Holster.RIGHT_SHOULDER, config.rightShoulder, v -> config.rightShoulder = v);
        y += 24;
        holsterRow(left, right, y, w, Holster.LEFT_SHOULDER, config.leftShoulder, v -> config.leftShoulder = v);
        y += 24;
        holsterRow(left, right, y, w, Holster.RIGHT_HIP, config.rightHip, v -> config.rightHip = v);
        y += 24;
        holsterRow(left, right, y, w, Holster.LEFT_HIP, config.leftHip, v -> config.leftHip = v);
        y += 28;
        toggle(left, y, w, "option.handsonvr.render", config.renderHolstered, v -> config.renderHolstered = v);
        toggle(right, y, w, "option.handsonvr.backpack", config.disableBackpackWithShoulders,
                v -> config.disableBackpackWithShoulders = v);
        y += 24;
        toggle(left, y, w, "option.handsonvr.left_trigger", config.leftTriggerOffhandUse, v -> config.leftTriggerOffhandUse = v);
        toggle(right, y, w, "option.handsonvr.right_stick", config.rightStickHotbar, v -> config.rightStickHotbar = v);
        y += 30;
        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> close()).dimensions(width / 2 - 100, y, 200, 20).build());
    }

    private void holsterRow(int left, int right, int y, int w, Holster h, boolean value, Consumer<Boolean> setter) {
        toggle(left, y, w, h.translationKey(), value, setter);
        HolsterStorage.Binding binding = holsters.binding(h);
        Text label = binding == null
                ? Text.translatable("option.handsonvr.empty")
                : Text.translatable("option.handsonvr.clear", itemName(binding));
        ButtonWidget clear = ButtonWidget.builder(label, b -> {
            holsters.clear(h);
            clearAndInit();
        }).dimensions(right, y, w, 20).build();
        clear.active = binding != null;
        addDrawableChild(clear);
    }

    private static Text itemName(HolsterStorage.Binding binding) {
        if (binding.name() != null) {
            return Text.literal(binding.name());
        }
        Identifier id = Identifier.tryParse(binding.item());
        return id == null ? Text.literal(binding.item()) : Registries.ITEM.get(id).getName();
    }

    private void toggle(int x, int y, int w, String key, boolean value, Consumer<Boolean> setter) {
        addDrawableChild(CyclingButtonWidget.onOffBuilder(value).build(x, y, w, 20, Text.translatable(key), (button, v) -> {
            setter.accept(v);
            HandsOnConfig.get().save();
        }));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFF);
    }

    @Override
    public void close() {
        HandsOnConfig.get().save();
        client.setScreen(parent);
    }
}
