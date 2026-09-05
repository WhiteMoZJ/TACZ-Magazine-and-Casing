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
 * 开火时弹射出的弹壳实体。物理行为由 {@link AbstractDroppedEntity} 提供，
 * 弹跳衰减比弹匣更小（更易弹跳）。
 */
public class CasingEntity extends AbstractDroppedEntity {

    private static final EntityDataAccessor<String> AMMO_ID =
            SynchedEntityData.defineId(CasingEntity.class, EntityDataSerializers.STRING);

    private static final double GRAVITY = 0.045D;
    private static final double BOUNCE_XZ = 0.5D;
    private static final double BOUNCE_Y = 0.55D;
    private static final double BOUNCE_SCALE = 0.65D;
    private static final double AIR_DRAG = 0.985D;
    private static final double GROUND_FRICTION = 0.78D;
    private static final double REST_SPEED_SQR = 0.00018D;
    private static final double BOUNCE_LOSS = 0.015D;

    public CasingEntity(EntityType<? extends CasingEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(AMMO_ID, "");
    }

    @Nullable
    public ResourceLocation getAmmoId() {
        return parseResource(this.entityData.get(AMMO_ID));
    }

    public void setAmmoId(@Nullable ResourceLocation ammoId) {
        this.entityData.set(AMMO_ID, ammoId == null ? "" : ammoId.toString());
    }

    @Override
    protected float spinXRange() {
        return 20.0F;
    }

    @Override
    protected float spinYRange() {
        return 60.0F;
    }

    @Override
    protected float spinZRange() {
        return 5.0F;
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
        return ModConfigs.SERVER.casingDespawnTicks.get();
    }

    @Override
    protected void writeExtraData(CompoundTag tag) {
        tag.putString("AmmoId", this.entityData.get(AMMO_ID));
    }

    @Override
    protected void readExtraData(CompoundTag tag) {
        if (tag.contains("AmmoId")) {
            this.entityData.set(AMMO_ID, tag.getString("AmmoId"));
        }
    }
}