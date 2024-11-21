/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2024 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */

package net.ccbluex.liquidbounce.injection.mixins.minecraft.entity;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.ccbluex.liquidbounce.config.NoneChoice;
import net.ccbluex.liquidbounce.event.EventManager;
import net.ccbluex.liquidbounce.event.events.PacketEvent;
import net.ccbluex.liquidbounce.event.events.PlayerAfterJumpEvent;
import net.ccbluex.liquidbounce.event.events.PlayerJumpEvent;
import net.ccbluex.liquidbounce.event.events.TransferOrigin;
import net.ccbluex.liquidbounce.features.command.commands.client.fakeplayer.FakePlayer;
import net.ccbluex.liquidbounce.features.module.modules.movement.*;
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleAntiBlind;
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleRotations;
import net.ccbluex.liquidbounce.features.module.modules.world.scaffold.ModuleScaffold;
import net.ccbluex.liquidbounce.utils.aiming.RotationManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class MixinLivingEntity extends MixinEntity {

    @Shadow
    public boolean jumping;

    @Shadow
    public int jumpingCooldown;

    @Shadow
    public abstract float getJumpVelocity();

    @Shadow
    protected abstract void jump();

    @Shadow
    public abstract boolean hasStatusEffect(RegistryEntry<StatusEffect> effect);

    @Shadow
    public abstract void tick();

    @Shadow public abstract void swingHand(Hand hand, boolean fromServerPlayer);

    @Shadow
    public abstract void setHealth(float health);

    @Shadow
    public abstract boolean addStatusEffect(StatusEffectInstance effect);

    @Shadow
    public abstract boolean isFallFlying();


    /**
     * Hook anti levitation module
     */
    @Redirect(method = "travel", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;hasStatusEffect(Lnet/minecraft/registry/entry/RegistryEntry;)Z"))
    public boolean hookTravelStatusEffect(LivingEntity instance, RegistryEntry<StatusEffect> effect) {
        if ((effect == StatusEffects.LEVITATION || effect == StatusEffects.SLOW_FALLING) && ModuleAntiLevitation.INSTANCE.getEnabled()) {
            if (instance.hasStatusEffect(effect)) {
                instance.fallDistance = 0f;
            }

            return false;
        }

        return instance.hasStatusEffect(effect);
    }

    @Inject(method = "hasStatusEffect", at = @At("HEAD"), cancellable = true)
    private void hookAntiNausea(RegistryEntry<StatusEffect> effect, CallbackInfoReturnable<Boolean> cir) {
        if (effect == StatusEffects.NAUSEA && ModuleAntiBlind.INSTANCE.getEnabled() && ModuleAntiBlind.INSTANCE.getAntiNausea()) {
            cir.setReturnValue(false);
            cir.cancel();
        }
    }

    @ModifyExpressionValue(method = "jump", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;getJumpVelocity()F"))
    private float hookJumpEvent(float original) {
        if (((Object) this) != MinecraftClient.getInstance().player) {
            return original;
        }

        final var jumpEvent = EventManager.INSTANCE.callEvent(new PlayerJumpEvent(original));
        return jumpEvent.getMotion();
    }

    @Inject(method = "jump", at = @At("RETURN"))
    private void hookAfterJumpEvent(CallbackInfo ci) {
        if ((Object) this != MinecraftClient.getInstance().player) {
            return;
        }

        EventManager.INSTANCE.callEvent(new PlayerAfterJumpEvent());
    }

    /**
     * Hook velocity rotation modification
     * <p>
     * Jump according to modified rotation. Prevents detection by movement sensitive anticheats.
     */
    @ModifyExpressionValue(method = "jump", at = @At(value = "NEW", target = "(DDD)Lnet/minecraft/util/math/Vec3d;"))
    private Vec3d hookFixRotation(Vec3d original) {
        var rotationManager = RotationManager.INSTANCE;
        var rotation = rotationManager.getCurrentRotation();
        var configurable = rotationManager.getWorkingAimPlan();

        if ((Object) this != MinecraftClient.getInstance().player) {
            return original;
        }

        if (configurable == null || !configurable.getApplyVelocityFix() || rotation == null) {
            return original;
        }

        float yaw = rotation.getYaw() * 0.017453292F;

        return new Vec3d(-MathHelper.sin(yaw) * 0.2F, 0.0, MathHelper.cos(yaw) * 0.2F);
    }

    @Inject(method = "pushAwayFrom", at = @At("HEAD"), cancellable = true)
    private void hookNoPush(CallbackInfo callbackInfo) {
        if (ModuleNoPush.INSTANCE.getEnabled()) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "tickMovement", at = @At("HEAD"))
    private void hookTickMovement(CallbackInfo callbackInfo) {
        // We don't want NoJumpDelay to interfere with AirJump which would lead to a Jetpack-like behavior
        var noJumpDelay = ModuleNoJumpDelay.INSTANCE.getEnabled() && !ModuleAirJump.INSTANCE.getAllowJump();

        // The jumping cooldown would lead to very slow tower building
        var towerActive = ModuleScaffold.INSTANCE.getEnabled() && !(ModuleScaffold.INSTANCE.getTowerMode()
                .getActiveChoice() instanceof NoneChoice) && ModuleScaffold.INSTANCE.getTowerMode()
                .getActiveChoice().isActive();

        if (noJumpDelay || towerActive) {
            jumpingCooldown = 0;
        }
    }

    @Inject(method = "tickMovement", at = @At(value = "FIELD", target = "Lnet/minecraft/entity/LivingEntity;jumping:Z"))
    private void hookAirJump(CallbackInfo callbackInfo) {
        if (ModuleAirJump.INSTANCE.getAllowJump() && jumping && jumpingCooldown == 0) {
            this.jump();
            jumpingCooldown = 10;
        }
    }

    @Unique
    private boolean previousElytra = false;

    @Inject(method = "tickFallFlying", at = @At("TAIL"))
    public void recastIfLanded(CallbackInfo callbackInfo) {
        if ((Object) this != MinecraftClient.getInstance().player) {
            return;
        }
        boolean elytra = isFallFlying();
        if (ModuleElytraRecast.INSTANCE.getEnabled() &&  previousElytra && !elytra) {
            MinecraftClient.getInstance().getSoundManager().stopSounds(SoundEvents.ITEM_ELYTRA_FLYING.getId(),
                    SoundCategory.PLAYERS);
            ModuleElytraRecast.INSTANCE.recastElytra((ClientPlayerEntity) (Object) this);
            jumpingCooldown = 0;
        }
        previousElytra = elytra;
    }

    /**
     * Body rotation yaw injection hook
     */
    @ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;getYaw()F"), slice = @Slice(to = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;getYaw()F", ordinal = 1)))
    private float hookBodyRotationsA(float original) {
        if ((Object) this != MinecraftClient.getInstance().player) {
            return original;
        }

        var rotations = ModuleRotations.INSTANCE;
        var rotation = rotations.displayRotations();
        return rotations.shouldDisplayRotations() && rotations.getBodyParts().getBody() ? rotation.getYaw() : original;
    }

    /**
     * Body rotation yaw injection hook
     */
    @ModifyExpressionValue(method = "turnHead", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;getYaw()F"))
    private float hookBodyRotationsB(float original) {
        if ((Object) this != MinecraftClient.getInstance().player) {
            return original;
        }

        var rotations = ModuleRotations.INSTANCE;
        var rotation = rotations.displayRotations();

        return rotations.shouldDisplayRotations() && rotations.getBodyParts().getBody() ? rotation.getYaw() : original;
    }

    /**
     * Fall flying using modified-rotation
     */
    @ModifyExpressionValue(method = "travel", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;getPitch()F"))
    private float hookModifyFallFlyingPitch(float original) {
        if ((Object) this != MinecraftClient.getInstance().player) {
            return original;
        }

        var rotationManager = RotationManager.INSTANCE;
        var rotation = rotationManager.getCurrentRotation();
        var configurable = rotationManager.getWorkingAimPlan();

        if (rotation == null || configurable == null || !configurable.getApplyVelocityFix() || configurable.getChangeLook()) {
            return original;
        }

        return rotation.getPitch();
    }

    /**
     * Fall flying using modified-rotation
     */
    @ModifyExpressionValue(method = "travel", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;getRotationVector()Lnet/minecraft/util/math/Vec3d;"))
    private Vec3d hookModifyFallFlyingRotationVector(Vec3d original) {
        if ((Object) this != MinecraftClient.getInstance().player) {
            return original;
        }

        var rotationManager = RotationManager.INSTANCE;
        var rotation = rotationManager.getCurrentRotation();
        var configurable = rotationManager.getWorkingAimPlan();

        if (rotation == null || configurable == null || !configurable.getApplyVelocityFix() || configurable.getChangeLook()) {
            return original;
        }

        return rotation.getRotationVec();
    }

    /**
     * Allows instances of {@link FakePlayer} to pop infinite totems and
     * bypass {@link net.minecraft.registry.tag.DamageTypeTags.BYPASSES_INVULNERABILITY}
     * damage sources.
     */
    @SuppressWarnings({"JavadocReference", "UnreachableCode"})
    @Inject(method = "tryUseTotem", at = @At(value = "HEAD"), cancellable = true)
    private void hookTryUseTotem(DamageSource source, CallbackInfoReturnable<Boolean> cir) {
        if (LivingEntity.class.cast(this) instanceof FakePlayer) {
            addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 900, 1));
            addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 100, 1));
            addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 800, 0));
            setHealth(1.0F);

            EntityStatusS2CPacket packet = new EntityStatusS2CPacket(LivingEntity.class.cast(this), (byte) 35);
            PacketEvent event = new PacketEvent(TransferOrigin.RECEIVE, packet, true);
            EventManager.INSTANCE.callEvent(event);
            if (!event.isCancelled()) {
                packet.apply(MinecraftClient.getInstance().getNetworkHandler());
            }

            cir.setReturnValue(true);
        }
    }

    /**
     * Allows instances of {@link FakePlayer} to get attacked.
     */
    @SuppressWarnings("ConstantValue")
    @Redirect(method = "damage", at = @At(value = "FIELD", target = "Lnet/minecraft/world/World;isClient:Z", ordinal = 0))
    private boolean hookDamage(World world) {
        return !(LivingEntity.class.cast(this) instanceof FakePlayer) && world.isClient;
    }

}
