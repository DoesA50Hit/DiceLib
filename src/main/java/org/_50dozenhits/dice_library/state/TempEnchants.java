package org._50dozenhits.dice_library.state;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Unbreakable;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AnvilUpdateEvent;
import net.neoforged.neoforge.event.GrindstoneEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org._50dozenhits.dice_library.DiceLib;
import org._50dozenhits.dice_library.registry.DiceComponents;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Enchantments and Unbreakable put on an item for a while. The item remembers what it had before in the
 * dicelib:temp_enchants component, and gets it back once the time is up. That is checked every second for the items
 * in a player's inventory (armour and off hand included) and for items lying on the ground, and for a container's
 * items when a player opens it. An item kept somewhere else (another mod's slots, a hopper chain) changes back the
 * next time it reaches one of those places. Anvils and grindstones refuse such an item, so nothing temporary can be
 * kept or cashed in.
 */
@EventBusSubscriber(modid = DiceLib.MODID)
public final class TempEnchants {
    /**
     * {@code previous} is the level the item had before, 0 for none
     */
    public record Entry(Holder<Enchantment> enchantment, int previous, long until) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                Enchantment.CODEC.fieldOf("enchantment").forGetter(Entry::enchantment),
                Codec.INT.fieldOf("previous").forGetter(Entry::previous),
                Codec.LONG.fieldOf("until").forGetter(Entry::until)
        ).apply(i, Entry::new));
    }

    /**
     * unbreakableUntil 0 when it isn't temporarily unbreakable
     */
    public record Data(List<Entry> entries, long unbreakableUntil) {
        public static final Data EMPTY = new Data(List.of(), 0);
        public static final Codec<Data> CODEC = RecordCodecBuilder.create(i -> i.group(
                Entry.CODEC.listOf().fieldOf("entries").forGetter(Data::entries),
                Codec.LONG.optionalFieldOf("unbreakable_until", 0L).forGetter(Data::unbreakableUntil)
        ).apply(i, Data::new));
        @ApiStatus.Internal
        public static final StreamCodec<RegistryFriendlyByteBuf, Data> STREAM_CODEC = ByteBufCodecs.fromCodecWithRegistries(CODEC);
    }

    /**
     * Raises the enchantment to at least {@code level} until game time {@code until}. Calling it again never lowers the level or
     * shortens the time: the higher level and the later time count, and the level from before the first call is the
     * one put back.
     */
    public static void raise(ItemStack stack, Holder<Enchantment> enchantment, int level, long until) {
        Data data = stack.getOrDefault(DiceComponents.TEMP_ENCHANTS, Data.EMPTY);
        List<Entry> entries = new ArrayList<>(data.entries());
        int current = stack.getEnchantments().getLevel(enchantment);
        int previous = current;
        long end = until;
        boolean raisedBefore = false;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).enchantment().equals(enchantment)) {
                previous = entries.get(i).previous();
                end = Math.max(until, entries.get(i).until());
                entries.remove(i);
                raisedBefore = true;
                break;
            }
        }
        if (!raisedBefore && current >= level) return;
        entries.add(new Entry(enchantment, previous, end));
        stack.set(DiceComponents.TEMP_ENCHANTS, new Data(List.copyOf(entries), data.unbreakableUntil()));
        int raised = Math.max(level, current);
        EnchantmentHelper.updateEnchantments(stack, enchantments -> enchantments.set(enchantment, raised));
    }

    /**
     * Unbreakable until game time {@code until}. An item that already is stays as it is.
     */
    public static void unbreakable(ItemStack stack, long until) {
        Data data = stack.getOrDefault(DiceComponents.TEMP_ENCHANTS, Data.EMPTY);
        if (stack.has(DataComponents.UNBREAKABLE) && data.unbreakableUntil() == 0) return;
        stack.set(DiceComponents.TEMP_ENCHANTS, new Data(data.entries(), Math.max(until, data.unbreakableUntil())));
        stack.set(DataComponents.UNBREAKABLE, new Unbreakable(true));
    }

    /**
     * Puts back whatever has run out by {@code now}. True when anything changed.
     */
    public static boolean revert(ItemStack stack, long now) {
        Data data = stack.get(DiceComponents.TEMP_ENCHANTS);
        if (data == null) return false;
        List<Entry> left = new ArrayList<>();
        boolean changed = false;
        for (Entry entry : data.entries()) {
            if (entry.until() > now) {
                left.add(entry);
                continue;
            }
            EnchantmentHelper.updateEnchantments(stack, enchantments -> enchantments.set(entry.enchantment(), entry.previous()));
            changed = true;
        }
        long unbreakable = data.unbreakableUntil();
        if (unbreakable != 0 && unbreakable <= now) {
            stack.remove(DataComponents.UNBREAKABLE);
            unbreakable = 0;
            changed = true;
        }
        if (!changed) return false;
        if (left.isEmpty() && unbreakable == 0) stack.remove(DiceComponents.TEMP_ENCHANTS);
        else stack.set(DiceComponents.TEMP_ENCHANTS, new Data(List.copyOf(left), unbreakable));
        return true;
    }

    public static boolean isTemporary(ItemStack stack) {
        return stack.has(DiceComponents.TEMP_ENCHANTS);
    }

    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide() || player.tickCount % 20 != 0) return;
        long now = player.level().getGameTime();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) revert(stack, now);
        }
    }

    // An item entity changes back as it lands in the world and then every second. setItem sends the change to clients.
    @SubscribeEvent
    static void onItemJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof ItemEntity item) revert(item, event.getLevel().getGameTime());
    }

    @SubscribeEvent
    static void onItemTick(EntityTickEvent.Post event) {
        if (event.getEntity() instanceof ItemEntity item && item.tickCount % 20 == 0 && !item.level().isClientSide()) {
            revert(item, item.level().getGameTime());
        }
    }

    private static void revert(ItemEntity item, long now) {
        ItemStack stack = item.getItem();
        if (!isTemporary(stack)) return;
        ItemStack copy = stack.copy();
        if (revert(copy, now)) item.setItem(copy);
    }

    // A container's items change back when a player opens it. The menu sends the changed slots on its next update.
    @SubscribeEvent
    static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (event.getEntity().level().isClientSide()) return;
        long now = event.getEntity().level().getGameTime();
        for (Slot slot : event.getContainer().slots) {
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && revert(stack, now)) slot.setChanged();
        }
    }

    @SubscribeEvent
    static void onAnvil(AnvilUpdateEvent event) {
        if (isTemporary(event.getLeft()) || isTemporary(event.getRight())) event.setCanceled(true);
    }

    @SubscribeEvent
    static void onGrindstone(GrindstoneEvent.OnPlaceItem event) {
        if (isTemporary(event.getTopItem()) || isTemporary(event.getBottomItem())) event.setCanceled(true);
    }

    // client side only, where the event fires
    @SubscribeEvent
    static void onTooltip(ItemTooltipEvent event) {
        Data data = event.getItemStack().get(DiceComponents.TEMP_ENCHANTS);
        if (data == null || event.getEntity() == null) return;
        long now = event.getEntity().level().getGameTime();
        for (Entry entry : data.entries()) {
            Component name = Enchantment.getFullname(entry.enchantment(), Math.max(1, event.getItemStack().getEnchantments().getLevel(entry.enchantment())));
            event.getToolTip().add(Component.translatable("item.dicelib.temporary", name, seconds(entry.until() - now)).withStyle(ChatFormatting.GOLD));
        }
        if (data.unbreakableUntil() != 0) {
            event.getToolTip().add(Component.translatable("item.dicelib.temporary", Component.translatable("item.unbreakable"),
                    seconds(data.unbreakableUntil() - now)).withStyle(ChatFormatting.GOLD));
        }
    }

    private static String seconds(long ticks) {
        long seconds = Math.max(0, ticks / 20);
        return seconds / 60 + ":" + String.format("%02d", seconds % 60);
    }
}
