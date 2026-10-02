package copper.loadermods.util;

import java.io.PrintStream;

/**
 * Console output. One line per event, prefixed so a CI log stays skimmable, with ANSI colours dropped
 * when the output is not a terminal.
 */
public final class Log {
    private static final boolean COLOR = System.console() != null
            && !"false".equalsIgnoreCase(System.getProperty("loaderMods.color", "true"));

    private Log(){}

    public static void info(String message, Object... args){
        write(System.out, "info", null, message, args);
    }

    public static void warn(String message, Object... args){
        write(System.err, "warn", COLOR ? "\u001B[33m" : null, message, args);
    }

    public static void error(String message, Object... args){
        write(System.err, "error", COLOR ? "\u001B[31m" : null, message, args);
    }

    /** Only printed when {@code --verbose} is on; kept out of the default scan output. */
    public static void debug(String message, Object... args){
        if(!verbose) return;
        write(System.out, "debug", COLOR ? "\u001B[90m" : null, message, args);
    }

    private static volatile boolean verbose;

    public static void setVerbose(boolean value){
        verbose = value;
    }

    public static boolean isVerbose(){
        return verbose;
    }

    private static void write(PrintStream out, String level, String color, String message, Object... args){
        String text = args.length == 0 ? message : String.format(message, args);
        if(color != null){
            out.println(color + "[" + level + "] " + text + "\u001B[0m");
        }else{
            out.println("[" + level + "] " + text);
        }
    }
}
