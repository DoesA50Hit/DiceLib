package org._50dozenhits.dice_library.client;

import com.zigythebird.playeranim.animation.PlayerAnimResources;
import com.zigythebird.playeranim.animation.PlayerAnimationController;
import com.zigythebird.playeranim.api.PlayerAnimationAccess;
import com.zigythebird.playeranim.api.PlayerAnimationFactory;
import com.zigythebird.playeranimcore.animation.layered.modifier.AbstractFadeModifier;
import com.zigythebird.playeranimcore.animation.layered.modifier.MirrorModifier;
import com.zigythebird.playeranimcore.animation.layered.modifier.SpeedModifier;
import com.zigythebird.playeranimcore.api.firstPerson.FirstPersonConfiguration;
import com.zigythebird.playeranimcore.api.firstPerson.FirstPersonMode;
import com.zigythebird.playeranimcore.easing.EasingType;
import com.zigythebird.playeranimcore.enums.PlayState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import org._50dozenhits.dice_library.DiceLib;
import org._50dozenhits.dice_library.visual.PlayerAnims.PlayerAnimPayload;
import org.jetbrains.annotations.ApiStatus;

/**
 * Plays what PlayerAnims sends on DiceLib's one animation layer. The layer sits at priority 1500, the library's band for
 * gameplay animations, so abilities win over emotes. In first person you see your arms move with the cast.
 */
@EventBusSubscriber(modid = DiceLib.MODID, value = Dist.CLIENT)
public final class ClientPlayerAnims {
    public static final ResourceLocation LAYER = DiceLib.id("moves");
    private static final int PRIORITY = 1500;
    private static final ResourceLocation REST = DiceLib.id("rest");

    // the library requires this inside enqueueWork
    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(LAYER, PRIORITY, player -> {
            PlayerAnimationController controller = new PlayerAnimationController(player, (self, data, setter) -> PlayState.STOP);
            controller.setFirstPersonMode(FirstPersonMode.THIRD_PERSON_MODEL);
            controller.setFirstPersonConfiguration(new FirstPersonConfiguration(true, true, true, true));
            return controller;
        }));
    }

    @ApiStatus.Internal
    public static void accept(PlayerAnimPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !(minecraft.level.getEntity(payload.entity()) instanceof AbstractClientPlayer player)) return;
        if (!(PlayerAnimationAccess.getPlayerAnimationLayer(player, LAYER) instanceof PlayerAnimationController controller)) return;
        payload.id().ifPresentOrElse(id -> {
            if (PlayerAnimResources.hasAnimation(id)) {
                // drop the last animation's speed and mirroring, then add this one's
                controller.removeModifierIf(modifier -> modifier instanceof SpeedModifier || modifier instanceof MirrorModifier);
                if (payload.speed() != 1) controller.addModifierLast(new SpeedModifier(payload.speed()));
                if (payload.mirrored()) controller.addModifierLast(new MirrorModifier());
                controller.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(payload.fade(), EasingType.EASE_OUT_SINE), id);
            } else {
                DiceLib.LOGGER.warn("No player animation {}", id);
            }
        }, () -> {
            if (!controller.isActive()) return;
            // eases back to standing through a short still animation, then the layer goes idle
            controller.removeModifierIf(modifier -> modifier instanceof SpeedModifier || modifier instanceof MirrorModifier);
            controller.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(payload.fade(), EasingType.EASE_IN_OUT_SINE), REST);
        });
    }

    public static boolean playing(AbstractClientPlayer player) {
        return PlayerAnimationAccess.getPlayerAnimationLayer(player, LAYER) instanceof PlayerAnimationController controller && controller.isActive();
    }
}
