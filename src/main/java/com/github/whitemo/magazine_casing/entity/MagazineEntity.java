package com.github.whitemo.magazine_casing.entity;

import com.github.whitemo.magazine_casing.ModConfigs;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.entity.IEntityAdditionalSpawnData;
import net.minecraftforge.network.NetworkHooks;

import javax.annotation.Nullable;

/**
 * A magazine ejected during an empty reload. It uses a fixed collision box and
 * physical settling (gravity / bounce / friction / spin), mirroring how the
 * TaCZ Tactical Breaching addon drops shell casings. The gun id and display id
 * let the client render the correct magazine bone of the gun's bedrock model.
 */
public class MagazineEntity extends Entity implements IEntityAdditionalSpawnData {

    private static final EntityDataAccessor<String> GUN_ID =
            SynchedEntityData.defineId(MagazineEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DISPLAY_ID =
            SynchedEntityData.defineId(MagazineEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> MAGAZINE_LEVEL =
            SynchedEntityData.defineId(MagazineEntity.class, EntityDataSerializers.INT);

    private static final double GRAVITY = 0.045D;
    private static final double REST_SPEED_SQR = 0.00018D;
    private static final double BOUNCE_XZ = 0.42D;
    private static final double BOUNCE_Y = 0.46D;
    private static final double AIR_DRAG = 0.985D;
    private static final double GROUND_FRICTION = 0.78D;
    private static final double BOUNCE_LOSS = 0.04D;

    private float spinX;
    private float spinY;
    private float spinZ;
    private float roll;
    private float rollO;

    public MagazineEntity(EntityType<? extends MagazineEntity> type, Level level) {
        super(type, level);
        this.spinX = level.random.nextFloat() * 12.0F - 6.0F;
        this.spinY = level.random.nextFloat() * 10.0F - 5.0F;
        this.spinZ = level.random.nextFloat() * 8.0F - 4.0F;
        this.setYRot(level.random.nextFloat() * 360.0F);
        this.setXRot(level.random.nextFloat() * 360.0F);
        this.roll = level.random.nextFloat() * 360.0F;
        this.rollO = this.roll;
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(GUN_ID, "");
        this.entityData.define(DISPLAY_ID, "");
        this.entityData.define(MAGAZINE_LEVEL, 0);
    }

    @Nullable
    public ResourceLocation getGunId() {
        return parse(this.entityData.get(GUN_ID));
    }

    public void setGunId(ResourceLocation gunId) {
        this.entityData.set(GUN_ID, gunId == null ? "" : gunId.toString());
    }

    @Nullable
    public ResourceLocation getDisplayId() {
        return parse(this.entityData.get(DISPLAY_ID));
    }

    public void setDisplayId(ResourceLocation displayId) {
        this.entityData.set(DISPLAY_ID, displayId == null ? "" : displayId.toString());
    }

    public int getMagazineLevel() {
        return this.entityData.get(MAGAZINE_LEVEL);
    }

    public void setMagazineLevel(int level) {
        this.entityData.set(MAGAZINE_LEVEL, level);
    }

    public float getRenderRoll(float partialTick) {
        return Mth.lerp(partialTick, this.rollO, this.roll);
    }

    @Nullable
    private static ResourceLocation parse(String value) {
        return value == null || value.isEmpty() ? null : ResourceLocation.tryParse(value);
    }

    @Override
    public void tick() {
        super.tick();

        if (!this.level().isClientSide() && this.tickCount >= ModConfigs.SERVER.magazineDespawnTicks.get()) {
            this.discard();
            return;
        }

        this.xRotO = this.getXRot();
        this.yRotO = this.getYRot();
        this.rollO = this.roll;

        Vec3 velocity = this.getDeltaMovement();
        if (!this.onGround()) {
            velocity = velocity.add(0.0D, -GRAVITY, 0.0D);
        }

        this.move(MoverType.SELF, velocity);

        if (this.horizontalCollision || this.verticalCollision) {
            velocity = bounce(velocity);
        } else {
            velocity = velocity.scale(AIR_DRAG);
        }

        if (this.onGround()) {
            velocity = velocity.multiply(GROUND_FRICTION, 1.0D, GROUND_FRICTION);
            if (velocity.lengthSqr() < REST_SPEED_SQR) {
                velocity = Vec3.ZERO;
                this.spinX *= 0.6F;
                this.spinY *= 0.6F;
                this.spinZ *= 0.6F;
            }
        }

        this.setDeltaMovement(velocity);

        if (velocity != Vec3.ZERO) {
            this.setXRot(Mth.wrapDegrees(this.getXRot() + this.spinX));
            this.setYRot(Mth.wrapDegrees(this.getYRot() + this.spinY));
            this.roll = Mth.wrapDegrees(this.roll + this.spinZ);
        }
    }

    private Vec3 bounce(Vec3 velocity) {
        double x = this.horizontalCollision ? -velocity.x * BOUNCE_XZ : velocity.x;
        double y = this.verticalCollision ? -velocity.y * BOUNCE_Y : velocity.y;
        double z = this.horizontalCollision ? -velocity.z * BOUNCE_XZ : velocity.z;
        Vec3 bounced = new Vec3(x, y, z).scale(0.46D);

        double speed = bounced.length();
        if (speed <= BOUNCE_LOSS) {
            return Vec3.ZERO;
        }
        return bounced.scale((speed - BOUNCE_LOSS) / speed);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putString("GunId", this.entityData.get(GUN_ID));
        tag.putString("DisplayId", this.entityData.get(DISPLAY_ID));
        tag.putInt("MagazineLevel", this.entityData.get(MAGAZINE_LEVEL));
        tag.putFloat("SpinX", this.spinX);
        tag.putFloat("SpinY", this.spinY);
        tag.putFloat("SpinZ", this.spinZ);
        tag.putFloat("Roll", this.roll);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("GunId")) {
            this.entityData.set(GUN_ID, tag.getString("GunId"));
        }
        if (tag.contains("DisplayId")) {
            this.entityData.set(DISPLAY_ID, tag.getString("DisplayId"));
        }
        if (tag.contains("MagazineLevel")) {
            this.entityData.set(MAGAZINE_LEVEL, tag.getInt("MagazineLevel"));
        }
        this.spinX = tag.getFloat("SpinX");
        this.spinY = tag.getFloat("SpinY");
        this.spinZ = tag.getFloat("SpinZ");
        this.roll = tag.getFloat("Roll");
        this.rollO = this.roll;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public void writeSpawnData(FriendlyByteBuf buffer) {
        buffer.writeFloat(this.spinX);
        buffer.writeFloat(this.spinY);
        buffer.writeFloat(this.spinZ);
        buffer.writeFloat(this.roll);
    }

    @Override
    public void readSpawnData(FriendlyByteBuf buffer) {
        this.spinX = buffer.readFloat();
        this.spinY = buffer.readFloat();
        this.spinZ = buffer.readFloat();
        this.roll = buffer.readFloat();
        this.rollO = this.roll;
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}