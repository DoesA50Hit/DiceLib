# Body Parts

Body parts are GeckoLib models drawn on players, such as wings, horns or a tail. They are drawn by the player renderer over armor, so everyone sees them, and each player's copy animates on its own. Everything here is client side, in `client.render.bodypart`, registered at client setup (`FMLClientSetupEvent`).

Setting them up takes three calls:

| Piece | Says |
|---|---|
| `BodyParts.register(part)` | defines a part, with its model and how it moves |
| `BodyParts.add(group, uses...)` | which parts a group wears. A group is any id, such as a race or a class |
| `BodyParts.setSource(player -> worn)` | what each player wears right now, and in what look. DiceLib draws what it returns |

Parts are not drawn on invisible players. Each player can also choose whose parts are drawn with the client option `bodyParts.show` in `config/dicelib-client.toml`, which is `ALL`, `OTHERS` (everyone except themselves) or `NONE`.

## Parts

A part with id `ns:name` uses these files:

| File | Holds |
|---|---|
| `assets/ns/geo/bodypart/name.geo.json` | the model, standing on a player whose feet are at y 0 (the neck is at y 24 pixels) |
| `assets/ns/animations/bodypart/name.animation.json` | its animations |
| `assets/ns/textures/bodypart/name_<color>.png` | one per colour. `name_<color>_glow.png` for a rare colour |
| `assets/ns/textures/bodypart/name_mark_<marking>.png` | one per marking, drawn over the colour |

```java
@EventBusSubscriber(modid = MyMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ExampleParts {
    public static final ResourceLocation HORNS = MyMod.id("horns");
    public static final ResourceLocation WINGS = MyMod.id("wings");
    public static final ResourceLocation TAILS = MyMod.id("tails");

    @SubscribeEvent
    static void setup(FMLClientSetupEvent event) {
        // The parts. Each id names a model made in Blockbench.

        // Horns sit on the head, which is the default. anchor is the point a size option scales around, here 28
        // pixels up in the model. The two fidgets are animations played now and then while the player rests.
        BodyParts.register(BodyPart.builder(HORNS).anchor(28, 0).fidgets("fidget_tilt", "fidget_nod").build());
        // Wings sit on the body. flight picks the loop from how the player moves: "glide" while gliding, "flap"
        // while rising, "idle" otherwise. The sixfold shape has its own fidget animations.
        BodyParts.register(BodyPart.builder(WINGS).on(BodyPart.Attach.BODY).anchor(20, 2.5f)
                .loop("idle", BodyPart.flight("idle", "glide", "flap"))
                .fidgets("fidget_stretch", "fidget_ruffle")
                .shapeAnimations("sixfold", "fidget_sixfold_left", "fidget_sixfold_right")
                .build());
        // Tails: the model has bones tail_1 to tail_9. fan shows as many as the wearer's count says and spreads
        // them 13 degrees apart, lifted by -55 degrees.
        BodyParts.register(BodyPart.builder(TAILS).on(BodyPart.Attach.BODY).loop("sway").anchor(12, 2)
                .bones(BodyPart.fan("tail_", 9, 13, -55)).build());

        // Which group wears which parts. A group is any id you choose.
        BodyParts.add(MyMod.id("demon"), BodyParts.use(HORNS));                    // a whole race
        BodyParts.add(MyMod.id("wyrmkin/elder"), BodyParts.use(TAILS, 9));         // one lineage, nine tails
        BodyParts.add(MyMod.id("wyrmkin"), BodyParts.use(TAILS, 3), BodyParts.use(WINGS));

        // A group with no parts uses its parent's. Here the parent is the id with its last "/part" cut off, so
        // mymod:wyrmkin/young falls back to mymod:wyrmkin. Returning null means there is no parent.
        BodyParts.setParent(id -> {
            String path = id.getPath();
            return path.contains("/") ? id.withPath(path.substring(0, path.lastIndexOf('/'))) : null;
        });

        // What each player wears right now. This runs on the client for every player it draws. Here a
        // player's group is their scoreboard team's name. A real mod reads its own synced data instead.
        BodyParts.setSource(player -> {
            if (player.getTeam() == null) return List.of();
            ResourceLocation group = MyMod.id(player.getTeam().getName());
            // the look: colour, whether the colour is rare, markings, whether those are rare, shape, scale
            BodyParts.Look look = new BodyParts.Look("crimson", false, "none", false, "ram", 1);
            return BodyParts.wear(BodyParts.of(group), look);
        });
    }

    private ExampleParts() {
    }
}
```
| `BodyPart.Builder` | Default | Does |
|---|---|---|
| `on(HEAD or BODY)` | `HEAD` | where it hangs |
| `loop(name)` | `idle` | the animation it loops |
| `loop(resting, player -> name)` | | picks the loop every frame from the player. While it picks anything other than `resting`, the part doesn't fidget. `BodyPart.flight(idle, glide, flap)` picks by gliding or climbing |
| `anchor(y, z)` | 0, 0 | the point (in Blockbench pixels) a look's scale works around |
| `fidgets(names...)` | none | one plays every 8 to 20 seconds while resting |
| `hit(name)` | `hit` | plays once when the wearer is hurt. Leave it out of the file for none |
| `shapeAnimations(shape, fidgets...)` | | a shape with its own animations. While it is worn, every `<name>` plays `<name>_<shape>` instead, with these fidgets |
| `bones((bone, count, player) -> ...)` | | poses bones by hand every frame after the animations. `count` is the `Use`'s count. `BodyPart.fan(prefix, max, spread, lift)` shows the first `count` of bones `<prefix>1`... fanned out |

Each player's copy of a part runs three controllers: the loop, an occasional fidget (not while flinching or while the loop isn't resting), and `hit` when the player is hurt, which overrides the other two.

## Groups

`BodyParts.add(group, BodyParts.use(part), BodyParts.use(part, count)...)` gives a group its parts, replacing any it had, and `BodyParts.remove(group)` takes them away. `BodyParts.of(group)` reads them back: the group's own, else its parent's, and so on up. `BodyParts.setParent(id -> parent)` says what a group's parent is, returning null at the top. If it is never set, no group has a parent.

## What each player wears

`BodyParts.setSource(player -> List<Worn>)` is asked every frame for every drawn player, so it should only read data the client already has, such as synced attachments. Each `Worn` is a part's `Use` and a `BodyParts.Look`, and `BodyParts.wear(uses, look)` dresses a whole list in one look. Without a source, nobody wears anything.

| `BodyParts.Look` | Picks |
|---|---|
| `color`, `rareColor` | `textures/bodypart/<part>_<color>.png`. A rare colour also draws `<part>_<color>_glow.png` at full bright |
| `markings`, `rareMarkings` | `textures/bodypart/<part>_mark_<markings>.png` over the colour (`none` draws nothing). Rare markings draw at full bright |
| `shape` | the bones named `s_<shape>` or `s_<shape>_...`. Every other `s_` bone is hidden, so a part with shape bones needs a shape |
| `scale` | the size around the part's anchor (1 is as modelled) |

`BodyParts.Look.PLAIN` is the `default` colour, no markings, no shape and scale 1, for parts with one texture and no shapes.

---

← Previous: [[Effects and Animations]] · Next: [[Dialogue]] →
