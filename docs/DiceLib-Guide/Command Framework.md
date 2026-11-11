# Command Framework

Commands are annotated classes, and the framework builds the Brigadier trees from them. The annotations live inside `CommandRegistry` (package `org._50dozenhits.dice_library.command`), so a command class imports them from there: `import org._50dozenhits.dice_library.command.CommandRegistry.Execute;`. It builds the trees on every `RegisterCommandsEvent` (server start and `/reload`).

```java
@Command("heal")
@Permission(value = "mymod.heal", permissionLevel = Permission.PermissionLevel.GAMEMASTER)
public class HealCommand {
    @Execute                                                          // /heal
    public boolean self(@SenderArg ServerPlayer player) {
        player.setHealth(player.getMaxHealth());
        return true;
    }

    @Execute                                                          // /heal <target> <amount>
    public boolean other(@EntityArg(name = "target", value = EntityArg.Type.PLAYER) ServerPlayer target,
                         @FloatArg(min = 0) float amount) {
        target.heal(amount);
        return true;
    }
}
```
Register it once in the mod constructor:

```java
CommandRegistry.registerCommand(HealCommand.class);
```
## Classes and methods

| Annotation | On | Meaning |
|---|---|---|
| `@Command(value, subCommands)` | class | one literal per value (aliases). `subCommands` are child classes, each with their own `@Command` |
| `@Execute` | method | an executor. Its annotated parameters become the argument chain under the class's literal |
| `@Permission(value, permissionLevel)` | class or method | who may use it ([[#Permissions]]) |

The rules:

- An executor returns `boolean` (true = 1, false = 0), `int` (passed through) or `void` (1).
- Every parameter needs `@SenderArg` or an argument annotation that matches its type. A mismatch throws at server start, naming the class and method.
- A method with only `@SenderArg` parameters runs on the class's literal itself. A class has at most one.
- Classes, constructors and methods may be private, because the framework makes them accessible. Each class needs a no-arg constructor.
- Register only the root class, with `CommandRegistry.registerCommand` from the mod constructor. It checks the class tree immediately and creates the permission nodes.
- Brigadier merges executors that share leading nodes (like `grant <targets> <item>` and `grant <targets> <item> <count>`).

### A command tree

This example has nested classes, aliases, a literal with two values, an enum, and an optional last argument written as two executors that share a prefix:

```java
@Command(value = {"soulforge", "sf"}, subCommands = {SoulforgeCommand.Status.class, SoulforgeCommand.Tune.class})
@Permission(value = "mymod.soulforge", permissionLevel = Permission.PermissionLevel.PLAYER)
public class SoulforgeCommand {
    public enum Element { METAL, STEAM, INK }

    // /soulforge  (and /sf)
    @Execute
    public boolean help(@SenderArg CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Use /soulforge status or /soulforge tune"), false);
        return true;
    }

    @Command("status")
    public static class Status {
        // /soulforge status
        @Execute
        public boolean self(@SenderArg ServerPlayer player) {
            player.sendSystemMessage(Component.literal("Level " + player.experienceLevel));
            return true;
        }

        // /soulforge status <player>
        @Execute
        @Permission(value = "mymod.soulforge.status.others", permissionLevel = Permission.PermissionLevel.GAMEMASTER)
        public boolean other(@SenderArg CommandSourceStack source,
                             @EntityArg(name = "player", value = EntityArg.Type.PLAYER) ServerPlayer player) {
            source.sendSuccess(() -> Component.literal(player.getScoreboardName() + ": level " + player.experienceLevel), false);
            return true;
        }
    }

    @Command("tune")
    public static class Tune {
        // /soulforge tune up <amount>   and   /soulforge tune down <amount>
        @Execute
        public int shift(@SenderArg ServerPlayer player, @LiteralArg({"up", "down"}) String direction,
                         @IntegerArg(min = 1, max = 10) int amount) {
            int delta = direction.equals("up") ? amount : -amount;
            player.giveExperienceLevels(delta);
            return player.experienceLevel;             // the int becomes the command result
        }

        // /soulforge tune element <metal|steam|ink>
        @Execute
        public boolean element(@SenderArg ServerPlayer player, @LiteralArg("element") String literal, @EnumArg Element element) {
            player.getPersistentData().putString("mymod_tuned", element.name());
            return true;
        }

        // /soulforge tune name <word>   and   /soulforge tune name <word> <times>
        @Execute
        public boolean name(@SenderArg ServerPlayer player, @LiteralArg("name") String literal, @TextArg(name = "word") String word) {
            return name(player, literal, word, 1);
        }

        @Execute
        public boolean name(@SenderArg ServerPlayer player, @LiteralArg("name") String literal, @TextArg(name = "word") String word,
                            @IntegerArg(value = "times", min = 1, max = 5) int times) {
            player.sendSystemMessage(Component.literal(word.repeat(times)));
            return true;
        }
    }
}
```
## Errors

- A `CommandSyntaxException` thrown by an executor shows the player a red error, as a vanilla command does.
- Any other exception is logged with the method name, and the sender sees "The command failed: <message>" with a result of 0.

```java
@Command("refund")
public class RefundCommand {
    private static final SimpleCommandExceptionType NOTHING_SPENT =
            new SimpleCommandExceptionType(Component.literal("Nothing to refund"));
    private static final DynamicCommandExceptionType TOO_MUCH =
            new DynamicCommandExceptionType(amount -> Component.literal("Can't refund " + amount));

    @Execute
    public boolean refund(@SenderArg ServerPlayer player, @IntegerArg(min = 1) int amount) throws CommandSyntaxException {
        // levels spent at the forge, counted in the player's saved data
        int spent = player.getPersistentData().getInt("mymod_spent");
        if (spent == 0) throw NOTHING_SPENT.create();                 // shows as a red message
        if (amount > spent) throw TOO_MUCH.create(amount);            // shows as a red message
        player.getPersistentData().putInt("mymod_spent", spent - amount);
        player.giveExperienceLevels(amount);
        return true;
    }
}
```
## Argument names

An argument's name comes from the annotation when it gives one, otherwise from the Java parameter name (the build keeps parameter names with `-parameters`). Two arguments with the same name on one executor are refused when the commands are built, because Brigadier looks values up by name.

```java
// /tp2 <x> <y>: "x" and "y" come from the parameter names
@Execute
public boolean coordinates(@SenderArg ServerPlayer player, @DoubleArg double x, @DoubleArg double y) {
    return true;
}
```
## Built-in argument types

| Annotation | Java type | Brigadier | Name from |
|---|---|---|---|
| `@SenderArg` | `CommandSourceStack`, `ServerPlayer`, `Player`, `Entity`, `ServerLevel`, `MinecraftServer` | none | |
| `@LiteralArg({"a", "b"})` | `String` | one literal per value. The parameter receives the one typed | |
| `@BooleanArg` | `boolean` | `bool()` | `value` |
| `@IntegerArg(min, max)` | `int` | `integer` | `value` |
| `@LongArg(min, max)` | `long` | `longArg` | `value` |
| `@FloatArg(min, max)` | `float` | `floatArg` | `value` |
| `@DoubleArg(min, max)` | `double` | `doubleArg` | `value` |
| `@TextArg(name, value = WORD/STRING/GREEDY_STRING)` | `String` | `word` / `string` / `greedyString` | `name` |
| `@UuidArg` | `UUID` | `uuid()` | `value` |
| `@EnumArg` | any enum | one lower case literal per constant | |
| `@EntityArg(name, value = ENTITY/ENTITIES/PLAYER/PLAYERS)` | `EntitySelector`, `ServerPlayer`, `Entity`, `Collection` | `entity()` ... `players()` | `name` (default `entity`) |
| `@ResourceLocationArg` | `ResourceLocation` | `id()` | `value` |
| `@BlockPosArg` | `BlockPos` | `blockPos()` | `value` (`pos`) |
| `@Vec3Arg(name, value = NORMAL/CENTER)` | `Vec3` | `vec3` | `name` |
| `@DimensionArg` | `ServerLevel` | `dimension()` | `value` (`dimension`) |
| `@CompoundTagArg` | `CompoundTag` | `compoundTag()` | `value` |
| `@ItemArg` | `ItemInput` | `item` | `value` (`item`) |
| `@AttributeArg` | `Holder` | attribute registry | `value` (`attribute`) |
| `@MobEffectArg` | `Holder` | mob effect registry | `value` (`effect`) |
| `@GameModeArg` | `GameType` | `gameMode()` | `value` (`mode`) |
| `@DiceCommands.DialogueArg` | `ResourceLocation` | a dialogue tree id with suggestions. The namespace can be left off | `value` (`dialogue`) |
| `@DiceCommands.HologramArg` | `ResourceLocation` | a hologram template id with suggestions | `value` (`template`) |

`@EntityArg` resolves the selector when the parameter is `ServerPlayer`, `Entity` or `Collection` (players for `PLAYER`/`PLAYERS`, entities otherwise). An `EntitySelector` parameter leaves resolving to the executor.

Number ranges default to the full range of the type, negatives and zero included.

Every built-in type in one class:

```java
@Command("argdemo")
@Permission(value = "mymod.argdemo", permissionLevel = Permission.PermissionLevel.GAMEMASTER)
public class ArgumentDemo {
    public enum Speed { SLOW, FAST }

    @Execute
    public boolean numbers(@SenderArg CommandSourceStack source, @LiteralArg("numbers") String l,
                           @BooleanArg boolean flag, @IntegerArg(min = 0, max = 64) int count,
                           @LongArg long big, @FloatArg float ratio, @DoubleArg(min = -1, max = 1) double unit) {
        return true;
    }

    @Execute
    public boolean text(@SenderArg CommandSourceStack source, @LiteralArg("text") String l,
                        @TextArg(name = "word") String word, @TextArg(name = "rest", value = TextArg.Type.GREEDY_STRING) String rest) {
        return true;
    }

    @Execute
    public boolean ids(@SenderArg CommandSourceStack source, @LiteralArg("ids") String l,
                       @UuidArg UUID uuid, @ResourceLocationArg ResourceLocation id, @EnumArg Speed speed) {
        return true;
    }

    @Execute
    public boolean entities(@SenderArg CommandSourceStack source, @LiteralArg("entities") String l,
                            @EntityArg(name = "one", value = EntityArg.Type.ENTITY) Entity one,
                            @EntityArg(name = "players", value = EntityArg.Type.PLAYERS) Collection<ServerPlayer> players,
                            @EntityArg(name = "raw", value = EntityArg.Type.ENTITIES) EntitySelector raw) throws CommandSyntaxException {
        List<? extends Entity> resolved = raw.findEntities(source);
        return true;
    }

    @Execute
    public boolean world(@SenderArg CommandSourceStack source, @LiteralArg("world") String l,
                         @BlockPosArg BlockPos pos, @Vec3Arg(name = "at", value = Vec3Arg.Type.CENTER) Vec3 at,
                         @DimensionArg ServerLevel dimension, @GameModeArg GameType mode) {
        return true;
    }

    @Execute
    public boolean registries(@SenderArg CommandSourceStack source, @LiteralArg("registries") String l,
                              @ItemArg ItemInput item, @AttributeArg Holder<Attribute> attribute,
                              @MobEffectArg Holder<MobEffect> effect, @CompoundTagArg CompoundTag nbt) throws CommandSyntaxException {
        ItemStack stack = item.createItemStack(1, false);
        return true;
    }
}
```
## Optional arguments and suggestions

`@OptionalArg` next to a parameter's argument annotation lets the command run without it, and without every parameter after it, which must be optional too. A left-out parameter arrives as null (0 or false for a primitive), so use `Integer`, `Boolean` and so on where "not given" differs from 0.

```java
// /coins give 5        /coins give 5 quest        /coins give 5 quest 3
@Execute
public boolean give(@SenderArg ServerPlayer player, @LiteralArg("give") String literal, @IntegerArg(min = 1) int amount,
                    @OptionalArg @TextArg String reason, @OptionalArg @IntegerArg Integer times) {
    int repeats = times == null ? 1 : times;
    // ...
    return true;
}
```

Optional parameters are left out from the end, so `/coins give 5 3` reads `3` as the reason. Arguments that can each be given alone still need a method per form.

`@Suggest` sets what tab completion offers for a parameter. Give it fixed values, or a provider class with a no-arg constructor when the values have to be worked out at the time. Players can still type anything, so check the value.

```java
@Suggest({"red", "green", "blue"}) @TextArg String colour
@Suggest(from = OnlineGuilds.class) @TextArg String guild     // class OnlineGuilds implements SuggestionProvider<CommandSourceStack>
```

## Permissions

`@Permission("modid.path")` becomes a NeoForge permission node, registered in `PermissionGatherEvent.Nodes`. The part before the first dot is the node's namespace, so the framework refuses a value with no dot. When two annotations use the same node, the first one's level wins.

A sender with the vanilla permission level may run the command. A player without the level still may when NeoForge's PermissionAPI grants the node, which is how permission mods hand out single commands.

| Placed on | Applies to |
|---|---|
| a class | its literal and everything under it. Hidden from tab completion when not allowed |
| a method | the method's first argument node (hidden when not allowed), checked again when the command runs |
| neither, on a class with sub commands and nothing to run itself | shown only to senders who can use something under it |

The run time check means a method permission still works on an executor with no arguments, and when another method shares the first node.

The last row suits an admin branch such as `/mymod admin`. The branch has no node, but every command in it does, so a player granted one command sees the branch holding that one command.

```java
@Command("warden")
@Permission(value = "mymod.warden", permissionLevel = Permission.PermissionLevel.PLAYER)
public class WardenCommand {
    // everyone: /warden
    @Execute
    public boolean info(@SenderArg CommandSourceStack source) {
        return true;
    }

    // ops only: /warden summon <pos>, hidden from everyone else
    @Execute
    @Permission(value = "mymod.warden.summon", permissionLevel = Permission.PermissionLevel.GAMEMASTER)
    public boolean summon(@SenderArg CommandSourceStack source, @LiteralArg("summon") String l, @BlockPosArg BlockPos pos) {
        Warden warden = EntityType.WARDEN.create(source.getLevel());
        if (warden == null) return false;
        warden.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        return source.getLevel().addFreshEntity(warden);
    }
}
```
A permission mod like LuckPerms grants `mymod.warden.summon` to a single player without making them op.

## Custom argument types

Register a factory for an annotation plus a Java type, before the first command build (the mod constructor is safe). The factory runs once for every place the parameter appears in the tree, so it creates new node builders every time.

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ElementArg {
    String value() default "element";
}
```
```java
public enum ExampleElement {
    METAL, STEAM, INK, MUSIC;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
```
```java
public final class ExampleArguments {
    private static final DynamicCommandExceptionType UNKNOWN =
            new DynamicCommandExceptionType(id -> Component.literal("Unknown element " + id));

    // call from the mod constructor, before CommandRegistry.registerCommand
    public static void register() {
        CommandRegistry.registerArgument(ElementArg.class, ExampleElement.class, (arg, handler) -> {
            String name = handler.name(arg.value());
            handler.addNode(Commands.argument(name, StringArgumentType.word())
                    .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                            Arrays.stream(ExampleElement.values()).map(ExampleElement::id), builder)));
            handler.addValueExtractor(context -> {
                String typed = StringArgumentType.getString(context, name);
                for (ExampleElement element : ExampleElement.values()) {
                    if (element.id().equals(typed)) return element;
                }
                throw UNKNOWN.create(typed);           // red error for the player
            });
        });
    }
}
```
```java
@Command("attune")
public class AttuneCommand {
    // /attune <element>, with suggestions
    @Execute
    public boolean attune(@SenderArg ServerPlayer player, @ElementArg ExampleElement element) {
        player.sendSystemMessage(Component.literal("Attuned to " + element.id()));
        return true;
    }
}
```
A factory can also add several alternative nodes, each with its own value, as `@LiteralArg` does. A `boolean` parameter typed as `on` or `off`:

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface OnOffArg {
}
```
```java
CommandRegistry.registerArgument(OnOffArg.class, boolean.class, (arg, handler) -> {
    handler.addNode(Commands.literal("on"));
    handler.addValueExtractorToLastNode(context -> true);
    handler.addNode(Commands.literal("off"));
    handler.addValueExtractorToLastNode(context -> false);
});
```
`ArgumentNodeHandler` methods:

| Method | Use |
|---|---|
| `addNode(builder)` | a node for the parameter. Adding several makes alternative branches |
| `addValueExtractor(context -> value)` | how to read the value, for every branch without its own. May throw `CommandSyntaxException` |
| `addValueExtractorToLastNode(context -> value)` | a value for the branch just added. This is how `@LiteralArg` knows which literal was typed |
| `name(annotationName)` | the annotation's name, or the Java parameter name when blank |
| `getAutoGeneratedArgumentName()` | the Java parameter name |
| `getBuildContext()` | for item, block and registry arguments |
| `getParameter()` | the `java.lang.reflect.Parameter` |

A later registration for the same annotation and type beats an earlier one, built-ins included. Lookup tries the exact type first, then registered supertypes, which is how `@EnumArg` handles every enum.

## Testing commands

`CommandBuilder.build` puts a command class on any dispatcher, for tests without a running world's command list:

```java
CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
CommandBuilder.build(HealCommand.class, dispatcher, CommandBuildContext.simple(level.registryAccess(), level.enabledFeatures()));
CommandSourceStack op = player.createCommandSourceStack().withPermission(2).withSuppressedOutput();
int result = dispatcher.execute("heal @s 4", op);       // throws CommandSyntaxException on errors
```
The real commands are on the server's dispatcher:

```java
int ran = server.getCommands().getDispatcher().execute("dicelib dialogue list", player.createCommandSourceStack().withPermission(2));
```

## DiceLib's own commands

DiceLib builds `/dicelib dialogue ...` ([[Dialogue#Commands]]) and `/dicelib hologram ...` ([[Holograms#Commands]]) with this framework, for operators only.

---

← Previous: [[Looks and Custom UIs]]
