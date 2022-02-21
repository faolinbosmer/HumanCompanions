package com.github.justinwon777.humancompanions.entity;

import com.github.justinwon777.humancompanions.container.CompanionContainer;
import com.github.justinwon777.humancompanions.core.EntityInit;
import com.github.justinwon777.humancompanions.core.PacketHandler;
import com.github.justinwon777.humancompanions.networking.OpenInventoryPacket;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.*;
import net.minecraft.entity.ai.attributes.AttributeModifierMap;
import net.minecraft.entity.ai.attributes.Attributes;
import net.minecraft.entity.ai.goal.*;
import net.minecraft.entity.monster.CreeperEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.entity.projectile.AbstractArrowEntity;
import net.minecraft.entity.projectile.ProjectileHelper;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.*;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.pathfinding.GroundPathNavigator;
import net.minecraft.util.*;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.IServerWorld;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.fml.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;


public class HumanCompanionEntity extends TameableEntity implements IRangedAttackMob{

    private static final DataParameter<Integer> DATA_TYPE_ID = EntityDataManager.defineId(HumanCompanionEntity.class, DataSerializers.INT);
    private static final DataParameter<Boolean> EATING = EntityDataManager.defineId(HumanCompanionEntity.class,
            DataSerializers.BOOLEAN);
    private static final DataParameter<Boolean> ALERT = EntityDataManager.defineId(HumanCompanionEntity.class,
            DataSerializers.BOOLEAN);
    private static final DataParameter<Boolean> HUNTING = EntityDataManager.defineId(HumanCompanionEntity.class,
            DataSerializers.BOOLEAN);
    private static final DataParameter<String> COMPANION_TYPE = EntityDataManager.defineId(HumanCompanionEntity.class,
            DataSerializers.STRING);
    public Inventory inventory = new Inventory(27);
    public EquipmentSlotType[] armorTypes = new EquipmentSlotType[]{EquipmentSlotType.FEET, EquipmentSlotType.LEGS,
            EquipmentSlotType.CHEST, EquipmentSlotType.HEAD};
    public List<NearestAttackableTargetGoal> alertMobGoals = new ArrayList<>();
    public List<NearestAttackableTargetGoal> huntMobGoals = new ArrayList<>();

    public HumanCompanionEntity(EntityType<? extends TameableEntity> entityType, World level) {
        super(entityType, level);
        this.setTame(false);
        ((GroundPathNavigator)this.getNavigation()).setCanOpenDoors(true);
        this.getNavigation().setCanFloat(true);
        for (int i = 0; i < CompanionData.alertMobs.length; i++) {
            alertMobGoals.add(new NearestAttackableTargetGoal(this, CompanionData.alertMobs[i], false));
        }
        for (int i = 0; i < CompanionData.huntMobs.length; i++) {
            huntMobGoals.add(new NearestAttackableTargetGoal(this, CompanionData.huntMobs[i], false));
        }
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new SwimGoal(this));
        this.goalSelector.addGoal(0, new EatGoal(this));
        this.goalSelector.addGoal(1, new SitGoal(this));
        this.goalSelector.addGoal(2, new AvoidCreeperGoal(this, CreeperEntity.class, 10.0F, 1.5D, 1.5D));
        this.goalSelector.addGoal(3, new ArcherBowAttackGoal<>(this, 1.0D, 5, 15.0F));
        this.goalSelector.addGoal(3, new KnightMeleeAttackGoal(this, 1.0D, true));
        this.goalSelector.addGoal(3, new FollowOwnerGoal(this, 1.3D, 8.0F, 2.0F, false));
        this.goalSelector.addGoal(5, new WaterAvoidingRandomWalkingGoal(this, 1.0D));
        this.goalSelector.addGoal(6, new LookAtGoal(this, PlayerEntity.class, 6.0F));
        this.goalSelector.addGoal(7, new LookRandomlyGoal(this));
        this.goalSelector.addGoal(8, new OpenDoorGoal(this, true));
        this.goalSelector.addGoal(9, new LowHealthGoal(this));
        this.targetSelector.addGoal(1, new CustomOwnerHurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new CustomOwnerHurtTargetGoal(this));
        this.targetSelector.addGoal(3, (new HurtByTargetGoal(this)).setAlertOthers());
    }

    public static AttributeModifierMap.MutableAttribute createAttributes() {
        return MobEntity.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.ATTACK_DAMAGE, 1.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.32D);
    }

    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_TYPE_ID, 1);
        this.entityData.define(EATING, false);
        this.entityData.define(ALERT, false);
        this.entityData.define(HUNTING, true);
        this.entityData.define(COMPANION_TYPE, "none");
    }

    public ILivingEntityData finalizeSpawn(IServerWorld worldIn, DifficultyInstance difficultyIn,
                                           SpawnReason reason, @Nullable ILivingEntityData spawnDataIn,
                                           @Nullable CompoundNBT dataTag) {
        this.setCompanionSkin(this.random.nextInt(CompanionData.maleSkins.length));
        this.setCustomName(new StringTextComponent(CompanionData.getRandomName()));
        this.setCompanionType(CompanionData.getRandomCompanionType());

        for (int i = 0; i < 4; i++) {
            EquipmentSlotType armorType = armorTypes[i];
            ItemStack itemstack = getSpawnArmor(armorType);
            if(!itemstack.isEmpty()) {
                this.inventory.setItem(i, itemstack);
            }
        }
        checkArmor();
        if (this.getCompanionType().equals("archer")) {
            this.inventory.setItem(4, Items.BOW.getDefaultInstance());
            checkBow();
        } else {
            ItemStack itemstack = getSpawnSword();
            if(!itemstack.isEmpty()) {
                this.inventory.setItem(4, itemstack);
                checkSword();
            }
        }
        return super.finalizeSpawn(worldIn, difficultyIn, reason, spawnDataIn, dataTag);
    }

    public void addAdditionalSaveData(CompoundNBT tag) {
        super.addAdditionalSaveData(tag);
        tag.put("inventory", this.inventory.createTag());
        tag.putInt("skin", this.getCompanionSkin());
        tag.putBoolean("Eating", this.isEating());
        tag.putBoolean("Alert", this.isAlert());
        tag.putBoolean("Hunting", this.isHunting());
        tag.putString("Type", this.getCompanionType());
    }

    public void readAdditionalSaveData(CompoundNBT tag) {
        super.readAdditionalSaveData(tag);
        this.setCompanionSkin(tag.getInt("skin"));
        this.setEating(tag.getBoolean("Eating"));
        this.setAlert(tag.getBoolean("Alert"));
        this.setHunting(tag.getBoolean("Hunting"));
        this.setCompanionType(tag.getString("Type"));
        if (tag.getBoolean("Alert")) {
            this.addAlertGoals();
        }
        if (tag.getBoolean("Hunting")) {
            this.addHuntingGoals();
        }
        if (tag.contains("inventory", 9)) {
            this.inventory.fromTag(tag.getList("inventory", 10));
        }
        this.setItemSlot(EquipmentSlotType.FEET, ItemStack.EMPTY);
        this.setItemSlot(EquipmentSlotType.LEGS, ItemStack.EMPTY);
        this.setItemSlot(EquipmentSlotType.CHEST, ItemStack.EMPTY);
        this.setItemSlot(EquipmentSlotType.HEAD, ItemStack.EMPTY);
        this.setItemSlot(EquipmentSlotType.MAINHAND, ItemStack.EMPTY);
        if (getCompanionType().equals("archer")) {
            checkBow();
        } else {
            checkSword();
        }
        checkArmor();
    }

    @Override
    public AgeableEntity getBreedOffspring(ServerWorld level, AgeableEntity parent) {
        return EntityInit.HumanCompanionEntity.get().create(level);
    }

    public void tick() {
        checkArmor();
        if (getCompanionType().equals("archer")) {
            checkBow();
        } else {
            checkSword();
        }
        super.tick();
    }

    public ActionResultType mobInteract(PlayerEntity player, Hand hand) {
        ItemStack itemstack = player.getItemInHand(hand);
        Item item = itemstack.getItem();
        if (!this.level.isClientSide && hand == Hand.MAIN_HAND) {
            if (!this.isTame()) {
                if (itemstack.isEdible()) {
                    itemstack.shrink(1);
                    if (this.random.nextInt(4) == 0) {
                        this.tame(player);
                        player.sendMessage(new StringTextComponent("Companion added"), this.getUUID());
                    } else {
                        player.sendMessage(new TranslationTextComponent("chat.type.text", this.getDisplayName(),
                                CompanionData.tameFail[this.random.nextInt(CompanionData.tameFail.length)]), this.getUUID());
                    }
                } else {
                    player.sendMessage(new TranslationTextComponent("chat.type.text", this.getDisplayName(),
                            CompanionData.notTamed[this.random.nextInt(CompanionData.notTamed.length)]), this.getUUID());
                }
            } else {
                if(player.isShiftKeyDown()) {
                    if (!this.isOrderedToSit()) {
                        this.setOrderedToSit(true);
                        StringTextComponent text = new StringTextComponent("I'll stay here.");
                        player.sendMessage(new TranslationTextComponent("chat.type.text", this.getDisplayName(),
                                text), this.getUUID());
                    } else {
                        this.setOrderedToSit(false);
                        StringTextComponent text = new StringTextComponent("I'll follow you.");
                        player.sendMessage(new TranslationTextComponent("chat.type.text", this.getDisplayName(),
                                text), this.getUUID());
                    }
                } else {
                    this.openGui((ServerPlayerEntity) player);
                }
                return ActionResultType.SUCCESS;
            }
            return ActionResultType.SUCCESS;
        }
        return super.mobInteract(player, hand);
    }

    public void openGui(ServerPlayerEntity player) {
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
        player.nextContainerCounter();
        PacketHandler.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), new OpenInventoryPacket(
                player.containerCounter, this.inventory.getContainerSize(), this.getId()));
        player.containerMenu = new CompanionContainer(player.containerCounter, player.inventory, this.inventory);
        player.containerMenu.addSlotListener(player);
        MinecraftForge.EVENT_BUS.post(new PlayerContainerEvent.Open(player, player.containerMenu));
    }

    public ItemStack getSpawnSword() {
        float materialFloat = this.random.nextFloat();
        if(materialFloat < 0.5F) {
            return Items.WOODEN_SWORD.getDefaultInstance();
        } else if(materialFloat < 0.90F) {
            return Items.STONE_SWORD.getDefaultInstance();
        } else {
            return Items.IRON_SWORD.getDefaultInstance();
        }
    }

    public void checkArmor() {
        ItemStack head = this.getItemBySlot(EquipmentSlotType.HEAD);
        ItemStack chest = this.getItemBySlot(EquipmentSlotType.CHEST);
        ItemStack legs = this.getItemBySlot(EquipmentSlotType.LEGS);
        ItemStack feet = this.getItemBySlot(EquipmentSlotType.FEET);
        for (int i = 0; i < this.inventory.getContainerSize(); ++i) {
            ItemStack itemstack = this.inventory.getItem(i);
            if (itemstack.getItem() instanceof ArmorItem) {
                switch (((ArmorItem) itemstack.getItem()).getSlot()) {
                    case HEAD:
                        if (head.isEmpty()) {
                            this.setItemSlot(EquipmentSlotType.HEAD, itemstack);
                        } else {
                            if (((ArmorItem) itemstack.getItem()).getDefense() > ((ArmorItem) head.getItem()).getDefense()) {
                                this.setItemSlot(EquipmentSlotType.HEAD, itemstack);
                            } else if (((ArmorItem) itemstack.getItem()).getMaterial() == ArmorMaterial.NETHERITE && ((ArmorItem) head.getItem()).getMaterial() != ArmorMaterial.NETHERITE) {
                                this.setItemSlot(EquipmentSlotType.HEAD, itemstack);
                            }
                        }
                        break;
                    case CHEST:
                        if (chest.isEmpty()) {
                            this.setItemSlot(EquipmentSlotType.CHEST, itemstack);
                        } else {
                            if (((ArmorItem) itemstack.getItem()).getDefense() > ((ArmorItem) chest.getItem()).getDefense()) {
                                this.setItemSlot(EquipmentSlotType.CHEST, itemstack);
                            } else if (((ArmorItem) itemstack.getItem()).getMaterial() == ArmorMaterial.NETHERITE && ((ArmorItem) chest.getItem()).getMaterial() != ArmorMaterial.NETHERITE) {
                                this.setItemSlot(EquipmentSlotType.CHEST, itemstack);
                            }
                        }
                        break;
                    case LEGS:
                        if (legs.isEmpty()) {
                            this.setItemSlot(EquipmentSlotType.LEGS, itemstack);
                        } else {
                            if (((ArmorItem) itemstack.getItem()).getDefense() > ((ArmorItem) legs.getItem()).getDefense()) {
                                this.setItemSlot(EquipmentSlotType.LEGS, itemstack);
                            } else if (((ArmorItem) itemstack.getItem()).getMaterial() == ArmorMaterial.NETHERITE && ((ArmorItem) legs.getItem()).getMaterial() != ArmorMaterial.NETHERITE) {
                                this.setItemSlot(EquipmentSlotType.LEGS, itemstack);
                            }
                        }
                        break;
                    case FEET:
                        if (feet.isEmpty()) {
                            this.setItemSlot(EquipmentSlotType.FEET, itemstack);
                        } else {
                            if (((ArmorItem) itemstack.getItem()).getDefense() > ((ArmorItem) feet.getItem()).getDefense()) {
                                this.setItemSlot(EquipmentSlotType.FEET, itemstack);
                            } else if (((ArmorItem) itemstack.getItem()).getMaterial() == ArmorMaterial.NETHERITE && ((ArmorItem) feet.getItem()).getMaterial() != ArmorMaterial.NETHERITE) {
                                this.setItemSlot(EquipmentSlotType.FEET, itemstack);
                            }
                        }
                        break;
                }
            }
        }
    }

    public void checkBow() {
        ItemStack hand = this.getItemBySlot(EquipmentSlotType.MAINHAND);
        for (int i = 0; i < this.inventory.getContainerSize(); ++i) {
            ItemStack itemstack = this.inventory.getItem(i);
            if (itemstack.getItem() instanceof BowItem) {
                if (hand.isEmpty()) {
                    this.setItemSlot(EquipmentSlotType.MAINHAND, itemstack);
                }
            }
        }
    }

    public void checkSword() {
        ItemStack hand = this.getItemBySlot(EquipmentSlotType.MAINHAND);
        for (int i = 0; i < this.inventory.getContainerSize(); ++i) {
            ItemStack itemstack = this.inventory.getItem(i);
            if (itemstack.getItem() instanceof SwordItem) {
                if (hand.isEmpty()) {
                    this.setItemSlot(EquipmentSlotType.MAINHAND, itemstack);
                } else if (itemstack.getItem() instanceof SwordItem && hand.getItem() instanceof SwordItem) {
                    if (((SwordItem) itemstack.getItem()).getDamage() > ((SwordItem) hand.getItem()).getDamage()) {
                        this.setItemSlot(EquipmentSlotType.MAINHAND, itemstack);
                    }
                }
            }
        }
    }

    public boolean hurt(DamageSource p_34288_, float p_34289_) {
        if (p_34288_.getEntity() instanceof TameableEntity) {
            if (this.isTame() && ((TameableEntity) p_34288_.getEntity()).isTame()) {
                if (this.getOwner().is(((TameableEntity) p_34288_.getEntity()).getOwner())) {
                    return false;
                }
            }
        }
        hurtArmor(p_34288_, p_34289_);
        return super.hurt(p_34288_, p_34289_);
    }

    public void hurtArmor(DamageSource p_150073_, float p_150074_) {
        if (!(p_150074_ <= 0.0F)) {
            p_150074_ /= 4.0F;
            if (p_150074_ < 1.0F) {
                p_150074_ = 1.0F;
            }

            for(ItemStack itemstack : this.getArmorSlots()) {
                if ((!p_150073_.isFire() || !itemstack.getItem().isFireResistant()) && itemstack.getItem() instanceof ArmorItem) {
                    itemstack.hurtAndBreak((int)p_150074_, this, (p_35997_) -> {
                        p_35997_.broadcastBreakEvent(((ArmorItem) itemstack.getItem()).getSlot());
                    });
                }
            }

        }
    }

    public void die(DamageSource source) {
        super.die(source);
    }

    protected void dropEquipment() {
        for (int i = 0; i < this.inventory.getContainerSize(); ++i) {
            ItemStack itemstack = this.inventory.getItem(i);
            if (!itemstack.isEmpty() && !EnchantmentHelper.hasVanishingCurse(itemstack)) {
                this.spawnAtLocation(itemstack);
            }
        }
    }

    public boolean doHurtTarget(Entity entity) {
        ItemStack itemstack = this.getMainHandItem();
        System.out.println(itemstack);
        if (!this.level.isClientSide && !itemstack.isEmpty() && entity instanceof LivingEntity) {
            itemstack.hurtAndBreak(1, this, (p_43296_) -> {
                p_43296_.broadcastBreakEvent(EquipmentSlotType.MAINHAND);
            });
            if (this.getMainHandItem().isEmpty()) {
                StringTextComponent broken = new StringTextComponent("My sword broke!");
                if (this.isTame()) {
                    this.getOwner().sendMessage(new TranslationTextComponent("chat.type.text", this.getDisplayName(),
                            broken), this.getUUID());
                }
            }
        }
        return super.doHurtTarget(entity);
    }

    public ItemStack getSpawnArmor(EquipmentSlotType armorType) {
        float materialFloat = this.random.nextFloat();
        if (materialFloat <= 0.4F) {
            return ItemStack.EMPTY;
        } else if(materialFloat < 0.70F) {
            switch(armorType) {
                case HEAD:
                    return Items.LEATHER_HELMET.getDefaultInstance();
                case CHEST:
                    return Items.LEATHER_CHESTPLATE.getDefaultInstance();
                case LEGS:
                    return Items.LEATHER_LEGGINGS.getDefaultInstance();
                case FEET:
                    return Items.LEATHER_BOOTS.getDefaultInstance();
            }
        } else if(materialFloat < 0.90F) {
            switch(armorType) {
                case HEAD:
                    return Items.CHAINMAIL_HELMET.getDefaultInstance();
                case CHEST:
                    return Items.CHAINMAIL_CHESTPLATE.getDefaultInstance();
                case LEGS:
                    return Items.CHAINMAIL_LEGGINGS.getDefaultInstance();
                case FEET:
                    return Items.CHAINMAIL_BOOTS.getDefaultInstance();
            }
        } else {
            switch(armorType) {
                case HEAD:
                    return Items.IRON_HELMET.getDefaultInstance();
                case CHEST:
                    return Items.IRON_CHESTPLATE.getDefaultInstance();
                case LEGS:
                    return Items.IRON_LEGGINGS.getDefaultInstance();
                case FEET:
                    return Items.IRON_BOOTS.getDefaultInstance();
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void performRangedAttack(LivingEntity p_82196_1_, float p_82196_2_) {
        ItemStack itemstack = this.getProjectile(this.getItemInHand(ProjectileHelper.getWeaponHoldingHand(this, item -> item instanceof net.minecraft.item.BowItem)));
        AbstractArrowEntity abstractarrowentity = this.getArrow(itemstack, p_82196_2_);
        if (this.getMainHandItem().getItem() instanceof net.minecraft.item.BowItem)
            abstractarrowentity = ((net.minecraft.item.BowItem)this.getMainHandItem().getItem()).customArrow(abstractarrowentity);
        double d0 = p_82196_1_.getX() - this.getX();
        double d1 = p_82196_1_.getY(0.3333333333333333D) - abstractarrowentity.getY();
        double d2 = p_82196_1_.getZ() - this.getZ();
        double d3 = (double) MathHelper.sqrt(d0 * d0 + d2 * d2);
        abstractarrowentity.shoot(d0, d1 + d3 * (double)0.2F, d2, 1.6F, (float)(14 - this.level.getDifficulty().getId() * 4));
        this.playSound(SoundEvents.SKELETON_SHOOT, 1.0F, 1.0F / (this.getRandom().nextFloat() * 0.4F + 0.8F));
        this.level.addFreshEntity(abstractarrowentity);
        if (!this.level.isClientSide) {
            this.getMainHandItem().hurtAndBreak(1, this, (p_43296_) -> {
                p_43296_.broadcastBreakEvent(EquipmentSlotType.MAINHAND);
            });
            if (this.getMainHandItem().isEmpty()) {
                StringTextComponent broken = new StringTextComponent("My bow broke!");
                if (this.isTame()) {
                    this.getOwner().sendMessage(new TranslationTextComponent("chat.type.text", this.getDisplayName(),
                            broken), this.getUUID());
                }
            }
        }
    }

    protected AbstractArrowEntity getArrow(ItemStack p_213624_1_, float p_213624_2_) {
        return ProjectileHelper.getMobArrow(this, p_213624_1_, p_213624_2_);
    }

    @Override
    public ItemStack eat(World world, ItemStack stack) {
        if (stack.isEdible()) {
            this.heal(stack.getItem().getFoodProperties().getNutrition());
        }
        super.eat(world, stack);
        return stack;
    }

    public ItemStack checkFood() {
        for (int i = 0; i < this.inventory.getContainerSize(); ++i) {
            ItemStack itemstack = this.inventory.getItem(i);
            if (itemstack.isEdible()) {
                if ((float)itemstack.getItem().getFoodProperties().getNutrition() + this.getHealth() <= 20) {
                    return itemstack;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    public ResourceLocation getResourceLocation() {
        return CompanionData.maleSkins[getCompanionSkin()];
    }

    public int getCompanionSkin() {
        return this.entityData.get(DATA_TYPE_ID);
    }

    public void setCompanionSkin(int skinIndex) {
        this.entityData.set(DATA_TYPE_ID, skinIndex);
    }

    public boolean isEating() {
        return this.entityData.get(EATING);
    }

    public boolean isAlert() {
        return this.entityData.get(ALERT);
    }

    public boolean isHunting() {
        return this.entityData.get(HUNTING);
    }

    public void setEating(boolean eating) {
        this.entityData.set(EATING, eating);
    }

    public void setAlert(boolean alert) {
        this.entityData.set(ALERT, alert);
    }

    public void setHunting(boolean hunting) {
        this.entityData.set(HUNTING, hunting);
    }

    public void setCompanionType(String type) {
        this.entityData.set(COMPANION_TYPE, type);
    }

    public String getCompanionType() {
        return this.entityData.get(COMPANION_TYPE);
    }

    public void addAlertGoals() {
        for (NearestAttackableTargetGoal alertMobGoal : alertMobGoals) {
            this.targetSelector.addGoal(4, alertMobGoal);
        }
    }

    public void removeAlertGoals() {
        for (NearestAttackableTargetGoal alertMobGoal : alertMobGoals) {
            this.targetSelector.removeGoal(alertMobGoal);
        }
    }

    public void addHuntingGoals() {
        for (int i = 0; i < huntMobGoals.size(); i++) {
            this.targetSelector.addGoal(4, huntMobGoals.get(i));
        }
    }

    public void removeHuntingGoals() {
        for (int i = 0; i < huntMobGoals.size(); i++) {
            this.targetSelector.removeGoal(huntMobGoals.get(i));
        }
    }
}