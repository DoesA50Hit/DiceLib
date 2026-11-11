package org._50dozenhits.dice_library.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.CompoundTagArgument;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameModeArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;
import org._50dozenhits.dice_library.DiceLib;
import org._50dozenhits.dice_library.command.CommandRegistry.*;
import org.jetbrains.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns @Command classes into Brigadier trees. Each class adds one level of literals (one per alias), each @Execute
 * method hangs its argument chain under its class's literal, and Brigadier merges the nodes methods share.
 */
public final class CommandBuilder {
    private static final SimpleCommandExceptionType NO_PERMISSION =
            new SimpleCommandExceptionType(Component.translatable("dicelib.command.no_permission"));

    // Collected when classes are registered, because NeoForge wants them at server start, before any tree is built.
    // Two annotations on one node: the first one's level counts.
    private static final Map<String, PermissionNode<Boolean>> NODES = new LinkedHashMap<>();

    /**
     * Checks the class tree and creates the permission nodes, with a clear message when something's off.
     */
    public static void validate(Class<?> type) {
        validate(type, new HashSet<>());
    }

    private static void validate(Class<?> type, Set<Class<?>> path) {
        Command command = type.getAnnotation(Command.class);
        if (command == null) throw new IllegalArgumentException(type.getName() + " is used as a command but has no @Command");
        if (command.value().length == 0) throw new IllegalArgumentException(type.getName() + ": @Command needs at least one name");
        if (!path.add(type)) throw new IllegalArgumentException(type.getName() + " is a sub command of itself");
        Permission permission = type.getAnnotation(Permission.class);
        if (permission != null) node(permission);

        int senderOnly = 0;
        for (Method method : type.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Execute.class)) continue;
            Class<?> returnType = method.getReturnType();
            if (returnType != boolean.class && returnType != Boolean.class && returnType != int.class
                    && returnType != Integer.class && returnType != void.class) {
                throw new IllegalArgumentException(describe(method) + " must return boolean, int or void");
            }
            Permission methodPermission = method.getAnnotation(Permission.class);
            if (methodPermission != null) node(methodPermission);
            // true while no argument has to be typed: such a method runs on the class's literal itself
            boolean onlySenders = true;
            boolean optionalSeen = false;
            for (Parameter parameter : method.getParameters()) {
                if (parameter.isAnnotationPresent(SenderArg.class)) {
                    senderExtractor(parameter.getType(), method);
                    continue;
                }
                boolean optional = parameter.isAnnotationPresent(OptionalArg.class);
                if (optionalSeen && !optional) {
                    throw new IllegalArgumentException(describe(method) + ": parameter " + parameter.getName()
                            + " comes after an @OptionalArg one, so it needs @OptionalArg too");
                }
                optionalSeen |= optional;
                if (!optional) onlySenders = false;
                Suggest suggest = parameter.getAnnotation(Suggest.class);
                if (suggest != null) suggestions(suggest, method);
            }
            if (onlySenders && ++senderOnly > 1) {
                throw new IllegalArgumentException(type.getName() + " has more than one @Execute method that runs without arguments");
            }
        }
        for (Class<?> sub : command.subCommands()) validate(sub, path);
        path.remove(type);
    }

    public static void build(Class<?> root, CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
        for (LiteralArgumentBuilder<CommandSourceStack> literal : literals(root, context)) dispatcher.register(literal);
    }

    private static List<LiteralArgumentBuilder<CommandSourceStack>> literals(Class<?> type, CommandBuildContext context) {
        Command command = type.getAnnotation(Command.class);
        Permission permission = type.getAnnotation(Permission.class);
        Object instance = instantiate(type);
        List<Executor> executors = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Execute.class)) executors.add(new Executor(method, instance));
        }
        // getDeclaredMethods has no fixed order. Sorting keeps the tree the same every start
        executors.sort(Comparator.comparing(executor -> executor.method.getName()));

        List<LiteralArgumentBuilder<CommandSourceStack>> result = new ArrayList<>();
        for (String name : command.value()) {
            LiteralArgumentBuilder<CommandSourceStack> literal = Commands.literal(name);
            if (permission != null) literal.requires(source -> checkPermission(source, permission));
            for (Executor executor : executors) executor.attach(literal, context);
            for (Class<?> sub : command.subCommands()) {
                for (LiteralArgumentBuilder<CommandSourceStack> child : literals(sub, context)) literal.then(child);
            }
            // A branch with no permission and nothing to run itself shows only to whoever can use something under it,
            // so a permission mod can hand out one command without the rest of the branch.
            if (permission == null && literal.getCommand() == null && !literal.getArguments().isEmpty()) {
                List<CommandNode<CommandSourceStack>> children = List.copyOf(literal.getArguments());
                literal.requires(source -> children.stream().anyMatch(child -> child.canUse(source)));
            }
            result.add(literal);
        }
        return result;
    }

    private static Object instantiate(Class<?> type) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Command class " + type.getName() + " needs a constructor without parameters", e);
        }
    }

    static ValueExtractor senderExtractor(Class<?> type, Method method) {
        if (type == CommandSourceStack.class) return CommandContext::getSource;
        if (type == ServerPlayer.class || type == Player.class) return context -> context.getSource().getPlayerOrException();
        if (type == Entity.class) return context -> context.getSource().getEntityOrException();
        if (type == ServerLevel.class) return context -> context.getSource().getLevel();
        if (type == MinecraftServer.class) return context -> context.getSource().getServer();
        throw new IllegalArgumentException(describe(method) + ": @SenderArg can't be a " + type.getSimpleName()
                + " (use CommandSourceStack, ServerPlayer, Player, Entity, ServerLevel or MinecraftServer)");
    }

    // what a parameter's @Suggest offers: the provider class it names, or its fixed values
    @SuppressWarnings("unchecked")
    static SuggestionProvider<CommandSourceStack> suggestions(Suggest suggest, Method method) {
        if (suggest.from() == SuggestionProvider.class) {
            String[] values = suggest.value();
            return (context, builder) -> SharedSuggestionProvider.suggest(values, builder);
        }
        try {
            Constructor<?> constructor = suggest.from().getDeclaredConstructor();
            constructor.setAccessible(true);
            return (SuggestionProvider<CommandSourceStack>) constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException(describe(method) + ": @Suggest's " + suggest.from().getName()
                    + " needs a constructor without parameters", e);
        }
    }

    // what a left-out @OptionalArg parameter gets
    @Nullable
    private static Object absent(Class<?> type) {
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == boolean.class) return false;
        return null;
    }

    private static String describe(Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName();
    }

    private static Class<?> box(Class<?> type) {
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == boolean.class) return Boolean.class;
        return type;
    }

    static synchronized PermissionNode<Boolean> node(Permission permission) {
        return NODES.computeIfAbsent(permission.value(), name -> {
            int dot = name.indexOf('.');
            if (dot <= 0 || dot == name.length() - 1) {
                throw new IllegalArgumentException("Permission '" + name + "' must look like <modid>.<node>, for example mymod.command.admin");
            }
            int level = permission.permissionLevel().level();
            return new PermissionNode<>(name.substring(0, dot), name.substring(dot + 1), PermissionTypes.BOOLEAN,
                    (player, uuid, context) -> player != null && player.hasPermissions(level));
        });
    }

    static synchronized List<PermissionNode<?>> permissionNodes() {
        return new ArrayList<>(NODES.values());
    }

    /**
     * The level is enough. Without it a player still passes if a permission mod grants the node. The console doesn't.
     */
    public static boolean checkPermission(CommandSourceStack source, Permission permission) {
        if (source.hasPermission(permission.permissionLevel().level())) return true;
        ServerPlayer player = source.getPlayer();
        if (player == null) return false;
        try {
            return PermissionAPI.getPermission(player, node(permission));
        } catch (RuntimeException e) {
            // the PermissionAPI isn't up yet, or the node came after the server started
            return false;
        }
    }

    private static final class Executor {
        final Method method;
        @Nullable
        final Object instance;
        final Parameter[] parameters;
        // the extractor of each @SenderArg parameter, null for the real arguments
        final ValueExtractor[] senders;
        final List<Integer> arguments = new ArrayList<>();
        @Nullable
        final Permission permission;

        Executor(Method method, Object instance) {
            method.setAccessible(true);
            this.method = method;
            this.instance = Modifier.isStatic(method.getModifiers()) ? null : instance;
            this.parameters = method.getParameters();
            this.senders = new ValueExtractor[parameters.length];
            for (int i = 0; i < parameters.length; i++) {
                if (parameters[i].isAnnotationPresent(SenderArg.class)) senders[i] = senderExtractor(parameters[i].getType(), method);
                else arguments.add(i);
            }
            this.permission = method.getAnnotation(Permission.class);
        }

        void attach(LiteralArgumentBuilder<CommandSourceStack> literal, CommandBuildContext context) {
            if (arguments.isEmpty()) {
                literal.executes(c -> run(c, List.of()));
                return;
            }
            // every argument can be left out: the literal alone runs it too
            if (optional(0)) literal.executes(c -> run(c, List.of()));
            for (ArgumentBuilder<CommandSourceStack, ?> node : chain(0, List.of(), Set.of(), context)) {
                // Hides the branch from tab completion. When another method shares this first node Brigadier keeps the
                // first requirement only, which is why run() checks again.
                if (permission != null) node.requires(source -> checkPermission(source, permission));
                literal.then(node);
            }
        }

        // the nodes for argument number `position`, each with the rest of the chain under it
        private List<ArgumentBuilder<CommandSourceStack, ?>> chain(int position, List<ValueExtractor> chosen, Set<String> names,
                                                                   CommandBuildContext context) {
            Parameter parameter = parameters[arguments.get(position)];
            Handler handler = handler(parameter, context);
            List<ArgumentBuilder<CommandSourceStack, ?>> result = new ArrayList<>();
            for (int i = 0; i < handler.nodes.size(); i++) {
                ArgumentBuilder<CommandSourceStack, ?> node = handler.nodes.get(i);
                ValueExtractor extractor = handler.ownValues.get(i) != null ? handler.ownValues.get(i) : handler.sharedValue;
                if (extractor == null) throw new IllegalStateException(describe(method) + ": no value for parameter " + parameter.getName());
                Set<String> nextNames = names;
                if (node instanceof RequiredArgumentBuilder<?, ?> required) {
                    if (names.contains(required.getName())) {
                        throw new IllegalStateException(describe(method) + ": two arguments are named '" + required.getName() + "'; give them different names");
                    }
                    nextNames = new HashSet<>(names);
                    nextNames.add(required.getName());
                }
                List<ValueExtractor> next = new ArrayList<>(chosen);
                next.add(extractor);
                if (position == arguments.size() - 1) {
                    List<ValueExtractor> values = List.copyOf(next);
                    node.executes(c -> run(c, values));
                } else {
                    for (ArgumentBuilder<CommandSourceStack, ?> child : chain(position + 1, next, nextNames, context)) node.then(child);
                    // the rest can be left out: the command runs from here too
                    if (optional(position + 1)) {
                        List<ValueExtractor> values = List.copyOf(next);
                        node.executes(c -> run(c, values));
                    }
                }
                result.add(node);
            }
            return result;
        }

        private boolean optional(int position) {
            return parameters[arguments.get(position)].isAnnotationPresent(OptionalArg.class);
        }

        private Handler handler(Parameter parameter, CommandBuildContext context) {
            for (Annotation annotation : parameter.getAnnotations()) {
                RegisteredArgument<?> argument = CommandRegistry.find(annotation.annotationType(), parameter.getType());
                if (argument == null && parameter.getType().isPrimitive()) {
                    argument = CommandRegistry.find(annotation.annotationType(), box(parameter.getType()));
                }
                if (argument == null) {
                    if (CommandRegistry.isArgumentAnnotation(annotation.annotationType())) {
                        throw new IllegalStateException(describe(method) + ": @" + annotation.annotationType().getSimpleName()
                                + " does not work on a " + parameter.getType().getSimpleName() + " parameter");
                    }
                    continue;
                }
                Handler handler = new Handler(parameter, context);
                create(argument, annotation, handler);
                if (handler.nodes.isEmpty()) {
                    throw new IllegalStateException(describe(method) + ": @" + annotation.annotationType().getSimpleName() + " added no node");
                }
                Suggest suggest = parameter.getAnnotation(Suggest.class);
                if (suggest != null) {
                    SuggestionProvider<CommandSourceStack> provider = suggestions(suggest, method);
                    for (ArgumentBuilder<CommandSourceStack, ?> node : handler.nodes) {
                        if (node instanceof RequiredArgumentBuilder<?, ?> required) suggest(required, provider);
                    }
                }
                return handler;
            }
            throw new IllegalStateException(describe(method) + ": parameter " + parameter.getName() + " needs an argument annotation (or @SenderArg)");
        }

        // every node here is built for CommandSourceStack. The wildcard only comes from the list's type
        @SuppressWarnings("unchecked")
        private static void suggest(RequiredArgumentBuilder<?, ?> node, SuggestionProvider<CommandSourceStack> provider) {
            ((RequiredArgumentBuilder<CommandSourceStack, ?>) node).suggests(provider);
        }

        @SuppressWarnings("unchecked")
        private static <A extends Annotation> void create(RegisteredArgument<A> argument, Annotation annotation, Handler handler) {
            argument.factory().create((A) annotation, handler);
        }

        private int run(CommandContext<CommandSourceStack> context, List<ValueExtractor> argumentValues) throws CommandSyntaxException {
            CommandSourceStack source = context.getSource();
            if (permission != null && !checkPermission(source, permission)) throw NO_PERMISSION.create();
            Object[] values = new Object[parameters.length];
            int next = 0;
            for (int i = 0; i < parameters.length; i++) {
                if (senders[i] != null) values[i] = senders[i].get(context);
                else if (next < argumentValues.size()) values[i] = argumentValues.get(next++).get(context);
                else values[i] = absent(parameters[i].getType());
            }
            Object result;
            try {
                result = method.invoke(instance, values);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof CommandSyntaxException syntax) throw syntax;
                DiceLib.LOGGER.error("Command {} failed", describe(method), cause);
                source.sendFailure(Component.translatable("dicelib.command.failed", String.valueOf(cause.getMessage())));
                return 0;
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
            if (result instanceof Boolean success) return success ? 1 : 0;
            if (result instanceof Number number) return number.intValue();
            return 1;
        }
    }

    private static final class Handler implements ArgumentNodeHandler {
        final Parameter parameter;
        final CommandBuildContext context;
        final List<ArgumentBuilder<CommandSourceStack, ?>> nodes = new ArrayList<>();
        final List<ValueExtractor> ownValues = new ArrayList<>();
        @Nullable
        ValueExtractor sharedValue;

        Handler(Parameter parameter, CommandBuildContext context) {
            this.parameter = parameter;
            this.context = context;
        }

        @Override
        public void addNode(ArgumentBuilder<CommandSourceStack, ?> node) {
            nodes.add(node);
            ownValues.add(null);
        }

        @Override
        public void addValueExtractor(ValueExtractor extractor) {
            sharedValue = extractor;
        }

        @Override
        public void addValueExtractorToLastNode(ValueExtractor extractor) {
            if (nodes.isEmpty()) throw new IllegalStateException("addValueExtractorToLastNode needs a node first");
            ownValues.set(nodes.size() - 1, extractor);
        }

        @Override
        public String getAutoGeneratedArgumentName() {
            return parameter.getName();
        }

        @Override
        public CommandBuildContext getBuildContext() {
            return context;
        }

        @Override
        public Parameter getParameter() {
            return parameter;
        }
    }

    // the argument types DiceLib provides
    static void registerBuiltins() {
        CommandRegistry.registerArgument(LiteralArg.class, String.class, (arg, handler) -> {
            for (String value : arg.value()) {
                handler.addNode(Commands.literal(value));
                handler.addValueExtractorToLastNode(context -> value);
            }
        });

        both(BooleanArg.class, boolean.class, Boolean.class, (arg, handler) -> {
            String name = handler.name(arg.value());
            handler.addNode(Commands.argument(name, BoolArgumentType.bool()));
            handler.addValueExtractor(context -> BoolArgumentType.getBool(context, name));
        });
        both(IntegerArg.class, int.class, Integer.class, (arg, handler) -> {
            String name = handler.name(arg.value());
            handler.addNode(Commands.argument(name, IntegerArgumentType.integer(arg.min(), arg.max())));
            handler.addValueExtractor(context -> IntegerArgumentType.getInteger(context, name));
        });
        both(LongArg.class, long.class, Long.class, (arg, handler) -> {
            String name = handler.name(arg.value());
            handler.addNode(Commands.argument(name, LongArgumentType.longArg(arg.min(), arg.max())));
            handler.addValueExtractor(context -> LongArgumentType.getLong(context, name));
        });
        both(FloatArg.class, float.class, Float.class, (arg, handler) -> {
            String name = handler.name(arg.value());
            handler.addNode(Commands.argument(name, FloatArgumentType.floatArg(arg.min(), arg.max())));
            handler.addValueExtractor(context -> FloatArgumentType.getFloat(context, name));
        });
        both(DoubleArg.class, double.class, Double.class, (arg, handler) -> {
            String name = handler.name(arg.value());
            handler.addNode(Commands.argument(name, DoubleArgumentType.doubleArg(arg.min(), arg.max())));
            handler.addValueExtractor(context -> DoubleArgumentType.getDouble(context, name));
        });

        CommandRegistry.registerArgument(TextArg.class, String.class, (arg, handler) -> {
            String name = handler.name(arg.name());
            StringArgumentType type = switch (arg.value()) {
                case WORD -> StringArgumentType.word();
                case STRING -> StringArgumentType.string();
                case GREEDY_STRING -> StringArgumentType.greedyString();
            };
            handler.addNode(Commands.argument(name, type));
            handler.addValueExtractor(context -> StringArgumentType.getString(context, name));
        });
        CommandRegistry.registerArgument(UuidArg.class, UUID.class, (arg, handler) -> {
            String name = handler.name(arg.value());
            handler.addNode(Commands.argument(name, UuidArgument.uuid()));
            handler.addValueExtractor(context -> UuidArgument.getUuid(context, name));
        });
        CommandRegistry.registerArgument(EnumArg.class, Enum.class, (arg, handler) -> {
            for (Object constant : handler.getParameter().getType().getEnumConstants()) {
                handler.addNode(Commands.literal(((Enum<?>) constant).name().toLowerCase(Locale.ROOT)));
                handler.addValueExtractorToLastNode(context -> constant);
            }
        });

        ArgumentFactory<EntityArg> entity = (arg, handler) -> {
            String name = handler.name(arg.name());
            handler.addNode(Commands.argument(name, switch (arg.value()) {
                case ENTITY -> EntityArgument.entity();
                case ENTITIES -> EntityArgument.entities();
                case PLAYER -> EntityArgument.player();
                case PLAYERS -> EntityArgument.players();
            }));
            Class<?> type = handler.getParameter().getType();
            boolean players = arg.value() == EntityArg.Type.PLAYER || arg.value() == EntityArg.Type.PLAYERS;
            if (type == ServerPlayer.class) handler.addValueExtractor(context -> EntityArgument.getPlayer(context, name));
            else if (type == Entity.class) handler.addValueExtractor(context -> EntityArgument.getEntity(context, name));
            else if (type == Collection.class && players) handler.addValueExtractor(context -> EntityArgument.getPlayers(context, name));
            else if (type == Collection.class) handler.addValueExtractor(context -> EntityArgument.getEntities(context, name));
            else handler.addValueExtractor(context -> context.getArgument(name, EntitySelector.class));
        };
        CommandRegistry.registerArgument(EntityArg.class, EntitySelector.class, entity);
        CommandRegistry.registerArgument(EntityArg.class, ServerPlayer.class, entity);
        CommandRegistry.registerArgument(EntityArg.class, Entity.class, entity);
        CommandRegistry.registerArgument(EntityArg.class, Collection.class, entity);

        CommandRegistry.registerArgument(ResourceLocationArg.class, ResourceLocation.class, (arg, handler) -> {
            String name = handler.name(arg.value());
            handler.addNode(Commands.argument(name, ResourceLocationArgument.id()));
            handler.addValueExtractor(context -> ResourceLocationArgument.getId(context, name));
        });
        CommandRegistry.registerArgument(BlockPosArg.class, BlockPos.class, (arg, handler) -> {
            String name = handler.name(arg.value());
            handler.addNode(Commands.argument(name, BlockPosArgument.blockPos()));
            handler.addValueExtractor(context -> BlockPosArgument.getBlockPos(context, name));
        });
        CommandRegistry.registerArgument(Vec3Arg.class, Vec3.class, (arg, handler) -> {
            String name = handler.name(arg.name());
            handler.addNode(Commands.argument(name, Vec3Argument.vec3(arg.value() == Vec3Arg.Type.CENTER)));
            handler.addValueExtractor(context -> Vec3Argument.getVec3(context, name));
        });
        CommandRegistry.registerArgument(DimensionArg.class, ServerLevel.class, (arg, handler) -> {
            String name = handler.name(arg.value());
            handler.addNode(Commands.argument(name, DimensionArgument.dimension()));
            handler.addValueExtractor(context -> DimensionArgument.getDimension(context, name));
        });
        CommandRegistry.registerArgument(CompoundTagArg.class, CompoundTag.class, (arg, handler) -> {
            String name = handler.name(arg.value());
            handler.addNode(Commands.argument(name, CompoundTagArgument.compoundTag()));
            handler.addValueExtractor(context -> CompoundTagArgument.getCompoundTag(context, name));
        });
        CommandRegistry.registerArgument(ItemArg.class, ItemInput.class, (arg, handler) -> {
            String name = handler.name(arg.value());
            handler.addNode(Commands.argument(name, ItemArgument.item(handler.getBuildContext())));
            handler.addValueExtractor(context -> ItemArgument.getItem(context, name));
        });
        CommandRegistry.registerArgument(AttributeArg.class, Holder.class, (arg, handler) -> {
            String name = handler.name(arg.value());
            handler.addNode(Commands.argument(name, ResourceArgument.resource(handler.getBuildContext(), Registries.ATTRIBUTE)));
            handler.addValueExtractor(context -> ResourceArgument.getAttribute(context, name));
        });
        CommandRegistry.registerArgument(MobEffectArg.class, Holder.class, (arg, handler) -> {
            String name = handler.name(arg.value());
            handler.addNode(Commands.argument(name, ResourceArgument.resource(handler.getBuildContext(), Registries.MOB_EFFECT)));
            handler.addValueExtractor(context -> ResourceArgument.getMobEffect(context, name));
        });
        CommandRegistry.registerArgument(GameModeArg.class, GameType.class, (arg, handler) -> {
            String name = handler.name(arg.value());
            handler.addNode(Commands.argument(name, GameModeArgument.gameMode()));
            handler.addValueExtractor(context -> GameModeArgument.getGameMode(context, name));
        });
    }

    private static <A extends Annotation> void both(Class<A> annotation, Class<?> primitive, Class<?> boxed, ArgumentFactory<A> factory) {
        CommandRegistry.registerArgument(annotation, primitive, factory);
        CommandRegistry.registerArgument(annotation, boxed, factory);
    }
}
