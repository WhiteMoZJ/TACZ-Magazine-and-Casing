package com.github.whitemo.magazine_casing;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;

/**
 * Common configuration for the magazine/casing drop features.
 * Lives in the common config so both sides share the same values.
 */
public class ModConfigs {

    public static final ForgeConfigSpec COMMON_SPEC;
    public static final CommonConfig COMMON;

    static {
        Pair<CommonConfig, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(CommonConfig::new);
        COMMON_SPEC = pair.getRight();
        COMMON = pair.getLeft();
    }

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, COMMON_SPEC);
    }

    public static class CommonConfig {
        /** Master switch: whether an empty-magazine reload drops a magazine at all. */
        public final ForgeConfigSpec.BooleanValue enableMagazineDrop;
        /** How many ticks a dropped magazine stays in the world before despawning. */
        public final ForgeConfigSpec.IntValue magazineDespawnTicks;
        /** Gun IDs that must never drop a magazine on empty reload. */
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> magazineDropBlacklist;
        /** Magazine model replacement map, entries of form "originalGun|modelSourceGun". */
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> magazineModelReplacements;
        /** Debug */
        public final ForgeConfigSpec.BooleanValue debug;
        /** 弹壳掉落总开关。 */
        public final ForgeConfigSpec.BooleanValue enableCasingDrop;
        /** 弹壳存活 tick 数。 */
        public final ForgeConfigSpec.IntValue casingDespawnTicks;
        /** 弹壳最大同时存在数量。 */
        public final ForgeConfigSpec.IntValue maxCasingCount;
        /** 射击时弹壳掉落黑名单。 */
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> casingDropBlacklist;
        /** 换弹时掉落弹壳（gunid|count）。 */
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> reloadCasingDrops;
        /** 反向抛壳的武器列表（弹壳横向初速度取反）。 */
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> reverseEjectGuns;
        /** 无横向初速抛壳的武器列表（弹壳没有横向初速度）。 */
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> noLateralEjectGuns;
        /** 弹壳模型替换（原枪ID|模型枪ID|每次射击抛壳数量）。 */
        public final ForgeConfigSpec.ConfigValue<List<? extends String>> casingModelReplacements;

        public CommonConfig(ForgeConfigSpec.Builder builder) {
            builder.push("magazine");

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
                            "tacz:m320", "hare:m18rr", "rcp:rpg26", "rcp:at4", "jak:airstrike", "tacz:kar98", "ccrp:lastwar"),
                            value -> value instanceof String id && ResourceLocation.tryParse(id) != null);

            magazineModelReplacements = builder
                    .comment("Magazine model replacement map. Each entry is \"originalGun|modelSourceGun\".",
                            "The original gun's ejected magazine uses the model source gun's magazine model.",
                            "Example: \"tacz:p90|ccrp:ar57\" makes tacz:p90 drop ccrp:ar57's magazine model.",
                            "弹匣模型替换映射，每项格式为 \"原枪ID|模型来源枪ID\"",
                            "原枪掉落的弹匣会使用模型来源枪的弹匣模型",
                            "例如 \"tacz:p90|ccrp:ar57\" 会让 tacz:p90 掉落 ccrp:ar57 的弹匣模型。")
                    .translation("config.magazine_casing.magazineModelReplacements")
                    .defineListAllowEmpty("magazineModelReplacements", List.of("tacz:p90|ccrp:ar57","ccrp:p90_effen_90|ccrp:ar57",
                                    "ccrp:p90_paw|ccrp:ar57","ccrp:p90_shround_s|ccrp:ar57", "classicr:m82a2|tacz:m95"),
                            CommonConfig::isModelReplacementEntry);
            builder.pop();

            builder.push("casing");
            enableCasingDrop = builder
                    .comment("Master switch: whether firing ejects a shell casing entity.",
                            "弹壳掉落总开关：是否在开火时掉落弹壳实体")
                    .translation("config.magazine_casing.enableCasingDrop")
                    .define("enableCasingDrop", true);

            casingDespawnTicks = builder
                    .comment("How many ticks a dropped shell casing stays in the world before despawning (20 ticks = 1 second).",
                            "掉落弹壳在消失前会停留的时间（tick，20 tick = 1 秒）")
                    .translation("config.magazine_casing.casingDespawnTicks")
                    .defineInRange("casingDespawnTicks", 200, 1, Integer.MAX_VALUE);

            maxCasingCount = builder
                    .comment("Maximum number of shell casing entities that can exist at once.",
                            "弹壳实体最大同时存在数量")
                    .translation("config.magazine_casing.maxCasingCount")
                    .defineInRange("maxCasingCount", 30, 1, Integer.MAX_VALUE);

            casingDropBlacklist = builder
                    .comment("Gun IDs that must not eject a shell casing when firing. (Do not affect guns that eject shell casings on reload)",
                            "射击时不掉落弹壳的枪械 ID 列表（与换弹掉壳设置互不影响）")
                    .translation("config.magazine_casing.casingDropBlacklist")
                    .defineListAllowEmpty("casingDropBlacklist", List.of("tacz:lonetrail", "classicr:colt_python", "ccrp:requiem",
                                    "tacz:taurus500", "tacz:rhino357", "tacz:taurus943", "hare:switchgun", "tacz:db_short", "tacz:db_long",
                                    "hare:m1216", "tacz:springfield1873", "ccrp:mp9_thunder", "ccrp:camg_dexterous", "classicr:mgl_40mm",
                                    "ccrp:lmt_m203", "tacz:m320", "hare:terminator"),
                            value -> value instanceof String id && ResourceLocation.tryParse(id) != null);

            reloadCasingDrops = builder
                    .comment("Guns that eject shell casings on reload. Each entry is \"gunId|count\".",
                            "换弹时掉落弹壳的枪械，格式 \"gunId|弹壳数量\"。")
                    .translation("config.magazine_casing.reloadCasingDrops")
                    .defineListAllowEmpty("reloadCasingDrops", List.of("tacz:lonetrail|1", "tacz:db_short|2", "tacz:db_long|2",
                                    "tacz:springfield1873|1", "kpp:870mcs|1", "kpp:870magpul_1|1", "kpp:870magpul|1", "kpp:m870_t|1",
                                    "kpp:870ll|1", "ccrp:lastwar|1", "tacz:m870|1", "tacz:spas_12|1", "hare:terminator|1", "hare:aek965|1",
                                    "ccrp:m1887_long|1", "ccrp:lmt_m203|1", "tacz:m320|1"),
                            CommonConfig::isReloadCasingEntry);

            reverseEjectGuns = builder
                    .comment("Guns that eject the shell casing to the opposite side.",
                            "反向抛壳的武器")
                    .translation("config.magazine_casing.reverseEjectGuns")
                    .defineListAllowEmpty("reverseEjectGuns", List.of("rfp:rpl20", "rfp:6p41bp", "rfp:mg43"),
                            value -> value instanceof String id && ResourceLocation.tryParse(id) != null);

            noLateralEjectGuns = builder
                    .comment("Guns that eject the shell casing with no lateral initial velocity (e.g. bottom ejection).",
                            "无横向初速抛壳的武器（弹壳没有横向初速度，如底部抛壳）")
                    .translation("config.magazine_casing.noLateralEjectGuns")
                    .defineListAllowEmpty("noLateralEjectGuns",
                            List.of("tacz:p90", "ccrp:p90_effen_90", "ccrp:p90_paw", "ccrp:p90_shround_s", "ccrp:ar57", "classicr:dp28", "hare:terminator"),
                            value -> value instanceof String id && ResourceLocation.tryParse(id) != null);

            casingModelReplacements = builder
                    .comment("Casing model replacement map. Each entry is \"originalGun|modelGun|shellCount\".",
                            "The original gun's ejected casing uses the model gun's casing model, and ejects shellCount casings per shot.",
                            "弹壳模型替换映射，每项格式为 \"原枪ID|模型枪ID|每次射击抛壳数量\"",
                            "原枪抛出的弹壳会使用模型枪的弹壳模型，且每次射击抛出指定数量的弹壳。")
                    .translation("config.magazine_casing.casingModelReplacements")
                    .defineListAllowEmpty("casingModelReplacements", List.of("hare:dp12|tacz:m870|1", "hare:ksg|tacz:m870|1"),
                            CommonConfig::isCasingReplacementEntry);
            builder.pop();

            builder.push("debug");
            debug = builder
                    .comment("Debug")
                    .define("debug", false);
            builder.pop();
        }

        private static boolean isModelReplacementEntry(Object value) {
            if (!(value instanceof String s)) {
                return false;
            }
            int sep = s.indexOf('|');
            if (sep <= 0 || sep == s.length() - 1) {
                return false;
            }
            return ResourceLocation.tryParse(s.substring(0, sep).trim()) != null
                    && ResourceLocation.tryParse(s.substring(sep + 1).trim()) != null;
        }

        private static boolean isReloadCasingEntry(Object value) {
            if (!(value instanceof String s)) {
                return false;
            }
            int sep = s.indexOf('|');
            if (sep <= 0 || sep == s.length() - 1) {
                return false;
            }
            if (ResourceLocation.tryParse(s.substring(0, sep).trim()) == null) {
                return false;
            }
            try {
                return Integer.parseInt(s.substring(sep + 1).trim()) > 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        private static boolean isCasingReplacementEntry(Object value) {
            if (!(value instanceof String s)) {
                return false;
            }
            String[] parts = s.split("\\|", -1);
            if (parts.length != 3) {
                return false;
            }
            if (ResourceLocation.tryParse(parts[0].trim()) == null
                    || ResourceLocation.tryParse(parts[1].trim()) == null) {
                return false;
            }
            try {
                return Integer.parseInt(parts[2].trim()) > 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
    }
}