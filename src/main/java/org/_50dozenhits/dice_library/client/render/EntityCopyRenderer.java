package org._50dozenhits.dice_library.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;
import org._50dozenhits.dice_library.prop.EntityCopy;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Entity copies: a player's own skin (wide or slim, as theirs is) on a player model, in the armor and held items it
 * copied. Any other entity drawn as a fresh one of its type (made once per copy on the client) holding the same items.
 */
public class EntityCopyRenderer extends EntityRenderer<EntityCopy> {
    private static final Map<EntityCopy, LivingEntity> STANDS = new WeakHashMap<>();
    private final Body wide;
    private final Body slim;

    public EntityCopyRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.wide = new Body(context, false);
        this.slim = new Body(context, true);
        this.shadowRadius = 0.5f;
    }

    // the owner's skin as this client knows it, or the default skin their id would get
    static PlayerSkin skin(EntityCopy decoy) {
        UUID owner = decoy.owner().orElse(decoy.getUUID());
        PlayerInfo info = Minecraft.getInstance().getConnection() == null ? null : Minecraft.getInstance().getConnection().getPlayerInfo(owner);
        return info != null ? info.getSkin() : DefaultPlayerSkin.get(owner);
    }

    @Override
    public void render(EntityCopy decoy, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        if (!decoy.getCopiedType().equals("minecraft:player")) {
            LivingEntity stand = stand(decoy);
            if (stand != null) {
                Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(stand).render(stand, yaw, partialTick, pose, buffers, light);
                return;
            }
        }
        Body body = skin(decoy).model() == PlayerSkin.Model.SLIM ? slim : wide;
        body.render(decoy, yaw, partialTick, pose, buffers, light);
        super.render(decoy, yaw, partialTick, pose, buffers, light);
    }

    // the stand-in drawn for a copied mob: a fresh one of its type, turned and dressed like the copy
    @Nullable
    private static LivingEntity stand(EntityCopy decoy) {
        LivingEntity stand = STANDS.get(decoy);
        if (stand == null) {
            ResourceLocation id = ResourceLocation.tryParse(decoy.getCopiedType());
            EntityType<?> type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
            if (type == null || !(type.create(decoy.level()) instanceof LivingEntity living)) return null;
            stand = living;
            STANDS.put(decoy, stand);
        }
        stand.setPos(decoy.position());
        stand.setYRot(decoy.getYRot());
        stand.yRotO = decoy.yRotO;
        stand.yBodyRot = decoy.yBodyRot;
        stand.yBodyRotO = decoy.yBodyRot;
        stand.yHeadRot = decoy.yHeadRot;
        stand.yHeadRotO = decoy.yHeadRot;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() != EquipmentSlot.Type.ANIMAL_ARMOR) stand.setItemSlot(slot, decoy.getItemBySlot(slot));
        }
        return stand;
    }

    @Override
    public ResourceLocation getTextureLocation(EntityCopy decoy) {
        return skin(decoy).texture();
    }

    // a player model with the armor and held-item layers, like a player's own renderer
    static final class Body extends LivingEntityRenderer<EntityCopy, PlayerModel<EntityCopy>> {
        Body(EntityRendererProvider.Context context, boolean slim) {
            super(context, new PlayerModel<>(context.bakeLayer(slim ? ModelLayers.PLAYER_SLIM : ModelLayers.PLAYER), slim), 0.5f);
            addLayer(new HumanoidArmorLayer<>(this,
                    new HumanoidArmorModel<>(context.bakeLayer(slim ? ModelLayers.PLAYER_SLIM_INNER_ARMOR : ModelLayers.PLAYER_INNER_ARMOR)),
                    new HumanoidArmorModel<>(context.bakeLayer(slim ? ModelLayers.PLAYER_SLIM_OUTER_ARMOR : ModelLayers.PLAYER_OUTER_ARMOR)),
                    context.getModelManager()));
            addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
        }

        @Override
        public ResourceLocation getTextureLocation(EntityCopy decoy) {
            return skin(decoy).texture();
        }

        @Override
        protected boolean shouldShowName(EntityCopy decoy) {
            return false;
        }
    }
}
