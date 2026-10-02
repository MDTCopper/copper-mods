package copper.loadermods.cli;

import copper.loadermods.index.*;
import copper.loadermods.net.GitHub;
import copper.loadermods.net.Json;
import copper.loadermods.util.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * {@code update}: discover Copper mods on GitHub and rewrite {@code mods.json} and {@code icons/}.
 *
 * <p>The scan is additive on purpose. Repositories that the search no longer returns - because GitHub
 * search is fuzzy, or because a topic was removed by accident - keep their previous entry unless
 * {@code --keep-missing} says otherwise, so one bad crawl cannot empty the index.</p>
 */
public final class UpdateCommand {
    /**
     * GitHub's search API returns at most 1000 results per query and at most 100 per page, so ten pages
     * is every result there is - a larger {@code --max-pages} cannot reach more. That cap is also why the
     * previous index is carried over: repositories that fall out of the search are still known.
     */
    private static final int PER_PAGE = 100;
    private static final int MAX_PAGES = 10;
    /** Below this the search would miss results for no reason. */
    private static final int MIN_PAGES = 1;
    /** The topic a repository must carry to be listed. */
    public static final String DEFAULT_TOPIC = "mindustry-copper-mod";

    /**
     * Repositories that are not mods anyone should install.
     *
     * <p>{@code is_template} on GitHub is the reliable signal, but it is opt-in and most template
     * repositories never set it - the Copper mod template among them - so the known ones are also listed
     * here. Patterns may use {@code *} and {@code ?}, and are matched against {@code owner/repo}.</p>
     */
    private static final List<String> DEFAULT_EXCLUDES = List.of(
            "mdtcopper/mod-template",
            "mdtcopper/mod-templete",
            "mdtcopper/template",
            "anuken/examplemod",
            "anuken/examplejavamod",
            "anuken/examplekotlinmod");

    private UpdateCommand(){}

    public static void run(String[] argv) throws Exception {
        Args args = Args.parse(argv, "out", "topic", "max-pages", "min-stars",
                "keep-missing", "exclude", "token", "threads", "verbose", "allow-empty", "keep-templates");
        if(args.has("verbose")) Log.setVerbose(true);

        Path out = Paths.get(args.get("out", ".")).toAbsolutePath().normalize();
        String topic = args.get("topic", DEFAULT_TOPIC);
        int maxPages = pageLimit(args.getInt("max-pages", MAX_PAGES));
        boolean keepMissing = args.has("keep-missing");
        boolean keepTemplates = args.has("keep-templates");
        int minStars = args.getInt("min-stars", 0);
        int threads = Math.max(1, args.getInt("threads", 8));
        String token = args.get("token", System.getenv("GITHUB_TOKEN"));

        List<String> excluded = new ArrayList<>(DEFAULT_EXCLUDES);
        for(String repo : args.positionals()) excluded.add(repo.toLowerCase(Locale.ROOT));

        GitHub github = new GitHub(token);

        Log.info("loader-mods: %s -> %s%s", topic, out,
                token == null ? " (anonymous; set GITHUB_TOKEN for more quota)" : "");

        Path indexPath = out.resolve("mods.json");
        List<String> previous = readPrevious(indexPath);

        Set<String> repos = new LinkedHashSet<>();
        Discovery.Found topicFound = Discovery.byTopic(github, topic, maxPages, PER_PAGE);
        repos.addAll(topicFound.repos());
        int discovered = repos.size();
        if(discovered == 0 && repos.isEmpty()){
            // an empty index is a valid outcome only when it is deliberate: overwriting a published
            // mods.json with an empty one because a search failed is much worse than failing loudly
            if(previous.isEmpty() && !args.has("allow-empty")){
                throw new IOException("discovery found no repositories at all for topic '" + topic
                        + "'; refusing to write an empty index (pass --allow-empty to override)");
            }
            Log.warn("discovery returned nothing; keeping the previous entries");
        }

        for(String repo : previous){
            if(keepMissing) repos.add(repo);
        }

        // template repositories and explicit excludes, before anything is fetched for them
        List<String> dropped = new ArrayList<>();
        for(String repo : new ArrayList<>(repos)){
            if(matchesAny(excluded, repo)){
                repos.remove(repo);
                dropped.add(repo);
            }
        }
        if(!dropped.isEmpty()){
            Log.info("excluded %d repository(ies): %s", dropped.size(), String.join(", ", dropped));
        }

        Log.info("scanning %d repository(ies) with %d thread(s)", repos.size(), threads);

        IndexScanner.Config config = new IndexScanner.Config(minStars, keepTemplates);
        List<IndexScanner.Scanned> scanned = scanAll(github, repos, topicFound.metadata(), config, threads);

        List<ModEntry> entries = assemble(scanned, indexPath, out);
        String json = ModIndex.toJson(new ModIndex.Document(
                Strings.iso8601(new Date()),
                loaderVersion(),
                entries));

        Files.createDirectories(out);
        Files.writeString(indexPath, json, StandardCharsets.UTF_8);

        Log.info("wrote %s: %d mod(s), %d request(s), API quota left: %s",
                indexPath, entries.size(), github.requestCount(), github.quotaSummary());
        reportSkipped(scanned);
    }

    // ── scanning ────────────────────────────────────────────────────────────

    private static List<IndexScanner.Scanned> scanAll(GitHub github, Collection<String> repos,
                                                     Map<String, Map<String, Object>> knownRepos,
                                                     IndexScanner.Config config, int threads) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try{
            List<Future<IndexScanner.Scanned>> futures = new ArrayList<>();
            for(String repo : repos){
                Map<String, Object> known = knownRepos.get(repo);
                futures.add(executor.submit(() -> IndexScanner.scan(github, repo, known, config)));
            }

            List<IndexScanner.Scanned> results = new ArrayList<>();
            for(Future<IndexScanner.Scanned> future : futures){
                try{
                    results.add(future.get());
                }catch(ExecutionException e){
                    Log.error("scan task failed: %s", e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
                }
            }
            return results;
        }finally{
            executor.shutdownNow();
        }
    }

    /**
     * Turns scan results into the final list: drops duplicates, carries over entries whose repository is
     * no longer discoverable, writes icons, and sorts by mod id.
     */
    private static List<ModEntry> assemble(List<IndexScanner.Scanned> scanned, Path indexPath, Path out) throws IOException {
        Map<String, ModEntry> byId = new LinkedHashMap<>();
        Map<String, byte[]> icons = new LinkedHashMap<>();
        Set<String> seenRepos = new HashSet<>();

        for(IndexScanner.Scanned result : scanned){
            if(result.entry() == null) continue;
            ModEntry entry = result.entry();
            if(!seenRepos.add(entry.repo())) continue;

            ModEntry existing = byId.get(entry.id());
            if(existing != null){
                // two repositories publishing the same mod id: the loader would refuse both, so keep one
                ModEntry winner = entry.stars() >= existing.stars() ? entry : existing;
                ModEntry loser = winner == entry ? existing : entry;
                Log.warn("mod id %s is published by both %s and %s; keeping %s",
                        entry.id(), existing.repo(), entry.repo(), winner.repo());
                byId.put(entry.id(), winner);
                if(loser.iconHash() != null) icons.remove(loser.repo());
            }else{
                byId.put(entry.id(), entry);
            }
            if(result.icon() != null) icons.put(entry.repo(), result.icon());
        }

        List<ModEntry> entries = new ArrayList<>(byId.values());
        entries.sort(Comparator.comparing(ModEntry::sortKey));
        writeIcons(out, entries, icons);
        return entries;
    }

    /**
     * Publishes the icons of the mods that are in the index.
     *
     * <p>Icons are not cached between runs, so every mod's icon is fetched again each scan; a repository
     * that could not be read this time has no icon in {@code icons} and its file is removed. That keeps
     * {@code icons/} exactly in step with {@code mods.json} - no stale file survives a delisted mod - at
     * the cost of an icon disappearing if one fetch fails.</p>
     */
    private static void writeIcons(Path out, List<ModEntry> entries, Map<String, byte[]> icons) throws IOException {
        Path iconDir = out.resolve("icons");
        Files.createDirectories(iconDir);

        Set<String> expected = new HashSet<>();
        for(ModEntry entry : entries){
            byte[] bytes = icons.get(entry.repo());
            if(bytes == null || entry.iconHash() == null) continue;
            if(!isPng(bytes)){
                Log.warn("%s: icon is not a PNG, ignoring it", entry.repo());
                continue;
            }
            String fileName = Strings.iconFileName(entry.repo());
            expected.add(fileName);
            Files.write(iconDir.resolve(fileName), bytes);
        }

        try(DirectoryStream<Path> stream = Files.newDirectoryStream(iconDir)){
            for(Path file : stream){
                String name = file.getFileName().toString();
                if(!expected.contains(name) && !Files.isDirectory(file)){
                    Files.deleteIfExists(file);
                }
            }
        }
        Log.info("wrote %d icon(s) to %s", expected.size(), iconDir);
    }

    private static boolean isPng(byte[] bytes){
        return bytes.length > 8
                && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
    }

    // ── inputs ──────────────────────────────────────────────────────────────

    private static List<String> readPrevious(Path indexPath){
        if(!Files.isRegularFile(indexPath)) return List.of();
        try{
            List<String> repos = ModIndex.previousRepos(Files.readString(indexPath, StandardCharsets.UTF_8));
            Log.info("previous index lists %d repository(ies)", repos.size());
            return repos;
        }catch(IOException e){
            Log.warn("could not read %s: %s", indexPath, e.getMessage());
            return List.of();
        }
    }

    /** Whether a repository matches any of the exclude patterns. */
    public static boolean matchesAny(Collection<String> patterns, String repo){
        for(String pattern : patterns){
            if(Strings.matches(pattern, repo)) return true;
        }
        return false;
    }

    /**
     * Clamps a page count to what the search API can actually serve.
     *
     * @return a value between {@value #MIN_PAGES} and {@value #MAX_PAGES}
     */
    public static int pageLimit(int requested){
        return Math.max(MIN_PAGES, Math.min(MAX_PAGES, requested));
    }

    /** The loader release this tool was built against, recorded in the index for clients. */
    private static String loaderVersion(){
        try(java.io.InputStream in = UpdateCommand.class.getResourceAsStream("/loader-mods.properties")){
            if(in == null) return null;
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("loaderVersion", "").trim();
            return version.isEmpty() ? null : version;
        }catch(IOException e){
            return null;
        }
    }

    private static void reportSkipped(List<IndexScanner.Scanned> scanned){
        Map<IndexScanner.Skip, Integer> counts = new EnumMap<>(IndexScanner.Skip.class);
        for(IndexScanner.Scanned result : scanned){
            if(result.skip() != null) counts.merge(result.skip(), 1, Integer::sum);
        }
        if(counts.isEmpty()) return;

        StringBuilder summary = new StringBuilder();
        for(Map.Entry<IndexScanner.Skip, Integer> entry : counts.entrySet()){
            if(summary.length() > 0) summary.append(", ");
            summary.append(entry.getKey().name().toLowerCase(Locale.ROOT)).append(' ').append(entry.getValue());
        }
        Log.info("skipped: %s", summary);
    }
}
