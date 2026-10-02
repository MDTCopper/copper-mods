package copper.loadermods;

import copper.loadermods.cli.UpdateCommand;
import copper.loadermods.cli.ValidateCommand;
import copper.loadermods.util.Args;
import copper.loadermods.util.Log;

/** Entry point. Subcommands are documented in the README. */
public final class Main {
    private Main(){}

    public static void main(String[] args){
        if(args.length > 0 && (args[0].equals("-h") || args[0].equals("--help") || args[0].equals("help"))){
            usage();
            return;
        }

        String command = args.length == 0 ? "" : args[0];
        try{
            switch(command){
                case "update" -> UpdateCommand.run(args);
                case "validate" -> ValidateCommand.run(args);
                default -> {
                    Log.error("unknown command: %s", command.isEmpty() ? "(none)" : command);
                    usage();
                    System.exit(2);
                }
            }
        }catch(Args.UsageException e){
            Log.error("%s", e.getMessage());
            usage();
            System.exit(2);
        }catch(Exception e){
            Log.error("%s", e.getMessage());
            if(Log.isVerbose()) e.printStackTrace();
            System.exit(1);
        }
    }

    private static void usage(){
        System.out.println("""
                loader-mods - compiles mods.json for the Copper mod ecosystem

                Usage:
                  loader-mods update [flags]        scan GitHub and rewrite mods.json + icons/
                  loader-mods validate <path>       check a mod jar/directory the way the loader does

                Common flags:
                  --verbose                         log every skipped repository and every request
                  --help                            this text

                update flags:
                  --out <dir>                       output directory (default: .)
                  --topic <name>                    discovery topic (default: mindustry-copper-mod)
                  --max-pages <n>                   search pages, 100 repos each (max 10 = GitHub's 1000-result cap)
                  --min-stars <n>                   only fetch icons for repos with this many stars (default: 0)
                  --keep-missing                    keep entries whose repo is no longer discoverable
                  --allow-empty                     permit writing an index with no mods
                  --keep-templates                  index template repositories too
                  --exclude <owner/repo>            skip a repository; repeatable, * and ? allowed
                  --token <token>                   GitHub token (default: $GITHUB_TOKEN)
                  --threads <n>                     concurrent repository scans (default: 8)

                validate flags:
                  --json                            print the parsed metadata as JSON

                Exit codes: 0 ok, 1 failure, 2 bad usage, 3 validation rejected""");
    }
}
