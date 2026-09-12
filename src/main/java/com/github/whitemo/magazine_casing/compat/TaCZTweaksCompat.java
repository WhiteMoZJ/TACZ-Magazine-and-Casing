package com.github.whitemo.magazine_casing.compat;

import com.github.whitemo.magazine_casing.MagazineAndCasing;
import me.muksc.tacztweaks.config.Config;
import net.minecraftforge.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


public final class TaCZTweaksCompat {
    private TaCZTweaksCompat() {}
    private static final Logger LOGGER = LogManager.getLogger(MagazineAndCasing.MOD_ID);
    private static final String MOD_ID = "tacztweaks";
    private static boolean installed;

    public static void init() {
        installed = ModList.get() != null && ModList.get().isLoaded(MOD_ID);
        if (installed) {
            LOGGER.info("[Magazine & Casing] TaCZ Tweaks detected, compatibility enabled");
        } else {
            LOGGER.info("[Magazine & Casing] TaCZ Tweaks not installed, compatibility disabled");
        }
    }

    public static boolean unloadAllowed() {
        if (!installed) {
            return false;
        }
        return Config.Gun.INSTANCE.allowUnload() && !Config.Gun.INSTANCE.unloadBulletInBarrel();
    }
}
