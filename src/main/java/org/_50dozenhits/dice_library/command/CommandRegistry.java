package org._50dozenhits.dice_library.command;

import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import org._50dozenhits.dice_library.DiceLib;
import org.jetbrains.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Annotated command classes. Register root classes and extra argument types from the mod constructor.
 * The Brigadier trees get built on every RegisterCommandsEvent (server start and /reload). Everything a command class
 * uses is in here: @Command, @Execute, @Permission, the @...Arg parameters, @OptionalArg and @Suggest for them, and the
 * hooks for new argument types.
 */
@EventBusSubscriber(modid = DiceLib.MODID)
public final class CommandRegistry {
    // mods register from their constructors, which run side by side
    private static final List<Class<?>> ROOTS = new CopyOnWriteArrayList<>();
    private static final List<RegisteredArgument<?>> ARGUMENTS = new CopyOnWriteArrayList<>();

    static {
        CommandBuilder.registerBuiltins();
    }

    public record RegisteredArgument<A extends Annotation>(Class<A> annotation, Class<?> type, ArgumentFactory<A> factory) {
    }

    /**
     * Checks the class tree right away and turns every @Permission in it into a permission node, which NeoForge asks for
     * at server start. That's why this belongs in the mod constructor.
     */
    public static void registerCommand(Class<?> root) {
        CommandBuilder.validate(root);
        ROOTS.add(root);
    }

    /**
     * A later registration for the same annotation and type wins, built-ins included.
     */
    public static <A extends Annotation> void registerArgument(Class<A> annotation, Class<?> type, ArgumentFactory<A> factory) {
        ARGUMENTS.addFirst(new RegisteredArgument<>(annotation, type, factory));
    }

    /**
     * exact type first, then a registered supertype (that's how @EnumArg covers every enum)
     */
    @Nullable
    public static RegisteredArgument<?> find(Class<? extends Annotation> annotation, Class<?> type) {
        for (RegisteredArgument<?> argument : ARGUMENTS) {
            if (argument.annotation() == annotation && argument.type() == type) return argument;
        }
        for (RegisteredArgument<?> argument : ARGUMENTS) {
            if (argument.annotation() == annotation && argument.type().isAssignableFrom(type)) return argument;
        }
        return null;
    }

    public static boolean isArgumentAnnotation(Class<? extends Annotation> annotation) {
        for (RegisteredArgument<?> argument : ARGUMENTS) {
            if (argument.annotation() == annotation) return true;
        }
        return false;
    }

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        for (Class<?> root : List.copyOf(ROOTS)) CommandBuilder.build(root, event.getDispatcher(), event.getBuildContext());
    }

    @SubscribeEvent
    static void onPermissionNodes(PermissionGatherEvent.Nodes event) {
        event.addNodes(CommandBuilder.permissionNodes());
    }

    /**
     * A literal per value (several values are aliases). subCommands become children. The class's @Execute methods hang
     * their arguments under its literal. Only the root class gets registered.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface Command {
        String[] value();

        Class<?>[] subCommands() default {};
    }

    /**
     * The annotated parameters, in order, become the arguments after the class's literal. Return boolean, int or void.
     * A method with only @SenderArg parameters runs on the literal itself (one per class). A CommandSyntaxException
     * reaches the player as a red error. Anything else is logged and reported as a failure.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface Execute {
    }

    /**
     * A NeoForge permission node, written modid.path. Senders with the level pass. Other players pass when a
     * permission mod grants the node. Branches you can't use are hidden from tab completion.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Permission {
        String value();

        PermissionLevel permissionLevel() default PermissionLevel.OWNER;

        enum PermissionLevel {
            PLAYER(0),
            MODERATOR(1),
            GAMEMASTER(2),
            ADMIN(3),
            OWNER(4);

            private final int level;

            PermissionLevel(int level) {
                this.level = level;
            }

            public int level() {
                return level;
            }
        }
    }

    /**
     * Whoever ran the command. Adds no node. CommandSourceStack, ServerPlayer, Player, Entity, ServerLevel or
     * MinecraftServer. The player types fail from the console with vanilla's "a player is required".
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface SenderArg {
    }

    /**
     * On a parameter next to its argument annotation: the command also runs without it, and without every parameter
     * after it, which must be optional too. A left-out parameter is null (0 or false for a primitive), so use Integer,
     * Boolean and so on to tell "not given" from 0.
     *
     * <pre>{@code
     * @Execute
     * public boolean give(@SenderArg ServerPlayer player, @IntegerArg(min = 1) int amount, @OptionalArg @TextArg String reason)
     * }</pre>
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface OptionalArg {
    }

    /**
     * On a parameter next to its argument annotation: what tab completion offers for it. Either fixed values, or a
     * provider class with a constructor without parameters for ones worked out when asked. Anything can still be
     * typed, so check the value.
     *
     * <pre>{@code
     * @Suggest({"red", "green", "blue"}) @TextArg String colour          @Suggest(from = OnlineGuilds.class) @TextArg String guild
     * }</pre>
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface Suggest {
        String[] value() default {};

        @SuppressWarnings("rawtypes")
        Class<? extends SuggestionProvider> from() default SuggestionProvider.class;
    }

    /**
     * One branch per value. The String parameter gets the one that was typed.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface LiteralArg {
        String[] value();
    }

    // For the next ones a blank name means the Java parameter name.

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface BooleanArg {
        String value() default "";
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface IntegerArg {
        String value() default "";

        int min() default Integer.MIN_VALUE;

        int max() default Integer.MAX_VALUE;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface LongArg {
        String value() default "";

        long min() default Long.MIN_VALUE;

        long max() default Long.MAX_VALUE;
    }

    /**
     * zero and negatives are allowed by default
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface FloatArg {
        String value() default "";

        float min() default -Float.MAX_VALUE;

        float max() default Float.MAX_VALUE;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface DoubleArg {
        String value() default "";

        double min() default -Double.MAX_VALUE;

        double max() default Double.MAX_VALUE;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface TextArg {
        String name() default "";

        Type value() default Type.WORD;

        enum Type {
            WORD,
            STRING,
            GREEDY_STRING
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface UuidArg {
        String value() default "";
    }

    /**
     * a lower case literal per constant of the parameter's enum
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface EnumArg {
    }

    /**
     * EntitySelector to resolve it yourself, or already resolved: ServerPlayer (PLAYER), Entity (ENTITY),
     * {@code Collection<ServerPlayer>} (PLAYERS), {@code Collection<Entity>} (ENTITIES)
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface EntityArg {
        String name() default "entity";

        Type value() default Type.ENTITY;

        enum Type {
            ENTITY,
            ENTITIES,
            PLAYER,
            PLAYERS
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface ResourceLocationArg {
        String value() default "";
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface BlockPosArg {
        String value() default "pos";
    }

    /**
     * CENTER adds 0.5 to whole numbers
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface Vec3Arg {
        String name() default "";

        Type value() default Type.NORMAL;

        enum Type {
            NORMAL,
            CENTER
        }
    }

    /**
     * a ServerLevel parameter
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface DimensionArg {
        String value() default "dimension";
    }

    /**
     * SNBT
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface CompoundTagArg {
        String value() default "";
    }

    /**
     * an ItemInput parameter
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface ItemArg {
        String value() default "item";
    }

    /**
     * a {@code Holder<Attribute>} parameter
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface AttributeArg {
        String value() default "attribute";
    }

    /**
     * a {@code Holder<MobEffect>} parameter
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface MobEffectArg {
        String value() default "effect";
    }

    /**
     * a GameType parameter
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface GameModeArg {
        String value() default "mode";
    }

    /**
     * Builds the nodes and the value for one parameter type. It runs for every place the parameter shows up in the
     * tree, so make new node builders every time.
     */
    @FunctionalInterface
    public interface ArgumentFactory<A extends Annotation> {
        void create(A annotation, ArgumentNodeHandler handler);
    }

    public interface ArgumentNodeHandler {
        // Several nodes become alternative branches.
        void addNode(ArgumentBuilder<CommandSourceStack, ?> node);

        // the value for every branch that has none of its own
        void addValueExtractor(ValueExtractor extractor);

        // the value of the branch just added (how a literal says which one was typed)
        void addValueExtractorToLastNode(ValueExtractor extractor);

        // the Java parameter name, kept by the -parameters compiler flag
        String getAutoGeneratedArgumentName();

        default String name(String annotationName) {
            return annotationName == null || annotationName.isBlank() ? getAutoGeneratedArgumentName() : annotationName;
        }

        CommandBuildContext getBuildContext();

        Parameter getParameter();
    }

    /**
     * A CommandSyntaxException thrown here reaches the player.
     */
    @FunctionalInterface
    public interface ValueExtractor {
        Object get(CommandContext<CommandSourceStack> context) throws CommandSyntaxException;
    }
}
