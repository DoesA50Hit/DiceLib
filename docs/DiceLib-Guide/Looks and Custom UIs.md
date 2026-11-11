# Looks and Custom UIs

DiceLib draws its holograms, mob panels, dialogue box, subtitles and speech bubbles with plain `GuiGraphics` calls, as vanilla screens do, so it needs no UI library. Each of those surfaces names a style, which is an id such as `dicelib:default`, and each client looks that id up in two places:

1. A custom UI registered for that surface under the id (`CustomUis`). If there is one, it draws the surface.
2. Otherwise a look (`Looks`), which sets the colours and backgrounds of DiceLib's own UI.

An id with neither falls back to `dicelib:default`, so a typo shows the plain look. The ids are in `ui.UiLooks` so that server code can name them. Looks and custom UIs are in `client.ui` and are registered on the client at setup (`FMLClientSetupEvent`).

| Surface | Named with |
|---|---|
| a hologram | `Holograms...style(id)`, `"style"` in a template ([[Holograms]]) |
| a mob panel | `MobPanels...style(id)` |
| a spoken line | `Speech...style(id)` ([[Dialogue#Spoken lines]]) |
| a dialogue tree | `"style"` in its file ([[Dialogue#Dialogue trees]]) |

## Looks

A `Look` is a record of how each surface is drawn. DiceLib ships two, `UiLooks.DEFAULT` (grey panels with vanilla's bevel, dark see-through subtitles, vanilla buttons) and `UiLooks.GLASS` (dark translucent glass with cyan edges).

| Part | Draws |
|---|---|
| `hologram`, `mobPanel`, `dialogue`, `subtitle`, `bubble` | the box behind each surface: a `Background` with its `padding` and the `gap` between what it holds |
| `title`, `text`, `dim` | headings and the speaker's name in the dialogue box, text, and quieter text (labels over bars, health numbers, the dialogue box's prompt) |
| `subtitleSpeaker`, `subtitleText`, `bubbleText` | the text of the subtitle box and the bubble |
| `divider` | the colour of a divider |
| `bar`, `barHeight` | the background behind a bar's fill, and its height |
| `button`, `buttonHeight`, `choiceHeight` | hologram buttons and dialogue choices: a background for normal, hovered and disabled, and their text |

Each text is a `Look.Text(color, shadow)`, colours in ARGB, so `0xFF404040` is opaque grey and `0x30000000` a faint black. A `Look.Background` fills a rectangle:

| Background | Is |
|---|---|
| `Background.panel()` | vanilla's window: grey, a white bevel top and left, a dark one bottom and right, a black outline with rounded corners |
| `Background.inset()` | a sunken box, like an inventory slot |
| `Background.rect(color)` | a flat colour |
| `Background.rounded(fill, radius, border, borderColor)` | rounded corners and an edge `border` pixels wide (0 for none) |
| `Background.sprite(id)` | a GUI sprite, `assets/<ns>/textures/gui/sprites/<path>.png`, stretched or nine-sliced as its `.mcmeta` says, so a resource pack can retexture it |
| `Background.NONE` | nothing |

Any lambda `(graphics, x, y, width, height) -> ...` is a background too. A new look starts from one of the two and changes what differs:

```java
// at client setup: a parchment look for quest boards and an innkeeper's dialogue
Looks.register(MyMod.id("parchment"), Looks.DEFAULT.toBuilder()   // start from the default and change what differs
        // a Box is a background, the padding inside it and the gap between parts, both in GUI pixels
        .hologram(new Look.Box(Look.Background.sprite(MyMod.id("parchment")), 8, 4))
        .dialogue(new Look.Box(Look.Background.sprite(MyMod.id("parchment")), 8, 6))
        .title(new Look.Text(0xFF5A3A10, false))      // an ARGB colour, and no shadow
        .text(new Look.Text(0xFF3B2A14, false))
        .divider(0x405A3A10)                          // the same brown at a quarter opacity
        .build());
```
`backgrounds(background)` on the builder swaps the background of every box and keeps each one's padding and gap.

## Custom UIs

`CustomUis` replaces DiceLib's own UI for one surface with yours, registered under an id. Draw it with plain `GuiGraphics`, or with any UI library the mod already uses.

| Register | Gets | Returns |
|---|---|---|
| `CustomUis.hologram(id, hologram -> ...)` | the `HologramPayload`: `width()`, `panel()`, `disabled()` buttons, `data()`, `follow()` | a `PanelUi` |
| `CustomUis.mobPanel(id, panel -> ...)` | the `MobPanelPayload`: `entity()`, `title()`, `body()`, `width()`, `color()` | a `PanelUi` |
| `CustomUis.dialogue(id, node -> ...)` | the first `DialogueNodePayload` | a `Screen` that implements `DialogueScreen.View` |
| `CustomUis.speech(id, (graphics, line, typed, bubble, partialTick) -> ...)` | the line showing, how much has typed out, and where a bubble would go | draws it every frame |

### Holograms and mob panels

A `PanelUi` is a small piece of UI with a size, drawn in GUI pixels from its top left:

| Method | Does |
|---|---|
| `width()`, `height()` | its size. A hologram's quad in the world is this size at its scale |
| `render(graphics, mouseX, mouseY, partialTick)` | draws it. On a hologram the mouse is where the crosshair meets the panel, and far away otherwise |
| `buttonAt(mouseX, mouseY)` | the id of the button under a point, or null |
| `tick()` | once a client tick. True asks a hologram to redraw |
| `mouseClicked(mouseX, mouseY, button)` | a left (0) or right (1) click on a hologram. Return true when the UI handled it |
| `mouseScrolled(mouseX, mouseY, amount)` | the scroll wheel, while the crosshair is on a hologram. Return true to keep the hotbar from moving |
| `update(newer)` | the server sent the hologram again. Return true to keep this UI and its state. By default a new UI is made |
| `animated()` | true redraws a hologram every frame |
| `removed()` | the hologram is gone |

A hologram draws its UI into a texture and redraws it when `tick()` says so or the crosshair moves on it. A mob panel draws its UI on the HUD every frame.

A click on a custom hologram still goes through the server's checks ([[Holograms#Clicks]]): the id `buttonAt` returns must be a button in the hologram's panel. The custom UI only changes how the panel is drawn. The panel on the server still decides which buttons exist, what their conditions and handlers are, and who may press them. A custom UI can read the panel's content, and anything else it needs goes in the hologram's `data` tag.

```java
// server: a ticket with a number and a Claim button
CompoundTag data = new CompoundTag();
data.putInt("number", 42);
Holograms.at(level, pos).style(MyMod.id("ticket")).data(data).width(150)
        .content(p -> p.title(Component.literal("Ferry to the Isle")).button("claim", Component.literal("Claim"), MyFerry::claim))
        .spawn();
```
```java
// client setup: how a ticket looks
CustomUis.hologram(MyMod.id("ticket"), hologram -> new PanelUi() {
    // read once from what the server sent: the data tag and the panel's title
    final int number = hologram.data().getInt("number");
    final Component title = hologram.panel().parts().getFirst() instanceof HologramPanel.Title t ? t.text() : Component.empty();

    // the size in GUI pixels. The width is the one the server set with width(150)
    public int width() { return hologram.width(); }
    public int height() { return 44; }

    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        graphics.fill(0, 0, width(), 44, 0xFFF2E3B6);
        graphics.drawString(font, "#" + number, 4, 18, 0xFF5A3A0A, false);
        graphics.drawString(font, title, 36, 6, 0xFF3A2A0A, false);
        // the button is drawn brighter while the crosshair is on it
        graphics.fill(36, 24, width() - 6, 38, "claim".equals(buttonAt(mouseX, mouseY)) ? 0xFF6A8A2A : 0xFF4A6A1A);
        graphics.drawCenteredString(font, "Claim", 36 + (width() - 42) / 2, 27, 0xFFFFFFFF);
    }

    // Which panel button is under a point. A right-click there presses "claim" on the server, with its usual checks.
    public String buttonAt(int x, int y) {
        return x >= 36 && x < width() - 6 && y >= 24 && y < 38 ? "claim" : null;
    }
});
```
`PanelRenderer` is DiceLib's own `PanelUi`. A custom UI can wrap one, for example to draw a frame around the usual parts.

### Hologram screens

To build a hologram the way you build a vanilla `Screen`, extend `HologramScreen` (in `client.hologram`). Override `init()` and add widgets, draw your own background, and the crosshair works as the mouse. Vanilla widgets such as `Button`, `EditBox` and `Checkbox` work as they are. A left or right click presses the widget under the crosshair, and the scroll wheel reaches the screen while you aim at it.

```java
public final class ShopScreen extends HologramScreen {
    public static final ResourceLocation ID = MyMod.id("shop");
    private static final ResourceLocation BACKGROUND = MyMod.id("textures/gui/shop.png");
    private EditBox amount;

    // Register at client setup: CustomUis.hologram(ShopScreen.ID, ShopScreen::new);
    public ShopScreen(HologramPayload hologram) {
        super(hologram, 176, 96);                         // the screen's size in GUI pixels
    }

    // Runs once before the first draw. Widgets are placed in GUI pixels from the screen's top left.
    @Override
    protected void init() {
        // a text box. A click on it gives it the keyboard until Enter, Escape or another click
        amount = addRenderableWidget(new EditBox(font, 8, 44, 60, 18, Component.literal("Amount")));
        amount.setValue("1");
        addRenderableWidget(Button.builder(Component.literal("Buy"), button -> {
            CompoundTag order = new CompoundTag();
            order.putString("amount", amount.getValue());
            send("buy", order);                           // goes to the hologram's onMessage on the server
        }).bounds(76, 43, 50, 20).build());
        // press("leave") presses the panel button with that id, as a click on DiceLib's own panel would
        addRenderableWidget(Button.builder(Component.literal("Leave"), button -> press("leave")).bounds(8, 70, 50, 20).build());
    }

    // Drawn under the widgets every time the screen redraws.
    @Override
    protected void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.blit(BACKGROUND, 0, 0, 0, 0, width, height, 256, 256);
        graphics.drawString(font, "Ferry tickets", 8, 8, 0xFF3A2A0A, false);
        // data() is the tag the server sent, with this player's own coins in it
        graphics.drawString(font, "Coins: " + data().getInt("coins"), 8, 24, 0xFF5A3A0A, false);
    }
}
```
Register it at client setup with `CustomUis.hologram(ShopScreen.ID, ShopScreen::new)`.

| `HologramScreen` | Does |
|---|---|
| `super(hologram, width, height)` | its size in GUI pixels. The hologram's `width(...)` on the server is ignored |
| `init()` | adds the widgets. Runs before the first draw and on `rebuild()` |
| `addRenderableWidget(widget)`, `addRenderableOnly(...)`, `addWidget(...)`, `clearWidgets()` | as on a vanilla screen |
| `renderBackground(graphics, mouseX, mouseY, partialTick)` | drawn under the widgets. Blit textures and draw text here |
| `press(buttonId)` | presses a button of the hologram's panel, with the server's usual checks. False when it is greyed out |
| `send(action, tag)` | sends your own message to the hologram's `onMessage` listener on the server |
| `data()`, `hologram()` | the data tag and everything else the server sent |
| `onData(tag)` | the server sent the hologram again. The screen is kept, so call `rebuild()` here if the widgets depend on the data |
| `type(editBox)` | gives a text box the keyboard until Enter, Escape or a click. A click on an `EditBox` does this already |
| `redraw()`, `animated(true)` | draws it again next frame, or every frame |
| `onTick()` | once a client tick |

A hologram is drawn into a texture, so it only redraws when the crosshair moves on it, after a click, or when you call `redraw()`. Sliders take their value from where you click them, because there is no dragging. A hologram has no keyboard focus, so a click on a text box opens a see-through typing screen that passes the keys to it.

The server still decides what a screen is allowed to do. `press` goes through the same checks as any hologram button. A message from `send` only arrives when the player is a viewer and an interactor of the hologram and stands within reach. The action and the tag are chosen by the client, so check them like any other input:

```java
public final class ShopCounter {
    private static final String COINS = "mymod_coins";

    // The server side of ShopScreen. The style id picks the screen on each client, the panel holds the one button
    // the screen presses through the normal checks, and onMessage receives what the screen sends.
    public static Hologram open(ServerLevel level, Vec3 at, ServerPlayer customer) {
        Hologram shop = Holograms.at(level, at).style(MyMod.id("shop"))
                .content(p -> p.button("leave", Component.literal("Leave"), (player, hologram) -> hologram.remove()))
                .onMessage(ShopCounter::message)
                .spawn();
        showCoins(shop, customer);
        return shop;
    }

    // Runs when a client's ShopScreen calls send(action, data). The action and the tag are chosen by the client, so
    // every value is checked here before anything is given.
    private static void message(ServerPlayer player, Hologram shop, String action, CompoundTag data) {
        if (!action.equals("buy")) return;
        int amount;
        try {
            amount = Integer.parseInt(data.getString("amount"));     // the text box can hold anything
        } catch (NumberFormatException e) {
            return;
        }
        int coins = player.getPersistentData().getInt(COINS);
        if (amount < 1 || amount > 64 || amount > coins) return;      // no free or negative orders
        player.getPersistentData().putInt(COINS, coins - amount);
        player.getInventory().add(new ItemStack(Items.PAPER, amount));
        showCoins(shop, player);                                      // the screen redraws with the new total
    }

    // data(player, tag) sends a tag to one viewer only, so each player sees their own coins on the same hologram
    private static void showCoins(Hologram shop, ServerPlayer player) {
        CompoundTag own = new CompoundTag();
        own.putInt("coins", player.getPersistentData().getInt(COINS));
        shop.data(player, own);
    }

    private ShopCounter() {
    }
}
```
`hologram.data(tag)` sends new data to every viewer, and `hologram.data(player, tag)` lays data over it for one viewer alone. Saved holograms can't have an `onMessage` listener. Listen for `Holograms.HologramEvent.Message` instead, which fires for every message (`getAction()`, `getData()`) and can be cancelled.

### Dialogue

A custom dialogue screen extends `Screen` and implements `DialogueScreen.View`:

| Method | Does |
|---|---|
| `style()` | the id it was opened for. A node with another style opens a new screen |
| `show(node)` | the next node of the same dialogue arrives |
| `endFromServer()` | the server ended the dialogue: close without answering |

It answers with `DialogueScreen.reply(node, choice)`: a choice's index, `Dialogues.ADVANCE` to go on from a node without choices, or `Dialogues.CLOSE` to leave. Every answer carries the node's step, so a stale answer does nothing. Closing the screen itself should reply `CLOSE` when `node.canClose()`, as DiceLib's box does in `removed()`. The node carries the speaker's `name()` and entity id, its `lines()`, the `choices()` the player may see (with `open()` false for locked ones), `canClose()`, `last()`, `voice()` and `portrait()`.

### Speech

A custom speech UI draws the line showing, every frame on the HUD. It gets the line (`name()`, `text()`, `portrait()`, `speaker()`), `typed` (as much of the text as has typed out so far), and `bubble`, the point above the speaker's head and how small a bubble there should be, or null when there should be none. DiceLib still queues the lines, types them out and plays their voice.

```java
// a caption across the top of the screen instead of a subtitle
CustomUis.speech(MyMod.id("caption"), (graphics, line, typed, bubble, partialTick) -> {
    Font font = Minecraft.getInstance().font;
    int width = font.width(typed) + 12, x = (graphics.guiWidth() - width) / 2;
    graphics.fill(x, 8, x + width, 26, 0xC0201008);
    graphics.drawString(font, typed, x + 6, 13, 0xFFF4E2C0, false);
});
```

---

← Previous: [[Holograms]] · Next: [[Command Framework]] →
