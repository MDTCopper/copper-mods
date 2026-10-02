package copper.loadermods.meta;

import copper.loader.mod.MetaMod;
import copper.loadermods.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The one entry point for reading a Copper mod's metadata.
 *
 * <p>The work is done by {@link MetaMod}, which runs the loader's own parser. This class only adapts its
 * result into the index's own view ({@link ModMeta}) and turns the loader's failure modes into the two
 * outcomes a caller cares about: "this file is not a Copper mod" and "this mod is broken".</p>
 */
public final class LoaderBridge {
    private LoaderBridge(){}

    /**
     * Reads a mod's metadata.
     *
     * @param file a mod jar/zip, or a mod directory
     * @throws ModMeta.MetaException when the loader rejects the metadata
     * @throws IOException           when the file cannot be read
     */
    public static ModMeta read(File file) throws IOException {
        return ModMeta.of(MetaMod.read(file));
    }

    /**
     * Reads metadata from bytes, without touching the filesystem.
     *
     * @param source  what to name in error messages, such as {@code example/mod@main}
     * @param content the meta file's text, keyed by its name inside the mod
     * @throws ModMeta.MetaException when the loader rejects the metadata
     */
    public static ModMeta read(String source, Map<String, String> content) throws IOException {
        Map<String, byte[]> bytes = new java.util.LinkedHashMap<>();
        content.forEach((name, text) -> bytes.put(name, text.getBytes(StandardCharsets.UTF_8)));
        return ModMeta.of(MetaMod.read(source, bytes));
    }

    /** Reads metadata, returning {@code null} and logging the reason instead of throwing. */
    public static ModMeta readOrNull(File file){
        try{
            return read(file);
        }catch(Exception e){
            Log.debug("skipping %s: %s", file, e.getMessage());
            return null;
        }
    }
}
