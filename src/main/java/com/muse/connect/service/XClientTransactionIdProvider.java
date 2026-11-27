package com.muse.connect.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.connect.model.ConnectorConfig;
import com.muse.connect.model.ConnectorProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates X web {@code x-client-transaction-id} headers for {@code COOKIE_AUTH} requests.
 * <p>
 * Mirrors the algorithm used by X's web client (and twscrape's {@code XClIdGen}):
 * fetches an authenticated X page to extract the verification key, SVG animation curves,
 * and dynamic signing script indices, then caches the derived keys in a small bounded map.
 */
@Component
public class XClientTransactionIdProvider {

    private static final Logger log = LoggerFactory.getLogger(XClientTransactionIdProvider.class);

    static final String DEFAULT_WEB_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private static final long EPOCH_OFFSET_MS = 1682924400_000L;
    private static final String DEFAULT_KEYWORD = "obfiowerehiring";
    private static final byte DEFAULT_RANDOM_BYTE = 3;
    private static final long CACHE_TTL_MS = Duration.ofMinutes(30).toMillis();
    private static final int MAX_CACHE_ENTRIES = 64;
    private static final int MAX_DYNAMIC_CHUNKS_TO_SCAN = 48;

    private static final Pattern META_TAG_PATTERN =
            Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_ATTR_PATTERN =
            Pattern.compile("\\bname\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENT_ATTR_PATTERN =
            Pattern.compile("\\bcontent\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private static final Pattern SVG_ANIM_PATTERN =
            Pattern.compile("<svg\\b[^>]*\\bid\\s*=\\s*[\"']loading-x-anim[^\"']*[\"'][^>]*>(.*?)</svg>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern FIRST_G_PATTERN =
            Pattern.compile("<g\\b[^>]*>(.*?)</g>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern PATH_D_PATTERN =
            Pattern.compile("<path\\b[^>]*\\bd\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE);

    private static final Pattern ASSET_XWEB_URL_PATTERN =
            Pattern.compile("https?://[\\w.:-]+/x-web/[\\w./-]+\\.js");
    private static final Pattern RESPONSIVE_WEB_URL_PATTERN =
            Pattern.compile("https?://[\\w.:-]+/responsive-web/client-web/[\\w./-]+\\.js");
    private static final Pattern LEGACY_MAIN_PATTERN =
            Pattern.compile("/client-web/main\\.([^.\"']+)\\.js");
    private static final Pattern HASH_MAP_PATTERN =
            Pattern.compile("(\\d+):\"([0-9a-f]{7}|[0-9a-f]{16})\"");
    private static final Pattern NAME_MAP_PATTERN =
            Pattern.compile("(\\d+):\"([^\"]+)\"");
    private static final Pattern HEX_HASH_PATTERN =
            Pattern.compile("[0-9a-f]{7}|[0-9a-f]{16}");
    private static final Pattern LOGGED_OUT_ENTRY_PATTERN =
            Pattern.compile("(?:^|/)entry-client-logged-out(?:[-.][^/?#]+)?\\.js(?:[?#].*)?$");
    private static final Pattern INDICES_FILE_PATTERN =
            Pattern.compile("(?:\\.{0,2}/)?[\\w./-]*?\\b(?:ondemand\\.s|sign\\.o)[\\w.-]*\\.js");
    private static final Pattern LOCAL_SCRIPT_SRC_PATTERN =
            Pattern.compile("<script\\b[^>]*\\bsrc\\s*=\\s*[\"']([^\"']+\\.js(?:\\?[^\"']*)?)[\"']",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern INDICES_REGEX =
            Pattern.compile("(\\(\\w\\[(\\d{1,2})],\\s*16\\))+");
    private static final Pattern GRAPHQL_OP_PATTERN = Pattern.compile(
            "queryId:\"([A-Za-z0-9_-]+)\",operationName:\"(CreateTweet|SearchTimeline|UserByScreenName)\"(?:[^}]*?metadata:\\{[^}]*?featureSwitches:\\[([^\\]]*)\\])?"
    );
    private static final Pattern QUOTED_STRING_PATTERN = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern INPUT_TAG_PATTERN =
            Pattern.compile("<input\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern VALUE_ATTR_PATTERN =
            Pattern.compile("\\bvalue\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Set<String> TRACKED_GRAPHQL_OPS =
            Set.of("CreateTweet", "SearchTimeline", "UserByScreenName");

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, CachedWebContext> cache = new ConcurrentHashMap<>();

    public XClientTransactionIdProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Optional<String> generateHeader(
            ConnectorProfile profile,
            HttpClient client,
            String baseUrl,
            String method,
            String path,
            int timeoutMs,
            boolean forceRefresh
    ) {
        Optional<CachedWebContext> ctx = getOrFetchContext(profile, client, baseUrl, timeoutMs, forceRefresh);
        return ctx.map(c -> c.keys().generate(method, path));
    }

    public Optional<GraphqlOperationMeta> resolveGraphqlOperation(
            ConnectorProfile profile,
            HttpClient client,
            String baseUrl,
            String operationName,
            int timeoutMs,
            boolean forceRefresh
    ) {
        if (!StringUtils.hasText(operationName)) {
            return Optional.empty();
        }
        Optional<CachedWebContext> ctxOpt = getOrFetchContext(profile, client, baseUrl, timeoutMs, forceRefresh);
        if (ctxOpt.isEmpty()) {
            return Optional.empty();
        }
        CachedWebContext ctx = ctxOpt.get();
        if (forceRefresh || !ctx.opsScanned().get()) {
            scanGraphqlOperations(ctx, client, timeoutMs);
        }
        return Optional.ofNullable(ctx.graphqlOps().get(operationName));
    }

    private Optional<CachedWebContext> getOrFetchContext(
            ConnectorProfile profile,
            HttpClient client,
            String baseUrl,
            int timeoutMs,
            boolean forceRefresh
    ) {
        ConnectorConfig cfg = profile.getConfig();
        if (cfg == null || !StringUtils.hasText(cfg.getAuthToken()) || !StringUtils.hasText(cfg.getCt0())) {
            return Optional.empty();
        }
        String bootstrapBase = resolveBootstrapBaseUrl(baseUrl);
        String cacheKey = buildCacheKey(bootstrapBase, cfg.getAuthToken().trim());
        long now = System.currentTimeMillis();

        if (!forceRefresh) {
            CachedWebContext cached = cache.get(cacheKey);
            if (cached != null && now - cached.fetchedAtMs() < CACHE_TTL_MS) {
                return Optional.of(cached);
            }
        }

        try {
            String cookieHeader = "auth_token=" + cfg.getAuthToken().trim() + "; ct0=" + cfg.getCt0().trim();
            CachedWebContext fresh = fetchWebContext(client, bootstrapBase, cookieHeader, timeoutMs);
            putCached(cacheKey, fresh);
            return Optional.of(fresh);
        } catch (Exception ex) {
            log.warn("Unable to initialize X web context from {}: {}", bootstrapBase, ex.getMessage());
            return Optional.empty();
        }
    }

    public void clearCache() {
        cache.clear();
    }

    private void putCached(String key, CachedWebContext value) {
        if (cache.size() >= MAX_CACHE_ENTRIES && !cache.containsKey(key)) {
            cache.clear();
        }
        cache.put(key, value);
    }

    static String resolveBootstrapBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return XRelayService.DEFAULT_WEB_BASE_URL;
        }
        String trimmed = baseUrl.trim().replaceAll("/+$", "");
        try {
            URI uri = URI.create(trimmed);
            String host = uri.getHost();
            if ("api.x.com".equalsIgnoreCase(host) || "api.twitter.com".equalsIgnoreCase(host)) {
                return XRelayService.DEFAULT_WEB_BASE_URL;
            }
        } catch (Exception ignored) {
            return XRelayService.DEFAULT_WEB_BASE_URL;
        }
        return trimmed;
    }

    private CachedWebContext fetchWebContext(
            HttpClient client,
            String bootstrapBase,
            String cookieHeader,
            int timeoutMs
    ) throws Exception {
        URI pageUri = URI.create(bootstrapBase + "/tesla");
        String html = fetchPageText(client, pageUri, cookieHeader, timeoutMs);
        byte[] vkBytes = parseVerificationKey(html);
        List<double[]> animRows = parseAnimationRows(html, vkBytes);
        List<String> allScripts = extractScriptUrls(html, pageUri);
        List<Integer> animIndices = parseSigningIndices(allScripts, client, timeoutMs);
        String animKey = computeAnimKey(vkBytes, animRows, animIndices);
        List<String> graphqlBundleCandidates = selectGraphqlBundleUrls(allScripts);
        return new CachedWebContext(
                new TransactionKeys(vkBytes, animKey),
                graphqlBundleCandidates,
                new ConcurrentHashMap<>(),
                new AtomicBoolean(false),
                System.currentTimeMillis()
        );
    }

    private void scanGraphqlOperations(CachedWebContext ctx, HttpClient client, int timeoutMs) {
        try {
            int limit = Math.min(ctx.bundleScriptUrls().size(), 24);
            for (int i = 0; i < limit; i++) {
                String scriptUrl = ctx.bundleScriptUrls().get(i);
                try {
                    HttpResponse<String> resp = sendGet(client, URI.create(scriptUrl), null, Math.min(timeoutMs, 10_000));
                    if (resp.statusCode() >= 200 && resp.statusCode() < 300 && resp.body() != null) {
                        Map<String, GraphqlOperationMeta> found = parseGraphqlOperations(resp.body());
                        ctx.graphqlOps().putAll(found);
                        if (ctx.graphqlOps().keySet().containsAll(TRACKED_GRAPHQL_OPS)) {
                            break;
                        }
                    }
                } catch (Exception ignored) {
                    // Skip unreachable chunk and continue scanning
                }
            }
        } finally {
            ctx.opsScanned().set(true);
        }
    }

    static List<String> selectGraphqlBundleUrls(List<String> allScripts) {
        List<String> mainFirst = new ArrayList<>();
        List<String> xWebNext = new ArrayList<>();
        for (String url : allScripts) {
            if (url.contains("/main.")) {
                mainFirst.add(url);
            } else if (url.contains("/x-web/") && !INDICES_FILE_PATTERN.matcher(url).find()) {
                xWebNext.add(url);
            }
        }
        mainFirst.addAll(xWebNext);
        return List.copyOf(mainFirst);
    }

    static Map<String, GraphqlOperationMeta> parseGraphqlOperations(String scriptText) {
        Map<String, GraphqlOperationMeta> ops = new LinkedHashMap<>();
        if (!StringUtils.hasText(scriptText)) {
            return ops;
        }
        Matcher matcher = GRAPHQL_OP_PATTERN.matcher(scriptText);
        while (matcher.find()) {
            String queryId = matcher.group(1);
            String operationName = matcher.group(2);
            String switchesRaw = matcher.group(3);
            List<String> switches = new ArrayList<>();
            if (StringUtils.hasText(switchesRaw)) {
                Matcher swMatcher = QUOTED_STRING_PATTERN.matcher(switchesRaw);
                while (swMatcher.find()) {
                    switches.add(swMatcher.group(1));
                }
            }
            ops.put(operationName, new GraphqlOperationMeta(queryId, List.copyOf(switches)));
        }
        return ops;
    }

    private String fetchPageText(
            HttpClient client,
            URI uri,
            String cookieHeader,
            int timeoutMs
    ) throws Exception {
        HttpResponse<String> resp = sendGet(client, uri, cookieHeader, timeoutMs);
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("Bootstrap page returned HTTP " + resp.statusCode());
        }
        String body = resp.body();
        if (body == null || !body.contains(">document.location =")) {
            return body == null ? "" : body;
        }

        String redirectUrl = extractBetween(body, "document.location = \"", "\"");
        if (!StringUtils.hasText(redirectUrl)) {
            return body;
        }
        HttpResponse<String> nextResp = sendGet(client, URI.create(redirectUrl), cookieHeader, timeoutMs);
        if (nextResp.statusCode() < 200 || nextResp.statusCode() >= 300) {
            throw new IllegalStateException("Redirect page returned HTTP " + nextResp.statusCode());
        }
        String nextBody = nextResp.body() == null ? "" : nextResp.body();
        if (!nextBody.contains("action=\"https://x.com/x/migrate\" method=\"post\"")) {
            return nextBody;
        }

        Map<String, String> formData = new LinkedHashMap<>();
        Matcher inputMatcher = INPUT_TAG_PATTERN.matcher(nextBody);
        while (inputMatcher.find()) {
            String tag = inputMatcher.group();
            String name = extractAttr(NAME_ATTR_PATTERN, tag);
            String value = extractAttr(VALUE_ATTR_PATTERN, tag);
            if (name != null && value != null) {
                formData.put(name, value);
            }
        }
        HttpRequest migrateReq = HttpRequest.newBuilder(URI.create("https://x.com/x/migrate"))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("User-Agent", DEFAULT_WEB_USER_AGENT)
                .header("Cookie", cookieHeader)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(formData)))
                .build();
        HttpResponse<String> migrateResp = client.send(migrateReq, HttpResponse.BodyHandlers.ofString());
        if (migrateResp.statusCode() < 200 || migrateResp.statusCode() >= 300) {
            throw new IllegalStateException("Migrate endpoint returned HTTP " + migrateResp.statusCode());
        }
        return migrateResp.body() == null ? "" : migrateResp.body();
    }

    private HttpResponse<String> sendGet(
            HttpClient client,
            URI uri,
            String cookieHeader,
            int timeoutMs
    ) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(timeoutMs))
                .header("User-Agent", DEFAULT_WEB_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9");
        if (StringUtils.hasText(cookieHeader)) {
            builder.header("Cookie", cookieHeader);
        }
        return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    static byte[] parseVerificationKey(String html) {
        Matcher metaMatcher = META_TAG_PATTERN.matcher(html);
        while (metaMatcher.find()) {
            String tag = metaMatcher.group();
            String name = extractAttr(NAME_ATTR_PATTERN, tag);
            if ("twitter-site-verification".equalsIgnoreCase(name)) {
                String content = extractAttr(CONTENT_ATTR_PATTERN, tag);
                if (StringUtils.hasText(content)) {
                    return Base64.getDecoder().decode(content.trim());
                }
            }
        }
        throw new IllegalStateException("X verification key meta tag not found");
    }

    static List<double[]> parseAnimationRows(String html, byte[] vkBytes) {
        if (vkBytes.length <= 5) {
            throw new IllegalStateException("X verification key is too short");
        }
        List<String> secondPathDs = new ArrayList<>();
        Matcher svgMatcher = SVG_ANIM_PATTERN.matcher(html);
        while (svgMatcher.find()) {
            String svgInner = svgMatcher.group(1);
            Matcher gMatcher = FIRST_G_PATTERN.matcher(svgInner);
            String container = gMatcher.find() ? gMatcher.group(1) : svgInner;

            Matcher pathMatcher = PATH_D_PATTERN.matcher(container);
            int pathIndex = 0;
            while (pathMatcher.find()) {
                pathIndex++;
                if (pathIndex == 2) {
                    secondPathDs.add(pathMatcher.group(1).trim());
                    break;
                }
            }
        }
        if (secondPathDs.isEmpty()) {
            throw new IllegalStateException("X SVG animation data not found");
        }

        int idx = (vkBytes[5] & 0xFF) % secondPathDs.size();
        String d = secondPathDs.get(idx);
        if (d.length() <= 9) {
            throw new IllegalStateException("X SVG animation path is malformed");
        }
        String[] segments = d.substring(9).split("C");
        List<double[]> rows = new ArrayList<>(segments.length);
        for (String seg : segments) {
            String cleaned = seg.replaceAll("[^\\d]+", " ").trim();
            if (cleaned.isEmpty()) {
                rows.add(new double[0]);
                continue;
            }
            String[] nums = cleaned.split("\\s+");
            double[] row = new double[nums.length];
            for (int i = 0; i < nums.length; i++) {
                row[i] = Double.parseDouble(nums[i]);
            }
            rows.add(row);
        }
        return rows;
    }

    static List<String> extractScriptUrls(String html, URI pageUri) {
        Set<String> urls = new LinkedHashSet<>();

        // Support script tags referencing local/relative signing or main bundles first (reverse proxy / test mock)
        Matcher localScriptMatcher = LOCAL_SCRIPT_SRC_PATTERN.matcher(html);
        while (localScriptMatcher.find()) {
            String src = localScriptMatcher.group(1);
            if (INDICES_FILE_PATTERN.matcher(src).find() || src.contains("/x-web/") || src.contains("/main.")) {
                urls.add(pageUri.resolve(src).toString());
            }
        }

        Matcher xWebMatcher = ASSET_XWEB_URL_PATTERN.matcher(html);
        while (xWebMatcher.find()) {
            urls.add(xWebMatcher.group());
        }
        Matcher responsiveMatcher = RESPONSIVE_WEB_URL_PATTERN.matcher(html);
        while (responsiveMatcher.find()) {
            urls.add(responsiveMatcher.group());
        }
        boolean hasMainUrl = urls.stream().anyMatch(u -> u.contains("/main."));
        if (!hasMainUrl) {
            Matcher mainMatcher = LEGACY_MAIN_PATTERN.matcher(html);
            if (mainMatcher.find()) {
                urls.add(clientWebScriptUrl("main", mainMatcher.group(1)));
            }
        }

        Map<String, String> hashMap = new LinkedHashMap<>();
        Matcher hashMatcher = HASH_MAP_PATTERN.matcher(html);
        while (hashMatcher.find()) {
            hashMap.put(hashMatcher.group(1), hashMatcher.group(2));
        }

        Map<String, String> nameMap = new LinkedHashMap<>();
        Matcher nameMatcher = NAME_MAP_PATTERN.matcher(html);
        while (nameMatcher.find()) {
            String val = nameMatcher.group(2);
            if (!HEX_HASH_PATTERN.matcher(val).matches()) {
                nameMap.put(nameMatcher.group(1), val);
            }
        }
        for (Map.Entry<String, String> entry : hashMap.entrySet()) {
            String name = nameMap.getOrDefault(entry.getKey(), entry.getKey());
            urls.add(clientWebScriptUrl(name, entry.getValue() + "a"));
        }

        if (urls.isEmpty()) {
            throw new IllegalStateException("X web script URLs not found");
        }
        return new ArrayList<>(urls);
    }

    private List<Integer> parseSigningIndices(
            List<String> allScripts,
            HttpClient client,
            int timeoutMs
    ) throws Exception {
        List<String> scripts = allScripts;
        List<String> xWebScripts = scripts.stream().filter(u -> u.contains("/x-web/")).toList();
        if (!xWebScripts.isEmpty()) {
            boolean loggedOut = xWebScripts.stream().anyMatch(u -> LOGGED_OUT_ENTRY_PATTERN.matcher(u).find());
            if (loggedOut) {
                throw new IllegalStateException("Logged-out X web app (auth_token or ct0 may be invalid/expired)");
            }
            scripts = xWebScripts;
        }

        String signingScriptUrl = scripts.stream()
                .filter(u -> INDICES_FILE_PATTERN.matcher(u).find())
                .findFirst()
                .orElse(null);

        if (signingScriptUrl == null) {
            signingScriptUrl = findDynamicIndicesUrl(scripts, client, timeoutMs);
        }

        HttpResponse<String> scriptResp = sendGet(client, URI.create(signingScriptUrl), null, timeoutMs);
        if (scriptResp.statusCode() < 200 || scriptResp.statusCode() >= 300) {
            throw new IllegalStateException("Signing script returned HTTP " + scriptResp.statusCode());
        }
        return extractIndicesFromScript(scriptResp.body() == null ? "" : scriptResp.body());
    }

    private String findDynamicIndicesUrl(List<String> scripts, HttpClient client, int timeoutMs) {
        int limit = Math.min(scripts.size(), MAX_DYNAMIC_CHUNKS_TO_SCAN);
        for (int i = 0; i < limit; i++) {
            String url = scripts.get(i);
            try {
                HttpResponse<String> resp = sendGet(client, URI.create(url), null, Math.min(timeoutMs, 8000));
                if (resp.statusCode() >= 200 && resp.statusCode() < 300 && resp.body() != null) {
                    Matcher m = INDICES_FILE_PATTERN.matcher(resp.body());
                    if (m.find()) {
                        return URI.create(url).resolve(m.group()).toString();
                    }
                }
            } catch (Exception ignored) {
                // Skip unreachable chunk and continue scanning
            }
        }
        throw new IllegalStateException("X signing script reference not found in web bundles");
    }

    static List<Integer> extractIndicesFromScript(String scriptText) {
        List<Integer> indices = new ArrayList<>();
        Matcher matcher = INDICES_REGEX.matcher(scriptText);
        while (matcher.find()) {
            indices.add(Integer.parseInt(matcher.group(2)));
        }
        if (indices.size() < 2) {
            throw new IllegalStateException("X signing indices not found in script");
        }
        return indices;
    }

    static String computeAnimKey(byte[] vkBytes, List<double[]> animArr, List<Integer> animIdx) {
        int frameTime = 1;
        for (int i = 1; i < animIdx.size(); i++) {
            frameTime *= (vkBytes[animIdx.get(i)] & 0xFF) % 16;
        }
        frameTime = (int) (Math.floor(frameTime / 10.0 + 0.5) * 10);

        int frameIdx = (vkBytes[animIdx.get(0)] & 0xFF) % 16;
        double[] frameRow = animArr.get(frameIdx);
        double frameDur = (double) frameTime / 4096.0;
        return calcAnimKey(frameRow, frameDur);
    }

    static String calcAnimKey(double[] frames, double targetTime) {
        double[] fromColor = new double[]{frames[0], frames[1], frames[2], 1.0};
        double[] toColor = new double[]{frames[3], frames[4], frames[5], 1.0};
        double fromRotation = 0.0;
        double toRotation = solve(frames[6], 60.0, 360.0, true);

        double[] curves = new double[frames.length - 7];
        for (int i = 0; i < curves.length; i++) {
            curves[i] = solve(frames[7 + i], (i % 2 != 0) ? -1.0 : 0.0, 1.0, false);
        }
        double val = new Cubic(curves).getValue(targetTime);

        double[] color = interpolate(fromColor, toColor, val);
        for (int i = 0; i < color.length; i++) {
            color[i] = Math.max(0.0, Math.min(255.0, color[i]));
        }
        double rotation = fromRotation * (1.0 - val) + toRotation * val;
        double[] matrix = getRotationMatrix(rotation);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            int roundedChannel = roundHalfEvenInt(color[i]);
            sb.append(Integer.toHexString(roundedChannel));
        }
        for (double value : matrix) {
            double rounded = roundHalfEven(value, 2);
            if (rounded < 0.0) {
                rounded = -rounded;
            }
            String hexValue = floatToHex(rounded);
            if (hexValue.startsWith(".")) {
                sb.append(("0" + hexValue).toLowerCase(Locale.ROOT));
            } else if (!hexValue.isEmpty()) {
                sb.append(hexValue);
            } else {
                sb.append("0");
            }
        }
        sb.append("00");
        return sb.toString().replace(".", "").replace("-", "");
    }

    private static double[] interpolate(double[] fromList, double[] toList, double f) {
        double[] result = new double[fromList.length];
        for (int i = 0; i < fromList.length; i++) {
            result[i] = fromList[i] * (1.0 - f) + toList[i] * f;
        }
        return result;
    }

    private static double[] getRotationMatrix(double rotationDeg) {
        double rad = Math.toRadians(rotationDeg);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        return new double[]{cos, -sin, sin, cos};
    }

    private static double solve(double value, double minVal, double maxVal, boolean floorRounding) {
        double result = value * (maxVal - minVal) / 255.0 + minVal;
        return floorRounding ? Math.floor(result) : roundHalfEven(result, 2);
    }

    private static double roundHalfEven(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_EVEN).doubleValue();
    }

    private static int roundHalfEvenInt(double value) {
        return BigDecimal.valueOf(value).setScale(0, RoundingMode.HALF_EVEN).intValue();
    }

    static String floatToHex(double x) {
        StringBuilder result = new StringBuilder();
        long quotient = (long) x;
        double fraction = x - quotient;

        while (quotient > 0) {
            quotient = (long) (x / 16.0);
            int remainder = (int) (x - ((double) quotient * 16.0));
            if (remainder > 9) {
                result.insert(0, (char) (remainder + 55));
            } else {
                result.insert(0, remainder);
            }
            x = (double) quotient;
        }

        if (fraction == 0.0) {
            return result.toString();
        }

        result.append('.');
        int guard = 0;
        while (fraction > 0.0 && guard++ < 64) {
            fraction *= 16.0;
            int integer = (int) fraction;
            fraction -= (double) integer;
            if (integer > 9) {
                result.append((char) (integer + 55));
            } else {
                result.append(integer);
            }
        }
        return result.toString();
    }

    private static String clientWebScriptUrl(String name, String hash) {
        return "https://abs.twimg.com/responsive-web/client-web/" + name + "." + hash + ".js";
    }

    private static String extractAttr(Pattern pattern, String tag) {
        Matcher m = pattern.matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    private static String extractBetween(String text, String startToken, String endToken) {
        int start = text.indexOf(startToken);
        if (start < 0) {
            return null;
        }
        int valStart = start + startToken.length();
        int end = text.indexOf(endToken, valStart);
        return end < 0 ? null : text.substring(valStart, end);
    }

    private static String buildCacheKey(String bootstrapBase, String authToken) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(authToken.getBytes(StandardCharsets.UTF_8));
            return bootstrapBase + "|" + Base64.getEncoder().encodeToString(hash);
        } catch (Exception ex) {
            return bootstrapBase + "|" + authToken.hashCode();
        }
    }

    static final class Cubic {
        private final double[] curves;

        Cubic(double[] curves) {
            this.curves = curves;
        }

        double getValue(double time) {
            double startGradient = 0.0;
            double endGradient = 0.0;
            double start = 0.0;
            double mid = 0.0;
            double end = 1.0;

            if (time <= 0.0) {
                if (curves[0] > 0.0) {
                    startGradient = curves[1] / curves[0];
                } else if (curves[1] == 0.0 && curves[2] > 0.0) {
                    startGradient = curves[3] / curves[2];
                }
                return startGradient * time;
            }

            if (time >= 1.0) {
                if (curves[2] < 1.0) {
                    endGradient = (curves[3] - 1.0) / (curves[2] - 1.0);
                } else if (curves[2] == 1.0 && curves[0] < 1.0) {
                    endGradient = (curves[1] - 1.0) / (curves[0] - 1.0);
                }
                return 1.0 + endGradient * (time - 1.0);
            }

            while (start < end) {
                mid = (start + end) / 2.0;
                double xEst = calculate(curves[0], curves[2], mid);
                if (Math.abs(time - xEst) < 0.00001) {
                    return calculate(curves[1], curves[3], mid);
                }
                if (xEst < time) {
                    start = mid;
                } else {
                    end = mid;
                }
            }
            return calculate(curves[1], curves[3], mid);
        }

        private static double calculate(double a, double b, double m) {
            return 3.0 * a * (1.0 - m) * (1.0 - m) * m
                    + 3.0 * b * (1.0 - m) * m * m
                    + m * m * m;
        }
    }

    static final class TransactionKeys {
        private final byte[] vkBytes;
        private final String animKey;

        TransactionKeys(byte[] vkBytes, String animKey) {
            this.vkBytes = vkBytes.clone();
            this.animKey = animKey;
        }

        byte[] vkBytes() {
            return vkBytes.clone();
        }

        String animKey() {
            return animKey;
        }

        String generate(String method, String path) {
            return generate(method, path, System.currentTimeMillis(), ThreadLocalRandom.current().nextInt(256));
        }

        String generate(String method, String path, long nowMillis, int xorMask) {
            long ts = Math.floorDiv(nowMillis - EPOCH_OFFSET_MS, 1000L);
            byte[] tsBytes = new byte[4];
            for (int i = 0; i < 4; i++) {
                tsBytes[i] = (byte) ((ts >> (i * 8)) & 0xFF);
            }

            String normalizedPath = StringUtils.hasText(path) ? path : "/";
            String payloadInput = method.toUpperCase(Locale.ROOT)
                    + "!" + normalizedPath
                    + "!" + ts + DEFAULT_KEYWORD + animKey;
            byte[] sha;
            try {
                sha = MessageDigest.getInstance("SHA-256")
                        .digest(payloadInput.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ex) {
                throw new IllegalStateException("SHA-256 unavailable", ex);
            }

            int rawLen = vkBytes.length + 4 + 16 + 1;
            byte[] out = new byte[1 + rawLen];
            int mask = xorMask & 0xFF;
            out[0] = (byte) mask;

            int pos = 1;
            for (byte b : vkBytes) {
                out[pos++] = (byte) ((b & 0xFF) ^ mask);
            }
            for (byte b : tsBytes) {
                out[pos++] = (byte) ((b & 0xFF) ^ mask);
            }
            for (int i = 0; i < 16; i++) {
                out[pos++] = (byte) ((sha[i] & 0xFF) ^ mask);
            }
            out[pos] = (byte) ((DEFAULT_RANDOM_BYTE & 0xFF) ^ mask);

            return Base64.getEncoder().withoutPadding().encodeToString(out);
        }
    }

    public record GraphqlOperationMeta(String queryId, List<String> featureSwitches) {
    }

    private record CachedWebContext(
            TransactionKeys keys,
            List<String> bundleScriptUrls,
            ConcurrentHashMap<String, GraphqlOperationMeta> graphqlOps,
            AtomicBoolean opsScanned,
            long fetchedAtMs
    ) {
    }
}
