package com.jmb.donut;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

public final class JmbDonutSmp implements ModInitializer {
    public static final String MOD_ID = "jmb_donutsmp";
    private static JmbMarket market;

    @Override
    public void onInitialize() {
        market = new JmbMarket();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                JmbCommands.register(dispatcher, market));

        ServerLifecycleEvents.SERVER_STARTED.register(server -> market.start(server));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> market.stop(server));
        ServerTickEvents.END_SERVER_TICK.register(server -> market.tick(server));
    }

    public static JmbMarket market() {
        return market;
    }
}