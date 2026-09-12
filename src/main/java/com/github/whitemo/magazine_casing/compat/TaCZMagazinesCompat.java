package com.github.whitemo.magazine_casing.compat;

import com.github.whitemo.magazine_casing.MagazineAndCasing;
import com.raiiiden.taczmagazines.capability.GunMagazineCapability;
import com.raiiiden.taczmagazines.capability.GunMagazineProvider;
import com.raiiiden.taczmagazines.item.MagazineItem;
import com.tacz.guns.api.DefaultAssets;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * TaCZ Magazines 兼容。以 compileOnly 依赖 + 加载时检测的方式软兼容：
 * 未安装时 {@link #takeMagazine(ItemStack)} 恒返回空栈，不会触碰其类。
 * 安装后，空仓换弹时把枪内弹匣直接取出并掉落它的物品实体（可拾取、可再次装填），
 * 同时清空枪内弹匣，阻止它把空弹匣归还回玩家背包。
 */
public final class TaCZMagazinesCompat {

    private static final Logger LOGGER = LogManager.getLogger(MagazineAndCasing.MOD_ID);
    private static final String MOD_ID = "taczmagazines";

    /** 是否已安装 TaCZ Magazines（游戏加载时检测一次并缓存）。 */
    private static boolean installed;

    private TaCZMagazinesCompat() {
    }

    /** 游戏加载时调用一次：确认 TaCZ Magazines 是否安装。 */
    public static void init() {
        installed = ModList.get() != null && ModList.get().isLoaded(MOD_ID);
        if (installed) {
            LOGGER.info("[Magazine & Casing] TaCZ Magazines detected, compatibility enabled");
        } else {
            LOGGER.info("[Magazine & Casing] TaCZ Magazines not installed, compatibility disabled");
        }
    }

    /** 是否已安装 TaCZ Magazines。 */
    public static boolean isInstalled() {
        return installed;
    }

    /**
     * 取出枪内弹匣并清空。清空后 TaCZ Magazines 的归还分支（以 hasMagazine() 为前提）
     * 不会执行，弹匣不会再被放回玩家背包。枪内没有弹匣时返回空栈。
     * <p>
     * 枪内弹匣的 NBT 记录的是它被装填时的弹量，射击并不会更新它（TaCZ Magazines 只在
     * 归还前才把剩余弹量写回），所以取出后必须按枪内当前弹量改写，否则掉出来的是满弹匣。
     */
    public static ItemStack takeMagazine(ItemStack gun, int ammoCount, ResourceLocation ammoId) {
        if (!installed || gun.isEmpty()) {
            return ItemStack.EMPTY;
        }
        GunMagazineCapability capability = gun.getCapability(GunMagazineProvider.GUN_MAGAZINE).orElse(null);
        if (capability == null || !capability.hasMagazine()) {
            return ItemStack.EMPTY;
        }
        ItemStack magazine = capability.getStoredMagazine();
        capability.clearMagazine();
        writeAmmo(magazine, ammoCount, ammoId);
        return magazine;
    }

    /** 与 TaCZ Magazines 归还前的写入保持一致。 */
    private static void writeAmmo(ItemStack magazine, int ammoCount, ResourceLocation ammoId) {
        if (!(magazine.getItem() instanceof MagazineItem magItem)) {
            return;
        }
        if (ammoCount > 0 && !DefaultAssets.EMPTY_AMMO_ID.equals(ammoId)) {
            magItem.setAmmoId(magazine, ammoId);
            magItem.setAmmoCount(magazine, ammoCount);
        } else {
            magItem.setAmmoCount(magazine, 0);
            magItem.setAmmoId(magazine, DefaultAssets.EMPTY_AMMO_ID);
        }
        if (magazine.hasTag()) {
            magazine.getTag().remove("TaCZMagazinesCreativeSource");
        }
    }

    /** 掉落被取消时把取出的弹匣还给玩家；背包放不下则掉在脚下。 */
    public static void giveBack(Player player, ItemStack magazine) {
        if (magazine.isEmpty()) {
            return;
        }
        if (!player.getInventory().add(magazine)) {
            player.drop(magazine, false);
        }
    }
}
