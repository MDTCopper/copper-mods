package copper.loadermods.meta;

import copper.loader.mod.MetaMod;
import copper.loader.mod.*;
import copper.loader.util.Jval;

import java.util.*;

/**
 * A Copper mod's metadata as the index sees it: what the loader parsed, plus the two things the
 * loader's {@link Mod} object does not keep.
 *
 * <p>{@code formatVersion} and the verbatim filter text have to be re-read from the document, because
 * {@code MetaReaderV1} consumes them while parsing and {@code Mod} stores only the resulting
 * {@link IVersionFilter} objects. Everything else - and every validation - is the loader's own.</p>
 *
 * @param formatVersion the top-level {@code version} field
 * @param mod           the loader's parsed mod
 * @param dependencies  declared dependencies, in file order, with their filter text
 * @param conflicts     declared conflicts, in file order, with their filter text
 */
public record ModMeta(
        int formatVersion,
        LoaderMod mod,
        List<Relation> dependencies,
        List<Relation> conflicts
) {
    /** The newest meta format this tool understands. */
    public static final int SUPPORTED_FORMAT_VERSION = 1;

    /** A dependency or conflict entry, mirroring the loader's {@code RelationDescriptor}. */
    public record Relation(String id, String version){
        /** The filter text to publish, or {@code *} when the file stated no requirement. */
        public String filterText(){
            return version == null || version.isBlank() ? "*" : version;
        }
    }

    public String id(){
        return mod.id();
    }

    public String name(){
        return mod.name();
    }

    public String author(){
        return mod.author();
    }

    public String description(){
        return mod.description();
    }

    public String version(){
        return mod.version();
    }

    public String main(){
        return mod.main();
    }

    public String repo(){
        return mod.repo();
    }

    public boolean hidden(){
        return mod.hidden();
    }

    /** The name this mod gets in the vanilla mod registry. */
    public String vanillaName(){
        return mod.vanillaName();
    }

    /** The loader version requirement, from the {@code loader} dependency. */
    public String loaderRequirement(){
        return filterOf(dependencies, "loader");
    }

    /**
     * The game version range the mod accepts, from the {@code mindustry} dependency, written verbatim as
     * a Copper version filter.
     *
     * @return the filter text, or {@code null} when the mod states no requirement
     */
    public String gameRequirement(){
        return filterOf(dependencies, "mindustry");
    }

    private static String filterOf(List<Relation> relations, String id){
        for(Relation relation : relations){
            if(relation.id().equals(id)) return relation.filterText();
        }
        return null;
    }

    /**
     * Builds the index view of a mod the loader already parsed.
     *
     * @param mod the loader's parsed mod
     * @throws MetaException if the document's format version is not supported
     */
    public static ModMeta of(MetaMod mod){
        Jval document = mod.rawDocument();
        if(document == null) throw new MetaException("no copper.mod.json or copper.mod.hjson in " + mod.file.getName());

        int formatVersion = document.getInt("version", 0);
        if(formatVersion <= 0 || formatVersion > SUPPORTED_FORMAT_VERSION){
            throw new MetaException("meta version is not supported: " + formatVersion);
        }

        Jval meta = document.get("meta");
        return new ModMeta(
                formatVersion,
                LoaderMod.of(mod),
                relations(meta, "dependencies"),
                relations(meta, "conflicts"));
    }

    /**
     * Reads the filter text of a {@code dependencies}/{@code conflicts} object.
     *
     * <p>Only the text is taken; whether it is a usable filter was already decided by
     * {@code MetaReaderV1}, which built the loader's own filter objects from the same values.</p>
     */
    private static List<Relation> relations(Jval meta, String field){
        List<Relation> result = new ArrayList<>();
        if(meta == null) return result;

        Jval value = meta.get(field);
        if(value == null || !value.isObject()) return result;

        for(Map.Entry<String, Jval> entry : value.asObject().entrySet()){
            Jval filter = entry.getValue();
            if(filter.isString()){
                result.add(new Relation(entry.getKey().trim(), filter.asString()));
            }else if(filter.isArray()){
                StringBuilder text = new StringBuilder("[");
                for(Jval item : filter.asArray()){
                    if(text.length() > 1) text.append(", ");
                    text.append(item.asString().trim());
                }
                result.add(new Relation(entry.getKey().trim(), text.append(']').toString()));
            }
        }
        return result;
    }

    /** Converts a parsed document subtree into plain Java values, for output. */
    static Map<String, Object> toPlainMap(Map<?, ?> map){
        Map<String, Object> result = new LinkedHashMap<>();
        for(Map.Entry<?, ?> entry : map.entrySet()){
            result.put(String.valueOf(entry.getKey()), toPlain(entry.getValue()));
        }
        return result;
    }

    private static Object toPlain(Object value){
        if(value == null) return null;
        if(value instanceof Jval jval){
            return switch(jval.getType()){
                case object -> toPlainMap(jval.asObject());
                case array -> {
                    List<Object> list = new ArrayList<>();
                    for(Jval item : jval.asArray()) list.add(toPlain(item));
                    yield list;
                }
                case string -> jval.asString();
                case bool -> jval.asBool();
                case number -> jval.toString();
                case nil -> null;
            };
        }
        if(value instanceof Map<?, ?> map) return toPlainMap(map);
        if(value instanceof List<?> list){
            List<Object> plain = new ArrayList<>();
            for(Object item : list) plain.add(toPlain(item));
            return plain;
        }
        return value;
    }

    /** Thrown when a meta file is missing a field or violates a loader rule. */
    public static class MetaException extends RuntimeException {
        public MetaException(String message){
            super(message);
        }
    }
}
