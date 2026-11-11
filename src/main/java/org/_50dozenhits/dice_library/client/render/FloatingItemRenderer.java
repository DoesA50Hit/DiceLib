package org._50dozenhits.dice_library.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import org._50dozenhits.dice_library.prop.FloatingItem;
import org._50dozenhits.dice_library.prop.FloatingItem.Tip;
import org.jetbrains.annotations.Nullable;

/**
 * Floating items: their item, full bright unless told otherwise. Orbiting, row and fanned ones are placed from their
 * holder's position this frame, so they keep up with a moving holder without waiting on the server.
 */
public class FloatingItemRenderer extends EntityRenderer<FloatingItem> {
    private final ItemRenderer items;

    public FloatingItemRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.items = context.getItemRenderer();
        this.shadowRadius = 0;
    }

    @Override
    public void render(FloatingItem object, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        double time = object.level().getGameTime() + partialTick;
        Vec3 drawn = new Vec3(Mth.lerp(partialTick, object.xo, object.getX()), Mth.lerp(partialTick, object.yo, object.getY()),
                Mth.lerp(partialTick, object.zo, object.getZ()));
        Entity holder = object.getHolder();
        FloatingItem.Pose floatPose = object.getFloatPose();
        Vec3 at = drawn;
        float holderYaw = 0;
        if (floatPose != FloatingItem.Pose.FREE) {
            holderYaw = holder instanceof LivingEntity living ? Mth.rotLerp(partialTick, living.yBodyRotO, living.yBodyRot) : holder == null ? 0 : holder.getYRot();
            at = object.posedAt(time, holder, holder == null ? Vec3.ZERO : holder.getPosition(partialTick), holderYaw);
            if (inFrontOfYourEyes(object, holder, at, partialTick)) return;
        }

        pose.pushPose();
        double strike = object.strikeProgress(time);
        Entity target = strike >= 0 ? object.strikeTarget() : null;
        if (target != null) {
            Vec3 aim = target.getPosition(partialTick).add(0, target.getBbHeight() / 2, 0);
            Vec3 darted = at.lerp(aim, Math.sin(Math.PI * strike) * 0.85);
            pose.translate(darted.x - drawn.x, darted.y - drawn.y, darted.z - drawn.z);
            along(pose, aim.subtract(at), 0);
        } else if (floatPose == FloatingItem.Pose.FAN) {
            // flat side to the holder's back, leaning out to the side and tipped a little back
            pose.translate(at.x - drawn.x, at.y - drawn.y, at.z - drawn.z);
            pose.mulPose(Axis.YP.rotation(-holderYaw * Mth.DEG_TO_RAD));
            pose.mulPose(Axis.XP.rotation(-FloatingItem.FAN_TILT));
            pose.mulPose(Axis.ZP.rotation((float) object.fanAngle(time)));
        } else {
            pose.translate(at.x - drawn.x, at.y - drawn.y, at.z - drawn.z);
            double face = switch (floatPose) {
                case ORBIT -> Math.PI / 2 - object.orbitAngle(time);
                case ROW -> -holderYaw * Mth.DEG_TO_RAD;
                case FAN, FREE -> 0;
            };
            Tip tip = object.getTip();
            switch (tip) {
                case UP, DOWN -> {
                    pose.mulPose(Axis.YP.rotation((float) face));
                    if (tip == Tip.DOWN) pose.mulPose(Axis.ZP.rotationDegrees(180));
                }
                case TANGENT -> {
                    // rolled a quarter turn so the flat of the object faces out from the circle, not up, and the ring
                    // reads as swords from beside it instead of thin black bars
                    double angle = object.orbitAngle(time);
                    along(pose, new Vec3(-Math.sin(angle), 0, Math.cos(angle)), (float) (Math.PI / 2));
                }
                case ALONG -> {
                    float objectYaw = Mth.rotLerp(partialTick, object.yRotO, object.getYRot()) * Mth.DEG_TO_RAD;
                    float pitch = Mth.lerp(partialTick, object.xRotO, object.getXRot()) * Mth.DEG_TO_RAD;
                    pose.mulPose(Axis.YP.rotation(objectYaw));
                    pose.mulPose(Axis.XP.rotation(-pitch));
                    pose.mulPose(Axis.XP.rotationDegrees(90));
                }
            }
        }
        // a tool's sprite points up and right. Its turn stands the tip straight up
        pose.mulPose(Axis.ZP.rotationDegrees(object.getSpriteTurn()));
        float scale = object.getScale();
        pose.scale(scale, scale, scale);
        items.renderStatic(object.getItem(), ItemDisplayContext.NONE, object.isFullBright() ? LightTexture.FULL_BRIGHT : light, OverlayTexture.NO_OVERLAY, pose, buffers,
                object.level(), object.getId());
        pose.popPose();
        super.render(object, yaw, partialTick, pose, buffers, light);
    }

    // In first person, your own orbiting objects in front of the camera are hidden so they don't cover the view.
    private static boolean inFrontOfYourEyes(FloatingItem object, @Nullable Entity holder, Vec3 at, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        if (holder != minecraft.player || !minecraft.options.getCameraType().isFirstPerson() || object.getFloatPose() != FloatingItem.Pose.ORBIT) return false;
        Vec3 look = holder.getViewVector(partialTick).multiply(1, 0, 1);
        Vec3 out = at.subtract(holder.getPosition(partialTick)).multiply(1, 0, 1);
        return look.lengthSqr() > 1.0E-4 && out.lengthSqr() > 1.0E-4 && look.normalize().dot(out.normalize()) > 0.2;
    }

    // turns the pose so the tip (+Y once the sprite is stood up) points along `direction`, rolled `roll` radians
    private static void along(PoseStack pose, Vec3 direction, float roll) {
        if (direction.lengthSqr() < 1.0E-6) return;
        Vec3 d = direction.normalize();
        pose.mulPose(Axis.YP.rotation((float) Mth.atan2(d.x, d.z)));
        pose.mulPose(Axis.XP.rotation((float) -Math.asin(Mth.clamp(d.y, -1, 1))));
        pose.mulPose(Axis.ZP.rotation(roll));
        pose.mulPose(Axis.XP.rotationDegrees(90));
    }

    @Override
    public ResourceLocation getTextureLocation(FloatingItem object) {
        return InventoryMenu.BLOCK_ATLAS;
    }
}
