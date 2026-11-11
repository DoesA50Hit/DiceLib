package org._50dozenhits.dice_library.client.render;

import net.minecraft.client.renderer.entity.NoopRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org._50dozenhits.dice_library.DiceLib;
import org._50dozenhits.dice_library.registry.DiceEntities;

/**
 * Renderers for the props. A shot draws nothing itself: its trail is what shows.
 */
@EventBusSubscriber(modid = DiceLib.MODID, value = Dist.CLIENT)
public final class DiceRenderers {
    @SubscribeEvent
    static void register(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(DiceEntities.FLOATING_ITEM.get(), FloatingItemRenderer::new);
        event.registerEntityRenderer(DiceEntities.TETHER.get(), TetherRenderer::new);
        event.registerEntityRenderer(DiceEntities.ANIMATED_PROP.get(), AnimatedPropRenderer::new);
        event.registerEntityRenderer(DiceEntities.ENTITY_COPY.get(), EntityCopyRenderer::new);
        event.registerEntityRenderer(DiceEntities.SHOT.get(), NoopRenderer::new);
    }
}
