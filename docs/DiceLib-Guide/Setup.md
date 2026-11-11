# Setup

DiceLib is a NeoForge 1.21.1 mod (`dicelib`) that other mods build on. It needs PlayerAnimationLib and GeckoLib. It does not need a UI library or an effect library.

## Adding it to a project

A project uses the built jar from its own `libs/` folder.

1. In DiceLib, `gradle.properties` lists the projects to copy into, separated by `;`:

```properties
dicelib_deploy_to=../My Mod/libs;../Other Mod/libs
```
2. `./gradlew deployJar` builds DiceLib and copies `dicelib-<version>.jar` and its sources jar into each `libs/` folder, removing older DiceLib jars there first.

3. The project depends on it and on the two libraries DiceLib needs, because a jar in `libs/` doesn't bring its dependencies with it:

```groovy
dependencies {
    implementation fileTree(dir: 'libs', include: 'dicelib-*.jar', exclude: '*-sources.jar')
    implementation "com.zigythebird.playeranim:PlayerAnimationLibNeo:1.1.6+mc.1.21.1"
    implementation "software.bernie.geckolib:geckolib-neoforge-1.21.1:4.9.3"
}
```
4. Its `neoforge.mods.toml` names DiceLib as a required dependency, ordered `AFTER`, so that DiceLib is set up before the project's constructor runs.

5. A project that writes commands with the [[Command Framework]] compiles with `-parameters`, so arguments can take their names from the Java parameters:

```groovy
tasks.withType(JavaCompile).configureEach { options.compilerArgs << '-parameters' }
```
After a change to DiceLib, run `deployJar` again and rebuild the project. In IntelliJ, attaching the sources jar to the library shows DiceLib's code and comments.

## Building DiceLib

| Command | Does |
|---|---|
| `./gradlew build` | compiles the library and the guide's examples |
| `./gradlew deployJar` | builds and copies the jar into each project in `dicelib_deploy_to` |
| `./gradlew runClient` | a dev client with DiceLib |

| Source set | Holds | In the jar |
|---|---|---|
| `main` | DiceLib | yes |
| `examples` | every full example class in this guide | no |

`examples` is compiled only. `python docs/build_guide.py` copies the example classes into these pages and rebuilds the site. With `--check` it changes nothing and reports the pages whose code no longer matches.

---

← Previous: [[Home]] · Next: [[Targeting]] →
