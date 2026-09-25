# Food Log

<img src="src/main/resources/logo.png" alt="Food Log icon" width="128" height="128">

**English** | [中文](README_CN.md)

**A pure client-side Minecraft mod for Forge 1.20.1, built with Java 21.**

Food Log is a food encyclopedia for your playthrough. It scans every food the game knows about,
works out which ones you have actually eaten, and shows you the rest — so a modpack with hundreds
of dishes no longer means permanently wondering what is still untried.

> **This mod is client-side only.** It installs on the client and nowhere else. The server does
> **not** need the mod, and it does **not** need to know about it. Drop the jar into your client's
> `mods` folder and you can use it on any server — vanilla, Forge, or a heavily modded pack — as
> well as in singleplayer. It only reads information the server already sends to every client, and
> it only ever performs ordinary player actions (opening a screen, clicking a slot, using the item
> in your hand).

## Requirements

| | |
|---|---|
| Minecraft | 1.20.1 |
| Mod loader | Forge 47.x (developed against 47.2.0) |
| Environment | **Client only** — do not install on the server |
| Built with | **Java 21** (bytecode targets Java 17, so it also runs on a Java 17 instance) |
| Optional | JEI (for the `%未吃` / `%uneaten` search alias) |

## Features

- **Food encyclopedia screen** — a paged grid of every food item, with a per-dimension tab bar.
  Open it with the `Open Food Log` keybind or with `/foodlog page`.
- **Five classification dimensions** — *Type*, *Raw/Cooked*, *Source*, *Nutrition* and *Condition*,
  each with its own set of filter tabs, so you can jump straight to "raw fish" or "low-nutrition
  dish" instead of scrolling an alphabetical list.
- **Eaten / not eaten at a glance** — foods you have eaten are green, foods you have not are grey.
- **Vanilla statistics import** — already ate a few hundred things before installing this mod? The
  import button reads your vanilla food statistics and back-fills your log from them. Those entries
  are shown in cyan, because a statistic only proves the item was *used*, not that it was eaten.
- **Container highlighting** — `/foodlog lighting true` outlines stacks of uneaten food in any
  container screen with a gold border and dims everything else, so the thing worth grabbing stands
  out in a chest full of junk. Works with vanilla containers, modded ones, and Refined Storage grids.
- **"Take 1 uneaten" button** — a draggable button in the corner of container screens that pulls one
  of every uneaten food in the container straight into your inventory.
- **JEI search alias** — search `%未吃` (or `%uneaten`) in JEI to see only the foods you have not
  tried yet.
- **Auto eat** — optionally, the mod eats your uneaten food for you, lowest nutrition first so the
  most different foods fit into a single hunger bar. It swaps each food into your hand, uses it, and
  swaps it back, so your inventory ends up exactly as it started. Foods that may always be eaten are
  honoured; nothing is ever recorded as eaten unless it was really eaten.

## Commands

All commands are **client-side** — they work on any server, and the server never sees them.

| Command | What it does |
|---|---|
| `/foodlog` or `/foodlog page` | Open the food encyclopedia screen |
| `/foodlog lighting` | Toggle container highlighting |
| `/foodlog lighting <true\|false>` | Set container highlighting explicitly |
| `/foodlog eat` | Toggle automatic eating |
| `/foodlog eat <true\|false>` | Set automatic eating explicitly |
| `/foodlog eat now` | Eat every uneaten food you are carrying, once, right now |

Auto eat only runs on its own when no screen is open, so opening a chest never starts a bite behind
your back. `/foodlog eat now` deliberately works with a container open, so it can eat from the
container too.

## Installation

1. Install **Minecraft Forge 47.x for 1.20.1** on the client.
2. Put `foodlog-<version>.jar` into your client's `mods` folder.
3. That is all. Do not put it on the server.

## How your data is stored

Everything is client-side. Each server you play on (and each singleplayer world) gets its own file
under `config/foodlog/`, keyed by the server address or the save name, so the same food is "eaten" on
one world and "not eaten" on another. UI preferences live in `config/foodlog/settings.json`.

Removing the mod leaves those files behind, and deleting them only costs you your log.

## Building from source

You need **JDK 21** installed. Gradle 8.1.1 itself must run on a JDK 17 (it cannot read Java 21
class files) — `gradle.properties` points `org.gradle.java.home` at one; adjust that line to your
JDK 17 path, or upgrade Gradle and delete the line.

```powershell
.\gradlew.bat build
```

The jar is written to `build/libs/`.

> JEI is a `compileOnly` dependency resolved from `libs/jei-1.20.1-forge-15.20.0.106.jar`, which is
> not committed to this repository. If you want to compile the JEI integration, download the JEI jar
> for 1.20.1 Forge and place it at that path. JEI is optional at runtime; without it the mod works
> normally and only the search alias is missing.

## Credits

Written with **AI assistance — DeepSeek-V4.1-Flash**. The design decisions, the code, and the
icon were all produced in collaboration with that model; the human author directed the work and
tested it in game.

## License

MIT — see [LICENSE](LICENSE).
