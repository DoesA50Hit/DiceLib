package org._50dozenhits.dice_library.client.ui;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.joml.Vector2f;

import java.util.ArrayList;
import java.util.List;

/**
 * Lines on the GUI at any angle and thickness: each segment is a thin quad. Client only.
 */
public final class Lines {
    /**
     * one line through every point in turn
     */
    public static void draw(GuiGraphics graphics, int color, float thickness, List<Vector2f> points) {
        VertexConsumer buffer = graphics.bufferSource().getBuffer(RenderType.gui());
        Matrix4f pose = graphics.pose().last().pose();
        float half = thickness / 2;
        for (int i = 0; i + 1 < points.size(); i++) {
            Vector2f a = points.get(i), b = points.get(i + 1);
            float dx = b.x - a.x, dy = b.y - a.y;
            float length = (float) Math.sqrt(dx * dx + dy * dy);
            if (length < 1e-4f) continue;
            // across the segment, and a little past each end so the corners close
            float nx = -dy / length * half, ny = dx / length * half;
            float ex = dx / length * half, ey = dy / length * half;
            // wound like GuiGraphics.fill's quads, so the GUI's face culling keeps them
            buffer.addVertex(pose, a.x - ex - nx, a.y - ey - ny, 0).setColor(color);
            buffer.addVertex(pose, a.x - ex + nx, a.y - ey + ny, 0).setColor(color);
            buffer.addVertex(pose, b.x + ex + nx, b.y + ey + ny, 0).setColor(color);
            buffer.addVertex(pose, b.x + ex - nx, b.y + ey - ny, 0).setColor(color);
        }
        graphics.flush();
    }

    /**
     * the same, from x, y pairs
     */
    public static void draw(GuiGraphics graphics, int color, float thickness, float... xy) {
        List<Vector2f> points = new ArrayList<>();
        for (int i = 0; i + 1 < xy.length; i += 2) points.add(new Vector2f(xy[i], xy[i + 1]));
        draw(graphics, color, thickness, points);
    }

    private Lines() {
    }
}
