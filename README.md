# DyneTech

A NeoForge mod for Minecraft 1.21.1. Shrink and grow things with discs, box up a whole structure into a pocket, redeploy it wherever. This mod is a 1.21.1 NeoForge port of the popular 1.12 [PymTech](https://www.curseforge.com/minecraft/mc-mods/pymtech) mod by Lucraft.

## Features

- **Pym Particle Discs**: throw one to shrink or enlarge a mob (or yourself). Comes in two flavors.
- **Structure Shrinker**: place the block, mark a region, get a shrunken copy of it in item form to pick up and regrow elsewhere.
- **Tissue Compression Eliminator**: a sidearm for when a mob is more useful pocket-sized. Optionally lethal.

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1+
- [Pehkui](https://github.com/Virtuoel/Pehkui) 3.8+ (handles the scale changes)

## Building

```
./gradlew build
```

Jar lands in `build/libs/`.

## Running in dev

```
./gradlew runClient   # or runServer
```

`run-dev.sh` is a convenience wrapper. On NixOS, `shell.nix` gives you a JDK 21 shell with the GL drivers wired up.