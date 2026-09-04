package com.github.whitemo.magazine_casing.mixin;

import com.tacz.guns.client.model.BedrockGunModel;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes TACZ's protected {@code magazineNode} so a dropped magazine entity
 * can render only the magazine bone of the gun's bedrock model.
 */
@Mixin(value = BedrockGunModel.class, remap = false)
public interface BedrockGunModelAccessor {

    @Accessor("magazineNode")
    BedrockPart magazineCasing$getMagazineNode();
}