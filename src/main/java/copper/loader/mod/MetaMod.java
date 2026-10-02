package copper.loader.mod;

import copper.loader.IPlatform;
import copper.loader.Loader;
import copper.loader.container.MetaContainer;
import copper.loader.container.MixinContainer;
import copper.loader.mod.meta.MetaReaderV1;
import copper.loader.util.Jval;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Reads Copper mod metadata through the loader's own parser.
 *
 * <p>This class lives in the loader's package on purpose: {@link Mod}'s constructor is package-private
 * and it calls {@link Loader#platform}, so this is the only way to run {@code Mod.loadMeta} without
 * reflection. It is a subclass, not a copy - {@link Mod} is left alone, and reading a mod goes through
 * exactly the code path the loader uses at startup, with {@link MetaReaderV1} at the end of it.</p>
 *
 * <p>The one thing the parent does that is not wanted here is the container: it goes through
 * {@code Loader.platform.createModContainer}. {@link MetaPlatform} answers that call with a
 * {@link MetaContainer}, which is all the parent needs to read the meta file.</p>
 *
 * <p>Metadata that is already in memory - anything fetched over HTTP - is parsed without a temporary
 * file, by handing the loader an in-memory resource instead of a file path.</p>
 */
public final class MetaMod extends Mod {

    /**
     * Content the platform should put in the next container it builds, per thread. Set only while
     * {@link #read(String, Map)} constructs a mod from bytes.
     */
    private static final ThreadLocal<Map<String, byte[]>> pending = new ThreadLocal<>();

    private MetaMod(File file){
        super(file);
    }

    /**
     * Reads a mod's metadata.
     *
     * @param file a mod jar/zip, or a mod directory
     * @return the loader's parsed mod
     * @throws ModMetaRejected if the loader's parser rejected the metadata
     * @throws IOException     if the file cannot be read
     */
    public static MetaMod read(File file) throws IOException {
        installPlatform();
        pending.remove();
        try{
            return new MetaMod(file);
        }catch(RuntimeException e){
            throw new ModMetaRejected(messageOf(e), e);
        }
    }

    /**
     * Reads metadata that is already in memory.
     *
     * @param source  what to name in error messages, such as {@code example/mod@main}
     * @param content the meta file's bytes, keyed by its name inside the mod
     * @return the loader's parsed mod
     * @throws ModMetaRejected if the loader's parser rejected the metadata
     */
    public static MetaMod read(String source, Map<String, byte[]> content) throws IOException {
        installPlatform();
        // the parent's constructor asks the platform for a container, so the content is published where
        // the platform can see it; thread-local, because repositories are scanned in parallel
        pending.set(content);
        try{
            return new MetaMod(new File(source));
        }catch(RuntimeException e){
            throw new ModMetaRejected(messageOf(e), e);
        }finally{
            pending.remove();
        }
    }

    /** Installs the minimal platform once. */
    private static void installPlatform(){
        if(Loader.platform instanceof MetaPlatform) return;
        synchronized(MetaMod.class){
            if(!(Loader.platform instanceof MetaPlatform)){
                Loader.platform = new MetaPlatform();
            }
        }
    }

    /** The loader's own failure message, unwrapped from the layers {@code Mod}'s constructor adds. */
    private static String messageOf(Throwable error){
        Throwable current = error;
        String message = null;
        while(current != null){
            if(current.getMessage() != null && !current.getMessage().isBlank()) message = current.getMessage();
            current = current.getCause();
        }
        return message == null ? "failed to read mod metadata" : message;
    }

    /** The raw meta document, as the loader read it. */
    public String rawMeta(){
        return ((MetaContainer)container).metaText();
    }

    /** The parsed meta document, for the fields {@link Mod} does not keep. */
    public Jval rawDocument(){
        String raw = rawMeta();
        return raw == null ? null : Jval.read(raw);
    }

    /** The metadata the loader rejected. */
    public static class ModMetaRejected extends RuntimeException {
        public ModMetaRejected(String message, Throwable cause){
            super(message, cause);
        }
    }

    /**
     * The platform the loader asks for a container. Only {@link #createModContainer} is ever called;
     * everything else would mean loading a mod, which this tool does not do.
     */
    private static final class MetaPlatform implements IPlatform {
        @Override
        public MixinContainer createModContainer(File file){
            Map<String, byte[]> content = pending.get();
            if(content != null) return MetaContainer.of(content);
            try{
                return MetaContainer.of(file);
            }catch(IOException e){
                throw new RuntimeException("failed to read " + file, e);
            }
        }

        @Override
        public String extractLibrary(byte[] library, String name){
            throw new UnsupportedOperationException("loader-mods only reads metadata");
        }

        @Override
        public void extractCoreMod(){
            throw new UnsupportedOperationException("loader-mods only reads metadata");
        }

        @Override
        public copper.loader.mixin.IMixinEngine createMixinEngine(){
            throw new UnsupportedOperationException("loader-mods never initializes mixins");
        }

        @Override
        public MixinContainer createGameContainer(){
            throw new UnsupportedOperationException("loader-mods only reads mod metadata");
        }

        @Override
        public copper.loader.container.Container createLoaderContainer(){
            throw new UnsupportedOperationException("loader-mods only reads mod metadata");
        }

        @Override
        public File getGameDataFolder(){
            return new File(".");
        }
    }
}
