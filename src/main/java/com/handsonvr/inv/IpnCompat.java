package com.handsonvr.inv;

import com.handsonvr.HandsOnVR;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;

/**
 * Inventory Profiles Next: o reabastecimento automático dele vê a mão esvaziar e põe de volta um item igual. Quando é
 * o HandsOn que tira o item da mão (guardar no coldre ou na mochila), isso desfazia a ação. Depois de cada clique do
 * HandsOn, o IPN recomeça a "foto" da mão e da hotbar ({@code AutoRefillHandler.init()}, o mesmo que ele faz com uma
 * tela aberta), então só repõe o que acabou de verdade (um bloco colocado, uma ferramenta quebrada). Sem o IPN, nada.
 */
public final class IpnCompat {
    private IpnCompat() {}

    private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("inventoryprofilesnext");
    private static Object handler;
    private static Method init;
    private static boolean failed = false;

    public static void afterClick() {
        if (!LOADED || failed) {
            return;
        }
        try {
            if (init == null) {
                Class<?> c = Class.forName("org.anti_ad.mc.ipnext.event.autorefill.AutoRefillHandler");
                handler = c.getField("INSTANCE").get(null);
                init = c.getMethod("init");
            }
            init.invoke(handler);
        } catch (Throwable t) {
            failed = true;
            HandsOnVR.LOGGER.warn("[HandsOn VR] Inventory Profiles Next: não consegui pausar o reabastecimento automático", t);
        }
    }
}
