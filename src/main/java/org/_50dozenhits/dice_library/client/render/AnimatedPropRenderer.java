package org._50dozenhits.dice_library.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import org._50dozenhits.dice_library.prop.AnimatedProp;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Animated props: whatever GeckoLib model each one names (geo, animations and texture under .../prop/), at its scale,
 * full bright and see-through when it glows.
 */
public class AnimatedPropRenderer extends GeoEntityRenderer<AnimatedProp> {
    public AnimatedPropRenderer(EntityRendererProvider.Context context) {
        super(context, new Model());
        this.shadowRadius = 0;
    }

    @Override
    public void render(AnimatedProp prop, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        pose.pushPose();
        float scale = prop.getScale();
        pose.scale(scale, scale, scale);
        super.render(prop, yaw, partialTick, pose, buffers, prop.isGlowing() ? LightTexture.FULL_BRIGHT : light);
        pose.popPose();
    }

    static final class Model extends GeoModel<AnimatedProp> {
        private static ResourceLocation file(AnimatedProp prop, String folder, String extension) {
            ResourceLocation id = prop.getModel();
            return ResourceLocation.fromNamespaceAndPath(id.getNamespace(), folder + "/prop/" + id.getPath() + extension);
        }

        @Override
        public ResourceLocation getModelResource(AnimatedProp prop) {
            return file(prop, "geo", ".geo.json");
        }

        @Override
        public ResourceLocation getTextureResource(AnimatedProp prop) {
            return file(prop, "textures", ".png");
        }

        @Override
        public ResourceLocation getAnimationResource(AnimatedProp prop) {
            return file(prop, "animations", ".animation.json");
        }

        @Override
        public RenderType getRenderType(AnimatedProp prop, ResourceLocation texture) {
            return prop.isGlowing() ? RenderType.entityTranslucentEmissive(texture) : RenderType.entityCutoutNoCull(texture);
        }
    }

    @Override
    public @Nullable RenderType getRenderType(AnimatedProp prop, ResourceLocation texture, @Nullable MultiBufferSource buffers, float partialTick) {
        return getGeoModel().getRenderType(prop, texture);
    }
}
