package copper.loadermods.index;

import copper.loadermods.net.Json;

import java.util.*;

/**
 * The shape of {@code mods.json}: a header plus the mod list.
 *
 * <p>Writing a header rather than a bare array is the one place this deviates from
 * {@code Anuken/MindustryMods}. That file is a top-level array, which leaves nowhere to say which loader
 * version the index was built against or when it was generated - and for Copper both matter, because
 * the loader is versioned separately from the game and clients gate mods on it.</p>
 */
public final class ModIndex {
    /** The format version of {@code mods.json} itself, so clients can detect a shape change. */
    public static final int FORMAT_VERSION = 1;

    private ModIndex(){}

    /**
     * @param generatedAt when the scan ran, ISO-8601
     * @param loaderVersion the loader release whose meta format was used to parse the mods
     * @param mods        the entries, sorted by mod id
     */
    public record Document(String generatedAt, String loaderVersion, List<ModEntry> mods){}

    /** Renders the document as pretty-printed JSON with a stable field order. */
    public static String toJson(Document document){
        List<Object> mods = new ArrayList<>();
        for(ModEntry mod : document.mods()) mods.add(toMap(mod));

        Json.Obj root = Json.object()
                .put("formatVersion", Json.intValue(FORMAT_VERSION, FORMAT_VERSION))
                .put("generatedAt", document.generatedAt())
                .putIfPresent("loaderVersion", document.loaderVersion())
                .put("mods", mods);
        return Json.pretty(root.map()) + "\n";
    }

    private static Map<String, Object> toMap(ModEntry mod){
        return Json.object()
                .put("repo", mod.repo())
                .put("id", mod.id())
                .put("vanillaName", mod.vanillaName())
                .put("name", mod.name())
                .put("author", mod.author())
                .putIfPresent("description", mod.description())
                .put("version", mod.version())
                .putIfPresent("lastUpdated", mod.lastUpdated())
                .put("stars", mod.stars())
                .putIfPresent("gameRequirement", mod.gameRequirement())
                .putIfPresent("loaderRequirement", mod.loaderRequirement())
                .putIfPresent("iconHash", mod.iconHash())
                .map();
    }

    /** Reads the repository names of a previously written index, in either format. */
    public static List<String> previousRepos(String json){
        List<String> repos = new ArrayList<>();
        try{
            Object parsed = Json.parse(json);
            List<?> mods;
            if(parsed instanceof Map<?, ?> root){
                // the current shape, or a MindustryMods-style file wrapped by hand
                Object value = root.containsKey("mods") ? root.get("mods") : null;
                mods = value instanceof List<?> list ? list : List.of();
            }else if(parsed instanceof List<?> list){
                // Anuken/MindustryMods' shape, which this tool also accepts so a migration needs no step
                mods = list;
            }else{
                return repos;
            }
            for(Object item : mods){
                if(item instanceof Map<?, ?> mod && mod.get("repo") instanceof String repo){
                    repos.add(repo.toLowerCase(Locale.ROOT));
                }
            }
        }catch(RuntimeException e){
            // a corrupt previous file must not stop a scan; the next write replaces it
            return repos;
        }
        return repos;
    }
}
