package org._50dozenhits.dice_library.command;

import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org._50dozenhits.dice_library.command.CommandRegistry.Command;
import org._50dozenhits.dice_library.command.CommandRegistry.DoubleArg;
import org._50dozenhits.dice_library.command.CommandRegistry.EntityArg;
import org._50dozenhits.dice_library.command.CommandRegistry.Execute;
import org._50dozenhits.dice_library.command.CommandRegistry.LiteralArg;
import org._50dozenhits.dice_library.command.CommandRegistry.Permission;
import org._50dozenhits.dice_library.command.CommandRegistry.SenderArg;
import org._50dozenhits.dice_library.command.CommandRegistry.TextArg;
import org._50dozenhits.dice_library.command.CommandRegistry.UuidArg;
import org._50dozenhits.dice_library.dialogue.DialogueTree;
import org._50dozenhits.dice_library.dialogue.Dialogues;
import org._50dozenhits.dice_library.dialogue.Speech;
import org._50dozenhits.dice_library.hologram.Hologram;
import org._50dozenhits.dice_library.hologram.Holograms;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.ApiStatus;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * /dicelib dialogue ... and /dicelib hologram ...: for trying dialogue trees, spoken lines and hologram templates
 * without code. Operators only. Also the two argument types other commands can use: @DialogueArg and @HologramArg.
 */
public final class DiceCommands {
    static final DynamicCommandExceptionType UNKNOWN_DIALOGUE = new DynamicCommandExceptionType(
            id -> Component.translatable("dicelib.command.unknown_dialogue", String.valueOf(id)));
    static final DynamicCommandExceptionType UNKNOWN_HOLOGRAM = new DynamicCommandExceptionType(
            id -> Component.translatable("dicelib.command.unknown_hologram", String.valueOf(id)));

    /**
     * Registers the argument types and /dicelib. Called once from the mod constructor.
     */
    @ApiStatus.Internal
    public static void init() {
        CommandRegistry.registerArgument(DialogueArg.class, ResourceLocation.class, (arg, handler) -> {
            String name = handler.name(arg.value());
            handler.addNode(Commands.argument(name, ResourceLocationArgument.id()).suggests((context, builder) -> suggestIds(builder, Dialogues.ids())));
            handler.addValueExtractor(context -> {
                ResourceLocation id = resolve(ResourceLocationArgument.getId(context, name), Dialogues.ids());
                if (Dialogues.lookup(id).isEmpty()) throw UNKNOWN_DIALOGUE.create(id);
                return id;
            });
        });
        CommandRegistry.registerArgument(HologramArg.class, ResourceLocation.class, (arg, handler) -> {
            String name = handler.name(arg.value());
            handler.addNode(Commands.argument(name, ResourceLocationArgument.id()).suggests((context, builder) -> suggestIds(builder, Holograms.templateIds())));
            handler.addValueExtractor(context -> {
                ResourceLocation id = resolve(ResourceLocationArgument.getId(context, name), Holograms.templateIds());
                if (Holograms.template(id).isEmpty()) throw UNKNOWN_HOLOGRAM.create(id);
                return id;
            });
        });
        CommandRegistry.registerCommand(Root.class);
    }

    /**
     * An id typed without a namespace parses as {@code minecraft:<path>}. When no such id is known, this picks the one known id
     * with that path, so "smith" finds mymod:smith. Ids typed with a namespace come back as they are.
     */
    public static ResourceLocation resolve(ResourceLocation typed, Collection<ResourceLocation> known) {
        if (!typed.getNamespace().equals(ResourceLocation.DEFAULT_NAMESPACE) || known.contains(typed)) return typed;
        List<ResourceLocation> matches = known.stream().filter(id -> id.getPath().equals(typed.getPath())).toList();
        return matches.size() == 1 ? matches.get(0) : typed;
    }

    /**
     * Suggests the ids that match what is typed so far, by full id or by path alone.
     */
    public static CompletableFuture<Suggestions> suggestIds(SuggestionsBuilder builder, Collection<ResourceLocation> ids) {
        String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (ResourceLocation id : ids) {
            if (SharedSuggestionProvider.matchesSubStr(typed, id.toString()) || SharedSuggestionProvider.matchesSubStr(typed, id.getPath())) {
                builder.suggest(id.toString());
            }
        }
        return builder.buildFuture();
    }

    /**
     * A dialogue tree id with tab completion. The namespace can be left off.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface DialogueArg {
        String value() default "dialogue";
    }

    /**
     * A hologram template id with tab completion. The namespace can be left off.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface HologramArg {
        String value() default "template";
    }

    @Command(value = "dicelib", subCommands = {DialogueCommands.class, HologramCommands.class})
    @Permission(value = "dicelib.command", permissionLevel = Permission.PermissionLevel.GAMEMASTER)
    public static class Root {
    }

    private static Component tag(Component text) {
        return Component.literal(" [").append(text).append("]").withStyle(ChatFormatting.GRAY);
    }

    /**
     * For trying dialogue trees and battle lines without code.
     */
    @Command("dialogue")
    public static class DialogueCommands {
        @Execute
        @Permission(value = "dicelib.command.dialogue.start", permissionLevel = Permission.PermissionLevel.GAMEMASTER)
        public boolean start(@SenderArg CommandSourceStack source, @LiteralArg("start") String literal,
                             @EntityArg(name = "targets", value = EntityArg.Type.PLAYERS) Collection<ServerPlayer> targets,
                             @DialogueArg ResourceLocation dialogue) {
            return startDialogue(source, targets, dialogue, null, null);
        }

        /**
         * at a node other than the start
         */
        @Execute
        @Permission(value = "dicelib.command.dialogue.start", permissionLevel = Permission.PermissionLevel.GAMEMASTER)
        public boolean startAt(@SenderArg CommandSourceStack source, @LiteralArg("start") String literal,
                               @EntityArg(name = "targets", value = EntityArg.Type.PLAYERS) Collection<ServerPlayer> targets,
                               @DialogueArg ResourceLocation dialogue, @LiteralArg("at") String at, @TextArg(name = "node") String node) {
            return startDialogue(source, targets, dialogue, null, node);
        }

        /**
         * spoken by an entity, so it ends when they walk away or it dies
         */
        @Execute
        @Permission(value = "dicelib.command.dialogue.start", permissionLevel = Permission.PermissionLevel.GAMEMASTER)
        public boolean startBy(@SenderArg CommandSourceStack source, @LiteralArg("start") String literal,
                               @EntityArg(name = "targets", value = EntityArg.Type.PLAYERS) Collection<ServerPlayer> targets,
                               @DialogueArg ResourceLocation dialogue, @LiteralArg("by") String by,
                               @EntityArg(name = "speaker") Entity speaker) {
            return startDialogue(source, targets, dialogue, speaker, null);
        }

        @Execute
        @Permission(value = "dicelib.command.dialogue.end", permissionLevel = Permission.PermissionLevel.GAMEMASTER)
        public boolean end(@SenderArg CommandSourceStack source, @LiteralArg("end") String literal,
                           @EntityArg(name = "targets", value = EntityArg.Type.PLAYERS) Collection<ServerPlayer> targets) {
            int ended = 0;
            for (ServerPlayer target : targets) if (Dialogues.end(target)) ended++;
            int count = ended;
            source.sendSuccess(() -> Component.translatable("dicelib.command.dialogue.ended", count), true);
            return count > 0;
        }

        /**
         * a right-click on the entity starts the dialogue
         */
        @Execute
        @Permission(value = "dicelib.command.dialogue.bind", permissionLevel = Permission.PermissionLevel.GAMEMASTER)
        public boolean bind(@SenderArg CommandSourceStack source, @LiteralArg("bind") String literal,
                            @EntityArg(name = "entity") Entity entity, @DialogueArg ResourceLocation dialogue) {
            if (entity instanceof ServerPlayer) {
                source.sendFailure(Component.translatable("dicelib.command.dialogue.bind_player"));
                return false;
            }
            Dialogues.bind(entity, dialogue);
            source.sendSuccess(() -> Component.translatable("dicelib.command.dialogue.bound", entity.getDisplayName(), dialogue.toString()), true);
            return true;
        }

        @Execute
        @Permission(value = "dicelib.command.dialogue.bind", permissionLevel = Permission.PermissionLevel.GAMEMASTER)
        public boolean unbind(@SenderArg CommandSourceStack source, @LiteralArg("unbind") String literal,
                              @EntityArg(name = "entity") Entity entity) {
            if (Dialogues.bound(entity).isEmpty()) {
                source.sendFailure(Component.translatable("dicelib.command.dialogue.not_bound", entity.getDisplayName()));
                return false;
            }
            Dialogues.unbind(entity);
            source.sendSuccess(() -> Component.translatable("dicelib.command.dialogue.unbound", entity.getDisplayName()), true);
            return true;
        }

        /**
         * every tree, its node count, and what was wrong with its file
         */
        @Execute
        @Permission(value = "dicelib.command.dialogue.list", permissionLevel = Permission.PermissionLevel.GAMEMASTER)
        public boolean list(@SenderArg CommandSourceStack source, @LiteralArg("list") String literal) {
            List<ResourceLocation> ids = Dialogues.ids();
            source.sendSuccess(() -> Component.translatable("dicelib.command.dialogue.list.header", ids.size()), false);
            for (ResourceLocation id : ids) {
                Optional<DialogueTree> tree = Dialogues.lookup(id);
                Component line = tree.isPresent()
                        ? Component.translatable("dicelib.command.dialogue.list.entry", id.toString(), tree.get().nodes().size())
                        : Component.translatable("dicelib.command.dialogue.list.broken", id.toString()).withStyle(ChatFormatting.RED);
                source.sendSuccess(() -> line, false);
                for (String problem : Dialogues.problems(id)) {
                    source.sendSuccess(() -> Component.literal("  " + problem).withStyle(ChatFormatting.GOLD), false);
                }
            }
            return true;
        }

        /**
         * a battle line from whoever runs it, heard within range
         */
        @Execute
        @Permission(value = "dicelib.command.dialogue.say", permissionLevel = Permission.PermissionLevel.GAMEMASTER)
        public boolean say(@SenderArg CommandSourceStack source, @LiteralArg("say") String literal,
                           @DoubleArg(value = "range", min = 1, max = 512) double range,
                           @TextArg(name = "text", value = TextArg.Type.GREEDY_STRING) String text) {
            Speech.Line line = source.getEntity() != null
                    ? Speech.of(source.getEntity(), Component.literal(text)).bubble().speakerPortrait()
                    : Speech.of(source.getDisplayName(), Component.literal(text)).at(source.getLevel(), source.getPosition());
            int heard = line.range(range).send().size();
            source.sendSuccess(() -> Component.translatable("dicelib.command.dialogue.said", heard), true);
            return heard > 0;
        }

        private static boolean startDialogue(CommandSourceStack source, Collection<ServerPlayer> targets, ResourceLocation dialogue,
                                             @Nullable Entity speaker, @Nullable String node) {
            int started = 0;
            for (ServerPlayer target : targets) if (Dialogues.start(target, dialogue, speaker, node)) started++;
            int count = started;
            if (count == 0) {
                source.sendFailure(Component.translatable("dicelib.command.dialogue.not_started", dialogue.toString()));
                return false;
            }
            source.sendSuccess(() -> Component.translatable("dicelib.command.dialogue.started", dialogue.toString(), count), true);
            return true;
        }
    }

    /**
     * Holograms from template files, for menus that need no code.
     */
    @Command("hologram")
    public static class HologramCommands {
        /**
         * a few blocks in front of whoever runs it, facing them
         */
        @Execute
        @Permission(value = "dicelib.command.hologram.place", permissionLevel = Permission.PermissionLevel.GAMEMASTER)
        public boolean place(@SenderArg CommandSourceStack source, @LiteralArg("place") String literal, @HologramArg ResourceLocation template) {
            return placeHologram(source, template, null, false);
        }

        /**
         * kept with the level through restarts
         */
        @Execute
        @Permission(value = "dicelib.command.hologram.place", permissionLevel = Permission.PermissionLevel.GAMEMASTER)
        public boolean placeSaved(@SenderArg CommandSourceStack source, @LiteralArg("place") String literal, @HologramArg ResourceLocation template,
                                  @LiteralArg("saved") String saved) {
            return placeHologram(source, template, null, true);
        }

        /**
         * billboard turns to face each viewer. Fixed stays facing whoever runs it, whatever the template says
         */
        @Execute
        @Permission(value = "dicelib.command.hologram.place", permissionLevel = Permission.PermissionLevel.GAMEMASTER)
        public boolean placeFacing(@SenderArg CommandSourceStack source, @LiteralArg("place") String literal, @HologramArg ResourceLocation template,
                                   @LiteralArg({"billboard", "fixed"}) String facing) {
            return placeHologram(source, template, facing.equals("billboard"), false);
        }

        @Execute
        @Permission(value = "dicelib.command.hologram.place", permissionLevel = Permission.PermissionLevel.GAMEMASTER)
        public boolean placeFacingSaved(@SenderArg CommandSourceStack source, @LiteralArg("place") String literal, @HologramArg ResourceLocation template,
                                        @LiteralArg({"billboard", "fixed"}) String facing, @LiteralArg("saved") String saved) {
            return placeHologram(source, template, facing.equals("billboard"), true);
        }

        /**
         * turns one to face each viewer, or fixes it facing whoever runs this
         */
        @Execute
        @Permission(value = "dicelib.command.hologram.place", permissionLevel = Permission.PermissionLevel.GAMEMASTER)
        public boolean face(@SenderArg CommandSourceStack source, @LiteralArg("face") String literal, @UuidArg("id") java.util.UUID id,
                            @LiteralArg({"billboard", "fixed"}) String facing) {
            return faceHologram(source, Holograms.get(source.getLevel(), id), facing.equals("billboard"));
        }

        @Execute
        @Permission(value = "dicelib.command.hologram.place", permissionLevel = Permission.PermissionLevel.GAMEMASTER)
        public boolean faceNearest(@SenderArg CommandSourceStack source, @LiteralArg("face") String literal, @LiteralArg("nearest") String nearest,
                                   @LiteralArg({"billboard", "fixed"}) String facing) {
            return faceHologram(source, Holograms.nearest(source.getLevel(), source.getPosition(), 16), facing.equals("billboard"));
        }

        @Execute
        @Permission(value = "dicelib.command.hologram.list", permissionLevel = Permission.PermissionLevel.GAMEMASTER)
        public boolean list(@SenderArg CommandSourceStack source, @LiteralArg("list") String literal) {
            List<Hologram> holograms = Holograms.all(source.getLevel());
            source.sendSuccess(() -> Component.translatable("dicelib.command.hologram.list.header", holograms.size()), false);
            for (Hologram hologram : holograms) {
                String id = hologram.id().toString();
                Component from = hologram.template() != null ? Component.literal(hologram.template().toString())
                        : Component.translatable("dicelib.command.hologram.list.code");
                Component line = Component.translatable("dicelib.command.hologram.list.entry", from,
                                String.format("%.1f %.1f %.1f", hologram.pos().x, hologram.pos().y, hologram.pos().z), hologram.viewers().size())
                        .append(hologram.isSaved() ? tag(Component.translatable("dicelib.command.hologram.list.saved")) : Component.empty())
                        .append(hologram.expiresIn() >= 0 ? tag(Component.translatable("dicelib.command.hologram.list.expires", hologram.expiresIn() / 20))
                                : Component.empty())
                        .withStyle(style -> style.withClickEvent(new net.minecraft.network.chat.ClickEvent(
                                net.minecraft.network.chat.ClickEvent.Action.SUGGEST_COMMAND, "/dicelib hologram remove " + id)));
                source.sendSuccess(() -> line, false);
            }
            return true;
        }

        /**
         * every template, and what was wrong with the files that didn't load
         */
        @Execute
        @Permission(value = "dicelib.command.hologram.list", permissionLevel = Permission.PermissionLevel.GAMEMASTER)
        public boolean templates(@SenderArg CommandSourceStack source, @LiteralArg("templates") String literal) {
            List<ResourceLocation> ids = Holograms.templateIds();
            source.sendSuccess(() -> Component.translatable("dicelib.command.hologram.templates.header", ids.size()), false);
            for (ResourceLocation id : ids) source.sendSuccess(() -> Component.literal("- " + id), false);
            return true;
        }

        @Execute
        @Permission(value = "dicelib.command.hologram.remove", permissionLevel = Permission.PermissionLevel.GAMEMASTER)
        public boolean remove(@SenderArg CommandSourceStack source, @LiteralArg("remove") String literal, @UuidArg("id") java.util.UUID id) {
            Optional<Hologram> hologram = Holograms.get(source.getLevel(), id);
            if (hologram.isEmpty()) {
                source.sendFailure(Component.translatable("dicelib.command.hologram.none"));
                return false;
            }
            hologram.get().remove();
            source.sendSuccess(() -> Component.translatable("dicelib.command.hologram.removed", 1), true);
            return true;
        }

        /**
         * the one closest to whoever runs it, within 16 blocks
         */
        @Execute
        @Permission(value = "dicelib.command.hologram.remove", permissionLevel = Permission.PermissionLevel.GAMEMASTER)
        public boolean removeNearest(@SenderArg CommandSourceStack source, @LiteralArg("remove") String literal,
                                     @LiteralArg("nearest") String nearest) {
            Optional<Hologram> hologram = Holograms.nearest(source.getLevel(), source.getPosition(), 16);
            if (hologram.isEmpty()) {
                source.sendFailure(Component.translatable("dicelib.command.hologram.none"));
                return false;
            }
            hologram.get().remove();
            source.sendSuccess(() -> Component.translatable("dicelib.command.hologram.removed", 1), true);
            return true;
        }

        // billboard: null keeps the template's choice
        private static boolean placeHologram(CommandSourceStack source, ResourceLocation template, @Nullable Boolean billboard, boolean saved) {
            Entity at = source.getEntity();
            net.minecraft.world.phys.Vec3 pos = at == null ? source.getPosition()
                    : at.getEyePosition().add(at.getLookAngle().multiply(1, 0, 1).normalize().scale(2.5));
            Optional<Holograms.Builder> builder = Holograms.fromTemplate(source.getLevel(), pos, template);
            if (builder.isEmpty()) {
                source.sendFailure(Component.translatable("dicelib.command.unknown_hologram", template.toString()));
                return false;
            }
            if (Boolean.TRUE.equals(billboard)) builder.get().billboard();
            // from a command block this keeps the template's own yaw
            else if (billboard != null || !builder.get().billboards()) builder.get().facingToward(at != null ? at.getEyePosition() : source.getPosition());
            if (saved) builder.get().saved();
            try {
                Hologram hologram = builder.get().spawn();
                source.sendSuccess(() -> Component.translatable("dicelib.command.hologram.placed", template.toString(), hologram.id().toString()), true);
                return true;
            } catch (IllegalStateException e) {
                source.sendFailure(Component.literal(e.getMessage()));
                return false;
            }
        }

        private static boolean faceHologram(CommandSourceStack source, Optional<Hologram> hologram, boolean billboard) {
            if (hologram.isEmpty()) {
                source.sendFailure(Component.translatable("dicelib.command.hologram.none"));
                return false;
            }
            Entity at = source.getEntity();
            if (billboard) hologram.get().billboard();
            else if (at != null) hologram.get().facingToward(at.getEyePosition());
            else hologram.get().facing(hologram.get().spec().yaw());
            source.sendSuccess(() -> Component.translatable(billboard ? "dicelib.command.hologram.billboard" : "dicelib.command.hologram.fixed"), true);
            return true;
        }
    }
}
