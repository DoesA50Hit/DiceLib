package org._50dozenhits.dice_library.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org._50dozenhits.dice_library.prop.Tether;
import org._50dozenhits.dice_library.prop.TetherLook;

/**
 * Tethers in their TetherLook. LINKS are oval links, each turned a quarter round from the one before like a real
 * chain. GLYPHS are a string of signs. A tether hangs as much as its sag, and a lash wobbles as it flies out. A RIBBON
 * is one band instead: two crossed strips so it reads from any side, its texture running along it with the flow,
 * swelling in pulses that travel the same way, and narrowing to a point at the far end.
 */
public class TetherRenderer extends EntityRenderer<Tether> {
    // ribbon pieces for each block of length, and how fast its texture runs (texture lengths a tick)
    private static final double BLOOD_STEPS_PER_BLOCK = 6;
    private static final double BLOOD_FLOW_SPEED = 0.09;

    public TetherRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0;
    }

    @Override
    public void render(Tether chain, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        double time = chain.level().getGameTime() + partialTick;
        Vec3 drawn = chain.getPosition(partialTick);
        Vec3 start = chain.start(partialTick).subtract(drawn);
        Vec3 tip = chain.tip(time, partialTick).subtract(drawn);
        double length = start.distanceTo(tip);
        if (length < 0.05) return;
        TetherLook look = chain.getLook();
        if (look.kind() == TetherLook.Kind.RIBBON) {
            ribbon(chain, start, tip, length, time, pose, buffers, light);
            super.render(chain, yaw, partialTick, pose, buffers, light);
            return;
        }
        boolean ink = look.kind() == TetherLook.Kind.GLYPHS;
        double spacing = Math.max(0.05, look.spacing());
        float width = look.width();
        float linkLength = look.linkLength();
        int links = Math.max(1, (int) Math.ceil(length / spacing));
        double sag = chain.getSag() * length;
        double reach = chain.reach(time);
        // a lash wobbles while it is flying out
        double wobble = reach < 1 ? 0.15 * (1 - reach) : 0;
        Vec3 axis = tip.subtract(start).normalize();
        Vec3 side = axis.cross(new Vec3(0, 1, 0));
        side = side.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : side.normalize();
        int lit = LightTexture.pack(Math.max(LightTexture.block(light), look.minLight()), LightTexture.sky(light));
        VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(look.texture()));
        PoseStack.Pose matrix = pose.last();
        for (int i = 0; i < links; i++) {
            double t0 = (double) i / links, t1 = (double) (i + 1) / links;
            Vec3 a = along(start, tip, t0, sag, wobble, side, time), b = along(start, tip, t1, sag, wobble, side, time);
            Vec3 middle = a.lerp(b, 0.5);
            Vec3 dir = b.subtract(a);
            if (dir.lengthSqr() < 1.0E-8) continue;
            dir = dir.normalize();
            Vec3 across1 = dir.cross(new Vec3(0, 1, 0));
            across1 = across1.lengthSqr() < 1.0E-4 ? dir.cross(new Vec3(1, 0, 0)).normalize() : across1.normalize();
            Vec3 across2 = dir.cross(across1).normalize();
            if (ink) {
                // each letter shows both ways round, so it reads from any side
                int cells = look.cells();
                float u0 = (float) ((i * 3) % cells) / cells, u1 = u0 + 1f / cells;
                quad(consumer, matrix, middle, dir, across1, width, linkLength, u0, u1, lit);
                quad(consumer, matrix, middle, dir, across2, width, linkLength, u0, u1, lit);
            } else {
                quad(consumer, matrix, middle, dir, i % 2 == 0 ? across1 : across2, width, linkLength, 0, 1, lit);
            }
        }
        if (!chain.getEnd().isAir()) {
            pose.pushPose();
            pose.translate(tip.x, tip.y, tip.z);
            pose.mulPose(Axis.YP.rotation((float) (time * 0.3)));
            pose.scale(1.2f, 1.2f, 1.2f);
            pose.translate(-0.5, -0.25, -0.5);
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(chain.getEnd(), pose, buffers, lit, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
        super.render(chain, yaw, partialTick, pose, buffers, light);
    }

    private static void ribbon(Tether chain, Vec3 start, Vec3 tip, double length, double time, PoseStack pose, MultiBufferSource buffers, int light) {
        double sag = chain.getSag() * length;
        double reach = chain.reach(time);
        double wobble = reach < 1 ? 0.2 * (1 - reach) : 0.03;
        Vec3 axis = tip.subtract(start).normalize();
        Vec3 side = axis.cross(new Vec3(0, 1, 0));
        side = side.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : side.normalize();
        int steps = Math.max(4, (int) Math.ceil(length * BLOOD_STEPS_PER_BLOCK));
        float width = chain.getWidth();
        double flow = switch (chain.getFlow()) {
            case OUT -> -BLOOD_FLOW_SPEED;
            case IN -> BLOOD_FLOW_SPEED;
            case STILL -> 0;
        };
        // the texture repeats every width*4 blocks, so a thick ribbon doesn't look stretched
        double repeat = Math.max(0.5, width * 4);
        int tint = chain.getTint();
        int r = (tint >> 16) & 255, g = (tint >> 8) & 255, b = tint & 255;
        int lit = LightTexture.pack(Math.max(LightTexture.block(light), chain.getLook().minLight()), LightTexture.sky(light));
        VertexConsumer consumer = buffers.getBuffer(RenderType.entityTranslucent(chain.getLook().texture()));
        PoseStack.Pose matrix = pose.last();
        Vec3[] at = new Vec3[steps + 1];
        for (int i = 0; i <= steps; i++) at[i] = along(start, tip, (double) i / steps, sag, wobble, side, time);
        for (int plane = 0; plane < 2; plane++) {
            for (int i = 0; i < steps; i++) {
                double t0 = (double) i / steps, t1 = (double) (i + 1) / steps;
                Vec3 dir = at[i + 1].subtract(at[i]);
                if (dir.lengthSqr() < 1.0E-8) continue;
                dir = dir.normalize();
                Vec3 across = dir.cross(new Vec3(0, 1, 0));
                across = across.lengthSqr() < 1.0E-4 ? dir.cross(new Vec3(1, 0, 0)).normalize() : across.normalize();
                if (plane == 1) across = dir.cross(across).normalize();
                float w0 = ribbonWidth(width, t0, time, flow), w1 = ribbonWidth(width, t1, time, flow);
                float v0 = (float) (t0 * length / repeat + time * flow), v1 = (float) (t1 * length / repeat + time * flow);
                Vec3 normal = across.cross(dir);
                bloodVertex(consumer, matrix, at[i].subtract(across.scale(w0 / 2)), 0, v0, normal, r, g, b, lit);
                bloodVertex(consumer, matrix, at[i].add(across.scale(w0 / 2)), 1, v0, normal, r, g, b, lit);
                bloodVertex(consumer, matrix, at[i + 1].add(across.scale(w1 / 2)), 1, v1, normal, r, g, b, lit);
                bloodVertex(consumer, matrix, at[i + 1].subtract(across.scale(w1 / 2)), 0, v1, normal, r, g, b, lit);
            }
        }
    }

    // full width along most of it, a point at the far end, a little swell travelling with the flow
    private static float ribbonWidth(float width, double t, double time, double flow) {
        double taper = t > 0.8 ? (1 - t) / 0.2 : 1;
        double start = t < 0.08 ? 0.6 + 0.4 * t / 0.08 : 1;
        double pulse = 1 + 0.18 * Math.sin(t * 14 + time * flow * 40);
        return (float) (width * taper * start * pulse);
    }

    private static void bloodVertex(VertexConsumer consumer, PoseStack.Pose matrix, Vec3 at, float u, float v, Vec3 normal, int r, int g, int b, int light) {
        consumer.addVertex(matrix, (float) at.x, (float) at.y, (float) at.z).setColor(r, g, b, 255).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(matrix, (float) normal.x, (float) normal.y, (float) normal.z);
    }

    // the point `t` of the way along, hanging `sag` in the middle and wobbling sideways
    private static Vec3 along(Vec3 start, Vec3 tip, double t, double sag, double wobble, Vec3 side, double time) {
        Vec3 at = start.lerp(tip, t);
        double hang = 4 * t * (1 - t);
        return at.add(0, -sag * hang, 0).add(side.scale(Math.sin(t * 9 - time * 1.4) * wobble * hang));
    }

    // a flat link `width` across along `across`, `length` along `dir`, centred on `middle`
    private static void quad(VertexConsumer consumer, PoseStack.Pose matrix, Vec3 middle, Vec3 dir, Vec3 across, float width, float length,
                             float u0, float u1, int light) {
        Vec3 w = across.scale(width / 2), l = dir.scale(length / 2);
        Vec3 normal = across.cross(dir);
        vertex(consumer, matrix, middle.subtract(w).subtract(l), u0, 1, normal, light);
        vertex(consumer, matrix, middle.add(w).subtract(l), u1, 1, normal, light);
        vertex(consumer, matrix, middle.add(w).add(l), u1, 0, normal, light);
        vertex(consumer, matrix, middle.subtract(w).add(l), u0, 0, normal, light);
    }

    private static void vertex(VertexConsumer consumer, PoseStack.Pose matrix, Vec3 at, float u, float v, Vec3 normal, int light) {
        consumer.addVertex(matrix, (float) at.x, (float) at.y, (float) at.z).setColor(255, 255, 255, 255).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(matrix, (float) normal.x, (float) normal.y, (float) normal.z);
    }

    @Override
    public ResourceLocation getTextureLocation(Tether chain) {
        return chain.getLook().texture();
    }
}
