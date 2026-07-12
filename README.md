![DyneTech Banner](https://cdn.modrinth.com/data/cached_images/d52b6cecd98c6bed5a0f8f5ab0b7e594c494b063.png)

🐜 **DyneTech:** This mod recreates the concept of Pym Particles to shrink and grow you, your pets, and even your house! This mod is a 1.21.1 NeoForge port of the popular 1.12 [PymTech](https://www.curseforge.com/minecraft/mc-mods/pymtech) mod by Lucraft, which was originally the Ant-Man mod.

![Divider](https://cdn.modrinth.com/data/cached_images/042a97c7a3ae381f30dcb520ffc9cf8fe2746347.png)

❓ **F.A.Q.:**
* **Will other features be ported?** Maybe. If there's demand and if we have the time to develop this mod further, anything is possible.
* **Is this a watered down derivative? What about the original?** I mean, kind of? This is a 1.21.1 port because that's the version I play most, and the version Landfall runs. (That said, if there *was* an official port of PymTech in progress, the structure shrinker implementation would be written by yours truly! 😉)
* **What is the Tissue Compression Eliminator?** [A cursed crossover because I felt like it.](https://tardis.fandom.com/wiki/Tissue_Compression_Eliminator)

![Divider](https://cdn.modrinth.com/data/cached_images/042a97c7a3ae381f30dcb520ffc9cf8fe2746347.png)

❗ **Details:**

Requirements:
- Minecraft 1.21.1
- NeoForge 21.1+
- [Pehkui](https://github.com/Virtuoel/Pehkui) 3.8+ (handles the scale changes)

Building:
```
./gradlew build
```

Running in dev:
```
./gradlew runClient   # or runServer
```

`run-dev.sh` is a convenience wrapper. On NixOS, `shell.nix` gives you a JDK 21 shell with the GL drivers wired up.
