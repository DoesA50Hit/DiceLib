package org._50dozenhits.dice_library.examples;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import org._50dozenhits.dice_library.command.CommandRegistry;
import java.util.Arrays;

// From the DiceLib Guide, Command Framework. docs/build_guide.py copies this class back into that page.
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
