# DiceLib

Library mod for NeoForge 1.21.1. Targeting, damage, projectiles, zones, dashes, cooldowns, props, animations,
dialogue, holograms, commands and more.

## Requirements

- NeoForge 21.1 (Minecraft 1.21.1)
- PlayerAnimationLib 1.1.6+
- GeckoLib 4.9+

## Usage

```groovy
repositories {
    mavenCentral()
    maven { url = 'https://repo.redlance.org/public' }
    maven { url = 'https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/' }
}

dependencies {
    implementation "io.github.doesa50hit:dicelib:1.0.0"
    implementation "com.zigythebird.playeranim:PlayerAnimationLibNeo:1.1.6+mc.1.21.1"
    implementation "software.bernie.geckolib:geckolib-neoforge-1.21.1:4.9.3"
}
```

Add `dicelib` as a required dependency in your `neoforge.mods.toml`.

## Docs

See the [wiki](https://github.com/DoesA50Hit/DiceLib/wiki).

## License

LGPL-3.0. See `COPYING.LESSER` and `COPYING`.
