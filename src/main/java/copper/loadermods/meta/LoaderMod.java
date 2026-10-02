package copper.loadermods.meta;

import copper.loader.mod.*;
import copper.loader.util.Jval;
import copper.loadermods.util.Strings;

import java.util.*;

/**
 * The loader's parsed {@link Mod}, in an immutable form.
 *
 * <p>Every field is read straight off the object {@code copper.loader.mod.Mod} produced, so the values
 * are the loader's, not a second reading of the file. {@link ModMeta} carries the two things that
 * object does not keep: the meta format version and the verbatim version-filter text, which the index
 * publishes so a client can show a mod's requirements without re-implementing the filter grammar.</p>
 */
public record LoaderMod(
        String id,
        String name,
        String author,
        String description,
        String version,
        String main,
        String repo,
        boolean hidden,
        Map<String, String> mixins,
        List<String> exports,
        Map<String, Object> extraMeta
) {
    /** Copies a loader mod into an immutable record. */
    public static LoaderMod of(Mod mod){
        Map<String, String> mixins = new LinkedHashMap<>();
        for(MixinDescriptor descriptor : mod.mixins){
            mixins.put(descriptor.id, descriptor.configPath);
        }

        return new LoaderMod(
                mod.id,
                mod.name,
                mod.author,
                mod.description,
                mod.version == null ? "" : mod.version.toString(),
                mod.main,
                mod.repo,
                mod.hidden,
                mixins,
                List.copyOf(mod.exportRules),
                parseExtra(mod.extraMeta));
    }

    /** The name this mod gets in the vanilla mod registry, and the prefix of all its content names. */
    public String vanillaName(){
        return "copper-" + Strings.folderName(id);
    }

    /** The mod's data folder name: the vanilla name without the {@code copper-} prefix. */
    public String dataFolderName(){
        return Strings.folderName(id);
    }

    /** @return the {@code extra} object, which the game's ModMeta receives */
    private static Map<String, Object> parseExtra(String extraMeta){
        if(extraMeta == null || extraMeta.isBlank() || extraMeta.equals("{}")) return new LinkedHashMap<>();
        try{
            Jval parsed = Jval.read(extraMeta);
            if(parsed != null && parsed.isObject()) return ModMeta.toPlainMap(parsed.asObject());
        }catch(Throwable ignored){
            // extra is a pass-through field; a broken one must not hide an otherwise valid mod
        }
        return new LinkedHashMap<>();
    }
}
