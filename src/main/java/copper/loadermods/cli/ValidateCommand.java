package copper.loadermods.cli;

import copper.loadermods.index.IndexScanner;
import copper.loadermods.meta.*;
import copper.loadermods.net.Json;
import copper.loadermods.util.*;

import java.io.File;
import java.nio.file.Path;
import java.util.*;

/**
 * {@code validate}: check a mod the way the loader does, without launching anything.
 *
 * <p>Useful before publishing: the loader rejects a mod at startup with the same messages this prints,
 * and it is much faster to find out here than by installing the jar.</p>
 */
public final class ValidateCommand {
    /** Exit code for a mod the loader would reject. */
    public static final int REJECTED = 3;

    private ValidateCommand(){}

    public static void run(String[] argv) throws Exception {
        Args args = Args.parse(argv, "json", "verbose");
        if(args.has("verbose")) Log.setVerbose(true);

        Path target = Path.of(args.positional(0, ""));
        if(target.toString().isEmpty()){
            throw new Args.UsageException("validate needs a path to a mod jar, directory, or meta file", null);
        }
        File file = target.toFile();
        if(!file.exists()){
            Log.error("no such file or directory: %s", target);
            System.exit(1);
            return;
        }

        ModMeta meta;
        try{
            meta = IndexScanner.readLocal(file);
        }catch(ModMeta.MetaException e){
            Log.error("%s: %s", target, e.getMessage());
            System.exit(REJECTED);
            return;
        }catch(copper.loader.mod.MetaMod.ModMetaRejected e){
            Log.error("%s: %s", target, e.getMessage());
            System.exit(REJECTED);
            return;
        }

        if(args.has("json")){
            System.out.println(Json.pretty(summary(meta).map()));
        }else{
            print(meta);
        }
    }

    private static void print(ModMeta meta){
        System.out.println("mod:          " + meta.name() + " (" + meta.id() + ")");
        System.out.println("author:       " + meta.author());
        System.out.println("version:      " + meta.version());
        System.out.println("main:         " + meta.main());
        System.out.println("vanilla name: " + meta.vanillaName() + "  (data folder: " + meta.mod().dataFolderName() + ")");
        System.out.println("format:       " + meta.formatVersion());
        if(!meta.repo().isEmpty()) System.out.println("repo:         " + meta.repo());
        if(!meta.description().isEmpty()){
            System.out.println("description:  " + Strings.displayName(meta.description(), 120));
        }

        printRelations("dependencies", meta.dependencies());
        printRelations("conflicts", meta.conflicts());

        if(!meta.mod().mixins().isEmpty()){
            System.out.println("mixins:       " + meta.mod().mixins());
        }
        if(!meta.mod().exports().isEmpty()){
            System.out.println("exports:      " + meta.mod().exports());
        }
        if(!meta.mod().extraMeta().isEmpty()){
            System.out.println("extra:        " + Json.compact(meta.mod().extraMeta()));
        }

        if(meta.hidden()){
            System.out.println();
            System.out.println("note: hidden mods add no content, so the mod browser will not list this mod.");
        }
        System.out.println();
        System.out.println("ok: the loader accepts this metadata.");
    }

    private static void printRelations(String label, List<ModMeta.Relation> relations){
        if(relations.isEmpty()) return;
        StringBuilder line = new StringBuilder();
        for(ModMeta.Relation relation : relations){
            if(line.length() > 0) line.append(", ");
            line.append(relation.id()).append(' ').append(relation.filterText());
        }
        System.out.println(String.format("%-13s %s", label + ":", line));
    }

    private static Json.Obj summary(ModMeta meta){
        List<Object> dependencies = new ArrayList<>();
        for(ModMeta.Relation relation : meta.dependencies()){
            dependencies.add(Json.object().put("id", relation.id()).put("version", relation.filterText()).map());
        }
        List<Object> conflicts = new ArrayList<>();
        for(ModMeta.Relation relation : meta.conflicts()){
            conflicts.add(Json.object().put("id", relation.id()).put("version", relation.filterText()).map());
        }

        return Json.object()
                .put("formatVersion", meta.formatVersion())
                .put("id", meta.id())
                .put("vanillaName", meta.vanillaName())
                .put("dataFolderName", meta.mod().dataFolderName())
                .put("name", meta.name())
                .put("author", meta.author())
                .put("version", meta.version())
                .put("main", meta.main())
                .put("description", meta.description())
                .put("repo", meta.repo())
                .put("hidden", meta.hidden())
                .put("dependencies", dependencies)
                .put("conflicts", conflicts)
                .put("mixins", meta.mod().mixins())
                .put("exports", meta.mod().exports())
                .put("extra", meta.mod().extraMeta())
                .put("gameRequirement", meta.gameRequirement())
                .put("loaderRequirement", meta.loaderRequirement());
    }
}
