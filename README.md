# copper-mods

[English](README.md) | [简体中文](README.zh_CN.md)

Automatically compiles a list of Copper mods into `mods.json`, for launchers and mod browsers to read.
Refreshes once a day from GitHub Actions.

Modelled on [Anuken/MindustryMods](https://github.com/Anuken/MindustryMods), which does the same job for
vanilla Mindustry mods.

**Do not make PRs adding your mod to this list** — make sure your repository fits the criteria below and
it will be picked up on the next scan.

## Criteria

- Must contain a valid **`copper.mod.json`** or **`copper.mod.hjson`** at the **root** of the repository
  (not under `assets/`). This is the file the loader itself looks for, so a mod that is not laid out that
  way cannot be installed by Copper either.
- Must carry the **`mindustry-copper-mod`** topic.
- Must be **public and not archived**.
- Must not be a **template repository**, and must not be one of the known template/example repositories
  (`MDTCopper/mod-template`, `Anuken/ExampleMod`, and the like). A template carries a valid meta file -
  often with the same mod id as the real mod - so indexing one would both list a mod nobody can install
  and, because duplicate ids are resolved by stars, could displace the real mod.

Hidden mods (`"hidden": true`) **are** listed. `hidden` means the mod registers no game content - it is a
plugin, in the loader's terms - so leaving it out would hide working mods. The flag itself is not recorded
in the index: whether a mod registers content is something the loader decides at install time.

A template repository is detected two ways, because GitHub's `is_template` flag is opt-in and most
templates never set it: the flag itself, and an exclude list of known names. Extend it with
`--exclude` (repeatable, `*` and `?` allowed) or index templates anyway with `--keep-templates`.

The metadata is parsed with the **loader's own parser**, not a reimplementation, so anything the loader
would reject at startup is rejected here too and the mod is skipped.

### Releases

The index does **not** look at releases at all. A client asks GitHub for
`/repos/<repo>/releases` itself and picks the build that suits the game version it runs - the same split
`Anuken/MindustryMods` uses. What the index publishes for gating that choice is `gameRequirement`, the
version range your `copper.mod.json` declares:

```json
"dependencies": { "mindustry": ">=159", "loader": ">=0.2.0" }
```

- `gameRequirement` is that `mindustry` filter, verbatim, so a client evaluates it with the loader's own
  `SemanticVersionFilter`. Omit it and the mod is listed as accepting any game version.
- `loaderRequirement` is the `loader` filter, since Copper versions the loader separately.
- `version` is the version in your `copper.mod.json` at the scanned commit. Bump it when you release, or
  clients cannot tell an update is available.

### Opting out

To stay out of the browser without changing what the loader does, add `browser: false` to the `extra`
object of your `copper.mod.json`. The loader and the game both ignore `extra` fields they do not know, so
this is invisible to them:

```json
{
  "version": 1,
  "meta": {
    "id": "example:examplemod",
    "name": "Example Mod",
    "author": "Example",
    "version": "1.0.0",
    "main": "example.examplemod.ExampleMod",
    "extra": { "browser": false }
  }
}
```

`hidden: true` does not keep a mod out of the index - see the criteria above.

## Using the index

```
https://raw.githubusercontent.com/MTDCopper/copper-mods/main/mods.json
```

`mods.json` is an object with a header and a `mods` array; see [docs/mods.schema.md](docs/mods.schema.md)
for every field. An entry is just metadata - no release, no download URL:

```json
{
  "repo": "example/examplemod",
  "id": "example:examplemod",
  "vanillaName": "copper-example-examplemod",
  "name": "Example Mod",
  "author": "Example",
  "version": "1.2.3",
  "gameRequirement": ">=159",
  "loaderRequirement": ">=0.2.0"
}
```

`gameRequirement` is the game version range the mod accepts, written as a **Copper version filter** so a
client can evaluate it with the loader's own `SemanticVersionFilter` before installing anything.
Dependencies, conflicts, mixin counts, the `hidden` flag and all release information are left out: the
loader enforces the first three at install time, and resolving a release is the client's job.

Icons are read from the repository (`icon.png`, else `assets/icon.png`), one raw request each, never from
a release asset. They are published under `icons/` - this is the output directory, not a cache, and it is
rewritten each run - named `<owner>_<repo>.png`, and referenced by `mods[].iconHash` (upper-case SHA-256 of
the file). Repositories with neither path have no `iconHash`.

## Running it

```bash
# the checks that need no network: parsing through the loader, rate-limit policy, exclusions, index shape
# (they live in src/test, so they are not in the jar and not reachable from Main)
./gradlew selfcheck

# a scan (needs network)
GITHUB_TOKEN=... ./gradlew update -Pargs="--out . --keep-missing"

# check a mod the way the loader does, before publishing it
./gradlew validate -Ptarget=../mod-templete
```

`./gradlew run --args="..."` takes the same flags; `./gradlew run --args="--help"` lists them.

`./gradlew jar` builds a self-contained `build/libs/loader-mods-0.1.0.jar`, which is what CI or a hook
should use if the exit code matters: `validate` exits `3` for a mod the loader rejects, and Gradle's
`JavaExec` reports any non-zero exit as its own failure, hiding which one it was.

A token is optional but recommended: anonymous GitHub access allows 60 requests an hour, a token 5000.

### Flags

| Flag | Default | Meaning |
| ---- | ------- | ------- |
| `--out <dir>` | `.` | where `mods.json` and `icons/` are written |
| `--topic <name>` | `mindustry-copper-mod` | discovery topic |
| `--max-pages <n>` | `10` | search pages to read, 100 repositories each. Clamped to 10: see below |
| `--min-stars <n>` | `0` | only fetch icons for repositories with at least this many stars |
| `--keep-missing` | off | keep entries whose repository is no longer discoverable |
| `--allow-empty` | off | permit writing an index with no mods |
| `--keep-templates` | off | index template repositories instead of skipping them |
| `--exclude <owner/repo>` | — | skip a repository; repeatable, `*` and `?` allowed |
| `--token <token>` | `$GITHUB_TOKEN` | GitHub token |
| `--threads <n>` | `8` | concurrent repository scans |
| `--verbose` | off | log every skipped repository and every request |
