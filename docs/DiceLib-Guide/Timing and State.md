# Timing and State

`Scheduler` runs code later, `CasterState` keeps values per caster, `Cooldowns` times abilities, `While` holds modifiers while a condition lasts, and `TempEnchants` puts enchantments on for a while. All five are in `state`.

## Scheduler

`Scheduler` runs code later on the server, either once after a delay or a set number of times on an interval. Abilities use it for anything delayed, like a blast a second after the cast or a hit every second for five seconds.

| Call | Runs |
|---|---|
| `Scheduler.after(ticks, () -> ...)` | once, `ticks` ticks from now |
| `Scheduler.after(entity, ticks, entity -> ...)` | the same, skipped if the entity is gone by then |
| `Scheduler.repeat(interval, times, run -> ...)` | `times` times, the first one `interval` ticks from now. `run` counts from 0 |
| `Scheduler.repeat(entity, interval, times, (entity, run) -> ...)` | the same, stopping early once the entity is gone |

"Gone" means removed from the world (killed, discarded, unloaded, a player who logged out) or, for a living entity, dead. Every call returns a `Scheduler.Task` with `cancel()` and `isDone()`.

It counts server ticks, so pausing a singleplayer world pauses it as well. `after(20, ...)` runs 20 full ticks later, and any delay under 1 counts as 1.

A blast that lands a second after the cast, on whatever the caster aimed at:

```java
LivingEntity target = Targets.lookedAt(caster, 16, Targets.Relation.NON_ALLIES);
if (target != null) {
    // One second later. The task is tied to the target, so it is skipped if the target is gone by then.
    Scheduler.after(target, 20, marked -> {
        marked.hurt(caster.damageSources().mobAttack(caster), 6);
        ((ServerLevel) marked.level()).sendParticles(ParticleTypes.EXPLOSION, marked.getX(), marked.getY() + 1, marked.getZ(), 1, 0, 0, 0, 0);
    });
}
```
For damage over time, this one hits every second for five seconds and stops early if the target dies:

```java
static void burn(LivingEntity caster, LivingEntity target) {
    // Every 20 ticks, 5 times. run counts from 0, and the task stops by itself if the target dies.
    Scheduler.repeat(target, 20, 5, (burning, run) -> {
        burning.hurt(caster.damageSources().onFire(), 2);
        if (run == 4) burning.clearFire();                   // the last of the five
    });
}
```
If an action throws, DiceLib logs the exception and drops that task. Other tasks keep running.

A delayed action runs no matter what happened in the meantime, so it should check what it depends on. Here the second hit only lands if the caster still holds the staff:

```java
target.hurt(caster.damageSources().mobAttack(caster), 3);
// Tied to the caster this time, so the second hit is dropped if the caster dies within those 2 seconds.
Scheduler.after(caster, 40, later -> {
    if (!later.getMainHandItem().is(MyItems.STAFF.get())) return;   // and it needs the staff still in hand
    if (target.isAlive()) target.hurt(later.damageSources().mobAttack(later), 5);
});
```
Tasks live in memory only, so stopping the server drops them. For something that must survive a restart, such as a curse that ends in an hour, save the end time with your own data and check that.

## CasterState

A `CasterState` keeps what an ability holds per caster and never saves, such as the chain it holds or the golems it commands. It empties itself when the caster dies, leaves the level (logs out, changes dimension, is unloaded), or the server stops. It runs `onClear` on whatever it drops, so the props it held go away. Make one per kind of value as a static field. Server side only.

```java
public final class ExampleHolds {
    // One chain per caster, for an ability that holds someone. CasterState forgets the chain when the caster dies or
    // leaves and runs the lambda on it, which pulls the chain back in so none is left hanging in the world.
    public static final CasterState<Tether> HOLDS = CasterState.create((caster, chain) -> chain.retract());
}
```
```java
// a new hold replaces the old one, and onClear pulls the old one back in
ExampleHolds.HOLDS.put(caster, Tether.spawn(level, TetherLook.CHAIN, caster.position()).from(caster, true).to(target).extend(4));
Tether held = ExampleHolds.HOLDS.get(caster);
// let go: the chain is pulled back in
ExampleHolds.HOLDS.remove(caster);
```
| Call | Does |
|---|---|
| `put(caster, value)` | sets it. The previous value is dropped through `onClear` |
| `get(caster)`, `getOrCreate(caster, supplier)`, `has(caster)` | |
| `remove(caster)` | takes it out and runs `onClear` |
| `take(caster)` | takes it out without `onClear`, when the ability hands it to something else |
| `CasterState.clearAll(caster)` | clears every store for that caster, for your own reset |

A value that must survive a restart belongs in saved data, such as an attachment or an item component.

## Cooldowns

`Cooldowns` keeps cooldowns on any entity, each under an id. Use one id per ability or item, or share an id between several abilities. Cooldowns are saved with the entity and kept through death. They count game time, so they don't run down while the server is off.

```java
public static final ResourceLocation FIREBALL = MyMod.id("fireball");

static void cast(LivingEntity caster) {
    if (!Cooldowns.tryStart(caster, FIREBALL, 100)) return;   // not ready yet
    // ... the fireball
}
```

| Call | Does |
|---|---|
| `Cooldowns.tryStart(entity, id, ticks)` | starts it and returns true when it is ready. While it is still running it returns false and changes nothing |
| `Cooldowns.start(entity, id, ticks)` | starts it, replacing one already running. 0 or less clears it |
| `Cooldowns.ready(entity, id)`, `remaining(entity, id)` | whether it is ready, and the ticks until it is |
| `Cooldowns.progress(entity, id, partialTick)` | 0 the moment it starts, 1 when ready, for drawing |
| `Cooldowns.reduce(entity, id, ticks)` | makes it ready that many ticks sooner, for a refund on a kill or a haste effect |
| `Cooldowns.clear(entity, id)`, `clearAll(entity)` | ends one, or all of them |
| `Cooldowns.all(entity)` | the ones still running, by id |

Starting, reducing and clearing are server side. Reading also works on the client. Each player's own cooldowns are synced to them, so your HUD can call `Cooldowns.progress(player, FIREBALL, partialTick)` without sending anything itself. Other entities' cooldowns are not synced.

## While

`While` is for effects that should apply only while a condition holds, such as while sprinting. Call its methods with the condition every tick, or whenever the condition may have changed. They change the entity only when the condition's value changed. When the rule stops applying, call them once more with false.

| Call | Does |
|---|---|
| `While.modifier(entity, attribute, id, amount, operation, on)` | the modifier on while `on`, off otherwise |
| `While.scaledModifier(entity, attribute, id, amount, operation)` | keeps a modifier at `amount`, replacing it if it changed. 0 removes it (attack speed per combo hit) |
| `While.effect(entity, effect, amplifier, on)` | keeps an effect up while `on`, no particles. The same effect from a potion is left alone |

```java
// boots of haste: faster while sprinting with them on
@SubscribeEvent
static void onTick(PlayerTickEvent.Post event) {
    if (!(event.getEntity() instanceof ServerPlayer player)) return;
    boolean on = player.isSprinting() && player.getItemBySlot(EquipmentSlot.FEET).is(MyItems.HASTE_BOOTS.get());
    // Called every tick with the condition. It adds the modifier when `on` turns true and removes it when it turns
    // false, and does nothing on the ticks in between.
    While.modifier(player, Attributes.MOVEMENT_SPEED, MyMod.id("haste_boots"), 0.1, AttributeModifier.Operation.ADD_MULTIPLIED_BASE, on);
}
```
## Temporary enchantments

`TempEnchants` puts an enchantment (or Unbreakable) on an item for a while. The item remembers its previous state in the `dicelib:temp_enchants` component and gets it back when time is up. DiceLib checks the items in a player's inventory and the items lying on the ground every second, and a container's items when a player opens it. An item kept anywhere else, such as another mod's slots, changes back once it reaches one of those places. Anvils and grindstones refuse these items, so nobody can make a temporary enchantment permanent or grind it into XP. The tooltip shows what is temporary and for how long.

```java
Holder<Enchantment> sharpness = level.registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(Enchantments.SHARPNESS);
long until = level.getGameTime() + 400;
TempEnchants.raise(sword, sharpness, 3, until);   // at least Sharpness III for 20 seconds
TempEnchants.unbreakable(sword, until);
```
A second `raise` on the same enchantment never lowers the level or shortens the time. The item keeps the higher level and the later end time, and when time is up it goes back to the level it had before the first `raise`.

`TempEnchants.revert(stack, now)` takes back whatever has run out by game time `now`, and `TempEnchants.isTemporary(stack)` asks whether anything on the item is temporary.

---

← Previous: [[Props]] · Next: [[Effects and Animations]] →
