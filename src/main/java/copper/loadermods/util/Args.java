package copper.loadermods.util;

import java.util.*;

/**
 * A tiny command-line parser: {@code loader-mods <command> [flags] [positionals]}.
 *
 * <p>Flags are {@code --name value}, {@code --name=value}, or a bare {@code --name} for a boolean.
 * Unknown flags are reported instead of ignored, so a typo in CI fails loudly.</p>
 */
public final class Args {
    private final String command;
    private final Map<String, String> flags = new LinkedHashMap<>();
    private final List<String> positionals = new ArrayList<>();
    private final Set<String> known;

    private Args(String command, Set<String> known){
        this.command = command;
        this.known = known;
    }

    /**
     * @param argv      the raw arguments, command first
     * @param flagNames the flags this command accepts; {@code "help"} is always accepted
     */
    public static Args parse(String[] argv, String... flagNames){
        Set<String> known = new LinkedHashSet<>(Arrays.asList(flagNames));
        known.add("help");

        if(argv.length == 0) throw new UsageException("no command given", null);

        Args args = new Args(argv[0], known);
        for(int i = 1; i < argv.length; i++){
            String token = argv[i];
            if(!token.startsWith("--")){
                args.positionals.add(token);
                continue;
            }
            String name = token.substring(2);
            String value = "true";
            int equals = name.indexOf('=');
            if(equals >= 0){
                value = name.substring(equals + 1);
                name = name.substring(0, equals);
            }else if(i + 1 < argv.length && !argv[i + 1].startsWith("--")){
                value = argv[++i];
            }
            if(!known.contains(name)) throw new UsageException("unknown flag --" + name, null);
            args.flags.put(name, value);
        }
        return args;
    }

    public String command(){
        return command;
    }

    public boolean has(String flag){
        return flags.containsKey(flag);
    }

    public String get(String flag, String fallback){
        return flags.getOrDefault(flag, fallback);
    }

    public String require(String flag){
        String value = flags.get(flag);
        if(value == null) throw new UsageException("missing required flag --" + flag, null);
        return value;
    }

    public int getInt(String flag, int fallback){
        String value = flags.get(flag);
        if(value == null) return fallback;
        try{
            return Integer.parseInt(value);
        }catch(NumberFormatException e){
            throw new UsageException("--" + flag + " must be a number, got '" + value + "'", null);
        }
    }

    public List<String> positionals(){
        return positionals;
    }

    /** @param index the positional index @param fallback used when it is absent */
    public String positional(int index, String fallback){
        return index < positionals.size() ? positionals.get(index) : fallback;
    }

    /** Thrown for a malformed command line; carries the usage text for the caller to print. */
    public static class UsageException extends RuntimeException {
        public UsageException(String message, Throwable cause){
            super(message, cause);
        }
    }
}
