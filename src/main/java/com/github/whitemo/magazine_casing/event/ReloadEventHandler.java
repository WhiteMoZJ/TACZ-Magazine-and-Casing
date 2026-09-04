package com.github.whitemo.magazine_casing.event;

import com.github.whitemo.magazine_casing.MagazineAndCasing;
import com.github.whitemo.magazine_casing.ModConfigs;
import com.github.whitemo.magazine_casing.entity.MagazineEntity;
import com.github.whitemo.magazine_casing.entity.ModEntities;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;

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

    @SubscribeEvent
    public static void onGunReload(GunReloadEvent event) {
        if (!ModConfigs.SERVER.enableMagazineDrop.get()) {
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
        if (gunId == null) {
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
            // Empty-magazine reload only (空仓换弹).
            return;
        }

        // open-bolt gun has one round in chamber when it's empty
        boolean oneRoundInChamber = (!iGun.hasBulletInBarrel(gun) && gunData.getBolt() == Bolt.OPEN_BOLT)
                || (iGun.hasBulletInBarrel(gun) && gunData.getBolt() != Bolt.OPEN_BOLT);
        if (oneRoundInChamber) {
            // One round still chambered: don't drop the magazine.
            return;
        }

        int magazineLevel = getExtendedMagLevel(gun, iGun);
        PENDING_DROPS.put(shooter.getUUID(),
                new PendingDrop(level, shooter.getUUID(), gunId, displayId, magazineLevel, gun.copy()));
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
                iterator.remove();
                continue;
            }
            // Switched items (or emptied hand) during the delay — cancel.
            if (!ItemStack.isSameItemSameTags(pending.gunSnapshot, shooter.getMainHandItem())) {
                iterator.remove();
                continue;
            }

            if (--pending.ticksLeft > 0) {
                continue;
            }
            iterator.remove();
            spawnMagazine(pending.level, shooter, pending.gunId, pending.displayId, pending.magazineLevel);
        }
    }

    private static void spawnMagazine(ServerLevel level, LivingEntity shooter, ResourceLocation gunId,
                                      ResourceLocation displayId, int magazineLevel) {
        Vec3 pos = shooter.getEyePosition().add(0.0D, -0.3D, 0.0D);
        MagazineEntity magazine = new MagazineEntity(ModEntities.MAGAZINE.get(), level);
        magazine.setPos(pos.x, pos.y, pos.z);
        magazine.setGunId(gunId);
        magazine.setDisplayId(displayId);
        magazine.setMagazineLevel(magazineLevel);
        magazine.setDeltaMovement(
                level.random.nextFloat() * 0.1D - 0.05D,
                0.12D,
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
        if (attachmentId == null) {
            return 0;
        }
        return TimelessAPI.getCommonAttachmentIndex(attachmentId)
                .map(index -> index.getData().getExtendedMagLevel())
                .orElse(0);
    }

    private static final class PendingDrop {
        final ServerLevel level;
        final UUID shooterId;
        final ResourceLocation gunId;
        final ResourceLocation displayId;
        final int magazineLevel;
        final ItemStack gunSnapshot;
        int ticksLeft;

        PendingDrop(ServerLevel level, UUID shooterId, ResourceLocation gunId, ResourceLocation displayId,
                    int magazineLevel, ItemStack gunSnapshot) {
            this.level = level;
            this.shooterId = shooterId;
            this.gunId = gunId;
            this.displayId = displayId;
            this.magazineLevel = magazineLevel;
            this.gunSnapshot = gunSnapshot;
            this.ticksLeft = DROP_DELAY_TICKS;
        }
    }
}