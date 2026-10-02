package copper.loadermods.index;

import copper.loadermods.util.Strings;

import java.util.*;

/**
 * One entry of {@code mods.json}: the basic metadata, in the shape {@code Anuken/MindustryMods} uses,
 * plus the two things a Copper mod needs on top of it.
 *
 * <p>What is deliberately not here:</p>
 * <ul>
 *   <li><b>Any release information.</b> The index lists mods, it does not resolve downloads. A client
 *       asks GitHub for {@code /repos/<repo>/releases} itself and picks the build it wants - the same
 *       split {@code Anuken/MindustryMods} uses. This is also what keeps a scan's API usage at zero
 *       requests per mod.</li>
 *   <li><b>Dependencies, conflicts and mixin counts.</b> The loader resolves those at install time and
 *       rejects a mod itself; a browser does not act on them.</li>
 * </ul>
 *
 * <p>Hidden mods are listed like any other. {@code hidden} means the mod adds no game content - it is a
 * plugin, in the loader's terms - not that it is unlisted. The flag itself is not published: whether a mod
 * registers content is something the loader decides at install time, and a client that cares can read it
 * from the mod's own metadata.</p>
 *
 * @param version        the mod version declared in the repository's {@code copper.mod.json}
 * @param gameRequirement the game version range the mod supports, as a Copper version filter written
 *                       verbatim from the mod's {@code mindustry} dependency (for example
 *                       {@code ">=159"}), meant to be evaluated with the loader's own
 *                       {@code SemanticVersionFilter}. Empty when the mod states no requirement, which
 *                       means "any version".
 * @param loaderRequirement the same thing for the {@code loader} dependency, which Copper versions
 *                       separately from the game
 */
public record ModEntry(
        String repo,
        String id,
        String name,
        String author,
        String description,
        String version,
        String lastUpdated,
        int stars,
        String gameRequirement,
        String loaderRequirement,
        String iconHash
) {
    /** The name this mod carries in the game's mod registry. */
    public String vanillaName(){
        return "copper-" + Strings.folderName(id);
    }

    /** A stable sort key: mod id, lowercased, so the file diffs cleanly between runs. */
    public String sortKey(){
        return id == null ? "" : id.toLowerCase(Locale.ROOT);
    }
}
