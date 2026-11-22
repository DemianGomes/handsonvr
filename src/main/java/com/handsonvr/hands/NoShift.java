package com.handsonvr.hands;

import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.player.Input;

/**
 * Em VR, abaixar o corpo de verdade conta como Shift (agachado). Mas o "usar" do jogo, com um item na mão e agachado,
 * ignora o bloco (não abre o suporte de poções, não põe comida na fogueira). Como os blocos baixos pedem para abaixar,
 * o mod tira o Shift só durante o "usar" dele: avisa o servidor "sem Shift", usa, e logo avisa o Shift de volta.
 * Os pacotes seguem em ordem, então o servidor vê o uso sem Shift e o corpo continua agachado.
 */
public final class NoShift {
    private NoShift() {
    }

    public static <T> T run(Minecraft client, Supplier<T> use) {
        LocalPlayer p = client.player;
        if (p == null || p.input == null || client.getConnection() == null || !p.input.keyPresses.shift()) {
            return use.get();
        }
        Input real = p.input.keyPresses;
        Input flat = new Input(real.forward(), real.backward(), real.left(), real.right(), real.jump(), false, real.sprint());
        p.input.keyPresses = flat;
        client.getConnection().send(new ServerboundPlayerInputPacket(flat));
        try {
            return use.get();
        } finally {
            p.input.keyPresses = real;
            client.getConnection().send(new ServerboundPlayerInputPacket(real));
        }
    }
}
