# Changelog

DiceLib follows semantic versioning. Anything marked `@ApiStatus.Internal` can change in any release.

## 1.0.0

The first release: the helpers that came out of Hoard of Deviations, made into a library and extended. "Changed" and
"Fixed" compare with those helpers as Hoard of Deviations had them.

### Added
- `Dialogues.placeholder(name, (player, speaker) -> ...)` adds your own `{name}` placeholders to dialogue text.
- `Fx.sound` overloads that take a `SoundSource`.
- `Heals.Heal.fx(id, scale, fallback)` picks what clients without the effect draw.
- The `dicelib:command` UI action takes an optional `permission` level, 2 by default.
- Javadoc on every public class and member, so IDE hover shows it from the sources jar.
- `@ApiStatus.Internal` on the network plumbing: payload `TYPE` and `STREAM_CODEC` fields, `handle` methods, the
  client receivers, `DiceNetwork` and the `init()` methods.
- README and this changelog.
- `Targets.targetable(entity)` and the `dicelib:untargetable` tag.
- `Targets.allyRule(rule)` adds your own way for entities to be allies, such as a party.
- `PlayerAnims.hold(entity, id)` plays a looping animation that players who come into view later also see.
- `Hits`: damage that knows its source and attacker, with `HitEvent.Hit` and `HitEvent.Landed`, as `Heals` has for healing.
- `Cooldowns`: cooldowns by id on any entity, saved, kept through death, and a player's own sent to their client.
- `Dashes`: `onEnd((caster, end) -> ...)` runs however a dash ends, `stopAtWalls()`, `Dashes.cancel(caster)` and
  `Dashes.dashing(caster)`.
- `Targets.touching` searches by the hitbox instead of the feet, and `Zone.hitboxes()` does the same for a zone.
- `Zone.onEnter` and `Zone.onExit`.
- `FloatingItem.sticksToTarget()` keeps a thrown object in the entity it hit, and `stuckIn()` says which.
- `@OptionalArg` and `@Suggest` for command parameters.
- Published to Maven Central as `io.github.doesa50hit:dicelib`, with sources and Javadoc.
- `HologramScreen`: a hologram built like a vanilla screen, with widgets, textures, left and right clicks, the scroll
  wheel and typing. `PanelUi` gains `mouseClicked`, `mouseScrolled`, `update`, `animated` and `removed`.
- Hologram messages: a custom UI sends its own data to the server with `send(action, tag)`, received by
  `onMessage(...)` on the builder and by `HologramEvent.Message`.
- `Hologram.data(tag)` changes a spawned hologram's data, and `Hologram.data(player, tag)` sends data to one viewer.
- `PlayerAnims.of(entity, id)`: speed, fade and mirroring, cues with `at(tick, ...)`, `stopWhenHurt`,
  `stopWhenMoving`, `stopOnItemSwap`, `stopWhen`, and `onEnd` with the reason. `PlayerAnims.playing(entity)` and
  `stop(entity, fadeOut)`.
- `Shots`: projectiles with no model that fly straight, arc or home, pierce, and hit through `Hits`.
- `Control`: takes over any mob for a while and gives it back unchanged. Bosses, the `dicelib:no_tame` tag and the
  `noTame` entity tag refuse it, and `allowBosses()` lets bosses through.

### Changed
- Every `Targets` search, `Zones`, `Dashes.onHit` and a flying `FloatingItem` skip entities that aren't targetable:
  armour stands and entity copies were hit, healed and chained to before. The entity type tag `dicelib:untargetable`
  lists them.
- `Targets.isAlly` also compares owners: two summons or pets of one caster are allies, and so is a summon and its
  caster's team-mate. Abilities that hit `NON_ALLIES` no longer hit those.
- A caster has one dash at a time: starting another interrupts the one running.
- A custom hologram UI is kept when the server sends new content, if its `update` returns true. `HologramScreen`
  does this, so its state survives updates.
- `Knockback.away`, `toward`, `along` and `push` post vanilla's `LivingKnockBackEvent`, so another mod can cancel or
  change them. `pull` and `leash` don't.
- `Fx.fly` sends one packet per throw, and each client steps the flight, where it used to send one packet per tick.
  Effect players still get one `FxPayload` per tick, as before.
- Dialogue placeholders work in `"text"` objects and in a translation's `"with"` arguments, not only in plain strings.
  A filled name keeps its style.
- `TempEnchants` also changes back items lying on the ground (as they land, then every second) and a container's
  items when a player opens it.
- NeoForge 21.1 or later is required (it was any 21.x). Parchment mappings match Minecraft 1.21.1.
- Comments no longer mention one mod's own systems.

### Fixed
- `/kill` and the void take an `EntityCopy` away. It used to survive both.
- A teleport or swap takes a rider off its mount first. It used to be carried straight back.
- A second `TempEnchants.raise` on the same enchantment no longer lowers the level or shortens the time.
- A `Scheduler` task whose action throws is logged and dropped, where it used to crash the server tick.
- A UI action or condition that throws (an argument its JSON left out) is logged once and skipped.
- `CommandRegistry`, `TetherLook`, `Dialogues.register` and `BodyParts` can be registered to from several mods loading
  at once.
- `CasterState` no longer clears for a death another mod cancels.
- `Zone.lasting(ticks)` works whether it is called before or after `every(...)`.
- Milk no longer removes the hidden status behind `Heals.absorb`, which took the absorption with it.
- A dialogue bound to an armour stand starts on a right-click.
- A hologram store saved after its level unloaded keeps its holograms.
