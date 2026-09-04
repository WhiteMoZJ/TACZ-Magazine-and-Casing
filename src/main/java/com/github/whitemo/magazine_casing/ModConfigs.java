package com.github.whitemo.magazine_casing;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Server-side configuration for the magazine-drop feature.
 * Both values live in the server config so the server is authoritative.
 */
public class ModConfigs {

    public static final ForgeConfigSpec SERVER_SPEC;
    public static final ServerConfig SERVER;

    static {
        Pair<ServerConfig, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(ServerConfig::new);
        SERVER_SPEC = pair.getRight();
        SERVER = pair.getLeft();
    }

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SERVER_SPEC);
    }

    public static class ServerConfig {
        /** Master switch: whether an empty-magazine reload drops a magazine at all. */
        public final ForgeConfigSpec.BooleanValue enableMagazineDrop;
        /** How many ticks a dropped magazine stays in the world before despawning. */
        public final ForgeConfigSpec.IntValue magazineDespawnTicks;

        public ServerConfig(ForgeConfigSpec.Builder builder) {
            builder.push("magazine_casing");

            enableMagazineDrop = builder
                    .comment("If true, reloading a magazine-fed gun from empty spawns a dropped magazine entity.")
                    .define("enableMagazineDrop", true);

            magazineDespawnTicks = builder
                    .comment("How many ticks a dropped magazine stays in the world before despawning (20 ticks = 1 second).")
                    .defineInRange("magazineDespawnTicks", 600, 1, Integer.MAX_VALUE);

            builder.pop();
        }
    }
}