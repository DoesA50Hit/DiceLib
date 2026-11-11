package org._50dozenhits.dice_library.client.hologram;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org._50dozenhits.dice_library.DiceLib;
import org._50dozenhits.dice_library.client.hologram.ClientHolograms.Shown;
import org._50dozenhits.dice_library.client.ui.PanelRenderer;
import org._50dozenhits.dice_library.hologram.Hologram;
import org._50dozenhits.dice_library.hologram.Holograms.HologramClickPayload;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Aiming at a hologram: finds the panel under the crosshair (nearer than any block), lights up the button there, and turns
 * a right-click on a button into a click sent to the server. A right-click anywhere on a panel does nothing else, so
 * it never places a block behind it. A UI that takes clicks itself (PanelUi.mouseClicked, as a HologramScreen does) gets
 * left and right clicks first, and the scroll wheel while it is aimed at.
 */
@EventBusSubscriber(modid = DiceLib.MODID, value = Dist.CLIENT)
public final class HologramPicker {
    @Nullable
    private static Shown aimed;

    @Nullable
    public static Shown aimed() {
        return aimed;
    }

    // Each frame before painting: where the crosshair meets the nearest panel
    static void pick(float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Shown best = null;
        int[] bestPoint = null;
        if (player != null && mc.screen == null) {
            Vec3 eye = player.getEyePosition(partialTick);
            Vec3 look = player.getViewVector(partialTick);
            double limit = Hologram.REACH;
            HitResult block = mc.hitResult;
            if (block != null && block.getType() != HitResult.Type.MISS) limit = Math.min(limit, block.getLocation().distanceTo(eye));
            for (Shown shown : ClientHolograms.all()) {
                if (shown.target == null || shown.broken) continue;
                Shown.Frame frame = shown.frame(eye, partialTick);
                if (frame == null) continue;
                double facing = look.dot(frame.normal());
                if (Math.abs(facing) < 1e-4) continue;
                double t = frame.center().subtract(eye).dot(frame.normal()) / facing;
                if (t <= 0 || t > limit) continue;
                Vec3 hit = eye.add(look.scale(t)).subtract(frame.center());
                double across = hit.dot(frame.right()), up = hit.dot(frame.up());
                if (Math.abs(across) > frame.halfWidth() || Math.abs(up) > frame.halfHeight()) continue;
                limit = t;
                best = shown;
                // GUI pixels from the panel's top left. The back of a fixed panel reads mirrored
                double x = (across / frame.halfWidth() + 1) / 2 * shown.ui.width();
                if (facing > 0) x = shown.ui.width() - x;
                double y = (1 - up / frame.halfHeight()) / 2 * shown.ui.height();
                bestPoint = new int[]{(int) Math.floor(x), (int) Math.floor(y)};
            }
        }
        for (Shown shown : ClientHolograms.all()) {
            int[] point = shown == best ? bestPoint : null;
            String under = point == null ? null : shown.ui.buttonAt(point[0], point[1]);
            boolean moved = point == null ? shown.hover != null : shown.hover == null || point[0] != shown.hover[0] || point[1] != shown.hover[1];
            // DiceLib's own panel only changes when the button under the crosshair does. A custom UI may light up
            // anything, so it redraws whenever the crosshair moves on it
            boolean custom = !(shown.ui instanceof PanelRenderer);
            if (!Objects.equals(under, shown.hoveredButton) || (point == null) != (shown.hover == null) || custom && moved) {
                shown.dirty = true;
            }
            shown.hover = point;
            shown.hoveredButton = under;
        }
        aimed = best;
    }

    @Nullable
    private static String aimedButton() {
        Shown shown = aimed;
        if (shown == null || shown.hover == null) return null;
        return shown.ui.buttonAt(shown.hover[0], shown.hover[1]);
    }

    @SubscribeEvent
    static void onUse(InputEvent.InteractionKeyMappingTriggered event) {
        Shown shown = aimed;
        if (shown == null || !(event.isUseItem() || event.isAttack())) return;
        if (shown.hover != null && clicked(shown, event.isAttack() ? 0 : 1)) {
            event.setCanceled(true);
            event.setSwingHand(true);
            return;
        }
        // a left click the UI didn't take goes on to the world behind
        if (!event.isUseItem()) return;
        event.setCanceled(true);
        String button = aimedButton();
        if (button == null || shown.data.disabled().contains(button)) {
            event.setSwingHand(false);
            return;
        }
        event.setSwingHand(true);
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1));
        PacketDistributor.sendToServer(new HologramClickPayload(shown.data.id(), shown.data.version(), button));
    }

    private static boolean clicked(Shown shown, int button) {
        int x = shown.hover[0], y = shown.hover[1];
        if (!shown.ask("handling a click", () -> shown.ui.mouseClicked(x, y, button))) return false;
        shown.dirty = true;
        return true;
    }

    @SubscribeEvent
    static void onScroll(InputEvent.MouseScrollingEvent event) {
        Shown shown = aimed;
        if (shown == null || shown.hover == null || event.getScrollDeltaY() == 0) return;
        int x = shown.hover[0], y = shown.hover[1];
        if (!shown.ask("handling a scroll", () -> shown.ui.mouseScrolled(x, y, event.getScrollDeltaY()))) return;
        shown.dirty = true;
        event.setCanceled(true);
    }

    private HologramPicker() {
    }
}
