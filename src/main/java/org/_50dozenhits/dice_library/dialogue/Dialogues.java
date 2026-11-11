package org._50dozenhits.dice_library.dialogue;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org._50dozenhits.dice_library.DiceLib;
import org._50dozenhits.dice_library.dialogue.DialogueTree.Choice;
import org._50dozenhits.dice_library.dialogue.DialogueTree.Line;
import org._50dozenhits.dice_library.dialogue.DialogueTree.Node;
import org._50dozenhits.dice_library.state.CasterState;
import org._50dozenhits.dice_library.ui.UiActions;
import org._50dozenhits.dice_library.registry.DiceAttachments;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Dialogue trees: conversations read from datapacks (DialogueTree has the file format), each player in at most one.
 * The player picks the choices. Players near the speaker overhear the lines as subtitles. Server side:
 *
 * <pre>{@code
 * Dialogues.start(player, DiceLib.id("smith"), smithEntity);     Dialogues.bind(smithEntity, DiceLib.id("smith"));
 * Dialogues.end(player);                                       Dialogues.active(player)
 * }</pre>
 *
 * <p>A right-click on a bound entity (main hand, not sneaking) starts its tree instead of the vanilla interaction. A
 * conversation ends at an end node or choice, when the player closes it, when the speaker dies or is left more than
 * the tree's range behind, or when the player dies or logs out. Choices and actions are checked and run here only.
 * The client sees the node it is on and nothing else.
 */
@EventBusSubscriber(modid = DiceLib.MODID)
public final class Dialogues {
    public static final ResourceLocation NONE = DiceLib.id("none");
    public static final String FOLDER = "dicelib/dialogues";
    /**
     * a reply that isn't a choice: move on from a node without choices, or close
     */
    public static final int ADVANCE = -1;
    public static final int CLOSE = -2;
    // nodes entered in a row without anything to show, before it gives up on a loop
    private static final int MAX_CHAIN = 32;

    private static Map<ResourceLocation, DialogueTree> trees = Map.of();
    private static Map<ResourceLocation, List<String>> problems = Map.of();
    // trees registered from code, kept across reloads
    private static final Map<ResourceLocation, DialogueTree> REGISTERED = new ConcurrentHashMap<>();

    public enum EndReason {
        /**
         * an end node or choice
         */
        FINISHED,
        /**
         * the player closed it
         */
        CLOSED,
        /**
         * end() from code, a command or the dicelib:close action
         */
        ENDED,
        /**
         * another dialogue started for the player
         */
        REPLACED,
        SPEAKER_GONE,
        TOO_FAR,
        /**
         * the player died, logged out or changed dimension
         */
        PLAYER_GONE,
        /**
         * a node the tree names is missing, or nodes loop with nothing to show
         */
        BROKEN
    }

    public static final class Session {
        private final ServerPlayer player;
        private final DialogueTree tree;
        @Nullable
        private final Entity speaker;
        private String node;
        private int step;
        // the node's choices on screen, by their index in the node
        private List<Integer> shown = List.of();
        private boolean over;

        private Session(ServerPlayer player, DialogueTree tree, @Nullable Entity speaker, String node) {
            this.player = player;
            this.tree = tree;
            this.speaker = speaker;
            this.node = node;
        }

        public ServerPlayer player() {
            return player;
        }

        public DialogueTree tree() {
            return tree;
        }

        @Nullable
        public Entity speaker() {
            return speaker;
        }

        public String node() {
            return node;
        }

        public int step() {
            return step;
        }

        public UiActions.Context context() {
            return new UiActions.Context(player.serverLevel(), speaker, tree.id(), node, null);
        }
    }

    private static final CasterState<Session> SESSIONS = CasterState.create((id, session) -> finish(session, EndReason.PLAYER_GONE));

    // ---- trees ----

    public static Optional<DialogueTree> lookup(ResourceLocation id) {
        DialogueTree tree = REGISTERED.get(id);
        return Optional.ofNullable(tree != null ? tree : trees.get(id));
    }

    public static List<ResourceLocation> ids() {
        List<ResourceLocation> ids = new ArrayList<>(trees.keySet());
        for (ResourceLocation id : REGISTERED.keySet()) if (!ids.contains(id)) ids.add(id);
        ids.addAll(problems.keySet().stream().filter(id -> !ids.contains(id)).toList());
        ids.sort(null);
        return ids;
    }

    /**
     * what was wrong with a file when it loaded. Empty when nothing was
     */
    public static List<String> problems(ResourceLocation id) {
        return problems.getOrDefault(id, List.of());
    }

    /**
     * A tree built in Java (DialogueTree.parse on a JsonObject you made), kept over datapack ones of the same id
     */
    public static void register(DialogueTree tree) {
        REGISTERED.put(tree.id(), tree);
    }

    // ---- sessions ----

    public static boolean start(ServerPlayer player, ResourceLocation id) {
        return start(player, id, null, null);
    }

    public static boolean start(ServerPlayer player, ResourceLocation id, @Nullable Entity speaker) {
        return start(player, id, speaker, null);
    }

    /**
     * Starts the tree at node (its start node when null), ending any dialogue the player is in. False for an unknown
     * tree or node, or when a DialogueEvent.Start listener cancels it.
     */
    public static boolean start(ServerPlayer player, ResourceLocation id, @Nullable Entity speaker, @Nullable String node) {
        DialogueTree tree = lookup(id).orElse(null);
        if (tree == null) return false;
        String first = node == null ? tree.start() : node;
        if (tree.node(first) == null) return false;
        if (NeoForge.EVENT_BUS.post(new DialogueEvent.Start(player, tree, speaker, first)).isCanceled()) return false;
        Session old = SESSIONS.take(player);
        if (old != null) {
            old.over = true;
            NeoForge.EVENT_BUS.post(new DialogueEvent.End(player, old, EndReason.REPLACED));
        }
        Session session = new Session(player, tree, speaker, first);
        SESSIONS.put(player, session);
        enter(session, first, 0);
        return true;
    }

    @Nullable
    public static Session active(ServerPlayer player) {
        return SESSIONS.get(player);
    }

    public static boolean inDialogue(ServerPlayer player) {
        return SESSIONS.has(player);
    }

    public static boolean end(ServerPlayer player) {
        return end(player, EndReason.ENDED);
    }

    public static boolean end(ServerPlayer player, EndReason reason) {
        Session session = SESSIONS.take(player);
        if (session == null) return false;
        finish(session, reason);
        return true;
    }

    private static void finish(Session session, EndReason reason) {
        if (session.over) return;
        session.over = true;
        if (!session.player.hasDisconnected()) PacketDistributor.sendToPlayer(session.player, new DialogueEndPayload(session.step));
        NeoForge.EVENT_BUS.post(new DialogueEvent.End(session.player, session, reason));
    }

    private static boolean current(Session session) {
        return !session.over && SESSIONS.get(session.player) == session;
    }

    // Moves to a node: runs its actions, then shows it, or moves on immediately when it has nothing to show
    private static void enter(Session session, String name, int chain) {
        Node node = session.tree.node(name);
        if (node == null || chain > MAX_CHAIN) {
            DiceLib.LOGGER.warn("Dialogue {} broke at node {}", session.tree.id(), name);
            end(session.player, EndReason.BROKEN);
            return;
        }
        session.node = name;
        session.step++;
        int step = session.step;
        NeoForge.EVENT_BUS.post(new DialogueEvent.Node(session.player, session, node));
        UiActions.runAll(node.actions(), session.player, session.context());
        // an action may have ended this dialogue, started another or moved on
        if (!current(session) || session.step != step) return;
        if (node.lines().isEmpty() && visibleChoices(session, node).isEmpty()) {
            moveOn(session, node, chain + 1);
            return;
        }
        show(session, node, false);
        overhear(session, node);
    }

    // after a node without choices: its next, or the end
    private static void moveOn(Session session, Node node, int chain) {
        if (node.end() || node.next() == null) end(session.player, EndReason.FINISHED);
        else enter(session, node.next(), chain);
    }

    private static List<Integer> visibleChoices(Session session, Node node) {
        List<Integer> visible = new ArrayList<>();
        for (int i = 0; i < node.choices().size(); i++) {
            Choice choice = node.choices().get(i);
            if (choice.locked() || UiActions.testAll(choice.conditions(), session.player, session.context())) visible.add(i);
        }
        return visible;
    }

    /**
     * A value for {name} in dialogue text, for the player it is shown to and the entity speaking (null when none).
     * Return null to leave the placeholder as written.
     */
    @FunctionalInterface
    public interface Placeholder {
        @Nullable
        Component value(ServerPlayer player, @Nullable Entity speaker);
    }

    private static final Map<String, Placeholder> PLACEHOLDERS = new ConcurrentHashMap<>();

    /**
     * Adds {name} to every dialogue's text, next to the built-in {player} and {speaker}. Names are lower case letters,
     * digits and _ . : -, and yours should start with your mod id. Register once at startup:
     *
     * <pre>{@code
     * Dialogues.placeholder("mymod:coins", (player, speaker) -> Component.literal(String.valueOf(Coins.of(player))));
     * }</pre>
     */
    public static void placeholder(String name, Placeholder placeholder) {
        if (name.equals("player") || name.equals("speaker")) throw new IllegalArgumentException("{" + name + "} is built in");
        PLACEHOLDERS.put(name, placeholder);
    }

    // what fills a session's placeholders, `speaker` being the name {speaker} becomes
    private static Function<String, @Nullable Component> values(Session session, Component speaker) {
        return name -> switch (name) {
            case "player" -> session.player.getDisplayName();
            case "speaker" -> speaker;
            default -> {
                Placeholder placeholder = PLACEHOLDERS.get(name);
                yield placeholder == null ? null : placeholder.value(session.player, session.speaker);
            }
        };
    }

    private static Component speakerName(Session session) {
        if (session.tree.speaker() != null) return session.tree.speaker().resolve(values(session, Component.empty()));
        return session.speaker == null ? Component.empty() : session.speaker.getDisplayName();
    }

    // again: the same node shown once more (a choice that stays), so the screen goes straight to the choices
    private static void show(Session session, Node node, boolean again) {
        Component speaker = speakerName(session);
        Function<String, @Nullable Component> values = values(session, speaker);
        List<Said> lines = new ArrayList<>();
        for (Line line : node.lines()) {
            Component name = line.speaker() == null ? speaker : line.speaker().resolve(values);
            lines.add(new Said(name, line.text().resolve(values)));
        }
        session.shown = visibleChoices(session, node);
        List<Option> options = new ArrayList<>();
        for (int index : session.shown) {
            Choice choice = node.choices().get(index);
            boolean open = !choice.locked() || UiActions.testAll(choice.conditions(), session.player, session.context());
            options.add(new Option(choice.text().resolve(values), open));
        }
        boolean last = node.choices().isEmpty() && (node.end() || node.next() == null);
        Optional<Holder<SoundEvent>> voice = session.tree.voice() == null ? Optional.empty()
                : BuiltInRegistries.SOUND_EVENT.getHolder(session.tree.voice()).map(holder -> holder);
        PacketDistributor.sendToPlayer(session.player, new DialogueNodePayload(session.step, session.speaker == null ? -1 : session.speaker.getId(),
                speaker, session.tree.portraitItem(), session.tree.speakerPortrait(), lines, options, node.canClose(), last, again, voice,
                session.tree.pitch(), session.tree.style()));
    }

    /**
     * The players who see a session's lines as subtitles: those within the tree's overhear range of the speaker (or
     * of the player, with no speaker), the player themself left out
     */
    public static List<ServerPlayer> overhearers(Session session) {
        if (session.tree.overhear() <= 0) return List.of();
        Entity from = session.speaker != null ? session.speaker : session.player;
        double rangeSq = session.tree.overhear() * session.tree.overhear();
        return session.player.serverLevel().players().stream()
                .filter(p -> p != session.player && p.distanceToSqr(from) <= rangeSq).toList();
    }

    private static void overhear(Session session, Node node) {
        if (node.lines().isEmpty()) return;
        List<ServerPlayer> near = overhearers(session);
        if (near.isEmpty()) return;
        Component speaker = speakerName(session);
        Function<String, @Nullable Component> values = values(session, speaker);
        for (Line line : node.lines()) {
            Component name = line.speaker() == null ? speaker : line.speaker().resolve(values);
            Component text = line.text().resolve(values);
            Speech.Line said = session.speaker != null ? Speech.of(session.speaker, text) : Speech.of(name, text);
            said.name(name).style(session.tree.style()).to(near).send();
        }
    }

    /**
     * A reply from the player's screen: a choice index (of those shown), ADVANCE or CLOSE, for the step it showed.
     * Anything for another step is a stale or doubled click and does nothing.
     */
    public static void reply(ServerPlayer player, int step, int choice) {
        Session session = SESSIONS.get(player);
        if (session == null || session.step != step) return;
        Node node = session.tree.node(session.node);
        if (node == null) return;
        if (choice == CLOSE) {
            if (node.canClose()) end(player, EndReason.CLOSED);
            return;
        }
        if (choice == ADVANCE) {
            if (session.shown.isEmpty()) moveOn(session, node, 0);
            return;
        }
        if (choice < 0 || choice >= session.shown.size()) return;
        int index = session.shown.get(choice);
        Choice picked = node.choices().get(index);
        // things may have changed since it was shown
        if (!UiActions.testAll(picked.conditions(), player, session.context())) {
            session.step++;
            show(session, node, true);
            return;
        }
        if (NeoForge.EVENT_BUS.post(new DialogueEvent.Choice(player, session, node, index)).isCanceled()) return;
        session.step++;
        int stepped = session.step;
        UiActions.runAll(picked.actions(), player, session.context());
        if (!current(session) || session.step != stepped) return;
        if (picked.end()) end(player, EndReason.FINISHED);
        else if (picked.next() != null) enter(session, picked.next(), 0);
        else show(session, node, true);
    }

    // ---- binding ----

    /**
     * A right-click on the entity starts this tree. Saved with the entity.
     */
    public static void bind(Entity entity, ResourceLocation id) {
        entity.setData(DiceAttachments.DIALOGUE, id);
    }

    public static void unbind(Entity entity) {
        entity.removeData(DiceAttachments.DIALOGUE);
    }

    public static Optional<ResourceLocation> bound(Entity entity) {
        ResourceLocation id = entity.getExistingData(DiceAttachments.DIALOGUE).orElse(NONE);
        return id.equals(NONE) ? Optional.empty() : Optional.of(id);
    }

    // Both sides: the client cancels its own interaction too, so a villager's trade screen never opens
    @SubscribeEvent
    static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (interact(event.getEntity(), event.getTarget(), event.getHand())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    // The click on a spot of the entity comes first, and is all some entities listen to (an armour stand)
    @SubscribeEvent
    static void onInteractAt(PlayerInteractEvent.EntityInteractSpecific event) {
        if (interact(event.getEntity(), event.getTarget(), event.getHand())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    // True when the entity is bound, so the click is the dialogue's. One click can arrive as both events. The second
    // finds the dialogue it started already running and leaves it be.
    private static boolean interact(Player player, Entity target, InteractionHand hand) {
        if (player.isSecondaryUseActive()) return false;
        Optional<ResourceLocation> tree = bound(target);
        if (tree.isEmpty()) return false;
        if (hand == InteractionHand.MAIN_HAND && player instanceof ServerPlayer server) {
            Session running = SESSIONS.get(server);
            if (running == null || running.speaker != target || !running.tree.id().equals(tree.get())) start(server, tree.get(), target);
        }
        return true;
    }

    // ---- range and speaker checks ----

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 10 != 0) return;
        for (Map.Entry<UUID, Session> entry : SESSIONS.view().entrySet()) {
            Session session = entry.getValue();
            Entity speaker = session.speaker;
            if (speaker == null) continue;
            if (!speaker.isAlive() || speaker.isRemoved()) end(session.player, EndReason.SPEAKER_GONE);
            else if (speaker.level() != session.player.level()
                    || speaker.distanceToSqr(session.player) > session.tree.range() * session.tree.range()) end(session.player, EndReason.TOO_FAR);
        }
    }

    // ---- loading ----

    @SubscribeEvent
    static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new Loader());
    }

    private static final class Loader extends SimpleJsonResourceReloadListener {
        Loader() {
            super(new Gson(), FOLDER);
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resources, ProfilerFiller profiler) {
            Map<ResourceLocation, DialogueTree> loaded = new HashMap<>();
            Map<ResourceLocation, List<String>> found = new HashMap<>();
            files.forEach((id, json) -> {
                DialogueTree.Parsed parsed = DialogueTree.parse(id, json);
                if (parsed.tree() != null) loaded.put(id, parsed.tree());
                if (!parsed.problems().isEmpty()) {
                    found.put(id, List.copyOf(parsed.problems()));
                    for (String problem : parsed.problems()) DiceLib.LOGGER.warn("Dialogue {}: {}", id, problem);
                }
            });
            trees = Map.copyOf(loaded);
            problems = Map.copyOf(found);
            DiceLib.LOGGER.debug("Loaded {} dialogues", loaded.size());
        }
    }

    // ---- payloads ----

    /**
     * one line on screen
     */
    public record Said(Component name, Component text) {
        @ApiStatus.Internal
        public static final StreamCodec<RegistryFriendlyByteBuf, Said> STREAM_CODEC = StreamCodec.composite(
                ComponentSerialization.STREAM_CODEC, Said::name,
                ComponentSerialization.STREAM_CODEC, Said::text,
                Said::new);
    }

    /**
     * one choice on screen. A locked one shows greyed out
     */
    public record Option(Component text, boolean open) {
        @ApiStatus.Internal
        public static final StreamCodec<RegistryFriendlyByteBuf, Option> STREAM_CODEC = StreamCodec.composite(
                ComponentSerialization.STREAM_CODEC, Option::text,
                ByteBufCodecs.BOOL, Option::open,
                Option::new);
    }

    /**
     * Server to client: the node to show. last is true when moving on ends the dialogue, and again when it is the node
     * already on screen, shown once more after a choice that stays.
     */
    public record DialogueNodePayload(int step, int speaker, Component name, ItemStack portrait, boolean speakerPortrait, List<Said> lines,
                                      List<Option> choices, boolean canClose, boolean last, boolean again, Optional<Holder<SoundEvent>> voice, float pitch,
                                      ResourceLocation style) implements CustomPacketPayload {
        @ApiStatus.Internal
        public static final Type<DialogueNodePayload> TYPE = new Type<>(DiceLib.id("dialogue_node"));

        @ApiStatus.Internal
        public static final StreamCodec<RegistryFriendlyByteBuf, DialogueNodePayload> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public DialogueNodePayload decode(RegistryFriendlyByteBuf buf) {
                return new DialogueNodePayload(
                        ByteBufCodecs.VAR_INT.decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf),
                        ComponentSerialization.STREAM_CODEC.decode(buf),
                        ItemStack.OPTIONAL_STREAM_CODEC.decode(buf),
                        ByteBufCodecs.BOOL.decode(buf),
                        Said.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf),
                        Option.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf),
                        ByteBufCodecs.BOOL.decode(buf),
                        ByteBufCodecs.BOOL.decode(buf),
                        ByteBufCodecs.BOOL.decode(buf),
                        ByteBufCodecs.optional(SoundEvent.STREAM_CODEC).decode(buf),
                        ByteBufCodecs.FLOAT.decode(buf),
                        ResourceLocation.STREAM_CODEC.decode(buf));
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buf, DialogueNodePayload payload) {
                ByteBufCodecs.VAR_INT.encode(buf, payload.step());
                ByteBufCodecs.VAR_INT.encode(buf, payload.speaker());
                ComponentSerialization.STREAM_CODEC.encode(buf, payload.name());
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, payload.portrait());
                ByteBufCodecs.BOOL.encode(buf, payload.speakerPortrait());
                Said.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, payload.lines());
                Option.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, payload.choices());
                ByteBufCodecs.BOOL.encode(buf, payload.canClose());
                ByteBufCodecs.BOOL.encode(buf, payload.last());
                ByteBufCodecs.BOOL.encode(buf, payload.again());
                ByteBufCodecs.optional(SoundEvent.STREAM_CODEC).encode(buf, payload.voice());
                ByteBufCodecs.FLOAT.encode(buf, payload.pitch());
                ResourceLocation.STREAM_CODEC.encode(buf, payload.style());
            }
        };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Client to server: a choice (index among those shown), ADVANCE or CLOSE, for the step on screen
     */
    public record DialogueReplyPayload(int step, int choice) implements CustomPacketPayload {
        @ApiStatus.Internal
        public static final Type<DialogueReplyPayload> TYPE = new Type<>(DiceLib.id("dialogue_reply"));

        @ApiStatus.Internal
        public static final StreamCodec<ByteBuf, DialogueReplyPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, DialogueReplyPayload::step,
                ByteBufCodecs.VAR_INT, DialogueReplyPayload::choice,
                DialogueReplyPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        @ApiStatus.Internal
        public static void handle(DialogueReplyPayload payload, IPayloadContext context) {
            if (context.player() instanceof ServerPlayer player) reply(player, payload.step(), payload.choice());
        }
    }

    /**
     * Server to client: the dialogue is over. Closes the screen
     */
    public record DialogueEndPayload(int step) implements CustomPacketPayload {
        @ApiStatus.Internal
        public static final Type<DialogueEndPayload> TYPE = new Type<>(DiceLib.id("dialogue_end"));

        @ApiStatus.Internal
        public static final StreamCodec<ByteBuf, DialogueEndPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, DialogueEndPayload::step,
                DialogueEndPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---- events ----

    /**
     * Server side, on NeoForge.EVENT_BUS.
     */
    public abstract static class DialogueEvent extends PlayerEvent {
        protected DialogueEvent(ServerPlayer player) {
            super(player);
        }

        public ServerPlayer getServerPlayer() {
            return (ServerPlayer) getEntity();
        }

        /**
         * Before a dialogue starts. Cancel to stop it
         */
        public static class Start extends DialogueEvent implements ICancellableEvent {
            private final DialogueTree tree;
            @Nullable
            private final Entity speaker;
            private final String node;

            public Start(ServerPlayer player, DialogueTree tree, @Nullable Entity speaker, String node) {
                super(player);
                this.tree = tree;
                this.speaker = speaker;
                this.node = node;
            }

            public DialogueTree getTree() {
                return tree;
            }

            @Nullable
            public Entity getSpeaker() {
                return speaker;
            }

            public String getNode() {
                return node;
            }
        }

        /**
         * A node was entered, before its actions run
         */
        public static class Node extends DialogueEvent {
            private final Session session;
            private final DialogueTree.Node node;

            public Node(ServerPlayer player, Session session, DialogueTree.Node node) {
                super(player);
                this.session = session;
                this.node = node;
            }

            public Session getSession() {
                return session;
            }

            public DialogueTree.Node getNode() {
                return node;
            }
        }

        /**
         * A choice was picked, before its actions run. Cancel to ignore the click
         */
        public static class Choice extends DialogueEvent implements ICancellableEvent {
            private final Session session;
            private final DialogueTree.Node node;
            private final int index;

            public Choice(ServerPlayer player, Session session, DialogueTree.Node node, int index) {
                super(player);
                this.session = session;
                this.node = node;
                this.index = index;
            }

            public Session getSession() {
                return session;
            }

            public DialogueTree.Node getNode() {
                return node;
            }

            /**
             * the choice's index in the node's list (not among those shown)
             */
            public int getIndex() {
                return index;
            }

            public DialogueTree.Choice getChoice() {
                return node.choices().get(index);
            }
        }

        /**
         * The dialogue is over
         */
        public static class End extends DialogueEvent {
            private final Session session;
            private final EndReason reason;

            public End(ServerPlayer player, Session session, EndReason reason) {
                super(player);
                this.session = session;
                this.reason = reason;
            }

            public Session getSession() {
                return session;
            }

            public EndReason getReason() {
                return reason;
            }
        }
    }

    private Dialogues() {
    }
}
