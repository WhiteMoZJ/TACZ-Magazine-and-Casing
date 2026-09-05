package com.github.whitemo.magazine_casing.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
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
 * 掉落物理实体基类（弹匣/弹壳共用）。封装了重力、落地反弹、摩擦、停稳与
 * 自旋，以及生成数据与存档的公共逻辑。各子类通过抽象方法提供各自的物理参数
 * 与数据字段。
 */
public abstract class AbstractDroppedEntity extends Entity implements IEntityAdditionalSpawnData {

    private float spinX;
    private float spinY;
    private float spinZ;
    private float roll;
    private float rollO;

    protected AbstractDroppedEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.spinX = (level.random.nextFloat() * 2.0F - 1.0F) * spinXRange();
        this.spinY = (level.random.nextFloat() * 2.0F - 1.0F) * spinYRange();
        this.spinZ = (level.random.nextFloat() * 2.0F - 1.0F) * spinZRange();
        this.setYRot(level.random.nextFloat() * 360.0F);
        this.setXRot(level.random.nextFloat() * 360.0F);
        this.roll = level.random.nextFloat() * 360.0F;
        this.rollO = this.roll;
    }

    // ---- 自旋幅度（度/tick，实际值为 ±range） ----
    protected abstract float spinXRange();

    protected abstract float spinYRange();

    protected abstract float spinZRange();

    // ---- 物理参数（子类覆盖） ----
    protected abstract double gravity();

    protected abstract double bounceXz();

    protected abstract double bounceY();

    protected abstract double bounceScale();

    protected abstract double airDrag();

    protected abstract double groundFriction();

    protected abstract double restSpeedSqr();

    protected abstract double bounceLoss();

    /** 服务端存活 tick 数，超过则消失。 */
    protected abstract int despawnTicks();

    // ---- 子类数据存档钩子 ----
    protected abstract void writeExtraData(CompoundTag tag);

    protected abstract void readExtraData(CompoundTag tag);

    public float getRenderRoll(float partialTick) {
        return Mth.lerp(partialTick, this.rollO, this.roll);
    }

    @Override
    public void tick() {
        super.tick();

        if (!this.level().isClientSide() && this.tickCount >= despawnTicks()) {
            this.discard();
            return;
        }

        this.xRotO = this.getXRot();
        this.yRotO = this.getYRot();
        this.rollO = this.roll;

        Vec3 velocity = this.getDeltaMovement();
        if (!this.onGround()) {
            velocity = velocity.add(0.0D, -gravity(), 0.0D);
        }

        this.move(MoverType.SELF, velocity);

        if (this.horizontalCollision || this.verticalCollision) {
            velocity = bounce(velocity);
        } else {
            velocity = velocity.scale(airDrag());
        }

        if (this.onGround()) {
            velocity = velocity.multiply(groundFriction(), 1.0D, groundFriction());
            if (velocity.lengthSqr() < restSpeedSqr()) {
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
        double x = this.horizontalCollision ? -velocity.x * bounceXz() : velocity.x;
        double y = this.verticalCollision ? -velocity.y * bounceY() : velocity.y;
        double z = this.horizontalCollision ? -velocity.z * bounceXz() : velocity.z;
        Vec3 bounced = new Vec3(x, y, z).scale(bounceScale());

        double speed = bounced.length();
        if (speed <= bounceLoss()) {
            return Vec3.ZERO;
        }
        return bounced.scale((speed - bounceLoss()) / speed);
    }

    @Override
    protected final void addAdditionalSaveData(CompoundTag tag) {
        tag.putFloat("SpinX", this.spinX);
        tag.putFloat("SpinY", this.spinY);
        tag.putFloat("SpinZ", this.spinZ);
        tag.putFloat("Roll", this.roll);
        writeExtraData(tag);
    }

    @Override
    protected final void readAdditionalSaveData(CompoundTag tag) {
        this.spinX = tag.getFloat("SpinX");
        this.spinY = tag.getFloat("SpinY");
        this.spinZ = tag.getFloat("SpinZ");
        this.roll = tag.getFloat("Roll");
        this.rollO = this.roll;
        readExtraData(tag);
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

    @Nullable
    protected static ResourceLocation parseResource(@Nullable String value) {
        return value == null || value.isEmpty() ? null : ResourceLocation.tryParse(value);
    }
}