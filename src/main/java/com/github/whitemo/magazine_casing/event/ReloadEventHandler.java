package com.github.whitemo.magazine_casing.event;

import com.github.whitemo.magazine_casing.MagazineAndCasing;
import com.github.whitemo.magazine_casing.ModConfigs;
import com.github.whitemo.magazine_casing.entity.MagazineEntity;
import com.github.whitemo.magazine_casing.entity.ModEntities;
import com.github.whitemo.magazine_casing.compat.TaCZMagazinesCompat;
import com.github.whitemo.magazine_casing.compat.TaCZTweaksCompat;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.event.common.GunReloadEvent;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.resource.pojo.data.gun.Bolt;
import com.tacz.guns.resource.pojo.data.gun.FeedType;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import com.tacz.guns.resource.pojo.data.gun.GunReloadData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Listens to TACZ's reload event and, on an empty magazine-fed reload, drops a
 * magazine entity after a short delay. If the shooter switches held items
 * during that delay, the drop is cancelled. Runs on the server only.
 */
@Mod.EventBusSubscriber(modid = MagazineAndCasing.MOD_ID)
public class ReloadEventHandler {

    private static final int DROP_DELAY_TICKS = 10;
    private static final Map<UUID, PendingDrop> PENDING_DROPS = new HashMap<>();
    private static final Logger LOGGER = LogManager.getLogger(MagazineAndCasing.MOD_ID);

    @SubscribeEvent
    public static void onGunReload(GunReloadEvent event) {
        if (!ModConfigs.COMMON.enableMagazineDrop.get()) {
            return;
        }
        if (event.getLogicalSide() != LogicalSide.SERVER) {
            return;
        }
        LivingEntity shooter = event.getEntity();
        if (!(shooter.level() instanceof ServerLevel level)) {
            return;
        }

        ItemStack gun = event.getGunItemStack();
        if (gun.isEmpty()) {
            return;
        }
        IGun iGun = IGun.getIGunOrNull(gun);
        if (iGun == null) {
            return;
        }

        ResourceLocation gunId = iGun.getGunId(gun);
        ResourceLocation displayId = iGun.getGunDisplayId(gun);

        // 换弹时掉落弹壳（gunid|count），与弹匣掉落互相独立
        if (ModConfigs.COMMON.enableCasingDrop.get()) {
            int reloadCasingCount = getReloadCasingCount(gunId);
            if (reloadCasingCount > 0 && iGun.getCurrentAmmoCount(gun) == 0) {
                CasingSpawnHandler.dropCasings(level, shooter, gunId, reloadCasingCount);
                return; // 换弹掉壳，不再走弹匣掉落
            }
        }

        if (ModConfigs.COMMON.magazineDropBlacklist.get().contains(gunId.toString())) {
            // Gun is blacklisted: never drop a magazine for it.
            return;
        }

        GunData gunData = TimelessAPI.getCommonGunIndex(gunId)
                .map(index -> index.getGunData())
                .orElse(null);
        if (gunData == null) {
            return;
        }
        GunReloadData reloadData = gunData.getReloadData();
        if (reloadData == null || reloadData.getType() != FeedType.MAGAZINE) {
            // Only magazine-fed guns eject a magazine.
            return;
        }
        if (iGun.getCurrentAmmoCount(gun) != 0) {
            // Empty-magazine reload only.
            return;
        }

        boolean trueEmpty;
        if (TaCZTweaksCompat.unloadAllowed() && gunData.getBolt() == Bolt.OPEN_BOLT) {
            trueEmpty = true;
        } else {
            trueEmpty = !iGun.hasBulletInBarrel(gun);
        }

        if (!trueEmpty) {
            // One round still chambered: don't drop the magazine.
            return;
        }

        int magazineLevel = getExtendedMagLevel(gun, iGun);
        ResourceLocation modelGunId = gunId;
        ResourceLocation modelDisplayId = displayId;
        ResourceLocation replacement = findModelReplacement(gunId);
        if (replacement != null) {
            if (ModConfigs.COMMON.debug.get()) {
                LOGGER.debug("[Magazine] Empty-magazine reload: gun={} modelReplacement={}", gunId, replacement);
            }
            modelGunId = replacement;
            modelDisplayId = null; // use the replacement gun's default magazine model
        }
        // 与 TaCZ Magazines 共存时，直接把枪内弹匣取走：它因此不会再把空弹匣归还背包，
        // 掉落物也换成它的弹匣物品实体，不再生成本模组的弹匣模型实体。
        // 必须在 gun.copy() 之前取走，让快照与随后切枪检测时的枪械状态一致。
        ItemStack compatMagazine = TaCZMagazinesCompat.takeMagazine(gun,
                iGun.getCurrentAmmoCount(gun), gunData.getAmmoId());
        if (TaCZMagazinesCompat.isInstalled() && compatMagazine.isEmpty()) {
            // 兼容 TaCZ Magazines 时不再生成本模组的弹匣实体：枪内没有可掉落的弹匣就不掉落。
            return;
        }

        PENDING_DROPS.put(shooter.getUUID(),
                new PendingDrop(level, shooter.getUUID(), gunId, modelGunId, modelDisplayId, magazineLevel,
                        gun.copy(), compatMagazine));
    }

    private static ResourceLocation findModelReplacement(ResourceLocation gunId) {
        String target = gunId.toString();
        for (String entry : ModConfigs.COMMON.magazineModelReplacements.get()) {
            int sep = entry.indexOf('|');
            if (sep <= 0) {
                continue;
            }
            String left = entry.substring(0, sep).trim();
            String right = entry.substring(sep + 1).trim();
            if (left.equals(target)) {
                return ResourceLocation.tryParse(right);
            }
        }
        return null;
    }

    private static int getReloadCasingCount(ResourceLocation gunId) {
        String target = gunId.toString();
        for (String entry : ModConfigs.COMMON.reloadCasingDrops.get()) {
            int sep = entry.indexOf('|');
            if (sep <= 0) {
                continue;
            }
            String left = entry.substring(0, sep).trim();
            String right = entry.substring(sep + 1).trim();
            if (left.equals(target)) {
                try {
                    return Integer.parseInt(right);
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING_DROPS.isEmpty()) {
            return;
        }

        Iterator<Map.Entry<UUID, PendingDrop>> iterator = PENDING_DROPS.entrySet().iterator();
        while (iterator.hasNext()) {
            PendingDrop pending = iterator.next().getValue();

            Entity entity = pending.level.getEntity(pending.shooterId);
            if (!(entity instanceof LivingEntity shooter) || shooter.isRemoved()) {
                cancelDrop(pending);
                iterator.remove();
                continue;
            }
            // Switched items (or emptied hand) during the delay — cancel.
            if (!ItemStack.isSameItemSameTags(pending.gunSnapshot, shooter.getMainHandItem())) {
                cancelDrop(pending);
                iterator.remove();
                continue;
            }

            if (--pending.ticksLeft > 0) {
                continue;
            }
            iterator.remove();
            spawnMagazine(pending.level, shooter, pending.originalGunId, pending.gunId, pending.displayId,
                    pending.magazineLevel, pending.compatMagazine);
        }
    }

    /**
     * 掉落取消（切枪、玩家不在）时，把已经从枪里取出的 TaCZ Magazines 弹匣还给玩家，
     * 否则它既不在枪里也不会掉出来，等于凭空消失。
     */
    private static void cancelDrop(PendingDrop pending) {
        if (pending.compatMagazine.isEmpty()) {
            return;
        }
        ServerPlayer player = pending.level.getServer() == null ? null
                : pending.level.getServer().getPlayerList().getPlayer(pending.shooterId);
        if (player != null) {
            TaCZMagazinesCompat.giveBack(player, pending.compatMagazine);
        }
    }

    private static void spawnMagazine(ServerLevel level, LivingEntity shooter, ResourceLocation gunId,
                                      ResourceLocation modelGunId, ResourceLocation displayId, int magazineLevel,
                                      ItemStack compatMagazine) {
        Vec3 pos = shooter.getEyePosition().add(0.0D, -0.4D, 0.0D);
        if (!compatMagazine.isEmpty()) {
            // TaCZ Magazines 兼容：掉落它自己的弹匣物品实体（可拾取、可再次装填）。
            if (ModConfigs.COMMON.debug.get()) {
                LOGGER.info("[Magazine] Spawning dropped TaCZ Magazines item: gun={} item={}",
                        gunId, compatMagazine.getItem());
            }
            ItemEntity itemEntity = new ItemEntity(level, pos.x, pos.y, pos.z, compatMagazine);
            itemEntity.setDefaultPickUpDelay();
            level.addFreshEntity(itemEntity);
            return;
        }
        if (ModConfigs.COMMON.debug.get()) {
            LOGGER.info("[Magazine] Spawning dropped magazine: gun={} modelGun={} extendedLevel={}", gunId, modelGunId, magazineLevel);
        }
        MagazineEntity magazine = new MagazineEntity(ModEntities.MAGAZINE.get(), level);
        magazine.setPos(pos.x, pos.y, pos.z);
        magazine.setGunId(modelGunId);
        magazine.setDisplayId(displayId);
        magazine.setMagazineLevel(magazineLevel);
        magazine.setDeltaMovement(
                level.random.nextFloat() * 0.1D - 0.05D,
                0.0D,
                level.random.nextFloat() * 0.1D - 0.05D);
        level.addFreshEntity(magazine);
    }

    /**
     * Reads the extended-magazine level installed on the gun (0 = standard
     * magazine, 1-3 = the corresponding extended magazine), so the dropped
     * entity renders the magazine that was actually ejected.
     */
    private static int getExtendedMagLevel(ItemStack gun, IGun iGun) {
        ItemStack extendedMag = iGun.getAttachment(gun, AttachmentType.EXTENDED_MAG);
        if (extendedMag.isEmpty()) {
            extendedMag = iGun.getBuiltinAttachment(gun, AttachmentType.EXTENDED_MAG);
        }
        IAttachment attachment = IAttachment.getIAttachmentOrNull(extendedMag);
        if (attachment == null) {
            return 0;
        }
        ResourceLocation attachmentId = attachment.getAttachmentId(extendedMag);

        return TimelessAPI.getCommonAttachmentIndex(attachmentId)
                .map(index -> index.getData().getExtendedMagLevel())
                .orElse(0);
    }

    private static final class PendingDrop {
        final ServerLevel level;
        final UUID shooterId;
        final ResourceLocation originalGunId;
        final ResourceLocation gunId;
        final ResourceLocation displayId;
        final int magazineLevel;
        final ItemStack gunSnapshot;
        /** 兼容 TaCZ Magazines 时，从枪内取出、待掉落的弹匣物品；无则为空栈。 */
        final ItemStack compatMagazine;
        int ticksLeft;

        PendingDrop(ServerLevel level, UUID shooterId, ResourceLocation originalGunId, ResourceLocation gunId,
                    ResourceLocation displayId, int magazineLevel, ItemStack gunSnapshot, ItemStack compatMagazine) {
            this.level = level;
            this.shooterId = shooterId;
            this.originalGunId = originalGunId;
            this.gunId = gunId;
            this.displayId = displayId;
            this.magazineLevel = magazineLevel;
            this.gunSnapshot = gunSnapshot;
            this.compatMagazine = compatMagazine;
            this.ticksLeft = DROP_DELAY_TICKS;
        }
    }
}