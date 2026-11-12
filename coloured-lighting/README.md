# old and most of it not true
# Coloured lighting: sun shadows

Blocks cast blocky sun shadows on the ground and on other surfaces. Shadow
length follows the time of day, using a lookup table, as described in the
video notes. Built on the Fabric 1.20.1 template.

## Features

- Shadows come from the real sun path (vanilla sky angle).
- Shadows are blocky and stay block-level, not per-face.
- Shadows are recalculated every 100 ticks (5 seconds), not every frame.
- No shadows at night or in dimensions without a sky.
- Press **O** to toggle (rebindable under Controls > Coloured lighting).

## Sodium

The mod has no mixins into Sodium or the chunk renderer. Shadows are drawn
after the world renders, through Fabric's `WorldRenderEvents`, so Sodium
0.5.13 (Fabric, 1.20.1) runs unchanged alongside it.

## Build

Requires JDK 17 or newer.

    ./gradlew build

The jar appears in `build/libs/`. Put it in `.minecraft/mods` along with
Fabric API (and Sodium, if you want it).

## Tuning

Constants live in `SunShadowMap` (scan radius, caster height, ray step) and
`ColouredLightingClient` (refresh interval, which is 100 ticks). Shadow
darkness is `ALPHA` in `ShadowRenderer`.

## License

This template is available under the CC0 license. Feel free to learn from it
and incorporate it in your own projects.
