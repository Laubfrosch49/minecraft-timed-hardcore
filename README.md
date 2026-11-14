<div align="center">

<img src="src/main/resources/assets/timed-hardcore/icon.png" alt="Timed Hardcore icon" width="128" height="128">

# Timed Hardcore

**Hardcore multiplayer where death is temporary.**
Die, and you stay dead for a set time instead of forever.

[![Minecraft](https://img.shields.io/badge/Minecraft-26.3-62B47A?style=for-the-badge)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/Mod%20loader-Fabric-DBD0B4?style=for-the-badge)](https://fabricmc.net/)
[![Java](https://img.shields.io/badge/Java-25-E76F00?style=for-the-badge)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-CC0--1.0-lightgrey?style=for-the-badge)](LICENSE)

[![Build](https://github.com/Laubfrosch49/minecraft-timed-hardcore/actions/workflows/build.yml/badge.svg)](https://github.com/Laubfrosch49/minecraft-timed-hardcore/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/Laubfrosch49/minecraft-timed-hardcore?label=Latest%20release)](https://github.com/Laubfrosch49/minecraft-timed-hardcore/releases/latest)

[Features](#features) · [How it works](#how-it-works) · [Discord](#discord) · [Installation](#installation) · [Commands](#commands) · [Configuration](#configuration) · [FAQ](#faq)

</div>

---

Timed Hardcore turns hardcore multiplayer into something you can actually play with friends. Death still hurts: you are kicked and cannot join until you are revived. But you come back, either after a set duration or at fixed times of the day.

> [!NOTE]
> The mod runs on the **server**. Installing it on the **client is optional**: players without it can join and play normally, they just miss a few extras.

## Features

- **Timed death**: die, and you stay dead for a configurable time. Rejoining shows how long is left.
- **Two revival modes**: after a duration (for example 24 hours) or at fixed times of the day (for example midnight and noon).
- **Safe rule**: with enough players online, a death has no consequences. Dying only counts when you play alone.
- **Extra life**: a green apple saves you once. It is only sold by the rare Wandering Healer.
- **Graveyard**: an advancement tab that shows the rules of the server and everyone who is currently dead.
- **Discord messages**: every death and every revival is posted to Discord, in one channel or in two.
- **Death clips**: a death message can show the last 10 seconds before the death, recorded by the player's own client. Every player decides what they share.
- **Always know where you stand**: a risk icon next to every name in the player list, and a notice with sound whenever your own risk changes.
- **Keep your inventory**: players who stay dead keep their items and XP, controlled by a game rule.
- **Easy to run**: every setting can be changed with a command or in a JSON file, without a restart.

## How it works

Every player is in one of three states at any moment. The state decides what happens if they die right now:

| State         | When                                 | What a death means                              | Hearts (with client mod) |
|---------------|--------------------------------------|-------------------------------------------------|--------------------------|
| **At risk**   | You are alone and have no extra life | You stay dead until you are revived             | Red hardcore hearts      |
| **Protected** | You have an extra life               | The extra life is used up, you respawn normally | Green hearts             |
| **Safe**      | Enough players are online            | Nothing, you respawn normally                   | Normal hearts            |

Players in creative or spectator mode are never affected.

```mermaid
flowchart TD
    A([A player dies]) --> B{Creative or<br>spectator?}
    B -- yes --> N[Normal respawn]
    B -- no --> C{Enough players<br>online?}
    C -- yes --> N
    C -- no --> D{Has an<br>extra life?}
    D -- yes --> E[Extra life is used up] --> N
    D -- no --> F[Player stays dead<br>and is kicked]
    F --> G([Revived when the time is up<br>or by an operator])
```

### What a dead player sees

- **On death**: the death screen, then a kick screen with the cause of death, how long they stay dead and when they are revived.
- **When they try to join**: `YOU ARE STILL DEAD`, with the time left.
- **In the server list** (client mod): the second MOTD line reads `You are dead for another 3 h 12 min.`, and after the revival `You have been revived · join again!`

<p align="center">
  <img src="docs/images/kick-screen.webp" alt="Kick screen after a timed death: YOU DIED, the cause of death, how long the player stays dead and when they are revived" width="720">
  <br>
  <sub>The kick screen after a timed death</sub>
</p>

Everyone else gets a chat message right when a player dies, and another one when they are alive again.

### Extra lives and the Wandering Healer

Eating a **green apple** grants one extra life. It has no other effect, can be eaten on a full stomach, and you can only hold one extra life at a time.

<p align="center">
  <img src="docs/images/extra-life.webp" alt="Eating the green apple: a green totem animation, green hearts and a chat message that the player now has an extra life" width="720">
  <br>
  <sub>Eating the green apple with the client mod: its own totem animation, and the hearts turn green</sub>
</p>

The apple is sold by the **Wandering Healer**, a rare variant of the wandering trader. Each healer offers six random healing and survival items with varying prices, and exactly one green apple at the bottom of the list. Expect it to be expensive.

### Graveyard

The advancement screen gets a new tab, **Graveyard**. It always shows the current rules (revival, safe rule, extra life, inventory) and lists every dead player with their head, cause of death and time of revival. It works without the client mod.

## Discord

The server can post every timed death and every revival to a Discord channel. Each message starts with the player's name and head:

- **Death**: how they died, how long they stay dead and when they come back, with a full-body picture of the player in **black and white**, or a [death clip](#death-clips).
- **Revival**: who revived them (or that their time was up), how long they were dead and what killed them, with the picture in **colour**.

<table>
  <tr>
    <td align="center"><img src="docs/images/discord-death.png" alt="Discord message of a death, with a black and white picture of the player" height="380"></td>
    <td align="center"><img src="docs/images/discord-revival.png" alt="Discord message of a revival, with the picture of the player in colour" height="380"></td>
    <td align="center"><img src="docs/images/discord-death-clip.png" alt="Discord message of a death with a death clip that ends on the death screen" height="330"></td>
  </tr>
  <tr>
    <td align="center"><sub>Death</sub></td>
    <td align="center"><sub>Revival</sub></td>
    <td align="center"><sub>Death with a death clip</sub></td>
  </tr>
</table>

The time of revival is a Discord timestamp, so everyone sees it in their own time zone, with a live countdown. Messages never ping anyone, even if a death message happens to contain `@everyone`. The pictures come from [NMSR](https://nmsr.nickac.dev/) and include the player's cape; any other render service can be set in the [configuration](#configuration).

### Setting it up

1. In Discord, open **Server Settings → Integrations → Webhooks**, create a webhook for your channel and click **Copy Webhook URL**.
2. Paste the URL into `discord.deathWebhookUrl` and/or `discord.revivalWebhookUrl` in `config/timed-hardcore.json`.
3. Run `/timedhardcore reload`.

| You want                       | `deathWebhookUrl` | `revivalWebhookUrl` |
|--------------------------------|-------------------|---------------------|
| Everything in one channel      | Webhook A         | Webhook A           |
| Deaths and revivals separately | Webhook A         | Webhook B           |
| Only deaths                    | Webhook A         | empty               |
| Only revivals                  | empty             | Webhook A           |

`/timedhardcore discord` shows what is posted right now. `/timedhardcore discord disable` switches the messages off without removing the webhooks, for example for a test round, and `/timedhardcore discord enable` switches them on again. Deaths and revivals in between are not sent later.

> [!CAUTION]
> A webhook URL contains a secret token: anyone who has it can post in your channel. The mod never shows it in chat or writes it to the log. Do not share your config file.

### Death clips

With `"deathMedia": "CLIP"`, a death message shows the **last 10 seconds before the death** as an animated GIF instead of the picture. The clip ends on the death screen with the cause of death, and Discord plays it right inside the message.

```mermaid
sequenceDiagram
    participant C as Client of the dying player
    participant S as Server
    participant D as Discord
    Note over C: Keeps the last 10 seconds in memory
    S->>C: Timed death: please send your clip
    Note over C: Keeps recording for 1.5 seconds<br>to capture the death screen
    C->>S: Clip as a GIF
    S->>D: Death message with the clip
    S->>C: Kick
```

- **Needs the client mod.** Only the dying player's own client can record what they saw. Players without the mod get the picture.
- **Every player decides per server**, like with server resource packs. The first time they join a server that records clips, a notice explains it and asks. The choice can be changed any time under **Edit** in the server list, or while playing in the pause menu under **World Options**:

  <p align="center">
    <img src="docs/images/clip-notice.webp" alt="Notice when joining a server with death clips, with the buttons Don't share, World only and With HUD" width="720">
    <br>
    <sub>The notice when joining a server that records death clips</sub>
  </p>

  | Setting       | What the clips show                                                             |
  |---------------|---------------------------------------------------------------------------------|
  | Ask (default) | The notice appears when joining, before the player is in the world              |
  | World only    | Only the game world, without HUD and chat                                       |
  | With HUD      | Everything the player sees: HUD, inventory and chat, including private messages |
  | Off           | Nothing is recorded; the death shows the picture                                |

  <table>
    <tr>
      <td align="center"><img src="docs/images/death.gif" alt="Death clip with World only: a zombie attacks in a cave, the clip ends on the death screen" width="400"></td>
      <td align="center"><img src="docs/images/death_hud.gif" alt="Death clip with HUD: fighting the Ender Dragon with hotbar and boss bar visible, the clip ends on the death screen" width="400"></td>
    </tr>
    <tr>
      <td align="center"><sub>World only</sub></td>
      <td align="center"><sub>With HUD</sub></td>
    </tr>
  </table>

- **Nothing is written to disk.** While the server asks for clips, the client keeps only the last 10 seconds in memory, at 480×270 pixels and 10 frames per second.
- **The kick waits for the clip.** After the death, the death screen stays a little longer and shows *Creating and sending death clip...*; this usually takes a second or two. The server waits at most 60 seconds, otherwise the message shows the picture.
- **Clips need a death webhook.** Without one, or while the messages are disabled, nothing is recorded and no notice is shown.

## Installation

**Requirements:** Minecraft 26.3 · Fabric Loader 0.19.5 or newer · [Fabric API](https://modrinth.com/mod/fabric-api) · Java 25

### Server

1. Set up a [Fabric server](https://fabricmc.net/use/server/) for Minecraft 26.3.
2. Put the Timed Hardcore JAR and Fabric API into the `mods` folder.
3. Recommended: create the world with `hardcore=true` in `server.properties`, so players without the client mod see hardcore hearts.

> [!IMPORTANT]
> `hardcore=true` only applies when the world is **created**. Changing it later has no effect on an existing world. The mod works either way and prints a hint in the console if the world is not in hardcore mode.

### Client (optional)

Put the same JAR and Fabric API into the `mods` folder of your client. That adds:

|                  | Without the client mod                 | With the client mod                                              |
|------------------|----------------------------------------|------------------------------------------------------------------|
| Hearts           | Follow the world's hardcore setting    | Change live with your risk: red, green or normal                 |
| Death screen     | Follows the world's hardcore setting   | The normal respawn button whenever the death has no consequences |
| Green apple      | A glinting red apple with a green name | Its own green texture, also in the totem animation               |
| Wandering Healer | Looks like a normal wandering trader   | Its own robe with a green cross                                  |
| Server list      | Normal MOTD                            | Shows how long you are still dead, or that you were revived      |
| Times            | In the server's time zone, e.g. `UTC`  | In your own time zone                                            |
| Death clips      | Your death shows the picture           | You can share a clip of your last seconds, if the server asks    |

> [!TIP]
> In singleplayer the mod does nothing, so you can leave it installed for your own worlds.

## Commands

### For everyone

| Command               | What it does                                                 |
|-----------------------|--------------------------------------------------------------|
| `/timedhardcore info` | Shows the rules of the server at a glance, and your own risk |
| `/timedhardcore dead` | Lists who is dead and for how long                           |

### For operators

| Command                                    | What it does                                                                                            |
|--------------------------------------------|---------------------------------------------------------------------------------------------------------|
| `/revive <player>`                         | Revives a dead player at once                                                                           |
| `/timedhardcore list`                      | Risk of all online players, the dead players (with a clickable revive button) and who has an extra life |
| `/timedhardcore duration [minutes]`        | Shows or sets how long players stay dead, and switches to the duration mode                             |
| `/timedhardcore times [times]`             | Shows or sets the fixed revival times, for example `0:00 12:00`, and switches to that mode              |
| `/timedhardcore mode [duration\|times]`    | Shows or switches the revival mode                                                                      |
| `/timedhardcore safe [count]`              | Shows or sets the safe rule. `0` switches it off                                                        |
| `/timedhardcore life give\|take <player>`  | Grants or removes an extra life                                                                         |
| `/timedhardcore apple [players]`           | Gives a green apple                                                                                     |
| `/timedhardcore healer`                    | Spawns a Wandering Healer at your position                                                              |
| `/timedhardcore discord [enable\|disable]` | Shows what is posted to Discord, or switches the messages on and off                                    |
| `/timedhardcore reload`                    | Reads the config and the data files again                                                               |

> [!NOTE]
> When the revival settings change, all dead players are recalculated from their time of death. Someone who is already due after the change is revived right away.

## Configuration

### Server

The settings live in `config/timed-hardcore.json`. The file is created on the first start. You can edit it while the server is running and apply it with `/timedhardcore reload`.

```json
{
  "reviveMode": "DURATION",
  "deathDuration": 1440.0,
  "reviveTimes": ["00:00", "12:00"],
  "safePlayerCount": 0,
  "extraLivesEnabled": true,
  "healerSpawnChance": 0.2,
  "showRemainingTimeInMotd": true,
  "discord": {
    "deathWebhookUrl": "",
    "revivalWebhookUrl": "",
    "enabled": true,
    "deathMedia": "PICTURE",
    "playerImage": "https://nmsr.nickac.dev/fullbody/{player}",
    "playerHead": "https://nmsr.nickac.dev/face/{player}"
  }
}
```

| Option                    | Default          | Meaning                                                                                             |
|---------------------------|------------------|-----------------------------------------------------------------------------------------------------|
| `reviveMode`              | `DURATION`       | `DURATION`: revived after `deathDuration`. `FIXED_TIMES`: revived at the next time in `reviveTimes` |
| `deathDuration`           | `1440.0`         | Minutes a player stays dead. Decimals are allowed. `0` means deaths have no consequences            |
| `reviveTimes`             | `00:00`, `12:00` | Times of day at which all dead players are revived, in the time zone of the server                  |
| `safePlayerCount`         | `0`              | With at least this many players online, a death has no consequences. `0` switches the rule off      |
| `extraLivesEnabled`       | `true`           | Enables the green apple and extra lives                                                             |
| `healerSpawnChance`       | `0.2`            | Share of naturally spawned wandering traders that are healers, from `0` to `1`                      |
| `showRemainingTimeInMotd` | `true`           | Lets the client mod show the remaining time in the server list                                      |

The [Discord](#discord) settings are grouped under `discord`:

| Option              | Default        | Meaning                                                                                               |
|---------------------|----------------|-------------------------------------------------------------------------------------------------------|
| `deathWebhookUrl`   | empty          | Webhook for death messages. Empty = no death messages and no death clips                              |
| `revivalWebhookUrl` | empty          | Webhook for revival messages. May be the same as the death webhook. Empty = no revival messages       |
| `enabled`           | `true`         | `false` sends nothing, without removing the webhooks. Same as `/timedhardcore discord disable`        |
| `deathMedia`        | `PICTURE`      | `PICTURE`: black and white picture of the player. `CLIP`: a [death clip](#death-clips) where possible |
| `playerImage`       | NMSR full body | Picture of the player. `{player}` is the UUID in online mode, the name otherwise. Empty = no picture  |
| `playerHead`        | NMSR face      | Head next to the player's name at the top of the message. Same placeholders. Empty = no head          |

### Client

Each player's own settings live in `config/timed-hardcore-client.json` on their computer. No server can change them.

```json
{
  "shareDeathClips": true,
  "deathClips": {
    "play.example.com": "WORLD_ONLY"
  }
}
```

| Option            | Default | Meaning                                                                                                    |
|-------------------|---------|------------------------------------------------------------------------------------------------------------|
| `shareDeathClips` | `true`  | `false` never records death clips, on any server and whatever is chosen there                              |
| `deathClips`      | empty   | The [death clip](#death-clips) choice per server address: `WORLD_ONLY`, `WITH_HUD` or `OFF`. Missing = Ask |

The choices are normally made in the game, under **Edit** in the server list or in **World Options**.

### Game rule

| Game rule                                      | Default | Meaning                                                                                                                |
|------------------------------------------------|---------|------------------------------------------------------------------------------------------------------------------------|
| `timed-hardcore:keep_inventory_on_timed_death` | `true`  | Players who stay dead keep their inventory and XP. If `false`, the normal `keepInventory` rule applies to them as well |

```
/gamerule timed-hardcore:keep_inventory_on_timed_death false
```

Deaths without consequences always follow the normal `keepInventory` rule.

### Data files

| File                                | Content                                |
|-------------------------------------|----------------------------------------|
| `<world>/timed-hardcore.json`       | Who is dead, since when and until when |
| `<world>/timed-hardcore-lives.json` | Who has an extra life                  |

Both files can be edited by hand and applied with `/timedhardcore reload`. If a file is invalid, the reload reports the error and the current data stays active. A file that is already broken at server start is moved aside as `.broken` instead of being overwritten.

## FAQ

<details>
<summary><b>Can players without the mod join?</b></summary>

Yes. Everything that matters happens on the server. The mod adds no new items or blocks: the green apple is a normal apple with extra data, and the healer is a normal wandering trader.
</details>

<details>
<summary><b>Does the dead time keep running while the server is offline?</b></summary>

Yes. The revival is a fixed point in real time, not a countdown.
</details>

<details>
<summary><b>Which time zone do the times use?</b></summary>

The fixed revival times in the config are in the time zone of the server. Many hosts run on UTC, so `00:00` may not be midnight for your players.

In the game, players with the client mod see every time (revival, time of death, the fixed revival times) in their own time zone: on the kick screen, in chat and in the graveyard. Without the mod, the times are shown in the server's time zone with its name, for example `8:00 PM UTC`.
</details>

<details>
<summary><b>What does the server list reveal?</b></summary>

So that the client mod can find its own player, the server attaches the UUID and remaining time of every dead player to its server list response. Anyone who pings the server can read that. No names and no IP addresses are sent or stored. Set `showRemainingTimeInMotd` to `false` to switch it off.
</details>

<details>
<summary><b>What do the Discord messages send, and where?</b></summary>

The messages go only to the webhooks you set. For the picture, the server asks the render service in `discord.playerImage` for the player's UUID or name. Leave both webhooks empty, or run `/timedhardcore discord disable`, and nothing is sent.
</details>

<details>
<summary><b>Can a server record me without asking?</b></summary>

No. Your client records nothing until you have chosen **World only** or **With HUD** for that server, and only while the server asks for clips. The frames stay in memory and are only sent when you die and stay dead. The client sends the clip to the server, which passes it on to its webhook; the client never learns the webhook address, and the server only accepts a clip right after it asked for one.

With **World only**, the frames are taken before chat and HUD are drawn, so private messages can never end up in a clip.
</details>

<details>
<summary><b>Can I still ban players the normal way?</b></summary>

Yes. Timed deaths are stored separately and have nothing to do with the ban list.
</details>

## Building from source

```bash
git clone https://github.com/Laubfrosch49/minecraft-timed-hardcore.git
cd minecraft-timed-hardcore
./gradlew build
```

The JAR ends up in `build/libs`. The build needs Java 25 and runs the automated server tests. To run only those:

```bash
./gradlew runGameTest
```

## License

Released under [CC0 1.0](LICENSE): do whatever you like with it.

<div align="center">
<sub>Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.</sub>
</div>
