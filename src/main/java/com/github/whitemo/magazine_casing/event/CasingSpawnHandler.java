package com.github.whitemo.magazine_casing.event;

import com.github.whitemo.magazine_casing.ModConfigs;
import com.github.whitemo.magazine_casing.entity.CasingEntity;
import com.github.whitemo.magazine_casing.entity.ModEntities;
import com.tacz.guns.api.TimelessAPI;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 生成弹壳实体。精确位置由客户端渲染时计算并通过网络包发给服务端
 * （见 {@code spawnCasingFromClient}）；换弹掉壳等服务端场景则用枪类型做近似
 * （见 {@code dropCasings}）。
 */
public class CasingSpawnHandler {

    /** 枪类型 -> 抛壳口近似偏移（相对玩家脚部：高度 / 右 / 前），仅用于服务端触发的掉壳。 */
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

    /** 客户端算出的精确位置向玩家后方（-视线方向）的修正量，用于把弹壳生成点挪到抛壳口偏后。 */
    private static final double BACKWARD_OFFSET = 0.25D;

    /** 换弹掉壳的去重标记：记录某玩家最近一次服务端掉壳的 tick 与枪械，用于屏蔽客户端换弹退壳的重复包。 */
    private record ReloadCasingMark(int tick, ResourceLocation gunId) {
    }

    private static final Map<UUID, ReloadCasingMark> RELOAD_CASING_MARKS = new HashMap<>();
    /** 换弹掉壳去重窗口（tick）。窗口内同一把枪的客户端退壳包会被忽略。 */
    private static final int RELOAD_CASING_WINDOW_TICKS = 60;

    /** 每个玩家最近一次生成弹壳的 tick，用于在同一 tick 内去掉「主模型 + LOD 低模」各发一次造成的重复。 */
    private static final Map<UUID, Integer> LAST_CASING_TICK = new HashMap<>();

    /**
     * 客户端发来的精确生成请求（第一人称模型计算出的世界坐标）。
     */
    public static void spawnCasingFromClient(ServerPlayer player, ResourceLocation gunId, Vec3 worldPos) {
        if (!ModConfigs.COMMON.enableCasingDrop.get()) {
            return;
        }
        if (gunId == null || ModConfigs.COMMON.casingDropBlacklist.get().contains(gunId.toString())) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }

        // 换弹掉壳去重：服务端已通过 dropCasings 掉过这把枪的壳，忽略窗口内客户端的重复退壳包。
        ReloadCasingMark mark = RELOAD_CASING_MARKS.get(player.getUUID());
        if (mark != null && player.tickCount - mark.tick() <= RELOAD_CASING_WINDOW_TICKS && mark.gunId().equals(gunId)) {
            return;
        }

        // 开火去重：主模型与 LOD 低模在同一渲染帧各发一次包，同一 tick 内只生成第一个。
        int tick = player.tickCount;
        Integer lastTick = LAST_CASING_TICK.get(player.getUUID());
        if (lastTick != null && lastTick == tick) {
            return;
        }
        LAST_CASING_TICK.put(player.getUUID(), tick);

        ResourceLocation ammoId = TimelessAPI.getCommonGunIndex(gunId)
                .map(index -> index.getGunData().getAmmoId())
                .orElse(null);
        if (ammoId == null) {
            return;
        }
        Vec3 adjusted = worldPos.subtract(player.getLookAngle().scale(BACKWARD_OFFSET));
        spawnCasingAt(level, player, ammoId, adjusted);
    }

    /**
     * 掉落 count 个弹壳（用于换弹掉壳等服务端触发场景，位置用枪类型近似）。
     */
    public static void dropCasings(ServerLevel level, LivingEntity shooter, ResourceLocation gunId, int count) {
        ResourceLocation ammoId = TimelessAPI.getCommonGunIndex(gunId)
                .map(index -> index.getGunData().getAmmoId())
                .orElse(null);
        if (ammoId == null) {
            return;
        }
        String gunType = TimelessAPI.getCommonGunIndex(gunId)
                .map(index -> index.getPojo().getType())
                .orElse("");
        Vec3 look = shooter.getLookAngle();
        Vec3 right = new Vec3(-look.z, 0.0D, look.x).normalize();
        CasingOffset offset = OFFSET_BY_TYPE.getOrDefault(gunType, DEFAULT_OFFSET);
        Vec3 pos = shooter.position()
                .add(0.0D, offset.height(), 0.0D)
                .add(right.scale(offset.right()))
                .add(look.scale(offset.forward()));

        for (int i = 0; i < count; i++) {
            spawnCasingAt(level, shooter, ammoId, pos);
        }

        // 记录去重标记：窗口内同一把枪的客户端换弹退壳包不再重复生成。
        RELOAD_CASING_MARKS.put(shooter.getUUID(), new ReloadCasingMark(shooter.tickCount, gunId));
    }

    /**
     * 在指定世界位置生成一个弹壳实体，并施加「向右 + 向上」的初速度。
     */
    private static void spawnCasingAt(ServerLevel level, LivingEntity shooter, ResourceLocation ammoId, Vec3 pos) {
        // 限制弹壳最大数量：超出时移除最早的一个。
        int max = ModConfigs.COMMON.maxCasingCount.get();
        List<CasingEntity> existing = level.getEntitiesOfClass(CasingEntity.class,
                new AABB(pos.x, pos.y, pos.z, pos.x, pos.y, pos.z).inflate(256.0D));
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

        CasingEntity casing = new CasingEntity(ModEntities.CASING.get(), level);
        casing.setPos(pos.x, pos.y, pos.z);
        casing.setAmmoId(ammoId);

        // 初速度对标原版 shell 的 initial_velocity [5,2,1]（块/秒）≈ [0.25,0.10,0.05]（块/tick），
        // 方向为「玩家右侧 + 向上 + 前方」，并叠加玩家的当前速度（移动时抛出的弹壳带有玩家动量）。
        Vec3 look = shooter.getLookAngle();
        Vec3 right = new Vec3(-look.z, 0.0D, look.x).normalize();
        Vec3 playerVelocity = shooter.getDeltaMovement();
        double rightSpeed = 0.25D + (level.random.nextDouble() - 0.5D) * 0.10D;
        double upSpeed = 0.10D + (level.random.nextDouble() - 0.5D) * 0.10D;
        double forwardSpeed = 0.05D + (level.random.nextDouble() - 0.5D) * 0.05D;
        casing.setDeltaMovement(
                right.x * rightSpeed + look.x * forwardSpeed + playerVelocity.x,
                upSpeed + playerVelocity.y,
                right.z * rightSpeed + look.z * forwardSpeed + playerVelocity.z);

        level.addFreshEntity(casing);
    }
}
