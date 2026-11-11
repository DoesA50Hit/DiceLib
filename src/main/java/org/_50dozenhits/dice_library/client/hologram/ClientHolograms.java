package org._50dozenhits.dice_library.client.hologram;

import com.mojang.blaze3d.pipeline.TextureTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org._50dozenhits.dice_library.DiceLib;
import org._50dozenhits.dice_library.client.ui.CustomUis;
import org._50dozenhits.dice_library.client.ui.Look;
import org._50dozenhits.dice_library.client.ui.Looks;
import org._50dozenhits.dice_library.client.ui.PanelRenderer;
import org._50dozenhits.dice_library.client.ui.PanelUi;
import org._50dozenhits.dice_library.hologram.Holograms.HologramPayload;
import org._50dozenhits.dice_library.hologram.Holograms.HologramRemovePayload;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.ApiStatus;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * The holograms this client was told to show. The server decides who sees what. This only keeps the latest of each.
 */
@EventBusSubscriber(modid = DiceLib.MODID, value = Dist.CLIENT)
public final class ClientHolograms {
    /**
     * 1/64 of a block a pixel at scale 1
     */
    public static final float PIXEL = 1 / 64f;

    private static final Map<UUID, Shown> SHOWN = new LinkedHashMap<>();

    /**
     * One hologram on this client: its data, the UI that draws it, and the texture it is drawn into.
     */
    public static final class Shown {
        public HologramPayload data;
        public final PanelUi ui;
        @Nullable
        TextureTarget target;
        boolean dirty = true;
        boolean broken;
        // where the crosshair is on it, in GUI pixels, or null
        @Nullable
        int[] hover;
        @Nullable
        String hoveredButton;

        Shown(HologramPayload data) {
            this.data = data;
            this.ui = ui(data);
        }

        private static PanelUi ui(HologramPayload data) {
            CustomUis.HologramUi custom = CustomUis.hologram(data.style());
            if (custom != null) return custom.create(data);
            Look look = Looks.get(data.style());
            return new PanelRenderer(data.panel(), look, look.hologram(), data.width(), data.disabled(), data::follow);
        }

        /**
         * Where its centre is this frame
         */
        @Nullable
        public Vec3 center(float partialTick) {
            if (data.follow() < 0) return data.pos();
            Minecraft mc = Minecraft.getInstance();
            Entity entity = mc.level == null ? null : mc.level.getEntity(data.follow());
            return entity == null ? null : entity.getPosition(partialTick).add(data.offset());
        }

        /**
         * The panel's corners in the world: its centre, a unit vector to its right and one up (as the viewer sees it),
         * and its half width and half height in blocks
         */
        public record Frame(Vec3 center, Vec3 right, Vec3 up, Vec3 normal, double halfWidth, double halfHeight) {
        }

        @Nullable
        public Frame frame(Vec3 camera, float partialTick) {
            Vec3 center = center(partialTick);
            if (center == null || ui.height() <= 0) return null;
            Vec3 normal;
            if (data.billboard()) {
                Vec3 toCamera = new Vec3(camera.x - center.x, 0, camera.z - center.z);
                normal = toCamera.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : toCamera.normalize();
            } else {
                double radians = Math.toRadians(data.yaw());
                normal = new Vec3(-Math.sin(radians), 0, Math.cos(radians));
            }
            Vec3 right = new Vec3(normal.z, 0, -normal.x);
            double pixel = PIXEL * data.scale();
            return new Frame(center, right, new Vec3(0, 1, 0), normal, ui.width() * pixel / 2, ui.height() * pixel / 2);
        }

        // Every call into the UI goes through here, since a custom UI is another mod's code. One that throws is logged
        // and neither drawn nor asked again.
        boolean ask(String doing, BooleanSupplier call) {
            if (broken) return false;
            try {
                return call.getAsBoolean();
            } catch (RuntimeException e) {
                broken = true;
                free();
                DiceLib.LOGGER.error("Hologram {}'s UI threw while {}", data.id(), doing, e);
                return false;
            }
        }

        void gone() {
            ask("being removed", () -> {
                ui.removed();
                return true;
            });
            free();
        }

        void free() {
            if (target != null) {
                target.destroyBuffers();
                target = null;
            }
            dirty = true;
        }
    }

    @ApiStatus.Internal
    public static void accept(HologramPayload payload) {
        Shown old = SHOWN.get(payload.id());
        // a UI that says it can show the new content is kept, with its state (a tab, a scroll position, typed text)
        if (old != null && old.data.style().equals(payload.style()) && old.ask("taking new content", () -> old.ui.update(payload))) {
            old.data = payload;
            old.dirty = true;
            return;
        }
        if (old != null) {
            SHOWN.remove(payload.id());
            old.gone();
        }
        SHOWN.put(payload.id(), new Shown(payload));
    }

    @ApiStatus.Internal
    public static void remove(HologramRemovePayload payload) {
        Shown old = SHOWN.remove(payload.id());
        if (old != null) old.gone();
    }

    public static Collection<Shown> all() {
        return SHOWN.values();
    }

    public static void clear() {
        for (Shown shown : List.copyOf(SHOWN.values())) shown.gone();
        SHOWN.clear();
    }

    // live numbers (health) redraw the texture when they change
    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        for (Shown shown : SHOWN.values()) {
            if (shown.ask("ticking", shown.ui::tick)) shown.dirty = true;
        }
    }

    @SubscribeEvent
    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    // respawning or changing dimension: the server sends what is in view again
    @SubscribeEvent
    static void onClone(ClientPlayerNetworkEvent.Clone event) {
        clear();
    }

    private ClientHolograms() {
    }
}
