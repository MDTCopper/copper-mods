# `mods.json` 格式

[English](mods.schema.md) | [简体中文](mods.schema.zh_CN.md)

格式版本 `1`。文件是一个对象而非裸数组：文件头写明元数据是用哪个 loader 发行版解析的，
这对 Copper 有意义，因为加载器与游戏是分开版本化的。

```json
{
  "formatVersion": 1,
  "generatedAt": "2026-01-05T12:00:00Z",
  "loaderVersion": "810afee77f",
  "mods": [ /* … */ ]
}
```

| 字段 | 类型 | 含义 |
| ---- | ---- | ---- |
| `formatVersion` | int | 本文件的结构版本；当前为 `1` |
| `generatedAt` | string | 扫描运行的时间，ISO-8601 UTC |
| `loaderVersion` | string | 元数据解析所用的 loader 发行版；未知时省略 |
| `mods` | array | 模组列表，按 `id` 排序 |

只认裸数组的客户端可以直接读 `mods` 并忽略其余部分；`ModIndex` 里的 `previousRepos()`
同时接受两种结构，正是为此。

## 模组条目

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

| 字段 | 类型 | 含义 |
| ---- | ---- | ---- |
| `repo` | string | `owner/name`，已转小写 |
| `id` | string | Copper 模组 id，`author:modname` |
| `vanillaName` | string | 该模组在游戏自身模组注册表里的名字：`copper-` 加上把 `id` 的 `:` 换成 `-`。它是该模组注册的每一个内容名与贴图区域名的前缀。可由 `id` 推导，但照样发布，这样客户端不必知道这条规则。 |
| `name` | string | 显示名，已剥掉格式代码与换行 |
| `author` | string | 元数据里写的作者 |
| `description` | string | 已剥掉格式代码；可能省略 |
| `version` | string | 仓库里 `copper.mod.json` 声明的模组版本 |
| `lastUpdated` | string | 仓库最后一次 push 的时间，ISO-8601 UTC |
| `stars` | int | GitHub star 数 |
| `gameRequirement` | string | 该模组支持的游戏版本范围，写成 Copper 版本过滤条件；模组未声明时省略，表示任意版本 |
| `loaderRequirement` | string | 同上，但针对 `loader` 依赖；未声明时省略 |
| `iconHash` | string | `icons/<owner>_<repo>.png` 的大写 SHA-256；仓库没有 `icon.png` 也没有 `assets/icon.png` 时省略 |

插件（声明 `"hidden": true` 的模组）同样会被列出，条目结构完全一致——`hidden` 不写入索引，
因为"是否注册游戏内容"由加载器在安装时判定。

### 版本过滤条件

`gameRequirement` 与 `loaderRequirement` 都是**原样**发布的 Copper 版本过滤条件。这套过滤语法
（比较运算、`^`、`~`、区间、通配符、`&&`/`||`，以及一组精确版本）属于加载器，
所以应当用加载器自己的 `SemanticVersionFilter` 求值，而不是另写一套：

```java
// 该模组是否接受玩家所运行的游戏版本？
boolean supported = new SemanticVersionFilter(mod.gameRequirement()).check(new SemanticVersion("159.7"));
```

过滤条件缺失或为空表示匹配一切。

### 图标

图标文件名可由仓库推导，所以无需发布路径：

```
icons/<owner>_<repo>.png      # 全部小写，"/" 换成 "_"
```

`iconHash` 是该文件的大写 SHA-256。已经下载过图标的客户端，在手里的哈希仍然匹配时就可以跳过下载。

## 下载是客户端的职责

索引**不包含任何 release 信息**——没有 tag、没有资产名、没有 URL——所以由客户端自己解析构建，
与 `Anuken/MindustryMods` 完全一致：

```
GET https://api.github.com/repos/<repo>/releases
```

然后挑一个 release，下载
`https://github.com/<repo>/releases/download/<tag_name>/<asset_name>`。

这个分工是有意的：哪个构建适合玩家，取决于玩家的游戏版本以及 release 标题里写了什么，
而只有客户端知道前者。它同时也是扫描**每个模组零 API 请求**的原因——索引完全不必读 release。

`gameRequirement` 就是客户端在安装前用来把关的东西：模组自己的元数据已经声明超出范围的 release，
可以不下载就跳过。

## 文件中没有的东西

- **模板仓库。** 模组模板带着合法的元数据文件，所以会被 GitHub 的 `is_template` 标记
  与已知名字的排除表跳过。
- **主动退出收录的模组。** `extra.browser: false` 能让模组不进列表，且不影响加载器。
- **加载器拒绝其元数据的模组。** 它们会被跳过，并记录在扫描日志里。
- **依赖、冲突与 mixin 数量。** 这些由加载器在安装时解析并校验；浏览器不会据此行事。
- **任何 release 信息**，见上。
