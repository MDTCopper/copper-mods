package copper.loadermods;

import copper.loadermods.cli.UpdateCommand;
import copper.loadermods.index.*;
import copper.loadermods.meta.*;
import copper.loadermods.net.GitHub;
import copper.loadermods.net.Json;
import copper.loadermods.util.Log;
import copper.loadermods.util.Strings;

import java.io.File;
import java.nio.file.*;
import java.util.*;

/**
 * The checks that can run without GitHub. Test code, not application code: this class is not in the jar
 * and not reachable from {@link Main}.
 *
 * <p>Two things are worth checking offline. First, that the loader really is the parser: every meta file
 * below is read by running the loader's own {@code Mod.loadMeta} and {@code MetaReaderV1}, so a mismatch
 * shows up here rather than as a wrong field in a published index. Second, that the pieces with their
 * own rules - release targeting, exclusions, and the index shape - behave as documented.</p>
 */
public final class SelfCheck {
    private static int failures;

    private SelfCheck(){}

    /** Entry point, run by the {@code selfcheck} Gradle task. */
    public static void main(String[] args) throws Exception {
        int exit = run();
        if(exit != 0) System.exit(exit);
    }

    /** @return 0 when every check passed, 1 otherwise */
    public static int run() throws Exception {
        loaderParsesAJsonMeta();
        loaderParsesTheModTemplate();
        loaderParsesHjson();
        loaderRejectsBrokenMetadata();
        loaderValidatesDependencyFilters();
        rateLimitsAreHandledAsDocumented();
        excludesMatchRepositories();
        indexRoundTrips();

        if(failures > 0){
            Log.error("selfcheck: %d check(s) failed", failures);
            return 1;
        }
        Log.info("selfcheck: all checks passed");
        return 0;
    }

    // ── the loader is the parser ────────────────────────────────────────────

    private static void loaderParsesAJsonMeta() throws Exception {
        ModMeta meta = parse("copper.mod.json", """
                {
                  "version": 1,
                  "meta": {
                    "id": "example:examplemod",
                    "name": "Example Mod",
                    "author": "Example",
                    "version": "1.2.3",
                    "main": "example.examplemod.ExampleMod",
                    "description": "A test mod.",
                    "repo": "example/examplemod",
                    "dependencies": { "mindustry": ">=159", "other:dep": ">=1.0.0" },
                    "conflicts": { "bad:mod": "*" },
                    "mixins": { "mindustry": "mixins/mindustry.json" },
                    "extra": { "subtitle": "Sub" }
                  }
                }
                """);

        check("id", "example:examplemod", meta.id());
        check("name", "Example Mod", meta.name());
        check("author", "Example", meta.author());
        check("version", "1.2.3", meta.version());
        check("main", "example.examplemod.ExampleMod", meta.main());
        check("repo", "example/examplemod", meta.repo());
        check("hidden defaults to false", "false", String.valueOf(meta.hidden()));
        check("format version", "1", String.valueOf(meta.formatVersion()));
        check("vanilla name", "copper-example-examplemod", meta.vanillaName());
        check("data folder", "example-examplemod", meta.mod().dataFolderName());
        check("game requirement is the filter verbatim", ">=159", meta.gameRequirement());
        check("loader requirement is absent", "null", String.valueOf(meta.loaderRequirement()));
        check("mixin target", "mixins/mindustry.json", meta.mod().mixins().get("mindustry"));
        check("extra subtitle", "Sub", String.valueOf(meta.mod().extraMeta().get("subtitle")));
    }

    /** The template shipped in this workspace must validate; it is the shape authors copy. */
    private static void loaderParsesTheModTemplate(){
        Path template = Path.of("..", "mod-templete").toAbsolutePath().normalize();
        if(!Files.isDirectory(template)){
            Log.warn("selfcheck: %s not found, skipping the template check", template);
            return;
        }
        try{
            ModMeta meta = IndexScanner.readLocal(template.toFile());
            check("template id", "example:examplemod", meta.id());
            check("template main", "example.examplemod.ExampleMod", meta.main());
            check("template mixin target", "mixins/mindustry.json", meta.mod().mixins().get("mindustry"));
            check("template format version", "1", String.valueOf(meta.formatVersion()));
            check("template states no game requirement", "null", String.valueOf(meta.gameRequirement()));
        }catch(Exception e){
            fail("the mod template did not validate: " + e.getMessage());
        }
    }

    private static void loaderParsesHjson() throws Exception {
        // comments, no quotes, no commas, no root braces: all legal HJSON, which the loader accepts
        ModMeta meta = parse("copper.mod.hjson", """
                # a Copper mod, HJSON style
                version: 1
                meta: {
                  id: hjson:example
                  name: HJSON Example
                  author: Example
                  version: 0.1
                  main: hjson.example.Main
                  description: Parsed from HJSON
                  hidden: true
                }
                """);

        check("hjson id", "hjson:example", meta.id());
        check("hjson version pads to semver", "0.1.0", meta.version());
        check("hjson hidden flag", "true", String.valueOf(meta.hidden()));
        check("hjson main", "hjson.example.Main", meta.main());
    }

    private static void loaderRejectsBrokenMetadata() throws Exception {
        expectRejected("copper.mod.json", """
                { "version": 1, "meta": { "id": "no-colon", "name": "x", "author": "x", "version": "1.0.0", "main": "no.colon.Main" } }
                """, "an id without a colon");

        expectRejected("copper.mod.json", """
                { "version": 1, "meta": { "id": "a:b", "name": "x", "author": "x", "version": "1.0.0", "main": "wrong.package.Main" } }
                """, "a main class outside the id's package");

        expectRejected("copper.mod.json", """
                { "version": 2, "meta": { "id": "a:b", "name": "x", "author": "x", "version": "1.0.0", "main": "a.b.Main" } }
                """, "an unsupported meta version");

        expectRejected("copper.mod.json", """
                { "version": 1, "meta": { "id": "mindustry:core", "name": "x", "author": "x", "version": "1.0.0", "main": "mindustry.core.Main" } }
                """, "a reserved id");

        expectRejected("copper.mod.json", """
                { "version": 1, "meta": { "id": "a:b", "name": "x", "author": "x", "version": "1.0.0" } }
                """, "a missing main class");
    }

    private static void loaderValidatesDependencyFilters() throws Exception {
        // both shapes the loader accepts: one semver expression, and a list of exact versions
        ModMeta meta = parse("copper.mod.json", """
                {
                  "version": 1,
                  "meta": {
                    "id": "deps:example",
                    "name": "Deps",
                    "author": "Example",
                    "version": "1.0.0",
                    "main": "deps.example.Main",
                    "dependencies": {
                      "other:mod": [ "3.2.1", "4.0.0" ],
                      "third:mod": ">=1.0.0 <2.0.0",
                      "loader": ">=0.2.0"
                    }
                  }
                }
                """);

        check("array dependency", "[3.2.1, 4.0.0]", filter(meta, "other:mod"));
        check("range dependency", ">=1.0.0 <2.0.0", filter(meta, "third:mod"));
        check("loader requirement", ">=0.2.0", meta.loaderRequirement());
    }

    private static String filter(ModMeta meta, String id){
        for(ModMeta.Relation relation : meta.dependencies()){
            if(relation.id().equals(id)) return relation.filterText();
        }
        return null;
    }

    // ── rate limits ─────────────────────────────────────────────────────────

    /**
     * The retry policy GitHub documents for 403/429, in its order of preference: {@code retry-after}
     * first, then waiting out a primary quota, else at least a minute for a secondary limit.
     */
    private static void rateLimitsAreHandledAsDocumented(){
        // retry-after wins even when the quota also looks exhausted
        check("retry-after is honoured", "30000",
                String.valueOf(GitHub.waitFor(30, true, 5, 1)));
        // a primary limit waits for the reset when that is soon
        check("a primary limit waits for the reset", "45000",
                String.valueOf(GitHub.waitFor(-1, true, 45, 1)));
        // a secondary limit with no retry-after backs off at least a minute, then doubles
        check("a secondary limit backs off a minute", "60000",
                String.valueOf(GitHub.waitFor(-1, false, -1, 1)));
        check("a repeated secondary limit backs off further", "120000",
                String.valueOf(GitHub.waitFor(-1, false, -1, 2)));
        check("the backoff is capped at the maximum wait", "120000",
                String.valueOf(GitHub.waitFor(-1, false, -1, 3)));
        // a wait longer than a scheduled run can afford is refused, so the caller fails instead
        check("a long retry-after is refused", "-1",
                String.valueOf(GitHub.waitFor(3600, false, -1, 1)));
        check("a distant reset is refused", "-1",
                String.valueOf(GitHub.waitFor(-1, true, 1800, 1)));
    }

    // ── exclusions ──────────────────────────────────────────────────────────

    private static void excludesMatchRepositories(){
        check("an exact exclude matches", "true", String.valueOf(Strings.matches("a/b", "a/b")));
        check("an exact exclude is not a prefix match", "false", String.valueOf(Strings.matches("a/b", "a/bc")));
        check("matching is case-insensitive", "true", String.valueOf(Strings.matches("A/B", "a/b")));
        check("a wildcard covers a name", "true", String.valueOf(Strings.matches("a/*template*", "a/mod-template")));
        check("a wildcard covers an owner", "true", String.valueOf(Strings.matches("*/*template", "someone/mod-template")));
        check("? is a single character", "true", String.valueOf(Strings.matches("a/mo?-template", "a/mod-template")));
        check("a dot is literal, not any character", "false", String.valueOf(Strings.matches("a/b.c", "a/bxc")));
        check("no match", "false", String.valueOf(Strings.matches("a/*template*", "a/realmod")));

        check("the default excludes cover the Copper template", "true",
                String.valueOf(UpdateCommand.matchesAny(
                        List.of("mdtcopper/mod-template", "anuken/examplemod"), "mdtcopper/mod-template")));

        // regression: JSON booleans are not numbers, so reading a flag through Json.number() would
        // answer false for a repo that really is a template
        check("a JSON boolean reads as true", "true",
                String.valueOf(copper.loadermods.net.Json.bool(true, false)));
        check("a JSON boolean reads as false", "false",
                String.valueOf(copper.loadermods.net.Json.bool(false, true)));
        check("a number is not mistaken for a boolean", "false",
                String.valueOf(copper.loadermods.net.Json.bool(1.0, false)));
        check("a missing flag falls back", "false",
                String.valueOf(copper.loadermods.net.Json.bool(null, false)));
    }

    // ── the index shape ─────────────────────────────────────────────────────

    private static void indexRoundTrips(){
        ModEntry entry = new ModEntry(
                "example/examplemod", "example:examplemod", "Example Mod", "Example", "A test mod.",
                "1.2.3", "2026-01-01T00:00:00Z", 7, ">=159", ">=0.2.0", "ABCDEF");

        String json = ModIndex.toJson(new ModIndex.Document("2026-01-01T00:00:00Z", "810afee77f", List.of(entry)));

        Map<String, Object> parsed = Json.objectOf(json);
        check("format version", "1", String.valueOf(Json.intValue(parsed.get("formatVersion"), -1)));
        check("loader version", "810afee77f", String.valueOf(parsed.get("loaderVersion")));
        check("previous repos are recoverable", "[example/examplemod]",
                ModIndex.previousRepos(json).toString());
        check("a MindustryMods-shaped file is accepted too", "[a/b]",
                ModIndex.previousRepos("[{\"repo\":\"a/b\"}]").toString());
        check("a corrupt file yields no repos", "[]",
                ModIndex.previousRepos("not json at all").toString());

        // the entry shape is the contract with clients: nothing extra may creep back in
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mods = (List<Map<String, Object>>)parsed.get("mods");
        check("mod entry fields", "[repo, id, vanillaName, name, author, description, version, "
                        + "lastUpdated, stars, gameRequirement, loaderRequirement, iconHash]",
                new ArrayList<>(mods.get(0).keySet()).toString());
        // no release data and no game version: resolving a download is the client's job, as in MindustryMods
        check("no release is published", "null", String.valueOf(mods.get(0).get("release")));
        check("no download url is published", "null", String.valueOf(mods.get(0).get("downloadUrl")));
        check("no game version is published", "null", String.valueOf(parsed.get("gameVersion")));
        check("no dependencies are published", "null", String.valueOf(mods.get(0).get("dependencies")));
        check("no hidden flag is published", "null", String.valueOf(mods.get(0).get("hidden")));
        // the icon file name is part of the contract: a client guesses it from the repository
        check("icon file name", "example_examplemod.png", Strings.iconFileName("example/ExampleMod"));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Parses meta text in memory, exactly as a repository scan does. */
    private static ModMeta parse(String name, String text) throws Exception {
        return IndexScanner.parse(text, name, "selfcheck/" + name);
    }

    private static void expectRejected(String name, String text, String description){
        try{
            ModMeta meta = parse(name, text);
            fail("the loader accepted " + description + " (got " + meta.id() + ")");
        }catch(Exception expected){
            // the message comes from the loader; only the rejection itself is asserted
            Log.debug("rejected %s: %s", description, expected.getMessage());
        }
    }

    private static void check(String what, String expected, String actual){
        if(Objects.equals(expected, actual)) return;
        fail(what + ": expected <" + expected + "> but was <" + actual + ">");
    }

    private static void fail(String message){
        failures++;
        Log.error("selfcheck: %s", message);
    }

    /** Kept for callers that want to validate a single file from a test. */
    static ModMeta validate(File file) throws Exception {
        return IndexScanner.readLocal(file);
    }
}
