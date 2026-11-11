package org._50dozenhits.dice_library.client.hologram;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org._50dozenhits.dice_library.DiceLib;
import org._50dozenhits.dice_library.client.hologram.ClientHolograms.Shown;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL20C;

/**
 * Draws each hologram's panel into its own texture (only when it changed or the crosshair moved over another
 * part), then puts that texture on a quad in the world. Walls hide it. Past DRAW_RANGE nothing is drawn and the texture
 * is freed.
 */
@EventBusSubscriber(modid = DiceLib.MODID, value = Dist.CLIENT)
public final class HologramRenderer {
    private static final double DRAW_RANGE = 48;
    // where the mouse is said to be when the crosshair is off the panel
    private static final int AWAY = -10_000;

    @SubscribeEvent
    static void onFrame(RenderFrameEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || ClientHolograms.all().isEmpty()) return;
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        HologramPicker.pick(partialTick);
        Vec3 eye = mc.player.getEyePosition(partialTick);
        for (Shown shown : ClientHolograms.all()) {
            Vec3 center = shown.center(partialTick);
            if (center == null || center.distanceToSqr(eye) > DRAW_RANGE * DRAW_RANGE) {
                if (shown.target != null) shown.free();
                continue;
            }
            if ((shown.dirty || shown.target == null || shown.ui.animated()) && !shown.broken) paint(shown);
        }
    }

    static void paint(Shown shown) {
        shown.ask("drawing", () -> {
            if (shown.ui.width() <= 0 || shown.ui.height() <= 0) return false;
            draw(shown);
            shown.dirty = false;
            return true;
        });
    }

    // A GUI frame's recipe, into the hologram's own target, cleared to transparent
    private static void draw(Shown shown) {
        Minecraft mc = Minecraft.getInstance();
        double guiScale = mc.getWindow().getGuiScale();
        int width = shown.ui.width(), height = shown.ui.height();
        int fbWidth = Mth.ceil(width * guiScale), fbHeight = Mth.ceil(height * guiScale);
        if (shown.target == null) {
            shown.target = new TextureTarget(fbWidth, fbHeight, true, Minecraft.ON_OSX);
        } else if (shown.target.width != fbWidth || shown.target.height != fbHeight) {
            shown.target.resize(fbWidth, fbHeight, Minecraft.ON_OSX);
        }
        RenderTarget target = shown.target;
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        RenderSystem.backupProjectionMatrix();
        try {
            target.bindWrite(true);
            RenderSystem.clearColor(0, 0, 0, 0);
            RenderSystem.clear(GL11C.GL_COLOR_BUFFER_BIT | GL11C.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            RenderSystem.viewport(0, 0, target.width, target.height);
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
            // keep the strongest alpha drawn at each pixel, so a see-through overlay never makes an opaque panel see-through
            GL20C.glBlendEquationSeparate(GL14C.GL_FUNC_ADD, GL14C.GL_MAX);

            float farPlane = ClientHooks.getGuiFarPlane();
            Matrix4f projection = new Matrix4f().setOrtho(0, (float) (target.width / guiScale), (float) (target.height / guiScale), 0, 1000, farPlane);
            RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);
            modelView.pushMatrix();
            modelView.translation(0, 0, 10000 - farPlane);
            RenderSystem.applyModelViewMatrix();
            Lighting.setupFor3DItems();

            GuiGraphics graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            int mouseX = shown.hover == null ? AWAY : shown.hover[0];
            int mouseY = shown.hover == null ? AWAY : shown.hover[1];
            shown.ui.render(graphics, mouseX, mouseY, mc.getTimer().getGameTimeDeltaPartialTick(true));
            graphics.flush();

            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
        } finally {
            GL20C.glBlendEquationSeparate(GL14C.GL_FUNC_ADD, GL14C.GL_FUNC_ADD);
            RenderSystem.restoreProjectionMatrix();
            RenderTarget main = mc.getMainRenderTarget();
            main.bindWrite(true);
            RenderSystem.viewport(0, 0, main.width, main.height);
        }
    }

    @SubscribeEvent
    static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || ClientHolograms.all().isEmpty()) return;
        Vec3 camera = event.getCamera().getPosition();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);

        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.set(event.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(event.getProjectionMatrix(), VertexSorting.DISTANCE_TO_ORIGIN);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.enableBlend();
        // the texture holds colour already multiplied by its alpha (it was drawn over transparent black)
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableCull();
        try {
            for (Shown shown : ClientHolograms.all()) {
                if (shown.target == null || shown.broken) continue;
                Shown.Frame frame = shown.frame(camera, partialTick);
                if (frame == null) continue;
                quad(shown.target.getColorTextureId(), frame, camera);
            }
        } finally {
            RenderSystem.enableCull();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
            RenderSystem.restoreProjectionMatrix();
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    private static void quad(int texture, Shown.Frame frame, Vec3 camera) {
        RenderSystem.setShaderTexture(0, texture);
        Vec3 c = frame.center().subtract(camera);
        Vec3 r = frame.right().scale(frame.halfWidth());
        Vec3 u = frame.up().scale(frame.halfHeight());
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        // the texture's first row is the panel's bottom
        vertex(buffer, c.subtract(r).add(u), 0, 1);
        vertex(buffer, c.subtract(r).subtract(u), 0, 0);
        vertex(buffer, c.add(r).subtract(u), 1, 0);
        vertex(buffer, c.add(r).add(u), 1, 1);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }

    private static void vertex(BufferBuilder buffer, Vec3 at, float u, float v) {
        buffer.addVertex((float) at.x, (float) at.y, (float) at.z).setUv(u, v).setColor(1f, 1f, 1f, 1f);
    }

    private HologramRenderer() {
    }
}
