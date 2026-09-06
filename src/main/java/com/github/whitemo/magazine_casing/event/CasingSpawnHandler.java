package com.github.whitemo.magazine_casing.event;

import com.github.whitemo.magazine_casing.MagazineAndCasing;
import com.github.whitemo.magazine_casing.ModConfigs;
import com.github.whitemo.magazine_casing.entity.CasingEntity;
import com.github.whitemo.magazine_casing.entity.ModEntities;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.event.common.GunFireEvent;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.entity.shooter.ShooterDataHolder;
import com.tacz.guns.resource.pojo.data.gun.Bolt;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 监听 TACZ 开火事件，生成弹壳实体（相对玩家向右弹出）。只在服务端运行。
 * 手动上膛（manual_action）枪械开火不掉壳，重新上膛时才掉壳。
 * 弹壳生成位置按枪械类型（type）做服务端近似：服务端拿不到客户端模型里
 * shell 骨骼与第一人称定位组，只能用枪类型 + 玩家朝向拼出近似抛壳口位置。
 */
@Mod.EventBusSubscriber(modid = MagazineAndCasing.MOD_ID)
public class CasingSpawnHandler {

    /** 手动上膛枪械开火后待拉栓的弹壳：shooter UUID -> 枪械 ID。 */
    private static final Map<UUID, ResourceLocation> PENDING_MANUAL = new HashMap<>();

    /** 枪类型 -> 抛壳口近似偏移（相对玩家脚部：高度 / 右 / 前）。 */
    private record CasingOffset(double height, double right, double forward) {
    }

    private static final Map<String, CasingOffset> OFFSET_BY_TYPE = Map.of(
            "pistol", new CasingOffset(0.90D, 0.25D, 0.25D),
            "smg", new CasingOffset(1.00D, 0.25D, 0.30D),
            "rifle", new CasingOffset(1.10D, 0.30D, 0.35D),
            "sniper", new CasingOffset(1.10D, 0.30D, 0.38D),
            "shotgun", new CasingOffset(1.05D, 0.28D, 0.32D),
            "mg", new CasingOffset(1.00D, 0.30D, 0.35D),
            "rpg", new CasingOffset(1.30D, 0.35D, 0.30D)
    );
    private static final CasingOffset DEFAULT_OFFSET = new CasingOffset(1.05D, 0.28D, 0.32D);

    @SubscribeEvent
    public static void onGunFire(GunFireEvent event) {
        if (!ModConfigs.SERVER.enableCasingDrop.get()) {
            return;
        }
        if (event.getLogicalSide() != LogicalSide.SERVER) {
            return;
        }
        LivingEntity shooter = event.getShooter();
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
        if (gunId == null) {
            return;
        }
        if (ModConfigs.SERVER.casingDropBlacklist.get().contains(gunId.toString())) {
            return; // 射击时弹壳掉落黑名单
        }

        GunData gunData = TimelessAPI.getCommonGunIndex(gunId)
                .map(index -> index.getGunData())
                .orElse(null);
        if (gunData == null || gunData.getAmmoId() == null) {
            return;
        }

        if (gunData.getBolt() == Bolt.MANUAL_ACTION) {
            // 手动上膛：开火不掉壳，等重新上膛再掉。
            PENDING_MANUAL.put(shooter.getUUID(), gunId);
            return;
        }

        spawnCasing(level, shooter, gunId);
    }

    /**
     * 由 mixin 在拉栓成功开始时调用，用于掉落手动上膛枪械的弹壳。
     */
    public static void onBolt(LivingEntity shooter, ShooterDataHolder data) {
        if (!ModConfigs.SERVER.enableCasingDrop.get()) {
            return;
        }
        if (!(shooter.level() instanceof ServerLevel level)) {
            return;
        }
        if (!data.isBolting) {
            return; // 拉栓未真正开始
        }
        ResourceLocation gunId = PENDING_MANUAL.remove(shooter.getUUID());
        if (gunId == null) {
            return; // 没有已击发的弹壳
        }
        spawnCasing(level, shooter, gunId);
    }

    /**
     * 掉落 count 个弹壳（用于换弹掉壳等场景）。
     */
    public static void dropCasings(ServerLevel level, LivingEntity shooter, ResourceLocation gunId, int count) {
        for (int i = 0; i < count; i++) {
            spawnCasing(level, shooter, gunId);
        }
    }

    private static void spawnCasing(ServerLevel level, LivingEntity shooter, ResourceLocation gunId) {
        Vec3 eye = shooter.getEyePosition();

        // 限制弹壳最大数量：超出时移除最早的一个。
        int max = ModConfigs.SERVER.maxCasingCount.get();
        List<CasingEntity> existing = level.getEntitiesOfClass(CasingEntity.class,
                new AABB(eye.x, eye.y, eye.z, eye.x, eye.y, eye.z).inflate(256.0D));
        if (existing.size() >= max) {
            CasingEntity oldest = null;
            for (CasingEntity casing : existing) {
                if (oldest == null || casing.tickCount > oldest.tickCount) {
                    oldest = casing;
                }
            }
            if (oldest != null) {
                oldest.discard();
            }
        }

        ResourceLocation ammoId = TimelessAPI.getCommonGunIndex(gunId)
                .map(index -> index.getGunData().getAmmoId())
                .orElse(null);
        if (ammoId == null) {
            return;
        }
        String gunType = TimelessAPI.getCommonGunIndex(gunId)
                .map(index -> index.getPojo().getType())
                .orElse("");

        // 服务端近似抛壳口位置：枪类型偏移 + 玩家朝向（服务端拿不到第一人称模型骨骼）。
        Vec3 look = shooter.getLookAngle();
        Vec3 right = new Vec3(-look.z, 0.0D, look.x).normalize();
        CasingOffset offset = OFFSET_BY_TYPE.getOrDefault(gunType, DEFAULT_OFFSET);
        Vec3 pos = shooter.position()
                .add(0.0D, offset.height(), 0.0D)
                .add(right.scale(offset.right()))
                .add(look.scale(offset.forward()));

        CasingEntity casing = new CasingEntity(ModEntities.CASING.get(), level);
        casing.setPos(pos.x, pos.y, pos.z);
        casing.setAmmoId(ammoId);

        // 初速度对标原版 shell 的 initial_velocity [5,2,1]（块/秒）≈ [0.25,0.10,0.05]（块/tick），
        // 方向为「玩家右侧 + 向上 + 前方」，并加随机扰动。方向符号可翻转 right 来左右对调。
        double rightSpeed = 0.25D + (level.random.nextDouble() - 0.5D) * 0.10D;
        double upSpeed = 0.10D + (level.random.nextDouble() - 0.5D) * 0.10D;
        double forwardSpeed = 0.05D + (level.random.nextDouble() - 0.5D) * 0.05D;
        casing.setDeltaMovement(
                right.x * rightSpeed + look.x * forwardSpeed,
                upSpeed,
                right.z * rightSpeed + look.z * forwardSpeed);

        level.addFreshEntity(casing);
    }
}
