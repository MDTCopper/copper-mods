package copper.loadermods.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Text helpers for turning repository metadata into index fields.
 *
 * <p>Mod metadata is written by humans and by the game's own formatting rules, so anything that ends up
 * in {@code mods.json} is sanitised the way the game's mod browser does it: formatting tags and
 * newlines stripped, length capped.</p>
 */
public final class Strings {
    /** The game's colour and formatting markers, which mods put in names and descriptions. */
    private static final Set<String> COLOR_MARKERS = Set.of(
            "white", "lightGray", "gray", "darkGray", "black", "red", "green", "yellow",
            "blue", "navy", "royal", "purple", "violet", "magenta", "pink", "coral",
            "sky", "cyan", "teal", "accent", "unlaunched", "highlight", "stat",
            "clear", "default", "[]");

    private static final int MAX_FIELD_LENGTH = 512;

    private Strings(){}

    /** Removes {@code [red]} style markers and collapses whitespace. */
    public static String stripFormatting(String value){
        if(value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        for(int i = 0; i < value.length(); i++){
            char c = value.charAt(i);
            if(c == '['){
                int close = value.indexOf(']', i);
                if(close > i){
                    String marker = value.substring(i + 1, close);
                    // a hex colour, e.g. [#88a4ff], or a named marker, e.g. [lightgray]
                    if(isMarker(marker)){
                        i = close;
                        continue;
                    }
                }
                // an escaped bracket, which the game writes as [[
                if(i + 1 < value.length() && value.charAt(i + 1) == '['){
                    out.append('[');
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean isMarker(String marker){
        if(marker.isEmpty()) return false;
        if(marker.charAt(0) == '#') return marker.length() > 1;
        for(String known : COLOR_MARKERS){
            if(known.equalsIgnoreCase(marker)) return true;
        }
        return false;
    }

    /** One line, no formatting, no control characters, capped. Used for display names. */
    public static String displayName(String value, int maxLength){
        String text = stripFormatting(value).replaceAll("\\s+", " ").trim();
        text = stripControl(text);
        if(text.length() > maxLength){
            text = text.substring(0, Math.max(0, maxLength - 3)).trim() + "...";
        }
        return text;
    }

    /** Multi-line safe text: formatting stripped, newlines kept, length capped. */
    public static String description(String value){
        if(value == null) return "";
        String text = stripControl(stripFormatting(value)).trim();
        return text.length() > MAX_FIELD_LENGTH ? text.substring(0, MAX_FIELD_LENGTH - 3) + "..." : text;
    }

    private static String stripControl(String value){
        StringBuilder out = new StringBuilder(value.length());
        for(int i = 0; i < value.length(); i++){
            char c = value.charAt(i);
            if(c == '\n' || c == '\t' || c >= ' '){
                out.append(c);
            }
        }
        return out.toString();
    }

    /** The mod id as a launcher would key it: lowercase, never blank. */
    public static String normalizeId(String id){
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }

    /** {@code author:mod} to {@code author-mod}, the mod's data folder name. */
    public static String folderName(String id){
        return id == null ? "" : id.replace(':', '-');
    }

    /** Formats an instant the way the rest of the ecosystem does, e.g. {@code 2026-09-07T12:39:06Z}. */
    public static String iso8601(Date date){
        return date == null ? "" : java.time.Instant.ofEpochMilli(date.getTime())
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                .toString()
                .replace(".000Z", "Z");
    }

    /** Uppercase SHA-256, the form the game's mod browser uses for icon hashes. */
    public static String sha256(byte[] bytes){
        try{
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for(byte b : hash){
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString().toUpperCase(Locale.ROOT);
        }catch(NoSuchAlgorithmException e){
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /**
     * The file name an icon is published under, {@code <owner>_<repo>.png} - the same convention
     * {@code Anuken/MindustryMods} uses, so a client can derive the path from the repository name.
     */
    public static String iconFileName(String repo){
        return repo.toLowerCase(Locale.ROOT).replace('/', '_') + ".png";
    }

    /**
     * Matches a repository name against a pattern where {@code *} is any run of characters and
     * {@code ?} is one, the same syntax the loader uses for class visibility rules.
     *
     * @param pattern the pattern, matched case-insensitively against the whole name
     * @param name    the {@code owner/repo}
     */
    public static boolean matches(String pattern, String name){
        if(pattern == null || name == null) return false;
        return name.toLowerCase(Locale.ROOT).matches(toRegex(pattern.toLowerCase(Locale.ROOT)));
    }

    private static String toRegex(String pattern){
        StringBuilder regex = new StringBuilder(pattern.length() + 8);
        for(int i = 0; i < pattern.length(); i++){
            char c = pattern.charAt(i);
            switch(c){
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                // everything else is literal, including the dots and slashes in owner/repo
                default -> regex.append(java.util.regex.Pattern.quote(String.valueOf(c)));
            }
        }
        return regex.toString();
    }

    /** Monotonic clock reading, for duration logs. */
    public static long nowMillis(){
        return System.nanoTime() / 1_000_000L;
    }
}
