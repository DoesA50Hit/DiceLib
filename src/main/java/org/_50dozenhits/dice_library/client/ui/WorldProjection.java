package org._50dozenhits.dice_library.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org._50dozenhits.dice_library.DiceLib;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Turns a point in the world into a point on the HUD, for speech bubbles, mob panels and arrows to things off screen.
 * It keeps the last frame's camera and matrices (view bobbing included), so HUD code drawn later in the same frame
 * lines up with the world. Client only.
 */
@EventBusSubscriber(modid = DiceLib.MODID, value = Dist.CLIENT)
public final class WorldProjection {
    private static final Matrix4f VIEW_PROJECTION = new Matrix4f();
    private static Vec3 camera = Vec3.ZERO;
    private static float partialTick;
    private static boolean ready;

    /**
     * Where a world point lands, in GUI pixels. Behind the camera the point is mirrored to the screen edge it is
     * closest to, so an arrow can still point the right way. onScreen is false then.
     */
    public record Point(float x, float y, boolean inFront, boolean onScreen, double distance) {
    }

    @SubscribeEvent
    static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        VIEW_PROJECTION.set(event.getProjectionMatrix()).mul(event.getModelViewMatrix());
        camera = event.getCamera().getPosition();
        partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        ready = true;
    }

    public static boolean ready() {
        return ready && Minecraft.getInstance().level != null;
    }

    public static Vec3 camera() {
        return camera;
    }

    public static float partialTick() {
        return partialTick;
    }

    /**
     * An entity's position this frame, between its last two ticks, plus an offset
     */
    public static Vec3 at(Entity entity, double up) {
        return entity.getPosition(partialTick).add(0, up, 0);
    }

    @Nullable
    public static Point project(Vec3 world) {
        if (!ready()) return null;
        Minecraft mc = Minecraft.getInstance();
        float width = mc.getWindow().getGuiScaledWidth();
        float height = mc.getWindow().getGuiScaledHeight();
        Vector4f clip = new Vector4f((float) (world.x - camera.x), (float) (world.y - camera.y), (float) (world.z - camera.z), 1).mul(VIEW_PROJECTION);
        boolean inFront = clip.w > 0.01f;
        float w = Math.max(Math.abs(clip.w), 0.01f);
        float nx = clip.x / w;
        float ny = clip.y / w;
        if (!inFront) {
            // mirror, then push out past the edge so it never reads as on screen
            nx = -nx;
            ny = -ny;
            float far = Math.max(Math.abs(nx), Math.abs(ny));
            if (far < 1.0001f) {
                float scale = 1.0001f / Math.max(far, 0.0001f);
                nx *= scale;
                ny *= scale;
            }
        }
        float x = (nx + 1) / 2 * width;
        float y = (1 - ny) / 2 * height;
        boolean onScreen = inFront && x >= 0 && x <= width && y >= 0 && y <= height;
        return new Point(x, y, inFront, onScreen, world.distanceTo(camera));
    }

    /**
     * Whether nothing solid lies between the camera and the point
     */
    public static boolean visible(Vec3 world) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return false;
        HitResult hit = mc.level.clip(new ClipContext(camera, world, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(world) < 0.04;
    }

    /**
     * Whether an entity can be seen: its eyes, middle or feet
     */
    public static boolean visible(Entity entity) {
        Vec3 feet = at(entity, 0.1);
        return visible(feet.add(0, entity.getEyeHeight(), 0)) || visible(feet.add(0, entity.getBbHeight() / 2, 0)) || visible(feet);
    }

    /**
     * The point on the screen's edge (inset by margin) in the direction of p from the centre, and the angle in
     * radians it points along (0 = right, clockwise)
     */
    public record Edge(float x, float y, float angle) {
    }

    public static Edge edge(Point p, float margin) {
        Minecraft mc = Minecraft.getInstance();
        float width = mc.getWindow().getGuiScaledWidth();
        float height = mc.getWindow().getGuiScaledHeight();
        float cx = width / 2, cy = height / 2;
        float dx = p.x() - cx, dy = p.y() - cy;
        if (Math.abs(dx) < 0.001f && Math.abs(dy) < 0.001f) dy = 1;
        float hw = cx - margin, hh = cy - margin;
        float t = Math.min(hw / Math.max(Math.abs(dx), 0.001f), hh / Math.max(Math.abs(dy), 0.001f));
        return new Edge(cx + dx * t, cy + dy * t, (float) Math.atan2(dy, dx));
    }

    private WorldProjection() {
    }
}
