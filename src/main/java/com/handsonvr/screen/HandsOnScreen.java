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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * "Opções HandsOn VR": todas as opções do mod, separadas em grupos (coldres, mochila e radial, mãos e itens, HUD), numa
 * lista que rola (roda do mouse ou analógico; e os botões ▲ ▼ ao lado, fáceis de apontar no VR). Aberta pelo botão do
 * menu de pausa ou pela tecla "HandsOn VR: ajustes".
 */
public class HandsOnScreen extends Screen {
    private static final int TOP = 28, ROW = 24, HEADER = 22, BOTTOM_SPACE = 36, W = 150, GAP = 4;

    private final Screen parent;
    private final HolsterManager holsters;
    /** O quanto a lista rolou (pixels da tela); fica igual quando a tela se refaz (ao mudar uma opção). */
    private int scroll = 0;
    private int contentHeight = 0;
    /** Rolagem e refazer pedidos por um clique: feitos no próximo tick (não no meio do clique). */
    private int pendingScroll = 0;
    private boolean dirty = false;
    /** Os cabeçalhos dos grupos visíveis agora: o texto e onde fica. */
    private final List<Header> headers = new ArrayList<>();

    private record Header(Component text, int y) {}

    /** Uma linha da lista: um cabeçalho ou até dois botões lado a lado. */
    private record Line(Component header, IntConsumer widgets) {
        int height() {
            return header != null ? HEADER : ROW;
        }
    }

    public HandsOnScreen(Screen parent, HolsterManager holsters) {
        super(Component.translatable("screen.handsonvr.title"));
        this.parent = parent;
        this.holsters = holsters;
    }

    private int left() {
        return width / 2 - W - GAP / 2;
    }

    private int right() {
        return width / 2 + GAP / 2;
    }

    private int bottom() {
        return height - BOTTOM_SPACE;
    }

    private List<Line> lines() {
        HandsOnConfig config = HandsOnConfig.get();
        List<Line> lines = new ArrayList<>();
        // --- coldres
        lines.add(header("group.handsonvr.holsters"));
        lines.add(row(y -> {
            toggle(left(), y, "option.handsonvr.holsters", config.holsters, v -> config.holsters = v);
            toggle(right(), y, "option.handsonvr.render", config.renderHolstered, v -> config.renderHolstered = v);
        }));
        lines.add(row(y -> holsterRow(y, Holster.RIGHT_SHOULDER, config.rightShoulder, v -> config.rightShoulder = v)));
        if (!config.backpack) {
            lines.add(row(y -> holsterRow(y, Holster.LEFT_SHOULDER, config.leftShoulder, v -> config.leftShoulder = v)));
        }
        lines.add(row(y -> holsterRow(y, Holster.RIGHT_HIP, config.rightHip, v -> config.rightHip = v)));
        lines.add(row(y -> holsterRow(y, Holster.LEFT_HIP, config.leftHip, v -> config.leftHip = v)));
        lines.add(row(y -> toggle(left(), y, "option.handsonvr.backpack", config.disableBackpackWithShoulders,
                v -> config.disableBackpackWithShoulders = v)));
        // --- mochila e menu radial
        lines.add(header("group.handsonvr.backpack"));
        lines.add(row(y -> {
            toggle(left(), y, "option.handsonvr.backpack_on", config.backpack, v -> config.backpack = v);
            toggle(right(), y, "option.handsonvr.radial", config.radialMenu, v -> config.radialMenu = v);
        }));
        lines.add(row(y -> {
            toggle(left(), y, "option.handsonvr.pickup_main", config.pickupToMain, v -> config.pickupToMain = v);
            toggle(right(), y, "option.handsonvr.grip_hotbar", config.gripScrollsHotbar, v -> config.gripScrollsHotbar = v);
        }));
        // --- mãos e itens
        lines.add(header("group.handsonvr.hands"));
        lines.add(row(y -> {
            toggle(left(), y, "option.handsonvr.bow_aim", config.bowAim, v -> config.bowAim = v);
            toggle(right(), y, "option.handsonvr.left_trigger", config.leftTriggerOffhandUse, v -> config.leftTriggerOffhandUse = v);
        }));
        lines.add(row(y -> {
            toggle(left(), y, "option.handsonvr.throw", config.physicalThrow, v -> config.physicalThrow = v);
            toggle(right(), y, "option.handsonvr.throw_power", config.throwRealPower, v -> config.throwRealPower = v);
        }));
        // --- HUD
        lines.add(header("group.handsonvr.hud"));
        lines.add(row(y -> {
            toggle(left(), y, "option.handsonvr.wrist_hud", config.wristHud, v -> config.wristHud = v);
            toggle(right(), y, "option.handsonvr.hand_count", config.handCount, v -> config.handCount = v);
        }));
        lines.add(row(y -> toggle(left(), y, "option.handsonvr.hide_vivecraft_hud", config.hideVivecraftHud, v -> {
            config.hideVivecraftHud = v;
            com.handsonvr.render.VivecraftHud.apply(minecraft);
        })));
        return lines;
    }

    private static Line header(String key) {
        return new Line(Component.translatable(key), null);
    }

    private static Line row(IntConsumer widgets) {
        return new Line(null, widgets);
    }

    @Override
    protected void init() {
        headers.clear();
        List<Line> lines = lines();
        contentHeight = 0;
        for (Line line : lines) {
            contentHeight += line.height();
        }
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
        int y = TOP - scroll;
        for (Line line : lines) {
            // Só o que cabe inteiro na área da lista aparece (o resto, rolando).
            if (y >= TOP && y + line.height() <= bottom()) {
                if (line.header() != null) {
                    headers.add(new Header(line.header(), y + 8));
                } else {
                    line.widgets().accept(y);
                }
            }
            y += line.height();
        }
        if (maxScroll() > 0) {
            int x = right() + W + 6;
            Button up = Button.builder(Component.literal("▲"), b -> pendingScroll -= 3 * ROW).bounds(x, TOP, 20, 20).build();
            Button down = Button.builder(Component.literal("▼"), b -> pendingScroll += 3 * ROW).bounds(x, bottom() - 20, 20, 20).build();
            up.active = scroll > 0;
            down.active = scroll < maxScroll();
            addRenderableWidget(up);
            addRenderableWidget(down);
        }
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(width / 2 - 100, height - 28, 200, 20).build());
    }

    private int maxScroll() {
        return Math.max(0, contentHeight - (bottom() - TOP));
    }

    /** Rola a lista (pixels; + desce). Retorna true se mudou. */
    public boolean scrollBy(int pixels) {
        int before = scroll;
        scroll = Math.max(0, Math.min(scroll + pixels, maxScroll()));
        if (scroll != before) {
            rebuildWidgets();
            return true;
        }
        return false;
    }

    /** Teste automático: dá para rolar? */
    public boolean canScroll() {
        return maxScroll() > 0;
    }

    @Override
    public void tick() {
        super.tick();
        int pixels = pendingScroll;
        pendingScroll = 0;
        if (!(pixels != 0 && scrollBy(pixels)) && dirty) {
            rebuildWidgets();
        }
        dirty = false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0 && scrollBy((int) Math.round(-scrollY * ROW))) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void holsterRow(int y, Holster h, boolean value, Consumer<Boolean> setter) {
        toggle(left(), y, h.translationKey(), value, setter);
        HolsterStorage.Binding binding = holsters.binding(h);
        Component label = binding == null
                ? Component.translatable("option.handsonvr.empty")
                : Component.translatable("option.handsonvr.clear", itemName(binding));
        Button clear = Button.builder(label, b -> {
            holsters.clear(h);
            dirty = true;
        }).bounds(right(), y, W, 20).build();
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

    private void toggle(int x, int y, String key, boolean value, Consumer<Boolean> setter) {
        addRenderableWidget(CycleButton.onOffBuilder(value).create(x, y, W, 20, Component.translatable(key), (button, v) -> {
            setter.accept(v);
            HandsOnConfig.get().save();
            dirty = true; // (a mochila ligada/desligada muda a lista dos coldres); refeita no próximo tick
        }));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
        for (Header h : headers) {
            graphics.centeredText(font, h.text(), width / 2, h.y(), 0xFFFFFF55); // amarelo, como os títulos do jogo
        }
        if (maxScroll() > 0) {
            // A barra de rolagem: o trilho e a parte visível, ao lado dos botões ▲ ▼.
            int x = right() + W + 14, top = TOP + 22, bottom = bottom() - 22, track = bottom - top;
            graphics.fill(x, top, x + 4, bottom, 0x66000000);
            int view = bottom() - TOP, thumb = Math.max(10, track * view / contentHeight);
            int at = top + (track - thumb) * scroll / maxScroll();
            graphics.fill(x, at, x + 4, at + thumb, 0xFFC0C0C0);
        }
    }

    @Override
    public void onClose() {
        HandsOnConfig.get().save();
        minecraft.gui.setScreen(parent);
    }
}
