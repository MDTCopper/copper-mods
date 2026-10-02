package copper.loadermods.net;

import com.google.gson.*;

import java.util.*;

/**
 * JSON output and the plain-Java view of parsed JSON that {@link GitHub} returns.
 *
 * <p>Parsed documents are converted to {@link LinkedHashMap}/{@link List}/{@link String}/{@link Double}/
 * {@link Boolean} so the rest of the code never touches a Gson tree, and output is written with an
 * explicit field order (mods.json is read by humans in a browser diff).</p>
 */
public final class Json {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    private Json(){}

    // ── output ──────────────────────────────────────────────────────────────

    /** A JSON object that remembers insertion order. */
    public static Obj object(){
        return new Obj();
    }

    /** An ordered JSON object; every {@code put} returns {@code this} for chaining. */
    public static final class Obj {
        private final Map<String, Object> values = new LinkedHashMap<>();

        public Obj put(String key, Object value){
            values.put(key, value);
            return this;
        }

        /** Only writes the value when it is not {@code null} and not an empty collection. */
        public Obj putIfPresent(String key, Object value){
            if(value == null) return this;
            if(value instanceof Collection<?> collection && collection.isEmpty()) return this;
            if(value instanceof Map<?, ?> map && map.isEmpty()) return this;
            values.put(key, value);
            return this;
        }

        public boolean isEmpty(){
            return values.isEmpty();
        }

        public Map<String, Object> map(){
            return values;
        }
    }

    public static Obj parseObject(String text){
        return wrapObject(asObject(JsonParser.parseString(text)));
    }

    public static Map<String, Object> objectOf(String text){
        return parseObject(text).map();
    }

    /** Parses any JSON value into plain Java values. */
    public static Object parse(String text){
        return toPlain(JsonParser.parseString(text));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(JsonElement element){
        if(!element.isJsonObject()) throw new IllegalArgumentException("expected a JSON object");
        return (Map<String, Object>)(Map<?, ?>)toPlain(element);
    }

    private static Obj wrapObject(Map<String, Object> map){
        Obj obj = new Obj();
        obj.values.putAll(map);
        return obj;
    }

    private static Object toPlain(JsonElement element){
        if(element == null || element.isJsonNull()) return null;
        if(element.isJsonObject()){
            Map<String, Object> map = new LinkedHashMap<>();
            for(Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()){
                map.put(entry.getKey(), toPlain(entry.getValue()));
            }
            return map;
        }
        if(element.isJsonArray()){
            List<Object> list = new ArrayList<>();
            for(JsonElement item : element.getAsJsonArray()) list.add(toPlain(item));
            return list;
        }
        if(element.isJsonPrimitive()){
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if(primitive.isBoolean()) return primitive.getAsBoolean();
            if(primitive.isNumber()) return primitive.getAsDouble();
            return primitive.getAsString();
        }
        return null;
    }

    /** A number out of a parsed document, tolerating strings, with a fallback. */
    public static double number(Object value, double fallback){
        if(value instanceof Number number) return number.doubleValue();
        if(value instanceof String text){
            try{
                return Double.parseDouble(text);
            }catch(NumberFormatException ignored){
            }
        }
        return fallback;
    }

    public static String string(Object value, String fallback){
        return value instanceof String text ? text : fallback;
    }

    /** A whole number out of a parsed document, so ids and format versions are not written as {@code 1.0}. */
    public static int intValue(Object value, int fallback){
        return value instanceof Number number ? number.intValue() : fallback;
    }

    /**
     * A boolean out of a parsed document.
     *
     * <p>Needed because JSON booleans are not numbers: {@link #number} would answer with its fallback for
     * them, which silently turns a flag like {@code is_template} into {@code false}.</p>
     */
    public static boolean bool(Object value, boolean fallback){
        if(value instanceof Boolean flag) return flag;
        if(value instanceof String text){
            if(text.equalsIgnoreCase("true")) return true;
            if(text.equalsIgnoreCase("false")) return false;
        }
        return fallback;
    }

    public static String pretty(Object value){
        return GSON.toJson(value);
    }

    /** One line per entry, no indentation - for logs. */
    public static String compact(Object value){
        return new Gson().toJson(value);
    }
}
