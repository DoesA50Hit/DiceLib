# DiceLib

A library mod of building blocks for NeoForge 1.21.1 mods. Mod id `dicelib`, package `org._50dozenhits.dice_library`.

It covers targeting, damage, projectiles, temporary control of mobs, knockback, zones, healing, cooldowns, dashes and teleports, props (floating items, formations, tethers,
animated props, entity copies), timing and per-caster state, effects and player animations, spoken lines and dialogue
trees, holograms and mob panels, a command framework and worn body parts. It adds no content of its own.

## Requirements

- NeoForge 21.1 for Minecraft 1.21.1
- PlayerAnimationLib 1.1.6 or later
- GeckoLib 4.9 or later

It has no UI library and no effect library. Its screens and panels are drawn with plain Minecraft code, and effects play
as vanilla particles unless a project adds an effect player for a library of its choice.

## Using it in a project

1. Build the jars with `./gradlew build`. `dicelib-<version>.jar` and its sources jar land in `build/libs`.
2. Copy both into the project's `libs/` folder. If you work on DiceLib next to your mod, list that folder in
   `dicelib_deploy_to` in `gradle.properties` (several are separated by `;`) and run `./gradlew deployJar`, which
   builds and copies in one step.
3. In the project's `build.gradle`:

   ```groovy
   dependencies {
       implementation fileTree(dir: 'libs', include: 'dicelib-*.jar', exclude: '*-sources.jar')
       implementation "com.zigythebird.playeranim:PlayerAnimationLibNeo:1.1.6+mc.1.21.1"
       implementation "software.bernie.geckolib:geckolib-neoforge-1.21.1:4.9.3"
   }
   ```

4. Name `dicelib` as a required dependency in the project's `neoforge.mods.toml`, ordered `AFTER`.

Attach the sources jar in the IDE to read the Javadoc on hover.

### Or from the local Maven repository

`./gradlew publishToMavenLocal` puts `org._50dozenhits:dicelib:<version>` and its sources in `~/.m2`. A project with
`mavenLocal()` in its repositories then names it, and the IDE finds the sources by itself:

```groovy
dependencies {
    implementation "org._50dozenhits:dicelib:1.0.0"
}
```

A project built on another machine or by CI won't find it there, so use the `libs/` folder for those.

## Building

| Command | Does |
|---|---|
| `./gradlew build` | compiles the library and the guide's examples |
| `./gradlew deployJar` | builds and copies the jar into every folder in `dicelib_deploy_to` |
| `./gradlew publishToMavenLocal` | publishes the jar and sources to `~/.m2` |
| `./gradlew runClient` | a dev client with DiceLib |

## Docs

The guide is on the [wiki](https://github.com/DoesA50Hit/DiceLib/wiki). The same pages are in `docs/DiceLib-Guide`,
with an offline copy in `index.html`. `python docs/build_guide.py` rebuilds the offline copy from the notes and the
example classes in `src/examples`, and with `--wiki <folder>` it also writes the wiki pages.

## Licence

Copyright (C) 2026 UnluckyWarl0ck

DiceLib is free software under the GNU Lesser General Public License, version 3 (`COPYING.LESSER`, which adds to the
GNU General Public License in `COPYING`). A mod may depend on DiceLib and ship it in a modpack or inside its own jar
under any licence. Changes to DiceLib itself have to be published under the same licence.

## Versions

DiceLib follows semantic versioning: a major version for a change that breaks a call, a minor version for something
new, a patch for a fix. `CHANGELOG.md` lists each release. Anything marked `@ApiStatus.Internal` can change in any
release.
