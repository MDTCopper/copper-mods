package copper.loadermods.index;

import copper.loadermods.meta.*;
import copper.loadermods.net.GitHub;
import copper.loadermods.net.Json;
import copper.loadermods.util.Log;
import copper.loadermods.util.Strings;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Scans one repository into a {@link ModEntry}.
 *
 * <p>The order of checks is the one that costs the least when a repository turns out not to be a mod:
 * archive state and a meta file first, the loader's parse next, opt-outs and releases last.</p>
 */
public final class IndexScanner {
    /** Candidate meta files, the loader's own order: the loader reads {@code .json} first. */
    public static final List<String> META_FILES = List.of("copper.mod.json", "copper.mod.hjson");

    /**
     * Where a mod's icon is looked for, in order, from the repository root only.
     *
     * <p>Never from a release asset: every candidate is fetched from
     * {@code raw.githubusercontent.com/<repo>/<branch>/<candidate>}, so an icon costs raw requests and no
     * release download. {@code icon.png} wins because that is what the mod template builds.</p>
     */
    public static final List<String> ICON_FILES = List.of("icon.png", "assets/icon.png");

    /** Why a repository is not in the index. */
    public enum Skip {
        ARCHIVED, TEMPLATE, NO_META, INVALID_META, OPT_OUT, DUPLICATE, EXCLUDED, ERROR
    }

    /**
     * @param entry the index entry, or {@code null} when the repository was skipped
     * @param icon  the icon bytes, or {@code null} when the repository has no icon
     * @param skip  why it was skipped, or {@code null} when it was not
     * @param detail extra context for the log, or {@code null}
     */
    public record Scanned(String repo, ModEntry entry, byte[] icon, Skip skip, String detail){
        static Scanned skipped(String repo, Skip skip, String detail){
            return new Scanned(repo, null, null, skip, detail);
        }
    }

    /** Everything a scan needs besides the repository itself. */
    public record Config(int minStars, boolean keepTemplates){
        public static Config defaults(){
            return new Config(0, false);
        }
    }

    private IndexScanner(){}

    /**
     * Scans one repository. Never throws for a repository that is merely not a mod - that is a skip.
     *
     * @param repository the {@code owner/name}, lowercased
     * @param knownRepo  the repository metadata a search already returned, or {@code null} to fetch it
     */
    public static Scanned scan(GitHub github, String repository, Map<String, Object> knownRepo, Config config){
        try{
            // a repository that discovery already described costs no request at all; only the ones that
            // came from the previous index are looked up
            Map<String, Object> repo = knownRepo != null ? knownRepo : github.repo(repository);
            if(repo == null) return Scanned.skipped(repository, Skip.NO_META, "repository is not visible");
            if(Json.bool(repo.get("archived"), false)){
                return Scanned.skipped(repository, Skip.ARCHIVED, null);
            }
            // a template has a valid meta file but is not a mod anyone should install
            if(!config.keepTemplates() && Json.bool(repo.get("is_template"), false)){
                return Scanned.skipped(repository, Skip.TEMPLATE, "marked as a template repository");
            }

            String branch = Json.string(repo.get("default_branch"), "main");
            String stars = String.valueOf((long)Json.number(repo.get("stargazers_count"), 0));

            // the loader finds the meta at the mod's root, so that is the only place worth looking
            String metaText = null;
            String metaName = null;
            for(String candidate : META_FILES){
                metaText = github.rawText(repository, branch, candidate);
                if(metaText != null){
                    metaName = candidate;
                    break;
                }
            }
            if(metaText == null) return Scanned.skipped(repository, Skip.NO_META, null);

            ModMeta meta;
            try{
                meta = parse(metaText, metaName, repository + "@" + branch);
            }catch(ModMeta.MetaException e){
                return Scanned.skipped(repository, Skip.INVALID_META, e.getMessage());
            }

            if(optedOut(meta)){
                return Scanned.skipped(repository, Skip.OPT_OUT, meta.id());
            }

            byte[] icon = null;
            if((long)Json.number(repo.get("stargazers_count"), 0) >= config.minStars()){
                icon = readIcon(github, repository, branch);
            }

            ModEntry entry = new ModEntry(
                    repository,
                    meta.id(),
                    meta.name(),
                    meta.author(),
                    meta.description(),
                    meta.version(),
                    Json.string(repo.get("pushed_at"), ""),
                    (int)(long)Json.number(repo.get("stargazers_count"), 0),
                    meta.gameRequirement(),
                    meta.loaderRequirement(),
                    icon == null ? null : Strings.sha256(icon));

            Log.debug("%s -> %s (%s stars)", repository, meta.id(), stars);
            return new Scanned(repository, entry, icon, null, null);
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
            return Scanned.skipped(repository, Skip.ERROR, "interrupted");
        }catch(Exception e){
            if(Log.isVerbose()) e.printStackTrace();
            return Scanned.skipped(repository, Skip.ERROR, e.getMessage());
        }
    }

    /**
     * Runs the loader's parser over a meta file's text, with no temporary file involved.
     *
     * @param metaText the meta file's contents
     * @param fileName the name it has inside a mod, so the loader finds it
     * @param source   what to name in error messages
     */
    public static ModMeta parse(String metaText, String fileName, String source) throws IOException {
        return LoaderBridge.read(source, Map.of(fileName, metaText));
    }

    /**
     * Whether a mod asked not to be listed.
     *
     * <p>{@code hidden} is a loader feature - it means "adds no content" - so the opt-out lives in the
     * {@code extra} pass-through object, which the game and the loader both ignore.</p>
     */
    private static boolean optedOut(ModMeta meta){
        Object browser = meta.mod().extraMeta().get("browser");
        if(browser == null) return false;
        return "false".equalsIgnoreCase(String.valueOf(browser)) || Boolean.FALSE.equals(browser);
    }

    private static byte[] readIcon(GitHub github, String repository, String branch){
        for(String candidate : ICON_FILES){
            try{
                byte[] bytes = github.rawBytes(repository, branch, candidate);
                if(bytes != null && bytes.length > 0) return bytes;
            }catch(InterruptedException e){
                Thread.currentThread().interrupt();
                return null;
            }catch(IOException e){
                Log.debug("icon %s/%s: %s", repository, candidate, e.getMessage());
            }
        }
        return null;
    }

    /** Reads a local mod file, for {@code validate}. */
    public static ModMeta readLocal(File file) throws IOException {
        return LoaderBridge.read(file);
    }
}
