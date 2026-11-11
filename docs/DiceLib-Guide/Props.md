# Props

Props are entities that are only there to be seen, such as floating swords or a chain. They can't be hit (except a breakable tether) and are never saved. The server places them and decides how long they last. Each one has a builder, so an ability can spawn one in a single statement. All of them are in `prop`.

| Prop | Is | Suits |
|---|---|---|
| `FloatingItem` | any item, floating: orbiting, fanned, in a row, flying, or stuck | thrown swords, orbiting embers |
| `Formation` | the floating items one caster has out, kept at a count and in an arrangement | a fan of blades behind the back, a held shieldwall |
| `Tether` (with `TetherLook`) | a line between two ends, drawn as links, glyphs or a ribbon | chains, ribbons of light, a flail |
| `AnimatedProp` | a GeckoLib model that appears, plays one animation and leaves, with server cues on set ticks | a serpent biting out of the ground, a fist slamming down |
| `EntityCopy` | a frozen copy of a living entity, with its skin and gear | a decoy |

## FloatingItem

A `FloatingItem` shows any item as its item model at full brightness, always in one of four poses:

| Pose | Set with | Does |
|---|---|---|
| `ORBIT` | `orbit(holder, anchor, slot, slots, radius, height, speed, tip)` | circles its holder (or `anchor` when the holder is null), `radius` out and `height` up, as slot `slot` of `slots` spread evenly, turning `speed` radians a tick |
| `ROW` | `row(holder, slot, slots, distance, height)` | stands in a line `distance` in front of its holder (whichever way it faces), half a block apart |
| `FAN` | `fan(holder, slot, slots, reach, height)` | one of a fan behind its holder's back, tips up and out like wings, leaning back 20 degrees and swaying slightly |
| `FREE` | `fly(...)` or `stay(...)` | the server moves it, flying or stuck where it landed |

Slot 0 of a fan is the middle, and later slots go outward one side at a time, so a fan that loses its last few objects keeps the rest in place.

The `tip` of an orbiting one is where it points: `UP` or `DOWN` (upright, bobbing slightly when up), `TANGENT` (along its circle, flat side out) or `ALONG` (its own rotation). Rows and fans point up. Flying and stuck ones point `ALONG` their travel.

```java
// three swords circling the caster, tips up
for (int slot = 0; slot < 3; slot++) {
    FloatingItem.spawn(level, new ItemStack(Items.IRON_SWORD), BodyPoints.shoulder(caster, 1))
            // slot of 3 spread evenly around the caster, 1.2 blocks out and 1.3 up, turning 0.1 radians a tick
            .orbit(caster, Vec3.ZERO, slot, 3, 1.2f, 1.3f, 0.1f, FloatingItem.Tip.UP);
}
```
Both sides compute where an orbiting, row or fanned object belongs from the game time and its holder, so it keeps up with a moving holder without waiting on the server. An item with a holder is removed when its holder is.

### Flying

`fly(owner, velocity, range, hitsEntities, relation, onHit, stickTicks)` sends it straight along `velocity` (blocks per tick) for `range` blocks. With `hitsEntities` it stops at the first living entity matching `relation` to `owner` (never the owner). It always stops at blocks. Then `onHit` runs once with whatever it hit (null for a block or the end of its range) and where it stopped, and it sticks there, pointing the way it flew, for `stickTicks` before fading. By default a stuck item stays where it stopped, so it hangs in the air if the entity it hit walks away. Call `sticksToTarget()` to keep it in that entity and move it along. `stuckIn()` returns the entity.

```java
// one sword thrown where the caster looks
Vec3 aim = caster.getLookAngle();
FloatingItem.spawn(level, new ItemStack(Items.IRON_SWORD), BodyPoints.shoulder(caster, 1))
        .fly(caster, aim.scale(1.6), 24,              // 1.6 blocks a tick for up to 24 blocks
                true, Targets.Relation.NON_ALLIES,    // it stops at the first non-ally
                (hit, at) -> {
                    // hit is null when a block stopped it or it ran out of range
                    if (hit != null) hit.hurt(caster.damageSources().mobAttack(caster), 5);
                },
                12);                                  // it stays stuck where it stopped for 12 ticks
```
| Call | Does |
|---|---|
| `stay(at, tip, ticks)` | stuck at `at` pointing along `tip` for `ticks`, then fades |
| `life(ticks)` | fades after `ticks` regardless. -1 (default) lasts forever |
| `strike(target)` | a quick dart at an entity and back, drawn on the client (6 ticks). It deals no damage, so use it for the look of a parry or a riposte |
| `scale(size)` | draws it this much bigger (default 1.1) |
| `spriteTurn(degrees)` | how far the item model turns in its own plane before being pointed. 45 (default) stands a sword or tool sprite upright. Use 0 for items already drawn upright |
| `worldLit()` | lit by the world instead of full bright |
| `item(stack)` | changes what it shows |

An effect played `on` a flying one travels with it. `Fx.on(object, id, 1, fallback)` follows the entity while the effect plays, so a short trail effect played on each throw goes along.

In first person, the client hides the player's own orbiting items in front of the camera so they don't block the view.

## Formation

A `Formation` keeps a caster's floating items in named groups. The `READY` group hangs around them, and `keep` tops it up or trims it to a count and places each one, every tick. An ability can hold other groups for a while, such as a shieldwall. A caster's items vanish when the caster dies or leaves.

A formation only handles how the items look. The number of items a caster has, like anything else that must be saved, belongs in saved data such as an attachment or the player's persistent data. Read the count from there and pass it to `keep`. After a restart the formation is rebuilt from that count.

```java
@EventBusSubscriber(modid = MyMod.MODID)
public final class ExampleSwords {
    // The fan. Each new object in it is an iron sword, and one that vanishes plays this effect.
    private static final Formation FAN = Formation.of(caster -> new ItemStack(Items.IRON_SWORD)).vanishFx(MyMod.id("sword_fade"));
    // How many swords are ready. The count lives in the player's saved data, so the fan is rebuilt from it after a
    // restart. The Formation only draws the swords and is never saved.
    private static final String READY = "mymod_swords_ready";
    private static final int MAX = 5;

    // Every tick: a player with a nether star in the off hand has their ready swords fanned out behind them.
    @SubscribeEvent
    static void onTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        CompoundTag data = player.getPersistentData();
        if (!data.contains(READY)) data.putInt(READY, MAX);          // a new player starts with all five
        boolean shown = player.getOffhandItem().is(Items.NETHER_STAR) && !player.isInvisible();
        int count = shown ? data.getInt(READY) : 0;                   // keeping 0 makes the swords vanish
        // keep adds or removes swords until there are `count`, then places each one with the lambda.
        // Here each sword takes its slot in a fan 0.9 blocks long that starts a little above the middle of the back.
        FAN.keep(player, count, (sword, slot, slots) -> sword.fan(player, slot, slots, 0.9f, player.getBbHeight() * 0.62f));
    }

    // Throws one sword where the player looks. It is ready again 4 seconds later. False when none is ready.
    public static boolean send(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        if (data.getInt(READY) <= 0) return false;
        data.putInt(READY, data.getInt(READY) - 1);                   // one fewer in the fan from the next tick
        // 80 ticks later the count goes back up. The task belongs to the player and is dropped if they leave.
        Scheduler.after(player, 80, owner -> {
            CompoundTag saved = owner.getPersistentData();
            saved.putInt(READY, Math.min(MAX, saved.getInt(READY) + 1));
        });
        // take pulls the last sword out of the fan, so keep stops moving it and it can fly off
        FAN.take(player).fly(player,
                player.getLookAngle().scale(1.6),                     // 1.6 blocks a tick
                24,                                                   // for up to 24 blocks
                true, Targets.Relation.NON_ALLIES,                    // it stops at the first non-ally
                (hit, at) -> {
                    // hit is null when a block stopped it or it ran out of range
                    if (hit != null) hit.hurt(player.damageSources().playerAttack(player), 5);
                },
                12);                                                  // then it stays stuck there for 12 ticks
        return true;
    }

    private ExampleSwords() {
    }
}
```
| Call | Does |
|---|---|
| `Formation.of(caster -> stack)` | one kind of set, made once as a static field. The function picks what new objects show |
| `vanishFx(id)` | the effect where one vanishes (otherwise a puff of crit sparks) |
| `keep(owner, count, arrange)` | keeps the `READY` group at `count`. `arrange` is a lambda `(item, slot, count) -> ...` that places each one |
| `take(owner)` | pulls out the last ready one to send it, or makes a new one by the right shoulder |
| `spawn(owner, at)` | a new one that isn't in any group |
| `hold(owner, key, objects, ticks)` | keeps `objects` as group `key` (the previous group vanishes), fading after `ticks` (-1 lasts forever) |
| `held(owner, key)` | the living objects in a group |
| `takeHeld(owner, key, n)` | takes up to `n` out of a group to send them. They no longer vanish with the group |
| `release(owner, key)`, `clear(owner)` | vanishes a group, or all of them |

```java
// shieldwall: four swords in a row for 5 seconds, with three of them thrown after 3
List<FloatingItem> wall = new ArrayList<>();
for (int slot = 0; slot < 4; slot++) {
    // each sword stands 1.2 blocks in front of the caster and 0.9 up, in its slot of 4 counted from the left
    wall.add(FormationsExample.SWORDS.spawn(caster, caster.position()).row(caster, slot, 4, 1.2f, 0.9f));
}
FormationsExample.SWORDS.hold(caster, "wall", wall, 100);   // kept as the group "wall", which fades after 100 ticks
Scheduler.after(caster, 60, owner -> {
    // takeHeld removes up to 3 swords from the group, so they don't fade with the rest
    for (FloatingItem sword : FormationsExample.SWORDS.takeHeld(owner, "wall", 3)) {
        sword.fly(owner, owner.getLookAngle().scale(1.4), 20, true, Targets.Relation.NON_ALLIES, null, 10);
    }
});
```
```java
public final class FormationsExample {
    // One Formation per kind of set, kept in a static field. The lambda says what each new object shows for a caster.
    public static final Formation SWORDS = Formation.of(caster -> new ItemStack(Items.IRON_SWORD));
}
```
## Tether

A line between two ends. Each end is an entity (followed as it moves) or a fixed point. A `TetherLook` draws it:

| Kind | Drawn as | Texture |
|---|---|---|
| `LINKS` | flat oval links, each one turned a quarter from the last like a real chain | one link |
| `GLYPHS` | a string of flat signs, each drawn both ways so it reads from either side | `cells` signs side by side, used in turn |
| `RIBBON` | one band made of two crossed strips. The texture scrolls along it with the flow, it swells in pulses, and it narrows to a point at the far end | the band, repeating along it |

DiceLib provides two looks: `TetherLook.CHAIN` (iron links) and `TetherLook.RIBBON` (a red band). Register your own looks once during common setup. A static field in a class that loads early is enough.

```java
public final class ExampleTethers {
    // A chain of thorn links. Each link is 0.26 blocks across and 0.4 long, with one every 0.28 blocks, so they
    // overlap a little. minLight(6) keeps it readable in the dark, and breakFx plays where a breakable tether snaps.
    public static final TetherLook THORNS = TetherLook.register(TetherLook.links(MyMod.id("thorns"),
            MyMod.id("textures/tether/thorns.png"), 0.26f, 0.4f, 0.28f).minLight(6).breakFx(MyMod.id("example/thorns_break")));
    // A string of signs. The texture holds four of them side by side (the last argument). Each is drawn 0.34 across
    // and 0.34 long, with one every 0.32 blocks.
    public static final TetherLook SCRIPT = TetherLook.register(TetherLook.glyphs(MyMod.id("script"),
            MyMod.id("textures/tether/script.png"), 0.34f, 0.34f, 0.32f, 4));
    // One smooth band of light, always drawn at full brightness.
    public static final TetherLook LIGHT = TetherLook.register(TetherLook.ribbon(MyMod.id("light"), MyMod.id("textures/tether/light.png")).minLight(15));
}
```
`minLight` is the block light it is never drawn darker than (0 to 15), so a dark chain stays visible at night. Signs and links use it as well.

| Call | Does |
|---|---|
| `Tether.spawn(level, look, at)` | a new one, both ends at `at` |
| `from(entity, hand)`, `from(point)` | where it starts: an entity's right hand (with `hand`) or middle, or a point |
| `to(entity)`, `to(point)` | where it ends. Calling `to(point)` every tick drags the far end around |
| `extend(ticks)` | shoots out from the start over `ticks` |
| `lash(extend, hold, retract)` | shoots out, stays, pulls back in, gone |
| `retract()` | pulls back in immediately and is removed |
| `life(ticks)` | goes after `ticks`, pulling back in over the last few |
| `sag(amount)` | how far the middle hangs, in blocks per block of length |
| `spin(radius, speed)` | the far end circles the start instead, `speed` radians a tick, for a flail or a wrecking ball |
| `end(blockState)` | a block hanging on the far end, turning slowly |
| `ribbon(width, flow, tint)` | ribbons only: width in blocks, `Flow.OUT`, `IN` or `STILL`, and an RGB tint over the texture |
| `breakable(owner, health, onBreak)` | barrier health that anyone not allied with `owner` can hit. `onBreak` runs when it breaks |

A tether is removed when an entity at either end is. A lash wobbles slightly while flying out.

```java
// a chain from the caster's hand that lashes out to the target and yanks it in
Tether.spawn(level, TetherLook.CHAIN, BodyPoints.hand(caster, true))
        .from(caster, true)       // true starts it at the hand, false at the middle of the body
        .to(target)
        .lash(4, 4, 4);           // 4 ticks out, 4 held, 4 back in, and then it is removed
// the pull lands when the chain has reached the target
Scheduler.after(caster, 4, owner -> Knockback.toward(target, owner.position(), 1.2, 0.3));

// a ribbon drawing the target's light into the caster for 2 seconds
Tether.spawn(level, TetherLook.RIBBON, target.position())
        .from(target, false).to(caster)
        .ribbon(0.3f, Tether.Flow.IN, 0xFFFFFF)   // 0.3 blocks wide, flowing IN, and white for no tint
        .extend(5)                // grows out over 5 ticks
        .life(40);                // removed after 40 ticks

// a flail: a heavy core on a short chain, swung around the caster for 3 seconds
Tether.spawn(level, TetherLook.CHAIN, caster.position())
        .from(caster, true)
        .spin(2.5f, 0.35f)        // the far end circles 2.5 blocks out at 0.35 radians a tick
        .end(Blocks.HEAVY_CORE.defaultBlockState())   // the block hanging on the far end
        .life(60);
```
This breakable tether holds a target in place, and breaking it ends the hold:

```java
// a chain that slows the target for 5 seconds unless someone breaks it
Tether hold = Tether.spawn(level, TetherLook.CHAIN, target.position())
        .from(caster, true).to(target)
        .sag(0.05f)               // the middle hangs 0.05 blocks for each block of length
        .extend(5)
        // Anyone not allied with the caster can hit it. After 8 damage it breaks and the lambda runs.
        .breakable(caster, 8, () -> target.removeEffect(MobEffects.MOVEMENT_SLOWDOWN));
target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 100, 3));
hold.life(100);
```
## AnimatedProp

An `AnimatedProp` is a GeckoLib model that appears, plays one animation and leaves. It has no AI. The model id `ns:name` points at `assets/ns/geo/prop/name.geo.json`, `assets/ns/animations/prop/name.animation.json` and `assets/ns/textures/prop/name.png`. Put the key moment (a bite, a slam) on a cue timed to the animation's frame.

```java
// a serpent that comes up 2.5 blocks short of the target and bites on tick 12
Vec3 dir = target.position().subtract(caster.position()).multiply(1, 0, 1).normalize();   // toward the target, along the ground
Vec3 from = target.position().subtract(dir.scale(2.5));
AnimatedProp.spawn(level, MyMod.id("stone_serpent"), from, AnimatedProp.yawToward(from, target.position()))   // facing the target
        .animation("animation.stone_serpent.strike")   // played once, then held on its last frame
        .life(30)                 // removed after 30 ticks
        .reach(4)                 // the model is up to 4 blocks long, so it is drawn while any of it is in view
        // The server can't read the animation, so the bite is timed by hand to match it.
        .on(12, serpent -> {
            if (target.isAlive()) target.hurt(caster.damageSources().mobAttack(caster), 8);
        });
```
| Call | Does |
|---|---|
| `AnimatedProp.spawn(level, model, at, yaw)` | a new one at `at`, its front (+Z in Blockbench) turned to `yaw` |
| `AnimatedProp.yawToward(from, to)` | the yaw that makes `from` face `to` along the ground |
| `animation(name)` | plays once from the first tick and holds the last frame |
| `looping(name)` | loops for as long as it lasts |
| `life(ticks)` | how long it lasts (default 40) |
| `on(tick, prop -> ...)` | runs on the server on that tick (1 is the first). Several may share a tick |
| `scale(size)` | draws it this much bigger |
| `glowing()` | full bright and see-through, for light and fire |
| `reach(blocks)` | how far the model reaches from its feet (default 3), so big ones still draw while any part is in view |
| `follow(entity)` | stands on `entity` and turns with its body, for an aura or a halo that plays out |

## EntityCopy

An `EntityCopy` is a frozen copy of a living entity where it stands. A player copy has their own skin (wide or slim arms), armor and held items. A mob copy draws as a fresh mob of the same type holding the same items. It can't be hurt, doesn't move or think, and is never saved. `copy` adds it to the world.

```java
// leave a decoy behind while the caster goes invisible for 3 seconds
EntityCopy.copy(caster).life(60).onEnd(decoy -> Fx.at(level, MyMod.id("smoke"), decoy.position(), 1,
        Fx.Fallback.burst(ParticleTypes.CLOUD, 0.6f, 20)));
caster.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 60, 0, false, false));
```
| Call | Does |
|---|---|
| `EntityCopy.copy(entity)` | a copy facing the same way, wearing and holding the same items |
| `life(ticks)` | goes after `ticks`, running `onEnd` first. -1 (default) lasts until `discard` |
| `onEnd(copy -> ...)` | |
| `owner()` | the id whose skin a player copy wears |

Mobs that target the caster don't switch to the copy by themselves. To make the copy work as a decoy, clear their target with `mob.setTarget(null)`.

## Body parts and transformation looks

Body parts are GeckoLib models drawn on players over their armor. They are attached to the player instead of being spawned, and have their own page ([[Body Parts]]).

---

← Previous: [[Movement]] · Next: [[Timing and State]] →
