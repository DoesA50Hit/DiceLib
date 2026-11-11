package org._50dozenhits.dice_library.network;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org._50dozenhits.dice_library.DiceLib;
import org._50dozenhits.dice_library.client.ClientFx;
import org._50dozenhits.dice_library.client.ClientPlayerAnims;
import org._50dozenhits.dice_library.client.dialogue.DialogueScreen;
import org._50dozenhits.dice_library.client.dialogue.SpeechHud;
import org._50dozenhits.dice_library.client.hologram.ClientHolograms;
import org._50dozenhits.dice_library.client.hologram.MobPanelHud;
import org._50dozenhits.dice_library.dialogue.Dialogues;
import org._50dozenhits.dice_library.dialogue.Speech;
import org._50dozenhits.dice_library.hologram.Holograms;
import org._50dozenhits.dice_library.hologram.MobPanels;
import org._50dozenhits.dice_library.visual.Fx;
import org._50dozenhits.dice_library.visual.PlayerAnims;
import org.jetbrains.annotations.ApiStatus;

/**
 * The payload records live on the helpers that send them. Client handlers are wrapped in lambdas, so a dedicated
 * server never loads a client class. Handlers run on the main thread, the registrar's default.
 */
@EventBusSubscriber(modid = DiceLib.MODID)
@ApiStatus.Internal
public final class DiceNetwork {
    @SubscribeEvent
    static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(Fx.FxPayload.TYPE, Fx.FxPayload.STREAM_CODEC, (payload, context) -> ClientFx.accept(payload));
        registrar.playToClient(Fx.FxFlightPayload.TYPE, Fx.FxFlightPayload.STREAM_CODEC, (payload, context) -> ClientFx.accept(payload));
        registrar.playToClient(PlayerAnims.PlayerAnimPayload.TYPE, PlayerAnims.PlayerAnimPayload.STREAM_CODEC, (payload, context) -> ClientPlayerAnims.accept(payload));
        registrar.playToClient(Speech.SpeechPayload.TYPE, Speech.SpeechPayload.STREAM_CODEC, (payload, context) -> SpeechHud.accept(payload));
        registrar.playToClient(Dialogues.DialogueNodePayload.TYPE, Dialogues.DialogueNodePayload.STREAM_CODEC, (payload, context) -> DialogueScreen.accept(payload));
        registrar.playToClient(Dialogues.DialogueEndPayload.TYPE, Dialogues.DialogueEndPayload.STREAM_CODEC, (payload, context) -> DialogueScreen.end(payload));
        registrar.playToServer(Dialogues.DialogueReplyPayload.TYPE, Dialogues.DialogueReplyPayload.STREAM_CODEC, Dialogues.DialogueReplyPayload::handle);
        registrar.playToClient(Holograms.HologramPayload.TYPE, Holograms.HologramPayload.STREAM_CODEC, (payload, context) -> ClientHolograms.accept(payload));
        registrar.playToClient(Holograms.HologramRemovePayload.TYPE, Holograms.HologramRemovePayload.STREAM_CODEC, (payload, context) -> ClientHolograms.remove(payload));
        registrar.playToServer(Holograms.HologramClickPayload.TYPE, Holograms.HologramClickPayload.STREAM_CODEC, Holograms.HologramClickPayload::handle);
        registrar.playToServer(Holograms.HologramMessagePayload.TYPE, Holograms.HologramMessagePayload.STREAM_CODEC, Holograms.HologramMessagePayload::handle);
        registrar.playToClient(MobPanels.MobPanelPayload.TYPE, MobPanels.MobPanelPayload.STREAM_CODEC, (payload, context) -> MobPanelHud.accept(payload));
        registrar.playToClient(MobPanels.MobPanelRemovePayload.TYPE, MobPanels.MobPanelRemovePayload.STREAM_CODEC, (payload, context) -> MobPanelHud.remove(payload));
    }
}
