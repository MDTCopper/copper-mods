package copper.loader.container;

import copper.loader.container.resource.FolderResource;
import copper.loader.container.resource.IResource;
import copper.loader.container.resource.ZipResource;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * The container {@code loader-mods} hands to the loader when it reads mod metadata.
 *
 * <p>It exists to give the loader a resource root, which is all {@code Mod.loadMeta} needs. No mixin
 * engine is ever created, because that happens in {@link MixinContainer#init()}, which this tool never
 * calls.</p>
 *
 * <p>A container can be backed by a file or by bytes already in memory, so metadata fetched over HTTP is
 * parsed without ever touching the filesystem.</p>
 *
 * <p>It is a thin adapter, not a reimplementation: it adds resource attachment and two convenience
 * lookups, and nothing else. {@link MixinContainer} itself is used unchanged.</p>
 */
public class MetaContainer extends MixinContainer {

    /** An empty container; a resource is attached by {@link #attach(File)} or {@link #attach(Map)}. */
    public MetaContainer(){
        super();
    }

    /** A container over a mod jar/zip, or over a mod directory. */
    public static MetaContainer of(File file) throws IOException {
        MetaContainer container = new MetaContainer();
        container.attach(file);
        return container;
    }

    /** A container over meta files that are already in memory, keyed by their name inside the mod. */
    public static MetaContainer of(Map<String, byte[]> content){
        MetaContainer container = new MetaContainer();
        container.attach(content);
        return container;
    }

    /** Attaches a jar/zip or a directory as this container's resource root. */
    public void attach(File file) throws IOException {
        resource.resources.add(file.isDirectory() ? new FolderResource(file) : new ZipResource(new ZipFile(file)));
    }

    /** Attaches in-memory content as this container's resource root. */
    public void attach(Map<String, byte[]> content){
        resource.resources.add(new InMemoryResource(content));
    }

    /** @return the bytes of the meta file at the container's root, or {@code null} when there is none */
    public byte[] metaBytes(){
        byte[] bytes = resource.get("copper.mod.json");
        return bytes != null ? bytes : resource.get("copper.mod.hjson");
    }

    /** @return the meta file's text, or {@code null} when the container holds none */
    public String metaText(){
        byte[] bytes = metaBytes();
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Never called: this container is only used to read a meta file. Class loading is what
     * {@link MixinContainer#init()} sets up, and that is deliberately never reached.
     */
    @Override
    public Class<?> loadOwnClass(String name){
        throw new UnsupportedOperationException("loader-mods only reads metadata");
    }

    /** Never called, for the same reason as {@link #loadOwnClass(String)}. */
    @Override
    public ClassLoader getClassLoader(){
        throw new UnsupportedOperationException("loader-mods only reads metadata");
    }

    /** The loader's {@code IResource} over a map of bytes, so no file is ever written. */
    private record InMemoryResource(Map<String, byte[]> content) implements IResource {
        @Override
        public byte[] read(String path){
            return content.get(path.replace('\\', '/').replaceFirst("^/", ""));
        }
    }
}
