# `mods.json` format

[English](mods.schema.md) | [简体中文](mods.schema.zh_CN.md)

Version `1`. The file is an object, not a bare array: the header says which loader release the metadata
was parsed with, which matters for Copper because the loader is versioned separately from the game.

```json
{
  "formatVersion": 1,
  "generatedAt": "2026-01-05T12:00:00Z",
  "loaderVersion": "810afee77f",
  "mods": [ /* … */ ]
}
```

| Field | Type | Meaning |
| ----- | ---- | ------- |
| `formatVersion` | int | the shape of this file; currently `1` |
| `generatedAt` | string | when the scan ran, ISO-8601 UTC |
| `loaderVersion` | string | the loader release whose meta format was used; absent if unknown |
| `mods` | array | the mods, sorted by `id` |

A client that only understands a bare array can read `mods` and ignore the rest; `previousRepos()` in
`ModIndex` accepts both shapes for that reason.

## A mod entry

```json
{
  "repo": "example/examplemod",
  "id": "example:examplemod",
  "vanillaName": "copper-example-examplemod",
  "name": "Example Mod",
  "author": "Example",
  "description": "A short description.",
  "version": "1.2.3",
  "lastUpdated": "2026-01-05T10:00:00Z",
  "stars": 12,
  "gameRequirement": ">=159",
  "loaderRequirement": ">=0.2.0",
  "iconHash": "5C26335C0F204FE0EA28F3425A5AFC7F438EAC5BF060E44E47E327C13F60B91E"
}
```

| Field | Type | Meaning |
| ----- | ---- | ------- |
| `repo` | string | `owner/name`, lower-cased |
| `id` | string | the Copper mod id, `author:modname` |
| `vanillaName` | string | the name the mod takes in the game's own mod registry: `copper-` + `id` with `:` → `-`. It is the prefix of every content name and sprite region the mod registers. Derivable from `id`, but published so a client does not have to know the rule. |
| `name` | string | display name, formatting tags and newlines stripped |
| `author` | string | as written in the metadata |
| `description` | string | formatting stripped; may be absent |
| `version` | string | the mod version declared in the repository's `copper.mod.json` |
| `lastUpdated` | string | the repository's last push, ISO-8601 UTC |
| `stars` | int | GitHub stars |
| `gameRequirement` | string | the game version range the mod supports, as a Copper version filter; absent when the mod states no requirement, which means any version |
| `loaderRequirement` | string | the same for the `loader` dependency; absent when the mod states none |
| `iconHash` | string | upper-case SHA-256 of `icons/<owner>_<repo>.png`; absent when the repo has no `icon.png` or `assets/icon.png` |

### Version filters

`gameRequirement` and `loaderRequirement` are published **verbatim** as Copper version filters. The filter
grammar (comparisons, `^`, `~`, ranges, wildcards, `&&`/`||`, and a list of exact versions) belongs to the
loader, so evaluate them with the loader's own `SemanticVersionFilter` rather than re-deriving it:

```java
// does this mod accept the game version the player runs?
boolean supported = new SemanticVersionFilter(mod.gameRequirement()).check(new SemanticVersion("159.7"));
```

An absent or empty filter matches everything.

### Icons

The icon file name is derivable from the repository, so no path needs to be published:

```
icons/<owner>_<repo>.png      # lower-cased, "/" replaced by "_"
```

`iconHash` is the upper-case SHA-256 of that file. A client that has already downloaded an icon can skip
the download when the hash it holds still matches.

## Downloads are the client's job

The index carries **no release information** - no tag, no asset, no URL - so a client resolves the build
itself, exactly as `Anuken/MindustryMods` does:

```
GET https://api.github.com/repos/<repo>/releases
```

Then pick a release and download
`https://github.com/<repo>/releases/download/<tag_name>/<asset_name>`.

That split is deliberate: which build suits a player depends on their game version and on what the release
titles say, and the client is the only side that knows the first. It is also what keeps a scan's API usage
at **zero requests per mod** - the index never has to read releases at all.

`gameRequirement` is what the client gates on before installing: a release the mod's own metadata says is
out of range can be skipped without downloading it.

## What is not in the file

- **Template repositories.** A mod template carries a valid meta file, so it is skipped by GitHub's
  `is_template` flag and by the known-name exclude list.
- **Mods that opted out.** `extra.browser: false` keeps a mod out without affecting the loader.
- **Mods whose metadata the loader rejects.** They are skipped and reported in the scan log.
- **Dependencies, conflicts and mixin counts.** The loader resolves and enforces those at install time; a
  browser does not act on them.
- **Any release information**, as above.
