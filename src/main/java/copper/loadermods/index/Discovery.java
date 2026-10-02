package copper.loadermods.index;

import copper.loadermods.net.GitHub;
import copper.loadermods.net.Json;
import copper.loadermods.util.Log;

import java.io.IOException;
import java.util.*;

/**
 * Finds candidate repositories: the GitHub topic, and nothing else.
 *
 * <p>One source keeps discovery predictable and keeps the index's meaning simple - being listed means
 * carrying the topic. Code search for {@code copper.mod.json} was tried and dropped: it needs its own
 * tightly limited quota, it returns one hit per file rather than one per repository, and it would list
 * mods whose authors never opted in.</p>
 */
public final class Discovery {
    private Discovery(){}

    /**
     * Repositories carrying the topic.
     *
     * <p>The search response already contains every repository field the scan needs, so it is kept and
     * handed to the scanner. That is the difference between one API call per repository and none: a scan
     * of a thousand mods would otherwise spend a thousand requests re-reading what it was just told.</p>
     *
     * @param maxPages search pages to read; the API caps a query at 1000 results anyway
     * @return the discovered repositories and their metadata, keyed by lower-cased {@code owner/name}
     */
    public static Found byTopic(GitHub github, String topic, int maxPages, int perPage)
            throws IOException, InterruptedException {
        List<Object> items = github.search("/search/repositories",
                "topic:" + topic + " archived:false", maxPages, perPage);

        Map<String, Map<String, Object>> metadata = new LinkedHashMap<>();
        for(Object item : items){
            if(!(item instanceof Map<?, ?> repository)) continue;
            String fullName = Json.string(repository.get("full_name"), "");
            if(fullName.isEmpty()) continue;
            metadata.put(fullName.toLowerCase(Locale.ROOT), toPlainMap(repository));
        }
        Log.info("topic '%s': %d repository(ies)", topic, metadata.size());
        return new Found(new ArrayList<>(metadata.keySet()), metadata);
    }

    /** The repositories a search turned up, plus the metadata that came with them. */
    public record Found(List<String> repos, Map<String, Map<String, Object>> metadata){
        static Found empty(){
            return new Found(List.of(), Map.of());
        }
    }

    /** Converts a parsed search item into plain values, so it can be handed around as repository metadata. */
    private static Map<String, Object> toPlainMap(Map<?, ?> repository){
        Map<String, Object> plain = new LinkedHashMap<>();
        for(Map.Entry<?, ?> entry : repository.entrySet()){
            plain.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return plain;
    }
}
