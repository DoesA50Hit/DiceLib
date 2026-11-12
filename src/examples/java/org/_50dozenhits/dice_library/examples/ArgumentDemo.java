package org._50dozenhits.dice_library.examples;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org._50dozenhits.dice_library.command.CommandRegistry.AttributeArg;
import org._50dozenhits.dice_library.command.CommandRegistry.BlockPosArg;
import org._50dozenhits.dice_library.command.CommandRegistry.BooleanArg;
import org._50dozenhits.dice_library.command.CommandRegistry.Command;
import org._50dozenhits.dice_library.command.CommandRegistry.CompoundTagArg;
import org._50dozenhits.dice_library.command.CommandRegistry.DimensionArg;
import org._50dozenhits.dice_library.command.CommandRegistry.DoubleArg;
import org._50dozenhits.dice_library.command.CommandRegistry.EntityArg;
import org._50dozenhits.dice_library.command.CommandRegistry.EnumArg;
import org._50dozenhits.dice_library.command.CommandRegistry.Execute;
import org._50dozenhits.dice_library.command.CommandRegistry.FloatArg;
import org._50dozenhits.dice_library.command.CommandRegistry.GameModeArg;
import org._50dozenhits.dice_library.command.CommandRegistry.IntegerArg;
import org._50dozenhits.dice_library.command.CommandRegistry.ItemArg;
import org._50dozenhits.dice_library.command.CommandRegistry.LiteralArg;
import org._50dozenhits.dice_library.command.CommandRegistry.LongArg;
import org._50dozenhits.dice_library.command.CommandRegistry.MobEffectArg;
import org._50dozenhits.dice_library.command.CommandRegistry.Permission;
import org._50dozenhits.dice_library.command.CommandRegistry.ResourceLocationArg;
import org._50dozenhits.dice_library.command.CommandRegistry.SenderArg;
import org._50dozenhits.dice_library.command.CommandRegistry.TextArg;
import org._50dozenhits.dice_library.command.CommandRegistry.UuidArg;
import org._50dozenhits.dice_library.command.CommandRegistry.Vec3Arg;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

// From the wiki page Command Framework.
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
