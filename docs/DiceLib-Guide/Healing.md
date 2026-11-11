# Healing

`Heals` heals an entity and records where the heal came from. It also gives absorption and has a call for cheating death. It is in `combat` and runs on the server.

## Heals

`Heals.heal(source, target, amount)` heals the target and records the source, which is the entity the heal came from. The source can be the caster, something of theirs such as a projectile, a prop or a summon, or null when the heal has no source. Send all of your mod's healing through it, so that anything listening for heals knows where each one came from. It returns the health the target actually gained, after listeners changed the amount and max health capped it. Vanilla's `LivingHealEvent` still fires inside it, so other mods' healing rules apply as usual.

For more control, `Heals.of(source, target, amount)` builds a heal and `heal()` applies it:

```java
// mend an ally by 4
float healed = Heals.of(caster, ally, 4)
        .overheal(200)            // what doesn't fit under their max health stays as absorption for 10 seconds
        .fx(MyMod.id("mend"))     // played on the ally if anything was healed
        .sound(SoundEvents.AMETHYST_BLOCK_CHIME, 1, 1.2f)
        .heal();                  // heals, and returns what the ally gained
```
| Call | Does |
|---|---|
| `Heals.percent(source, target, fraction)` | starts a heal of `fraction` of the target's max health |
| `times(multiplier)` | multiplies the amount, for your mod's own scaling |
| `overheal(ticks)` | what the healing rules allowed beyond max health becomes absorption for `ticks` |
| `fx(id)`, `fx(id, scale)`, `fx(id, scale, fallback)`, `sound(sound, volume, pitch)` | played on the target when anything was healed ([[Effects and Animations]]). Without an effect player for the id, clients draw the fallback, a burst of hearts unless given |
| `onHealed(amount -> ...)` | runs with what was healed, overheal included, when it was more than 0 |
| `heal()` | heals, and returns what the target gained in health |

| Event | Cancellable | Fires | Can change |
|---|---|---|---|
| `Heals.HealEvent.Heal` | yes | before anything is healed | `setAmount` |
| `Heals.HealEvent.Healed` | no | afterwards, when anything was healed | `getAmount()` is the amount healed |

`getEntity()` is the healed entity. `getSource()` is the entity the heal came from, null for none. `getHealer()` is the living entity behind it: the source itself when it is alive, else its owner, found through vanilla's `TraceableEntity` (an arrow's shooter) and `OwnableEntity` (a tamed animal's owner). `Heals.healerOf(entity)` answers the same for any entity.

This listener rewards a player for healing others, including heals from their arrows:

```java
@EventBusSubscriber(modid = MyMod.MODID)
public final class HealerRewards {
    // A player who heals someone else earns a point of experience per health healed. An arrow or a spring of theirs
    // counts too, because getHealer is the player behind whatever the heal came from.
    @SubscribeEvent
    static void onHealed(Heals.HealEvent.Healed event) {
        // getEntity is who was healed. Healing yourself earns nothing.
        if (event.getHealer() instanceof ServerPlayer healer && event.getEntity() != healer) {
            healer.giveExperiencePoints(Math.round(event.getAmount()));
        }
    }

    private HealerRewards() {
    }
}
```
## Absorption, survival and health

| Call | Does |
|---|---|
| `Heals.absorb(target, amount, ticks)` | absorption on top of what the target has, up to 10 hearts more, for `ticks`. The hidden `dicelib:extra_absorption` effect makes the room |
| `Heals.absorb(target, amount, ticks, room)` | the same, using your own status as the room (one with a `MAX_ABSORPTION` modifier), shown as any status is |
| `Heals.survive(target, health)` | cheats death: health set to exactly `health`, with no healing rules and no events. It is for effects like "the next death leaves the player at 1 heart". Don't use it to heal |
| `Heals.fraction(entity)` | health over max health, 0 to 1 |
| `Heals.missing(entity)` | how much health it is missing |

```java
// a charm that refuses the wearer's first death and leaves them at half health
@SubscribeEvent
static void onDeath(LivingDeathEvent event) {
    if (!(event.getEntity() instanceof ServerPlayer player) || player.getTags().contains("mymod_charm_spent")) return;
    player.addTag("mymod_charm_spent");       // it only works once
    event.setCanceled(true);                  // the player doesn't die
    // survive sets health outright. No healing rules run and no heal events fire.
    Heals.survive(player, player.getMaxHealth() / 2);
}
```

---

← Previous: [[Damage]] · Next: [[Movement]] →
