# Dialogue

DiceLib has two ways to make characters talk. `Speech` shows a spoken line to the players near a speaker, as a subtitle above the hotbar and optionally as a bubble over the speaker's head. `Dialogues` runs conversation trees from datapack files. One player picks the choices in a box along the bottom of the screen, the server runs the actions those choices call, and nearby players can overhear the lines. Both are in `dialogue` and run on the server. How they look comes from a look or a custom UI ([[Looks and Custom UIs]]).

## Spoken lines

`Speech.of(entity, text)` makes a line said by an entity. The entity's name shows above the text, range is measured from it, and `bubble()` puts a bubble over its head. `Speech.of(name, text)` makes a line with no body, heard around a spot (`at(level, pos)`) or by the players named (`to(players)`). `send()` sends it and returns who got it.

```java
public final class BossTaunts {
    // A panel for the fight, shown to everyone within 48 blocks. Keep what show() returns to change it later.
    public static MobPanel showPanel(LivingEntity boss) {
        return MobPanels.on(boss)
                .range(48)
                .pinned(MobPanels.Corner.TOP_LEFT)        // fixed in a screen corner, with a line down to the boss
                .title(Component.literal("The Hollow King"))
                .icon(new ItemStack(Items.WITHER_SKELETON_SKULL))
                .health()                                 // a health bar that each client fills in from the boss itself
                .line("phase", Component.literal("Phase 1"))                  // "phase" is the key used to change it
                .bar("rage", Component.literal("Rage"), 0, 100, 0xFFC03030)   // starts at 0 of 100, in red
                .show();
    }

    // The fight opens with a line in the subtitle box and a bubble over the boss, heard within 32 blocks.
    public static void opening(LivingEntity boss) {
        Speech.of(boss, Component.literal("Another crown-seeker. Kneel, or be knelt."))
                .bubble()
                .voice(SoundEvents.WITHER_AMBIENT, 1.4f)  // a soft sound as the text types, at pitch 1.4
                .send();
    }

    // At half health. interrupt() cuts off a line that is still typing, where a normal line would wait its turn.
    public static void phaseTwo(LivingEntity boss, MobPanel panel) {
        Speech.of(boss, Component.literal("Enough. No more holding back."))
                .range(48).bubble().interrupt().voice(SoundEvents.WITHER_AMBIENT, 1.1f).send();
        // the same keys as above. Both changes go out to viewers together at the end of the tick
        panel.set("phase", Component.literal("Phase 2")).bar("rage", 100);
    }
}
```

| Call | Does |
|---|---|
| `range(blocks)` | how far the line reaches, from the speaker or the spot (32 by default) |
| `audience(audience)` | only the players in range this [[Holograms#Audiences]] lets through |
| `to(players)` | exactly these players. Range and audience don't apply |
| `at(level, pos)` | where a line with no speaker is said |
| `name(text)` | the name shown, instead of the speaker's own |
| `bubble()` | a bubble over the speaker's head as well |
| `portrait(item)`, `speakerPortrait()` | an item, or the speaker's own head, beside the name |
| `voice(sound, pitch)` | a soft blip every few characters while the line types |
| `ticks(ticks)` | how long the line stays once typed out (40 plus one a character by default) |
| `interrupt()` | replaces the line showing and empties the queue. Without it, a line waits its turn |
| `style(id)` | a look or custom UI for this line ([[Looks and Custom UIs]]) |
| `recipients()` | who `send()` would reach, without sending |

Each client keeps up to eight lines waiting. The bubble shrinks with distance, hides when the speaker is out of sight or off screen, and goes with the line. Each player has two options in `config/dicelib-client.toml`. `speech.bubbles` turns bubbles off, and `speech.typeSpeed` sets how many characters type out per second (45 by default, and 0 shows each line whole). Dialogue lines type at the same speed.

## Dialogue trees

A tree is a JSON file at `data/<namespace>/dicelib/dialogues/<path>.json`, and its id is `<namespace>:<path>`. A tree is made of nodes. A node shows its lines one after another, runs its actions as it opens, then offers its choices. A node without choices moves on to `next`, or ends the conversation.

```json
{
  "speaker": "The Ferryman",
  "portrait": "speaker",
  "voice": "minecraft:entity.villager.ambient",
  "range": 8,
  "overhear": 12,
  "start": "greet",
  "nodes": {
    "greet": {
      "lines": ["Evening, {player}.", "Crossing costs one emerald."],
      "choices": [
        {"text": "Here, one emerald", "next": "paid",
         "if": [{"id": "dicelib:has_item", "item": "minecraft:emerald"}], "locked": true},
        {"text": "Is it safe at night?", "next": "night", "if": [{"id": "mymod:example_is_night"}]},
        {"text": {"translate": "dialogue.example.bye"}, "end": true}
      ]
    },
    "paid": {
      "lines": ["Hold on to that ticket."],
      "actions": [{"id": "dicelib:take", "item": "minecraft:emerald"}, {"id": "mymod:example_ferry_ticket"}],
      "end": true
    },
    "night": {
      "lines": [{"speaker": "A voice from the reeds", "line": "No."}],
      "close": false,
      "next": "greet"
    }
  }
}
```

| Field | Means |
|---|---|
| `speaker` | the name shown. Left out, the speaker entity's own name |
| `portrait` | `"speaker"` for the speaker's head, an item id, or left out for none |
| `voice`, `pitch` | a sound played softly as the text types, and its pitch (1) |
| `style` | a look or custom UI id (`dicelib:default` by default) |
| `range` | blocks the player may wander from the speaker before it ends (8) |
| `overhear` | blocks around the speaker in which other players get the lines as subtitles (0 for nobody) |
| `start` | the first node (`"start"`) |
| `nodes` | node name to node |

| Node field | Means |
|---|---|
| `lines` | text shown one at a time. `{"speaker": ..., "line": ...}` gives one line another speaker |
| `actions` | calls run when the node opens |
| `choices` | what the player can pick once the lines finish |
| `next` | the node after this one, when it has no choices |
| `end` | `true` ends the conversation after this node |
| `close` | `false` stops Escape from closing it here |

| Choice field | Means |
|---|---|
| `text` | what the button says |
| `next`, `end` | where it leads. With neither, the node shows its choices again (for a choice that only runs actions) |
| `if` | conditions that must all pass, or the choice stays hidden |
| `locked` | `true` shows a choice whose conditions fail greyed out, instead of hiding it |
| `actions` | calls run when the player picks it, before moving on |

Text is a vanilla text component: a plain string, or an object such as `{"translate": "..."}`. The client only ever receives the node it is on, with the choices it may see, so hidden choices and their conditions stay on the server. `/dicelib dialogue list` shows every tree with the problems found in its file: a node that isn't there, or an action or condition nobody registered.

### Placeholders

Words in braces are filled in on the server as each line goes out. `{player}` and `{speaker}` become the names. They work in plain strings, in `"text"` objects and in a translation's `"with"` arguments. A translated line keeps `%s` in its lang file and puts the placeholder in `"with"`:

```json
{"translate": "dialogue.mymod.toll", "with": ["{player}", "{mymod:coins}"]}
```
`Dialogues.placeholder(name, (player, speaker) -> ...)` adds your own. Call it once at startup. The function returns the text for the player the line goes to, or null to leave the placeholder as written. Names are lower case, and yours should start with your mod id. A name nobody registered stays in the line, braces and all.

```java
Dialogues.placeholder("mymod:coins", (player, speaker) -> Component.literal(String.valueOf(Coins.of(player))));
```
### Starting and ending

| Call | Does |
|---|---|
| `Dialogues.start(player, id)` | starts a tree with no speaker. No range check then |
| `Dialogues.start(player, id, speaker)` | with an entity speaking. It ends when the speaker dies or the player walks out of `range` |
| `Dialogues.start(player, id, speaker, node)` | at another node than `start` |
| `Dialogues.bind(entity, id)`, `unbind(entity)`, `bound(entity)` | a right-click on the entity starts the tree. Saved with the entity |
| `Dialogues.end(player)` | ends it |
| `Dialogues.active(player)` | the running session: `tree()`, `node()`, `speaker()`, `step()`, or null |
| `Dialogues.register(tree)` | a tree built in Java, kept over a datapack one with the same id |

A right-click on a bound entity with the main hand starts its tree and replaces the vanilla interaction, so a bound villager never opens its trades. Sneaking skips the tree. Starting a dialogue for a player already in one ends the old one first. A conversation also ends when the player dies, logs out or changes dimension.

### The screen

The box sits along the bottom of the screen and the game keeps running behind it. Each line types out. A click, Space or Enter finishes the line, then shows the next. After the last line the choices appear: click one, or press 1 to 9. Escape closes the box when the node allows it. A tree whose style names a custom dialogue UI opens that screen instead ([[Looks and Custom UIs#Dialogue]]). Every answer carries the number of the step it was given for, and the server ignores an answer for any other step, so a double click or a late click does nothing.

### Actions and conditions

Choices, nodes and hologram buttons call actions, and choices, buttons and audiences check conditions. Both live in `UiActions` under an id. JSON names one with `"id"` and passes every other key as its arguments.

| Built-in action | Arguments | Does |
|---|---|---|
| `dicelib:command` | `command`, `permission` (2) | runs a command as the player, so `@s` is them, at that permission level. Anyone who can pick the choice or press the button runs it at that level, so the command stays fixed and gets no more rights than it needs |
| `dicelib:give`, `dicelib:take` | `item`, `count` (1) | gives or takes items |
| `dicelib:flag`, `dicelib:unflag` | `flag` | marks the player with a name, or clears it. Flags last through restarts and death |
| `dicelib:sound` | `sound`, `volume`, `pitch` | plays a sound to the player |
| `dicelib:dialogue` | `dialogue`, `node` | starts another tree with the same speaker |
| `dicelib:close` | | ends the player's dialogue |

| Built-in condition | Arguments | Passes when |
|---|---|---|
| `dicelib:has_item` | `item`, `count` (1) | the player carries that many |
| `dicelib:flag` | `flag` | the player has the flag |
| `dicelib:not` | `condition` | the condition inside fails |
| `dicelib:permission` | `level` (2) | the player has that permission level |

Register your own once, from your mod's constructor. `UiActions.flagged(player, flag)` and `setFlag(player, flag, on)` read and write flags from code.

```java
@EventBusSubscriber(modid = MyMod.MODID)
public final class FerrymanDialogue {
    // the tree in data/mymod/dicelib/dialogues/example/ferryman.json
    public static final ResourceLocation TREE = MyMod.id("example/ferryman");

    // Call once from the mod constructor. It registers an action and a condition that the tree's JSON names by id.
    public static void init() {
        // An action runs on the server when a node is entered or a choice is picked.
        // In the JSON: {"id": "mymod:example_ferry_ticket"}
        UiActions.action(MyMod.id("example_ferry_ticket"), (player, context, args) -> {
            ItemStack ticket = new ItemStack(Items.PAPER);
            ticket.set(DataComponents.CUSTOM_NAME, Component.literal("Ferry Ticket"));
            player.getInventory().add(ticket);
        });
        // A condition decides whether a choice is offered. In the JSON: {"id": "mymod:example_is_night"}
        UiActions.condition(MyMod.id("example_is_night"), (player, context, args) -> player.level().isNight());
    }

    // From now on a right-click on this entity starts the tree. The binding is saved with the entity.
    public static void hire(Entity ferryman) {
        Dialogues.bind(ferryman, TREE);
    }

    // DialogueEvent.End fires however a conversation ends. This one only answers a player who walked out of range.
    @SubscribeEvent
    static void onEnd(DialogueEvent.End event) {
        if (event.getSession().tree().id().equals(TREE) && event.getReason() == EndReason.TOO_FAR) {
            event.getServerPlayer().sendSystemMessage(Component.literal("The ferryman shrugs and goes back to his oars."));
        }
    }
}
```

An action's `context` says where the call came from: `level()`, `speaker()` (the speaker, or the entity a hologram follows), `dialogue()` and `node()` for a tree, `hologram()` for a hologram. An unknown id does nothing, and the log names it once.

### Events

All of these fire on the server.

| Event | Cancellable | Fires |
|---|---|---|
| `Dialogues.DialogueEvent.Start` | yes | before a tree starts: `getTree()`, `getSpeaker()`, `getNode()` |
| `Dialogues.DialogueEvent.Node` | no | as a node opens, before its actions run |
| `Dialogues.DialogueEvent.Choice` | yes | when the player picks a choice, before its actions run: `getChoice()`, `getIndex()` |
| `Dialogues.DialogueEvent.End` | no | when it is over: `getReason()` is `FINISHED`, `CLOSED`, `ENDED`, `REPLACED`, `SPEAKER_GONE`, `TOO_FAR`, `PLAYER_GONE` or `BROKEN` |

## Commands

All of these need permission level 2. Their permission nodes are `dicelib.command.dialogue.<action>`.

| Command | Does |
|---|---|
| `/dicelib dialogue start <targets> <dialogue>` | starts a tree with no speaker |
| `/dicelib dialogue start <targets> <dialogue> at <node>` | at a node |
| `/dicelib dialogue start <targets> <dialogue> by <speaker>` | with an entity speaking |
| `/dicelib dialogue end <targets>` | ends their dialogues |
| `/dicelib dialogue bind <entity> <dialogue>`, `unbind <entity>` | binds a right-click, or clears it |
| `/dicelib dialogue list` | every tree, its node count and the problems in its file |
| `/dicelib dialogue say <range> <text>` | a spoken line from whoever runs it |

```mcfunction
# talk to the nearest villager as the ferryman
/dicelib dialogue bind @e[type=villager,limit=1,sort=nearest] example/ferryman
# a line from the caller, heard within 16 blocks
/dicelib dialogue say 16 The bridge is out.
```

---

← Previous: [[Body Parts]] · Next: [[Holograms]] →
