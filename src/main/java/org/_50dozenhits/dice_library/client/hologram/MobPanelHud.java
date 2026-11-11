package org._50dozenhits.dice_library.client.hologram;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import org._50dozenhits.dice_library.DiceLib;
import org._50dozenhits.dice_library.client.ui.CustomUis;
import org._50dozenhits.dice_library.client.ui.Lines;
import org._50dozenhits.dice_library.client.ui.Look;
import org._50dozenhits.dice_library.client.ui.Looks;
import org._50dozenhits.dice_library.client.ui.PanelRenderer;
import org._50dozenhits.dice_library.client.ui.PanelUi;
import org._50dozenhits.dice_library.client.ui.WorldProjection;
import org._50dozenhits.dice_library.hologram.MobPanels.Corner;
import org._50dozenhits.dice_library.hologram.MobPanels.Mode;
import org._50dozenhits.dice_library.hologram.MobPanels.MobPanelPayload;
import org._50dozenhits.dice_library.hologram.MobPanels.MobPanelRemovePayload;
import org.joml.Vector2f;
import org.jetbrains.annotations.ApiStatus;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mob panels on the HUD. Beside: corner brackets around the mob on screen, the panel next to them (it flips to the
 * other side near the edge). Pinned: panels stack in their corner, each with a line from its edge to the mob: a short
 * stub, a diagonal, and a stub into a bracketed outline. A mob off screen gets an arrow at the screen's edge instead.
 */
@EventBusSubscriber(modid = DiceLib.MODID, value = Dist.CLIENT)
public final class MobPanelHud {
    private static final int GAP = 6;
    private static final int STUB = 10;
    private static final int BRACKET = 5;
    private static final int MARGIN = 6;
    private static final double SHOW_RANGE = 96;

    private static final Map<Integer, Shown> SHOWN = new LinkedHashMap<>();

    private static final class Shown {
        final MobPanelPayload data;
        final PanelUi ui;
        boolean inSight = true;

        Shown(MobPanelPayload data) {
            this.data = data;
            CustomUis.MobPanelUi custom = CustomUis.mobPanel(data.style());
            if (custom != null) {
                this.ui = custom.create(data);
            } else {
                Look look = Looks.get(data.style());
                this.ui = new PanelRenderer(data.body(), look, look.mobPanel(), data.width(), List.of(), data::entity,
                        new PanelRenderer.Header(data.title(), data.icon(), data.mobIcon()));
            }
        }
    }

    @ApiStatus.Internal
    public static void accept(MobPanelPayload payload) {
        SHOWN.put(payload.entity(), new Shown(payload));
    }

    @ApiStatus.Internal
    public static void remove(MobPanelRemovePayload payload) {
        SHOWN.remove(payload.entity());
    }

    public static int count() {
        return SHOWN.size();
    }

    @SubscribeEvent
    static void registerLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(DiceLib.id("mob_panels"), MobPanelHud::render);
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        for (Shown shown : SHOWN.values()) {
            shown.ui.tick();
            Entity mob = mc.level.getEntity(shown.data.entity());
            shown.inSight = shown.data.throughWalls() || mob != null && WorldProjection.visible(mob);
        }
    }

    @SubscribeEvent
    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        SHOWN.clear();
    }

    @SubscribeEvent
    static void onClone(ClientPlayerNetworkEvent.Clone event) {
        SHOWN.clear();
    }

    // the mob's outline on screen, or null when part of it is behind the camera
    private record Box(float minX, float minY, float maxX, float maxY) {
        float midX() {
            return (minX + maxX) / 2;
        }

        float midY() {
            return (minY + maxY) / 2;
        }
    }

    private static Box box(LivingEntity mob) {
        Vec3 at = WorldProjection.at(mob, 0);
        AABB bounds = mob.getBoundingBox().move(at.subtract(mob.position()));
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            Vec3 corner = new Vec3((i & 1) == 0 ? bounds.minX : bounds.maxX, (i & 2) == 0 ? bounds.minY : bounds.maxY,
                    (i & 4) == 0 ? bounds.minZ : bounds.maxZ);
            WorldProjection.Point p = WorldProjection.project(corner);
            if (p == null || !p.inFront()) return null;
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
            maxX = Math.max(maxX, p.x());
            maxY = Math.max(maxY, p.y());
        }
        return new Box(minX, minY, maxX, maxY);
    }

    private static boolean onScreen(Box box, float width, float height) {
        return box.maxX() > 0 && box.minX() < width && box.maxY() > 0 && box.minY() < height;
    }

    static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (SHOWN.isEmpty() || mc.level == null || mc.player == null || mc.options.hideGui || !WorldProjection.ready()) return;
        int width = graphics.guiWidth(), height = graphics.guiHeight();
        float partialTick = delta.getGameTimeDeltaPartialTick(false);
        Map<Corner, Float> stacks = new EnumMap<>(Corner.class);
        for (Shown shown : SHOWN.values()) {
            Entity entity = mc.level.getEntity(shown.data.entity());
            if (!(entity instanceof LivingEntity mob) || !shown.inSight || mob.distanceTo(mc.player) > SHOW_RANGE) continue;
            Box box = box(mob);
            boolean visible = box != null && onScreen(box, width, height);
            float panelWidth = shown.ui.width(), panelHeight = shown.ui.height();
            int color = shown.data.color();
            float x, y;
            if (shown.data.mode() == Mode.BESIDE) {
                if (!visible) continue;
                brackets(graphics, box, color);
                x = box.maxX() + GAP;
                if (x + panelWidth > width - MARGIN) x = box.minX() - GAP - panelWidth;
                x = Math.max(MARGIN, Math.min(width - MARGIN - panelWidth, x));
                y = Math.max(MARGIN, Math.min(height - MARGIN - panelHeight, box.minY()));
            } else {
                Corner corner = shown.data.corner();
                boolean left = corner == Corner.TOP_LEFT || corner == Corner.MIDDLE_LEFT;
                float top = switch (corner) {
                    case TOP_LEFT -> MARGIN;
                    case TOP_RIGHT -> mc.player.getActiveEffects().isEmpty() ? MARGIN : 56;
                    case MIDDLE_LEFT, MIDDLE_RIGHT -> height / 3f;
                };
                y = stacks.getOrDefault(corner, top);
                stacks.put(corner, y + panelHeight + 4);
                x = left ? MARGIN : width - MARGIN - panelWidth;
                Vector2f start = new Vector2f(left ? x + panelWidth : x, y + Math.min(10, panelHeight / 2));
                leader(graphics, start, left, mob, box, visible, color, width, height);
            }
            graphics.pose().pushPose();
            graphics.pose().translate(Math.round(x), Math.round(y), 0);
            shown.ui.render(graphics, Integer.MIN_VALUE, Integer.MIN_VALUE, partialTick);
            graphics.pose().popPose();
        }
    }

    // corner brackets around the outline
    private static void brackets(GuiGraphics graphics, Box box, int color) {
        float pad = 2;
        float x0 = box.minX() - pad, y0 = box.minY() - pad, x1 = box.maxX() + pad, y1 = box.maxY() + pad;
        float size = Math.min(BRACKET, Math.min(x1 - x0, y1 - y0) / 2);
        line(graphics, color, x0, y0 + size, x0, y0, x0 + size, y0);
        line(graphics, color, x1 - size, y0, x1, y0, x1, y0 + size);
        line(graphics, color, x1, y1 - size, x1, y1, x1 - size, y1);
        line(graphics, color, x0 + size, y1, x0, y1, x0, y1 - size);
    }

    // stub, diagonal, stub. Or to the screen's edge with an arrow when the mob is off screen
    private static void leader(GuiGraphics graphics, Vector2f start, boolean left, LivingEntity mob, Box box, boolean visible, int color,
                               int width, int height) {
        float side = left ? 1 : -1;
        Vector2f out = new Vector2f(start.x + STUB * side, start.y);
        if (visible) {
            brackets(graphics, box, color);
            // into the outline's nearer side, at its middle height
            boolean fromLeft = out.x < box.midX();
            Vector2f end = new Vector2f(fromLeft ? box.minX() - 2 : box.maxX() + 2, box.midY());
            Vector2f before = new Vector2f(end.x + (fromLeft ? -STUB : STUB), end.y);
            line(graphics, color, List.of(start, out, before, end));
            graphics.fill((int) end.x - 1, (int) end.y - 1, (int) end.x + 2, (int) end.y + 2, color);
            return;
        }
        WorldProjection.Point point = WorldProjection.project(WorldProjection.at(mob, mob.getBbHeight() / 2));
        if (point == null) return;
        WorldProjection.Edge edge = WorldProjection.edge(point, MARGIN + 4);
        Vector2f tip = new Vector2f(edge.x(), edge.y());
        line(graphics, color, List.of(start, out, tip));
        float dx = (float) Math.cos(edge.angle()), dy = (float) Math.sin(edge.angle());
        float back = 6, half = 4;
        line(graphics, color, tip.x - dx * back - dy * half, tip.y - dy * back + dx * half, tip.x, tip.y,
                tip.x - dx * back + dy * half, tip.y - dy * back - dx * half);
    }

    private static void line(GuiGraphics graphics, int color, float... xy) {
        Lines.draw(graphics, color, 1.5f, xy);
    }

    private static void line(GuiGraphics graphics, int color, List<Vector2f> points) {
        Lines.draw(graphics, color, 1.5f, points);
    }

    private MobPanelHud() {
    }
}
