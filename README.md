# AnyJar (Fabric + NeoForge, one jar)

Run any Minecraft server software — Paper, Velocity, Forge, NeoForge, and anything else
[mcjars.app](https://mcjars.app) serves — on a host that only lets you upload Fabric or
NeoForge mods.

Drop `build/libs/anyjar-<version>.jar` into a **server's** `mods` folder — same file for
Fabric or NeoForge. As early as each loader allows, the mod hijacks startup, before the
real loader/game ever gets a turn:

**First boot only** — an interactive, colored setup wizard:

1. Lists every server software mcjars.app knows about (Vanilla, Paper, Purpur, Spigot,
   Folia, Pufferfish, Waterfall, Velocity, Fabric, BungeeCord, Quilt, Forge, NeoForge,
   Mohist, Arclight, Sponge, Leaves, Canvas, ...) as a numbered menu. Answer with a number
   or the name.
2. Asks for a Minecraft version (with recent ones shown and `latest` as a shortcut) — skipped
   for software with no Minecraft-version concept, like Velocity.
3. Asks which build of that software/version to install (`latest` by default).
4. Saves the resolved choice to `launcher.properties` and never asks again. Delete that file
   to be prompted again on the next boot.

**Every boot** after that:

1. If `server.jar` doesn't exist yet, it's installed by replaying the exact install steps
   mcjars.app gave for the chosen build (plain download for most software; download + unzip +
   cleanup for installer-based software like Forge/NeoForge) — the same generic recipe mcjars
   uses for everything it serves, so one installer works for all of them.
2. If the chosen software is Velocity: `server.properties`' `server-port` is mirrored into
   `velocity.toml`'s `bind` address (seeding `velocity.toml` with just that line if it doesn't
   exist yet), and Velocity's own bundled `log4j2.xml` is patched so its `Done (0.85s)!`
   startup line also prints `For help, type "help" or "?" (no such commands exist on Velocity;
   this line only marks the proxy as finished starting)` — a pure logging-config patch (no
   bytecode touched) so process managers that only recognize a vanilla-style "Done ... for
   help" line correctly detect it as started.
3. `java -jar server.jar` is launched as a child process with the console (stdin/stdout/stderr)
   inherited directly, so it behaves exactly like running it yourself.
4. This JVM blocks on that process. Fabric/NeoForge/Minecraft never gets to continue starting,
   and nothing in the wizard, logs, or console ever reveals which of them is actually running
   underneath.
5. When the chosen software exits, this JVM exits immediately with the same exit code.

The mod only acts on a dedicated server; it's a no-op on a client install.

## Project layout

One Gradle build, three subprojects, one final jar:

- **`common/`** — the actual hijack logic, pure `java.*`, zero dependency on Fabric, NeoForge,
  or Minecraft itself: `AnyJarLauncher` (orchestrator), `SetupWizard` (first-boot UI),
  `McJarsApi` (mcjars.app client), `InstallSteps` (generic download/unzip/remove executor),
  `LaunchConfig` (persisted choice), `Json` (parser/writer).
- **`fabric/`** — `fabric.mod.json` + a thin `AnyJarPreLaunch` entrypoint (Fabric's
  `preLaunch`, the earliest hook Fabric offers) that delegates straight to `AnyJarLauncher`.
  Built with Fabric Loom.
- **`neoforge/`** — `META-INF/neoforge.mods.toml` + a thin `@Mod`-annotated `AnyJarNeoForge`
  class (its constructor is the earliest hook a NeoForge mod gets) that also just delegates to
  `AnyJarLauncher`. Built with NeoForge's ModDevGradle.
- **root `build.gradle`** — a `mergedJar` task that zips the Fabric and NeoForge jars together
  into one `build/libs/anyjar-<version>.jar`. Each loader only ever looks at its own metadata
  file and only classloads the entrypoint class that file points to, so one jar carrying both
  platforms' files is harmless; the shared `cc.kokodev.anyjar.*` classes end up in the merged
  jar exactly once.

`./gradlew build` builds everything and produces that merged jar.

Adding another loader later follows the same pattern: a new subproject with its own metadata
file and a thin entrypoint class delegating to `:common`'s `AnyJarLauncher`, then one more
`from(...)` line in the root `mergedJar` task.

## Setup

For Fabric-side IDE setup, see the
[Fabric Documentation page](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up)
for your IDE. For NeoForge, see the
[ModDevGradle](https://github.com/neoforged/ModDevGradle) docs.

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.
