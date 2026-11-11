# Holograms

A hologram is a panel floating in the world that only chosen players see. Two audiences decide who: viewers see it, and interactors may press its buttons. Everyone viewing sees the same content, and a change reaches all of them together. A player presses a button by aiming at it and right-clicking. A mob panel is the HUD equivalent. It is a small panel tied to a mob, shown either beside the mob in brackets or pinned to a corner of the screen with a line leading to the mob. Both are in `hologram` and run on the server, and the client only draws what the server sends.

## A hologram

`Holograms.at(level, pos)` starts one at a point (the middle of the panel), and `spawn()` shows it. `content(p -> ...)` lists the parts from top to bottom.

```java
public final class PartyBoard {
    // A board on a wall. Players tagged "party" see it and can press its buttons. Operators see it too but can't
    // press anything. Anyone in the party can leave, and only a player flagged as leader can open the way down.
    public static Hologram open(ServerLevel level, Vec3 wall) {
        // arguments for two built-in UiActions, written the way a JSON file would give them
        JsonObject leave = new JsonObject();
        leave.addProperty("command", "tag @s remove party");          // dicelib:command runs this as the presser
        JsonObject leader = new JsonObject();
        leader.addProperty("flag", "party_leader");                   // dicelib:flag passes for players with the flag
        return Holograms.at(level, wall)
                .facing(Direction.NORTH)                  // fixed, like a sign
                .width(150)                               // GUI pixels across
                .range(16)                                // seen from up to 16 blocks away
                .viewers(Audience.anyOf(Audience.tagged("party"), Audience.permission(2)))   // the party, and operators
                .interactors(Audience.tagged("party"))                                       // only the party presses
                .content(p -> p.title(Component.literal("Drowned Halls"))
                        .text("party", Component.literal("Party: 1/4"))    // "party" is the key joined() changes
                        .divider()
                        // greyed out for a viewer unless every condition in the list passes for them
                        .button("descend", Component.literal("Descend"), PartyBoard::descend, List.of(UiActions.ref("dicelib:flag", leader)))
                        // this button runs a UiActions call where the others run Java code
                        .button("leave", Component.literal("Leave the party"), UiActions.ref("dicelib:command", leave)))
                .spawn();
    }

    // Changes one line by its key. Everyone looking at the board sees the new text.
    public static void joined(Hologram board, int members) {
        board.text("party", Component.literal("Party: " + members + "/4"));
    }

    // update replaces the whole panel, so the buttons are gone afterwards
    private static void descend(ServerPlayer leader, Hologram board) {
        board.update(p -> p.title(Component.literal("Drowned Halls")).text(Component.literal("The way down is open.")));
    }
}
```

| Builder call | Does |
|---|---|
| `billboard()` | turns to face each viewer, staying upright (the default) ([[#Facing]]) |
| `facing(yaw)`, `facing(direction)`, `facingToward(point)` | stays put, its front facing that way, or turned toward a point once ([[#Facing]]) |
| `follow(entity, offset)` | rides along with an entity, offset from its feet, and is removed when the entity dies or leaves |
| `width(pixels)` | GUI pixels across (140). Its height follows from its parts |
| `scale(scale)` | 1 is 64 pixels to a block |
| `range(blocks)` | how close a viewer must be to see it (24) |
| `viewers(audience)`, `interactors(audience)` | who sees it, and who may press its buttons ([[#Audiences]]) |
| `style(id)` | a look, or a custom UI that draws the hologram its own way ([[Looks and Custom UIs]]) |
| `data(tag)` | a `CompoundTag` sent to clients with the panel, for a custom UI to read |
| `lifetime(ticks)`, `idleTimeout(ticks)` | goes on its own after that long, or after that long without a button press ([[#Going away on its own]]) |
| `tiedTo(player)`, `tiedTo(player, condition)` | belongs to a player, or to a player while a condition holds for them ([[#Going away on its own]]) |
| `onRemove((hologram, reason) -> ...)` | runs once when it goes, with the reason |
| `onMessage((player, hologram, action, data) -> ...)` | receives what a custom screen sends from a client ([[Looks and Custom UIs#Hologram screens]]) |
| `saved()` | kept with the level through restarts ([[#Saved holograms and templates]]) |

A spawned `Hologram` changes in place. `update(p -> ...)` swaps all its content, `set(key, part)` swaps one part, `text(key, text)` and `bar(key, value)` change a keyed text or bar, and `move(pos)`, `viewers(audience)` and `interactors(audience)` do what they say. `data(tag)` replaces the data tag for every viewer, and `data(player, tag)` adds data that only one viewer receives. `remove()` takes it away. `viewers()` gives the players seeing it right now. `Holograms.get(level, id)`, `all(level)` and `nearest(level, pos, within)` find holograms again.

Viewers are worked out again every 10 ticks, so a player who walks into range or gains a tag starts seeing it then. Changing the audience takes effect immediately. Past 48 blocks a client stops drawing a hologram and frees its texture.

## Facing

A hologram either turns or stays put.

| Mode | Calls | What viewers see |
|---|---|---|
| Turning (the default) | `billboard()` | each viewer sees the panel turned toward them, upright, so two players on opposite sides both read its front. Suits floating menus and labels over mobs |
| Fixed | `facing(yaw)`, `facing(direction)`, `facingToward(point)` | the panel keeps one facing, like a sign on a wall. From behind, it shows its back, mirrored, and its buttons still press |

`facingToward(point)` works out the facing that turns the front toward a point once, then keeps it, which suits a menu that should face the player who opened it. A spawned hologram switches either way with the same calls, and every viewer gets the change immediately. Templates choose with `"billboard"` and `"yaw"`.

## Going away on its own

A hologram made in code lasts until `remove()` or until the server stops, and a saved one lasts until `remove()`. Four builder options remove a hologram automatically. You can combine them, and whichever triggers first removes it.

| Builder call | Takes it away when |
|---|---|
| `lifetime(ticks)` | that many ticks have passed since it spawned |
| `idleTimeout(ticks)` | that many ticks pass without anyone pressing a button. Every press starts the count again |
| `tiedTo(player)` | the player logs out. A saved hologram waits for them to come back instead |
| `tiedTo(player, condition)` | the same, and also as soon as the condition fails for the player. The condition is a `UiActions` call ([[Dialogue#Actions and conditions]]), so a saved hologram keeps it |

The server checks timers and ties every 10 ticks. On a spawned hologram, `lifetime(ticks)` and `idleTimeout(ticks)` set a timer again, counted from now (0 turns it off). `keepAlive()` starts the idle count again, for interaction the hologram can't see, such as a player answering in chat. `expiresIn()` gives the ticks until a timer takes it away (-1 for never), for a countdown on the panel. `tie()` gives the player and condition it belongs to. Saved holograms keep their timers and ties through restarts, and their clocks stand still while the server is down.

Whatever takes a hologram away, its `onRemove` listener runs once, after its viewers lost it, with a `Hologram.RemoveReason`:

| Reason | Means |
|---|---|
| `REMOVED` | code or a command called `remove()` |
| `EXPIRED` | its lifetime ran out |
| `IDLE` | nobody pressed a button within its idle timeout |
| `ENTITY_GONE` | the entity it followed died or left |
| `OWNER_GONE` | the player it is tied to logged out |
| `CONDITION_FAILED` | the condition it is tied to failed for its player |

`Holograms.HologramRemovedEvent` (`getHologram()`, `getReason()`) fires right after, for saved holograms too, which can't have a listener. Neither fires when the server stops or a level unloads.

```java
public final class PathChoice {
    // A menu in front of one player that only they see. It belongs to a quest step: it closes when the player's
    // "on_crossroads_quest" flag is cleared, and by itself after 30 seconds untouched.
    public static Hologram open(ServerPlayer player) {
        Vec3 eyes = player.getEyePosition();
        // two blocks ahead at eye height: the look direction flattened, made one block long, then doubled
        Vec3 spot = eyes.add(player.getLookAngle().multiply(1, 0, 1).normalize().scale(2));
        // the arguments of the built-in dicelib:flag condition, written the way a JSON file would give them
        JsonObject quest = new JsonObject();
        quest.addProperty("flag", "on_crossroads_quest");
        return Holograms.at(player.serverLevel(), spot)
                .facingToward(eyes)                       // turned to the player once. It doesn't follow them
                .viewers(Audience.players(player))        // nobody else sees it
                .idleTimeout(600)                         // removed after 30 seconds with no button pressed
                // removed as soon as the condition fails for the player, which is when the flag is cleared
                .tiedTo(player, UiActions.ref("dicelib:flag", quest))
                // a button has an id, a label, and the code a press runs on the server
                .content(p -> p.title(Component.literal("Choose a path"))
                        .button("left", Component.literal("Left"), (chooser, menu) -> choose(chooser, menu, "left"))
                        .button("right", Component.literal("Right"), (chooser, menu) -> choose(chooser, menu, "right")))
                .onRemove((menu, reason) -> {
                    // only the timeout gets a message. Picking a path closes the menu without one
                    if (reason == Hologram.RemoveReason.IDLE) player.sendSystemMessage(Component.literal("The choice fades."));
                })
                .spawn();
    }

    private static void choose(ServerPlayer player, Hologram menu, String path) {
        player.sendSystemMessage(Component.literal("You take the " + path + " path."));
        UiActions.setFlag(player, "on_crossroads_quest", false);   // the quest step is over
        menu.remove();
    }

    private PathChoice() {
    }
}
```

## Parts

| Builder | JSON | Shows |
|---|---|---|
| `title(text)` | `{"type": "title", "text": ...}` | a centred heading |
| `text(text)`, `text(key, text)` | `{"type": "text", "key": ..., "text": ...}` | wrapped text |
| `button(id, text, handler)`, `button(id, text, action)` | `{"type": "button", "id": ..., "text": ..., "action": ...}` | a button. Both forms take a list of conditions last (`"if"` in JSON) |
| `item(item, text)` | `{"type": "item", "item": {"id": ...}, "text": ...}` | an item with text beside it |
| `bar(key, text, value, max, color)` | `{"type": "bar", "key": ..., "value": 3, "max": 10, "color": "#3ca0ff"}` | a filled bar with its label above. A colour without alpha (`0x3ca0ff`) is opaque |
| `health()` | `{"type": "health"}` | the followed mob's health, as numbers over a bar |
| `divider()`, `spacer(height)` | `{"type": "divider"}`, `{"type": "spacer", "height": 4}` | a thin line, or a gap |
| `row(r -> ...)` | `{"type": "row", "parts": [...]}` | parts side by side |

A button runs a Java handler, `(player, hologram) -> ...`, or a `UiActions` call ([[Dialogue#Actions and conditions]]). Its conditions are checked for each player: anyone they fail, or anyone who isn't an interactor, sees the button greyed out.

## Audiences

An `Audience` picks players. Holograms use two, and mob panels and spoken lines use one. An audience never includes distance. Holograms, mob panels and spoken lines each add their own range.

| Java | JSON | Lets through |
|---|---|---|
| `Audience.everyone()` | `{"type": "everyone"}` | every player |
| `Audience.players(...)` | `{"type": "players", "players": ["<uuid>"]}` | the players named |
| `Audience.team(name)` | `{"type": "team", "team": "red"}` | members of a scoreboard team |
| `Audience.tagged(tag)` | `{"type": "tagged", "tag": "party"}` | players with that `/tag` |
| `Audience.permission(level)` | `{"type": "permission", "level": 2}` | players with that permission level |
| `Audience.condition(id, args)` | `{"type": "condition", "condition": {"id": ...}}` | players a registered condition passes |
| `Audience.allOf(...)`, `anyOf(...)`, `not(...)` | `{"type": "all_of", "of": [...]}`, `any_of`, `not` | combinations |
| `Audience.of(predicate)` | none | anything else, in memory only |

## Clicks

A right-click counts only when every check passes on the server: the hologram is still there and unchanged since the player saw it, the player is viewing it, may press it and stands close enough (aiming reaches 8 blocks), and the button exists with its conditions passing. Then `Holograms.HologramEvent.Click` fires (cancellable, `getButton()`, `getHologram()`), and the button runs. A right-click anywhere on a panel never reaches the block behind it. A custom UI can also take left clicks and the scroll wheel ([[Looks and Custom UIs#Hologram screens]]).

## Saved holograms and templates

`saved()` keeps a hologram with its level. A saved hologram's buttons must use `UiActions` calls, its audiences can't come from a predicate, and it can't follow an entity or have an `onRemove` or `onMessage` listener. `spawn()` throws otherwise.

A template is a hologram in a file, at `data/<namespace>/dicelib/holograms/<path>.json`. `Holograms.fromTemplate(level, pos, id)` gives a builder filled in from it, to change further or spawn as it is.

```json
{
  "width": 140,
  "range": 24,
  "billboard": true,
  "style": "dicelib:default",
  "viewers": {"type": "everyone"},
  "interactors": {"type": "permission", "level": 2},
  "parts": [
    {"type": "title", "text": "Arena"},
    {"type": "text", "key": "round", "text": "Round 1"},
    {"type": "button", "id": "next", "text": "Next round", "action": {"id": "dicelib:command", "command": "function example:next_round"}}
  ]
}
```

Every key except `parts` is optional and defaults to the builder's default. `yaw` gives the facing of a template with `"billboard": false`. `"lifetime"` and `"idle_timeout"` set its timers in ticks (0, the default, for none).

## Mob panels

`MobPanels.on(mob)` builds a panel for a mob and `show()` shows it, replacing any panel the mob had. [[Dialogue#Spoken lines]] has a whole boss fight with one.

| Builder call | Does |
|---|---|
| `beside()` | next to the mob on screen, its outline in corner brackets (the default). It flips sides near the screen's edge |
| `pinned(corner)` | stacked in `TOP_LEFT`, `TOP_RIGHT`, `MIDDLE_LEFT` or `MIDDLE_RIGHT`, a line from the panel to the mob. A mob off screen gets an arrow at the screen's edge |
| `to(audience)`, `range(blocks)` | who sees it, and within how far (32) |
| `title(text)`, `icon(item)`, `mobIcon()` | the heading (the mob's name by default), and an item or the mob's head beside it |
| `health()` | the mob's health as numbers over a bar. Each client reads it, so it costs no packets |
| `line(key, text)`, `bar(key, text, value, max, color)`, `parts(p -> ...)` | lines, bars and any other [[#Parts]] under the heading |
| `throughWalls(false)` | hidden while the mob is out of the viewer's sight |
| `color(argb)`, `width(pixels)`, `style(id)` | the brackets and the line, the width (110), the look or custom UI |

The `MobPanel` that `show()` returns takes `set(key, text)`, `bar(key, value)`, `part(key, part)`, `title`, `icon`, `pinned`, `beside`, `to` and `hide()`. Changes made in one tick go out together at its end. The panel is removed when the mob dies or leaves. `MobPanels.get(mob)` finds it again.

## Looks and custom UIs

Holograms and mob panels are drawn by DiceLib's own UI in a look, or by your mod's own custom UI. A custom hologram can be a full screen with widgets, textures and its own messages to the server. `style(id)` names either one, and a hologram's `data(tag)` carries whatever its custom UI needs ([[Looks and Custom UIs]]).

## Commands

All of these need permission level 2. Their permission nodes are `dicelib.command.hologram.<action>`.

| Command | Does |
|---|---|
| `/dicelib hologram place <template> [saved]` | places a template two and a half blocks in front of whoever runs it. A fixed template faces them |
| `/dicelib hologram place <template> billboard\|fixed [saved]` | the same, whatever the template says: `billboard` turns it to each viewer, `fixed` faces it toward whoever runs the command |
| `/dicelib hologram face <id>\|nearest billboard\|fixed` | switches one, or the closest within 16 blocks, to turning or to fixed facing whoever runs it |
| `/dicelib hologram list` | the holograms in this dimension, with the seconds left on any timer. Click one to fill in the command that removes it |
| `/dicelib hologram templates` | every template |
| `/dicelib hologram remove <id>`, `remove nearest` | removes one by id, or the closest within 16 blocks |

---

← Previous: [[Dialogue]] · Next: [[Looks and Custom UIs]] →
