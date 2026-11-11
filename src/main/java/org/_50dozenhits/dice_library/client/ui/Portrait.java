package org._50dozenhits.dice_library.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A square picture beside a speaker's name: an item, or a living entity's head and shoulders. Client only.
 */
public final class Portrait {
    /**
     * whether there is anything to draw: an item, or the id of a living entity this client can see
     */
    public static boolean shows(ItemStack item, int entity) {
        return !item.isEmpty() || entity(entity) != null;
    }

    /**
     * Draws the item scaled to the square, else the entity, else nothing.
     */
    public static void draw(GuiGraphics graphics, int x, int y, int size, ItemStack item, int entity) {
        if (!item.isEmpty()) {
            float scale = size / 16f;
            graphics.pose().pushPose();
            graphics.pose().translate(x, y, 0);
            graphics.pose().scale(scale, scale, 1);
            graphics.renderItem(item, 0, 0);
            graphics.pose().popPose();
            return;
        }
        LivingEntity living = entity(entity);
        if (living != null) drawEntity(graphics, living, x, y, size);
    }

    @Nullable
    private static LivingEntity entity(int id) {
        Minecraft mc = Minecraft.getInstance();
        if (id < 0 || mc.level == null) return null;
        Entity found = mc.level.getEntity(id);
        return found instanceof LivingEntity living ? living : null;
    }

    // InventoryScreen.renderEntityInInventoryFollowsMouse, but clipped where the square really is on screen (it may
    // sit inside a moved or scaled panel) and after drawing what is already queued, which its clip would otherwise cut
    // off too
    private static void drawEntity(GuiGraphics graphics, LivingEntity living, int x, int y, int size) {
        graphics.flush();
        Matrix4f pose = graphics.pose().last().pose();
        Vector3f a = pose.transformPosition(new Vector3f(x, y, 0));
        Vector3f b = pose.transformPosition(new Vector3f(x + size, y + size, 0));
        graphics.enableScissor((int) Math.floor(Math.min(a.x, b.x)), (int) Math.floor(Math.min(a.y, b.y)),
                (int) Math.ceil(Math.max(a.x, b.x)), (int) Math.ceil(Math.max(a.y, b.y)));

        // turned a little towards the text beside it
        float turn = (float) Math.atan(-0.5f);
        Quaternionf body = new Quaternionf().rotateZ((float) Math.PI);
        Quaternionf tilt = new Quaternionf().rotateX(0);
        body.mul(tilt);
        float bodyRot = living.yBodyRot, yRot = living.getYRot(), xRot = living.getXRot(), headRotO = living.yHeadRotO, headRot = living.yHeadRot;
        living.yBodyRot = 180 + turn * 20;
        living.setYRot(180 + turn * 40);
        living.setXRot(0);
        living.yHeadRot = living.getYRot();
        living.yHeadRotO = living.getYRot();
        // about one block around the eyes fills the square
        float entityScale = living.getScale();
        float yOffset = living.getEyeHeight() - living.getBbHeight() / 2 - 0.1f;
        Vector3f translate = new Vector3f(0, living.getBbHeight() / 2 + yOffset * entityScale, 0);
        InventoryScreen.renderEntityInInventory(graphics, x + size / 2f, y + size / 2f, size * 0.9f / entityScale, translate, body, tilt, living);
        living.yBodyRot = bodyRot;
        living.setYRot(yRot);
        living.setXRot(xRot);
        living.yHeadRotO = headRotO;
        living.yHeadRot = headRot;

        graphics.disableScissor();
    }

    private Portrait() {
    }
}
