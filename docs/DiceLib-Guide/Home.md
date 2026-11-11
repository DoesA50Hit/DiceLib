# Home

DiceLib (mod id `dicelib`) is a library of building blocks for NeoForge 1.21.1 mods: who an ability reaches, damage and projectiles, taking control of mobs, knockback, zones, healing, dashes and teleports, props, timing, effects and player animations, spoken lines and dialogue, holograms and mob panels, a command framework and body parts. It adds no content of its own. It needs PlayerAnimationLib and GeckoLib, and nothing else.

The examples are written for an imaginary mod called `mymod`. The full example classes are in DiceLib's `examples` source set, where the build compiles them, and `docs/build_guide.py` copies them into these pages. The short snippets are not compiled by the build. If the guide and the code disagree, trust the code.

## Where to start

| Goal | Read |
|---|---|
| add DiceLib to a mod | [[Setup]] |
| hit, push or heal things from an ability | [[Targeting]], [[Damage]], [[Knockback]], [[Healing]] |
| put something on screen or in the world | [[Props]], [[Effects and Animations]], [[Holograms]] |
| make a character talk | [[Dialogue]] |
| change how DiceLib's UI looks, or draw it yourself | [[Looks and Custom UIs]] |

Press `/` on the site to search every section.

## Every note

| Group | Notes |
|---|---|
| Start here | [[Setup]] |
| Combat | [[Targeting]] · [[Knockback]] · [[Zones]] · [[Damage]] · [[Healing]] |
| Building blocks | [[Movement]] · [[Props]] · [[Timing and State]] · [[Effects and Animations]] · [[Body Parts]] |
| Talk and menus | [[Dialogue]] · [[Holograms]] · [[Looks and Custom UIs]] |
| Commands | [[Command Framework]] |

## Find it fast

| Task | Note |
|---|---|
| find the entity a player looks at, or everyone in an area, line, cone or chain | [[Targeting]] |
| shove, pull or leash | [[Knockback]] |
| make an area that lasts and pulses | [[Zones]] |
| deal damage that credits the right attacker | [[Damage]] |
| fire a projectile that pierces, arcs or homes | [[Damage#Shots]] |
| charm or mind-control a mob for a while | [[Targeting#Control]] |
| heal, overheal, give absorption or cheat death | [[Healing]] |
| find out who a heal came from, including an arrow's shooter | [[Healing#Heals]] |
| dash, lunge or pounce | [[Movement#Dashes]] |
| teleport, blink through walls, or land somewhere safe | [[Movement#Teleports]] |
| float swords, draw a chain, make a model bite on a frame, leave a copy | [[Props]] |
| run code later, or keep something per caster that cleans itself up | [[Timing and State]] |
| put an ability on cooldown, and show it on a HUD | [[Timing and State#Cooldowns]] |
| enchant an item for a while | [[Timing and State#Temporary enchantments]] |
| play an effect, a sound or a player animation | [[Effects and Animations]] |
| run code at a point in a player animation, or stop it when the player is hurt | [[Effects and Animations#Cues and stop conditions]] |
| play effects with Photon or another effect library | [[Effects and Animations#Effect players]] |
| give a race wings, horns or tails | [[Body Parts]] |
| make a boss or a mob say a line on screen | [[Dialogue#Spoken lines]] |
| write a conversation with choices that run code | [[Dialogue#Dialogue trees]] |
| put a player's name or one of your own values into a line | [[Dialogue#Placeholders]] |
| put a menu in the world that only some players see or press | [[Holograms]] |
| make a menu close by itself, or when a condition stops holding | [[Holograms#Going away on its own]] |
| show a panel tied to a mob, with its health | [[Holograms#Mob panels]] |
| restyle subtitles, dialogue, holograms and mob panels | [[Looks and Custom UIs#Looks]] |
| draw a hologram, mob panel, dialogue or line with your own code | [[Looks and Custom UIs#Custom UIs]] |
| build a hologram like a screen, with buttons, text boxes and textures | [[Looks and Custom UIs#Hologram screens]] |
| add a command | [[Command Framework]] |

---

Next: [[Setup]] →
