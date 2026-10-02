package copper.loadermods.net;

import copper.loadermods.util.Log;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GitHub access: the REST API for discovery, and {@code raw.githubusercontent.com} for file contents.
 *
 * <p>Everything the scan sends goes through here so that the two things that make a scheduled crawl
 * survivable are in one place: retry with backoff on transient failures, and rate-limit reporting that
 * says exactly what to do when the quota runs out.</p>
 */
public final class GitHub {
    private static final String API = "https://api.github.com";
    private static final String RAW = "https://raw.githubusercontent.com";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_ATTEMPTS = 3;
    /** The longest a rate limit is worth waiting out inside one run; a schedule can come back later. */
    private static final long MAX_RATE_LIMIT_WAIT_SECONDS = 120;
    /** GitHub asks for at most 100 concurrent requests; this leaves room for other callers. */
    private static final int MAX_CONCURRENT_REQUESTS = 16;

    private final HttpClient http;
    private final String token;
    private final AtomicInteger requests = new AtomicInteger();
    private final Semaphore inFlight = new Semaphore(MAX_CONCURRENT_REQUESTS);

    /**
     * The quota left in each pool. GitHub counts the search endpoints separately from the rest of the
     * API, and reports both under the same {@code x-ratelimit-*} headers, so they have to be told apart
     * by the URL of the request that carried them.
     */
    private volatile RateLimit coreLimit = RateLimit.UNKNOWN;
    private volatile RateLimit searchLimit = RateLimit.UNKNOWN;

    /**
     * The quota one endpoint pool reports.
     *
     * @param limit     the pool's requests per hour, or -1 when GitHub stated none
     * @param remaining requests left in the current hour, or -1 when GitHub stated none
     * @param resetAt   epoch seconds the window resets at, or -1 when GitHub stated none
     */
    public record RateLimit(int limit, int remaining, long resetAt){
        public static final RateLimit UNKNOWN = new RateLimit(-1, -1, -1L);

        /**
         * The more pessimistic of two observations of the same pool.
         *
         * <p>Requests run in parallel, so responses arrive out of order: keeping the lowest count seen
         * means the summary reports how little is left, not whichever response happened to finish last.</p>
         */
        public RateLimit lower(RateLimit other){
            if(remaining < 0) return other;
            if(other.remaining < 0) return this;
            return other.remaining < remaining ? other : this;
        }

        /** Seconds until the window resets, or -1 when unknown. */
        public long remainingSeconds(){
            return resetAt < 0 ? -1 : resetAt - System.currentTimeMillis() / 1000;
        }

        /** Whether anything is known about this pool yet. */
        public boolean known(){
            return remaining >= 0;
        }

        @Override
        public String toString(){
            if(!known()) return "unknown";
            String limitText = limit < 0 ? "?" : String.valueOf(limit);
            long seconds = remainingSeconds();
            return remaining + "/" + limitText + (seconds < 0 ? "" : ", resets in " + seconds + "s");
        }
    }

    /**
     * @param token a GitHub token, or {@code null} for anonymous access
     */
    public GitHub(String token){
        this.token = token;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    /** The number of requests that actually hit the network. */
    public int requestCount(){
        return requests.get();
    }

    /** The quota left in the general API pool, as last observed. */
    public RateLimit coreLimit(){
        return coreLimit;
    }

    /** The quota left in the search pool, which GitHub counts separately. */
    public RateLimit searchLimit(){
        return searchLimit;
    }

    /** Both pools, for a run summary. */
    public String quotaSummary(){
        return "core " + coreLimit + ", search " + searchLimit;
    }

    // ── API ─────────────────────────────────────────────────────────────────

    /** Performs a GET against the REST API. @return the body, or {@code null} on 404 */
    public String apiGet(String pathAndQuery) throws IOException, InterruptedException {
        String url = pathAndQuery.startsWith("http") ? pathAndQuery : API + pathAndQuery;
        Response response = get(url, true);
        if(response.status() == 404 || response.status() == 451) return null;
        if(response.status() / 100 != 2){
            throw new IOException("GET " + url + " returned " + response.status() + ": " + summarize(response.body()));
        }
        return response.body();
    }

    /**
     * Runs a search query, following pagination up to {@code maxPages}.
     *
     * @param endpoint  {@code /search/repositories} or {@code /search/code}
     * @param query     the raw query string, already encoded by the caller
     * @param maxPages  the page cap, because search results are capped at 1000 items anyway
     * @param perPage   the page size, at most 100
     * @return every {@code items} array element, in page order
     */
    public List<Object> search(String endpoint, String query, int maxPages, int perPage)
            throws IOException, InterruptedException {
        // the caller passes a query that may contain spaces; encode only the query value
        List<Object> items = new ArrayList<>();
        int total = -1;
        for(int page = 1; page <= maxPages; page++){
            String url = API + endpoint + "?q=" + encode(query) + "&per_page=" + perPage + "&page=" + page;
            Response response = get(url, true);
            if(response.status() == 422) return items; // beyond the 1000-result search cap
            if(response.status() / 100 != 2){
                throw new IOException("search " + endpoint + " returned " + response.status() + ": " + summarize(response.body()));
            }
            Map<String, Object> json = Json.objectOf(response.body());
            if(total < 0){
                total = (int)Json.number(json.get("total_count"), 0);
                Log.debug("search %s matched %d item(s)", endpoint, total);
            }
            Object rawItems = json.get("items");
            if(!(rawItems instanceof List<?> list) || list.isEmpty()) break;
            items.addAll(list);
            if(list.size() < perPage) break;
            if(items.size() >= total) break;
        }
        return items;
    }

    /** Fetches release metadata. @return the JSON array, or an empty list when the repo has none */
    public List<Object> releases(String repo, int maxPages) throws IOException, InterruptedException {
        List<Object> all = new ArrayList<>();
        for(int page = 1; page <= maxPages; page++){
            String body = apiGet("/repos/" + repo + "/releases?per_page=100&page=" + page);
            if(body == null) break;
            if(!(Json.parse(body) instanceof List<?> list) || list.isEmpty()) break;
            all.addAll(list);
            if(list.size() < 100) break;
        }
        return all;
    }

    public Map<String, Object> repo(String fullName) throws IOException, InterruptedException {
        String body = apiGet("/repos/" + fullName);
        return body == null ? null : Json.objectOf(body);
    }

    // ── raw contents ────────────────────────────────────────────────────────

    /**
     * Fetches a file from a repository.
     *
     * @param repo the {@code owner/name}
     * @param ref  a branch, tag or commit
     * @param path the path inside the repository
     * @return the file's text, or {@code null} when it does not exist
     */
    public String rawText(String repo, String ref, String path) throws IOException, InterruptedException {
        Response response = get(RAW + "/" + repo + "/" + ref + "/" + path, false);
        if(response.status() / 100 != 2) return null;
        return response.body();
    }

    /** Like {@link #rawText} but for binary content, such as an icon. */
    public byte[] rawBytes(String repo, String ref, String path) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = send(RAW + "/" + repo + "/" + ref + "/" + path, false,
                HttpResponse.BodyHandlers.ofByteArray());
        if(response.statusCode() / 100 != 2) return null;
        byte[] body = response.body();
        return body.length == 0 ? null : body;
    }

    // ── transport ───────────────────────────────────────────────────────────

    private record Response(int status, String body){}

    private Response get(String url, boolean api) throws IOException, InterruptedException {
        IOException lastFailure = null;
        for(int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++){
            try{
                HttpResponse<String> response = send(url, api, HttpResponse.BodyHandlers.ofString());
                recordRateLimit(url, response);

                int status = response.statusCode();
                if(status == 403 || status == 429){
                    // GitHub's own order of preference, from the REST API troubleshooting guide:
                    // honour retry-after first, then wait out a primary limit, else back off a minute.
                    long waitMillis = waitFor(response, attempt);
                    if(waitMillis < 0){
                        throw new IOException(rateLimitMessage(response) + hint());
                    }
                    Log.warn("rate limited on %s; waiting %d second(s) before retrying", url, waitMillis / 1000);
                    sleep(waitMillis);
                    continue;
                }
                if(status >= 500){
                    lastFailure = new IOException("server error " + status + " for " + url);
                    sleep(500L * attempt);
                    continue;
                }

                return new Response(status, response.body());
            }catch(IOException e){
                lastFailure = e;
                sleep(500L * attempt);
            }
        }
        throw lastFailure != null ? lastFailure : new IOException("GET " + url + " failed");
    }

    /**
     * How long to wait after a 403/429, following GitHub's documented preference order.
     *
     * <ol>
     *   <li>{@code retry-after}, which is what a secondary limit sends: never retry before it elapses.</li>
     *   <li>an exhausted primary quota: wait for {@code x-ratelimit-reset}, if that is soon enough to be
     *       worth it for a scheduled job.</li>
     *   <li>otherwise a secondary limit without {@code retry-after}: at least a minute, growing with each
     *       attempt.</li>
     * </ol>
     *
     * @return milliseconds to wait, or -1 when waiting is pointless and the caller should fail
     */
    public static long waitFor(long retryAfterSeconds, boolean primaryExhausted, long resetInSeconds, int attempt){        if(retryAfterSeconds >= 0){
            // capped: a scheduled run should come back later rather than hold a runner for an hour
            return retryAfterSeconds > MAX_RATE_LIMIT_WAIT_SECONDS ? -1 : retryAfterSeconds * 1000L;
        }
        if(primaryExhausted){
            if(resetInSeconds < 0 || resetInSeconds > MAX_RATE_LIMIT_WAIT_SECONDS) return -1;
            return Math.max(resetInSeconds, 1) * 1000L;
        }
        // a secondary limit with no retry-after: one minute minimum, then exponential
        return Math.min(60_000L << (attempt - 1), MAX_RATE_LIMIT_WAIT_SECONDS * 1000L);
    }

    private long waitFor(HttpResponse<?> response, int attempt){
        return waitFor(retryAfterSeconds(response), rateLimitExhausted(), resetInSeconds(response), attempt);
    }

    /** {@code retry-after} in seconds, or -1 when the header is absent or unreadable. */
    private static long retryAfterSeconds(HttpResponse<?> response){
        return response.headers().firstValue("retry-after").map(value -> {
            try{
                return Long.parseLong(value.trim());
            }catch(NumberFormatException e){
                return -1L;
            }
        }).orElse(-1L);
    }

    /** Seconds until {@code x-ratelimit-reset}, or -1 when the header is absent or unreadable. */
    private static long resetInSeconds(HttpResponse<?> response){
        return response.headers().firstValue("x-ratelimit-reset").map(value -> {
            try{
                return Long.parseLong(value.trim()) - System.currentTimeMillis() / 1000;
            }catch(NumberFormatException e){
                return -1L;
            }
        }).orElse(-1L);
    }

    /** Says which limit was hit, so a log line is actionable. */
    private static String rateLimitMessage(HttpResponse<?> response){
        if(rateLimitExhausted(response)){
            return "GitHub API quota is exhausted";
        }
        return "GitHub API secondary rate limit hit";
    }

    private <T> HttpResponse<T> send(String url, boolean api, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        requests.incrementAndGet();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("user-agent", "copper-loader-mods")
                .header("accept", api ? "application/vnd.github+json" : "*/*")
                .GET();
        if(api) request.header("x-github-api-version", "2022-11-28");
        if(token != null && api) request.header("authorization", "Bearer " + token);

        // a semaphore rather than a thread count, so a scan with many threads still cannot exceed the
        // documented concurrency limit
        inFlight.acquire();
        try{
            return http.send(request.build(), handler);
        }finally{
            inFlight.release();
        }
    }

    /**
     * Records what a response said about its quota, in the pool that response belongs to.
     *
     * @param url      the request's URL, which is what tells the two pools apart
     * @param response the response, whose headers carry the count
     */
    private void recordRateLimit(String url, HttpResponse<?> response){
        Optional<String> remaining = response.headers().firstValue("x-ratelimit-remaining");
        if(remaining.isEmpty()) return;

        RateLimit observed;
        try{
            observed = new RateLimit(
                    headerInt(response, "x-ratelimit-limit"),
                    Integer.parseInt(remaining.get().trim()),
                    headerLong(response, "x-ratelimit-reset"));
        }catch(NumberFormatException e){
            return;
        }

        if(url.contains("/search/")){
            searchLimit = searchLimit.lower(observed);
        }else{
            coreLimit = coreLimit.lower(observed);
        }
    }

    private static int headerInt(HttpResponse<?> response, String name){
        return response.headers().firstValue(name).map(GitHub::parseInt).orElse(-1);
    }

    private static long headerLong(HttpResponse<?> response, String name){
        return response.headers().firstValue(name).map(GitHub::parseLong).orElse(-1L);
    }

    private static int parseInt(String text){
        try{
            return Integer.parseInt(text.trim());
        }catch(NumberFormatException e){
            return -1;
        }
    }

    private static long parseLong(String text){
        try{
            return Long.parseLong(text.trim());
        }catch(NumberFormatException e){
            return -1L;
        }
    }

    /** Whether this response says the primary quota is used up, as opposed to a secondary limit. */
    private static boolean rateLimitExhausted(HttpResponse<?> response){
        return response.headers().firstValue("x-ratelimit-remaining")
                .map(value -> value.trim().equals("0"))
                .orElse(false);
    }

    /** Whether the general API quota was seen exhausted at any point, for the run summary. */
    public boolean rateLimitExhausted(){
        return coreLimit.remaining() == 0;
    }

    /** What to do about an exhausted quota. */
    public String hint(){
        return token == null
                ? "; set GITHUB_TOKEN to raise the anonymous limit of 60 requests per hour"
                : "; the token's quota is used up, try again after the reset";
    }

    private static void sleep(long millis){
        try{
            Thread.sleep(millis);
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
        }
    }

    /** Percent-encodes a query value. Spaces become {@code %20}, not {@code +}, as query strings require. */
    private static String encode(String value){
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String summarize(String body){
        if(body == null) return "(no body)";
        String text = body.replaceAll("\\s+", " ");
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
