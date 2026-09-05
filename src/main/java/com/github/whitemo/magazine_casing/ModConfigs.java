package com.github.whitemo.magazine_casing;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;

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
        /** Gun IDs that must never drop a magazine on empty reload. */
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> magazineDropBlacklist;

        public ServerConfig(ForgeConfigSpec.Builder builder) {
            builder.push("magazine_casing");

            enableMagazineDrop = builder
                    .comment("If true, reloading a magazine-fed gun from empty spawns a dropped magazine entity.",
                            "若为 true，弹匣供弹的枪械空仓换弹时会掉落一个弹匣实体")
                    .translation("config.magazine_casing.enableMagazineDrop")
                    .define("enableMagazineDrop", true);

            magazineDespawnTicks = builder
                    .comment("How many ticks a dropped magazine stays in the world before despawning (20 ticks = 1 second).",
                            "掉落弹匣在消失前会停留的时间（tick，20 tick = 1 秒）")
                    .translation("config.magazine_casing.magazineDespawnTicks")
                    .defineInRange("magazineDespawnTicks", 600, 1, Integer.MAX_VALUE);

            magazineDropBlacklist = builder
                    .comment("Gun IDs that must never drop a magazine on empty reload.",
                            "Use TaCZ gun IDs such as \"tacz:ak47\".",
                            "空仓换弹时不掉落弹匣的枪械 ID 列表",
                            "使用 TaCZ 枪械 ID（例如 \"tacz:ak47\"）")
                    .translation("config.magazine_casing.magazineDropBlacklist")
                    .defineListAllowEmpty("magazineDropBlacklist", List.of(
                            "classicr:colt_python", "ccrp:requiem", "tacz:taurus500", "tacz:rhino357",
                            "tacz:taurus943", "hare:switchgun", "tacz:springfield1873",
                            "ccrp:springfield1873_tube_mag", "jak:mors", "ccrp:marlin_1895",
                            "kpp:870mcs", "kpp:870magpul_1", "kpp:m870_t", "kpp:870ll", "kpp:870magpul",
                            "ccrp:m1887_long", "ccrp:camg_m1014", "tacz:db_short", "tacz:db_long",
                            "tacz:m870", "tacz:spas_12", "tacz:m1014", "hare:aek965", "hare:terminator",
                            "hare:ksg", "hare:striker", "hare:dp12", "rfp:reapr", "tacz:minigun",
                            "classicr:minigun", "classicr:mgl_40mm", "ccrp:lmt_m203", "tacz:rpg7",
                            "tacz:m320", "hare:m18rr", "rcp:rpg26", "rcp:at4", "jak:airstrike"),
                            value -> value instanceof String id && ResourceLocation.tryParse(id) != null);

            builder.pop();
        }
    }
}