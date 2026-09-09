package com.github.whitemo.magazine_casing.compat;

import com.github.whitemo.magazine_casing.MagazineAndCasing;
import me.muksc.tacztweaks.config.Config;
import net.minecraftforge.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class TaCZTweaksCompat {
    private TaCZTweaksCompat() {}
    private static final Logger LOGGER = LogManager.getLogger(MagazineAndCasing.MOD_ID);

    private static boolean isInstalled() {
        ModList mods = ModList.get();
        boolean load = mods != null && mods.isLoaded("tacztweaks");
        if (load) {
            LOGGER.debug("[Magazine & Casing] tacztweaks installed, compatibility is available");
        }
        return load;
    }

    public static boolean unloadAllowed() {
        if (!isInstalled())
            return false;
        return Config.Gun.INSTANCE.allowUnload() && !Config.Gun.INSTANCE.unloadBulletInBarrel();
    }
}
