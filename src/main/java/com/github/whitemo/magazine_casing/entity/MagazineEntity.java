package com.github.whitemo.magazine_casing.entity;

import com.github.whitemo.magazine_casing.ModConfigs;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;

/**
 * 空仓换弹时掉落的弹匣实体。物理行为由 {@link AbstractDroppedEntity} 提供。
 */
public class MagazineEntity extends AbstractDroppedEntity {

    private static final EntityDataAccessor<String> GUN_ID =
            SynchedEntityData.defineId(MagazineEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DISPLAY_ID =
            SynchedEntityData.defineId(MagazineEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> MAGAZINE_LEVEL =
            SynchedEntityData.defineId(MagazineEntity.class, EntityDataSerializers.INT);

    private static final double GRAVITY = 0.045D;
    private static final double BOUNCE_XZ = 0.42D;
    private static final double BOUNCE_Y = 0.46D;
    private static final double BOUNCE_SCALE = 0.46D;
    private static final double AIR_DRAG = 0.985D;
    private static final double GROUND_FRICTION = 0.78D;
    private static final double REST_SPEED_SQR = 0.00018D;
    private static final double BOUNCE_LOSS = 0.04D;

    public MagazineEntity(EntityType<? extends MagazineEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(GUN_ID, "");
        this.entityData.define(DISPLAY_ID, "");
        this.entityData.define(MAGAZINE_LEVEL, 0);
    }

    @Nullable
    public ResourceLocation getGunId() {
        return parseResource(this.entityData.get(GUN_ID));
    }

    public void setGunId(@Nullable ResourceLocation gunId) {
        this.entityData.set(GUN_ID, gunId == null ? "" : gunId.toString());
    }

    @Nullable
    public ResourceLocation getDisplayId() {
        return parseResource(this.entityData.get(DISPLAY_ID));
    }

    public void setDisplayId(@Nullable ResourceLocation displayId) {
        this.entityData.set(DISPLAY_ID, displayId == null ? "" : displayId.toString());
    }

    public int getMagazineLevel() {
        return this.entityData.get(MAGAZINE_LEVEL);
    }

    public void setMagazineLevel(int level) {
        this.entityData.set(MAGAZINE_LEVEL, level);
    }

    @Override
    protected float spinXRange() {
        return 6.0F;
    }

    @Override
    protected float spinYRange() {
        return 5.0F;
    }

    @Override
    protected float spinZRange() {
        return 4.0F;
    }

    @Override
    protected double gravity() {
        return GRAVITY;
    }

    @Override
    protected double bounceXz() {
        return BOUNCE_XZ;
    }

    @Override
    protected double bounceY() {
        return BOUNCE_Y;
    }

    @Override
    protected double bounceScale() {
        return BOUNCE_SCALE;
    }

    @Override
    protected double airDrag() {
        return AIR_DRAG;
    }

    @Override
    protected double groundFriction() {
        return GROUND_FRICTION;
    }

    @Override
    protected double restSpeedSqr() {
        return REST_SPEED_SQR;
    }

    @Override
    protected double bounceLoss() {
        return BOUNCE_LOSS;
    }

    @Override
    protected int despawnTicks() {
        return ModConfigs.COMMON.magazineDespawnTicks.get();
    }

    @Override
    protected void writeExtraData(CompoundTag tag) {
        tag.putString("GunId", this.entityData.get(GUN_ID));
        tag.putString("DisplayId", this.entityData.get(DISPLAY_ID));
        tag.putInt("MagazineLevel", this.entityData.get(MAGAZINE_LEVEL));
    }

    @Override
    protected void readExtraData(CompoundTag tag) {
        if (tag.contains("GunId")) {
            this.entityData.set(GUN_ID, tag.getString("GunId"));
        }
        if (tag.contains("DisplayId")) {
            this.entityData.set(DISPLAY_ID, tag.getString("DisplayId"));
        }
        if (tag.contains("MagazineLevel")) {
            this.entityData.set(MAGAZINE_LEVEL, tag.getInt("MagazineLevel"));
        }
    }
}