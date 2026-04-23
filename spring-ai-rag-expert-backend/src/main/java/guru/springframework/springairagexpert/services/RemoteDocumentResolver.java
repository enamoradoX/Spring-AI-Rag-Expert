package guru.springframework.springairagexpert.services;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class RemoteDocumentResolver {

    private static final Tika TIKA = new Tika();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static final Pattern LINK_ATTR_PATTERN = Pattern.compile("(?i)(?:href|src)\\s*=\\s*[\"']([^\"'#]+)[\"']");
    private static final Pattern URL_PATTERN = Pattern.compile("(?i)https?://[^\"'\\s>]+|//[^\"'\\s>]+|/getmedia/[^\"'\\s>]+");
    private static final Pattern FILENAME_PATTERN = Pattern.compile("(?i)filename\\*?=(?:UTF-8''|\"?)([^\";]+)\"?");
    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    public ResolvedRemoteDocument resolve(String url) throws Exception {
        return resolve(url, url, 0, new java.util.HashSet<>(Set.of(url)));
    }

    private ResolvedRemoteDocument resolve(String originalUrl, String currentUrl, int depth, Set<String> visited) throws Exception {
        HttpResponse<byte[]> response = fetch(currentUrl);
        byte[] bytes = response.body();
        URI finalUri = response.uri();
        String contentTypeHeader = firstHeader(response, "Content-Type");
        String contentDisposition = firstHeader(response, "Content-Disposition");
        String filename = determineFilename(originalUrl, finalUri, contentDisposition);
        String mimeType = determineMimeType(bytes, filename, contentTypeHeader);

        if (!looksLikeHtml(mimeType, bytes)) {
            return new ResolvedRemoteDocument(originalUrl, finalUri.toString(), bytes, mimeType, filename);
        }

        if (depth >= 2) {
            return new ResolvedRemoteDocument(originalUrl, finalUri.toString(), bytes, mimeType, filename);
        }

        String html = decodeText(bytes, contentTypeHeader);
        for (String candidate : extractCandidateUrls(html, finalUri, originalUrl)) {
            if (!visited.add(candidate)) {
                continue;
            }
            try {
                ResolvedRemoteDocument resolvedCandidate = resolve(originalUrl, candidate, depth + 1, visited);
                if (isPdfLike(resolvedCandidate)) {
                    log.debug("Resolved landing page {} to PDF asset {}", originalUrl, resolvedCandidate.resolvedUrl());
                    return resolvedCandidate;
                }
            } catch (Exception e) {
                log.debug("Candidate URL {} did not resolve to a PDF for {}: {}", candidate, originalUrl, e.getMessage());
            }
        }

        return new ResolvedRemoteDocument(originalUrl, finalUri.toString(), bytes, mimeType, filename);
    }

    private HttpResponse<byte[]> fetch(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "application/pdf,text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .GET()
                .timeout(Duration.ofSeconds(60))
                .build();

        HttpResponse<byte[]> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + response.statusCode() + " fetching " + url);
        }
        return response;
    }

    private static String firstHeader(HttpResponse<byte[]> response, String name) {
        return response.headers().firstValue(name).orElse("");
    }

    private static String determineMimeType(byte[] bytes, String filename, String headerContentType) {
        if (hasPdfMagic(bytes)) {
            return "application/pdf";
        }

        String detected = TIKA.detect(bytes, filename);
        if (detected != null && detected.toLowerCase(Locale.ROOT).contains("pdf")) {
            return detected;
        }

        String headerMime = cleanMimeType(headerContentType);
        if (!headerMime.isBlank() && !"application/octet-stream".equalsIgnoreCase(headerMime)) {
            return headerMime;
        }

        if (detected == null || detected.isBlank()) {
            return "application/octet-stream";
        }
        return detected;
    }

    private static boolean looksLikeHtml(String mimeType, byte[] bytes) {
        String lowerMime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if (lowerMime.contains("html") || lowerMime.contains("xhtml")) {
            return true;
        }
        String sample = new String(bytes, 0, Math.min(bytes.length, 256), StandardCharsets.UTF_8)
                .trim()
                .toLowerCase(Locale.ROOT);
        return sample.startsWith("<!doctype html") || sample.startsWith("<html") || sample.contains("<html");
    }

    private static boolean isPdfLike(ResolvedRemoteDocument document) {
        return document.mimeType().toLowerCase(Locale.ROOT).contains("pdf")
                || hasPdfMagic(document.bytes())
                || document.filename().toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    private static boolean hasPdfMagic(byte[] bytes) {
        if (bytes == null || bytes.length < PDF_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < PDF_MAGIC.length; i++) {
            if (bytes[i] != PDF_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static String determineFilename(String originalUrl, URI finalUri, String contentDisposition) {
        String fromDisposition = extractFilenameFromContentDisposition(contentDisposition);
        if (!fromDisposition.isBlank()) {
            return fromDisposition;
        }

        String fromFinalUri = lastPathSegment(finalUri.toString());
        if (!fromFinalUri.isBlank()) {
            return fromFinalUri;
        }

        String fromOriginal = lastPathSegment(originalUrl);
        if (!fromOriginal.isBlank()) {
            return fromOriginal;
        }

        return "document";
    }

    private static String extractFilenameFromContentDisposition(String contentDisposition) {
        if (contentDisposition == null || contentDisposition.isBlank()) {
            return "";
        }
        Matcher matcher = FILENAME_PATTERN.matcher(contentDisposition);
        if (matcher.find()) {
            String filename = matcher.group(1).trim();
            return URLDecoder.decode(filename, StandardCharsets.UTF_8);
        }
        return "";
    }

    private static String cleanMimeType(String contentTypeHeader) {
        if (contentTypeHeader == null || contentTypeHeader.isBlank()) {
            return "";
        }
        int semi = contentTypeHeader.indexOf(';');
        return (semi >= 0 ? contentTypeHeader.substring(0, semi) : contentTypeHeader).trim();
    }

    private static String decodeText(byte[] bytes, String contentTypeHeader) {
        Charset charset = StandardCharsets.UTF_8;
        String lower = contentTypeHeader == null ? "" : contentTypeHeader.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("charset=");
        if (idx >= 0) {
            String raw = contentTypeHeader.substring(idx + 8).trim();
            int semi = raw.indexOf(';');
            if (semi >= 0) {
                raw = raw.substring(0, semi);
            }
            raw = raw.replace("\"", "").trim();
            try {
                charset = Charset.forName(raw);
            } catch (Exception ignored) {
            }
        }
        return new String(bytes, charset);
    }

    private static List<String> extractCandidateUrls(String html, URI baseUri, String originalUrl) {
        Map<String, Integer> candidates = new LinkedHashMap<>();
        addCandidatesFromMatcher(candidates, LINK_ATTR_PATTERN.matcher(html), 1, baseUri, originalUrl);
        addCandidatesFromMatcher(candidates, URL_PATTERN.matcher(html), 0, baseUri, originalUrl);

        return candidates.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .limit(12)
                .toList();
    }

    private static void addCandidatesFromMatcher(Map<String, Integer> candidates,
                                                 Matcher matcher,
                                                 int group,
                                                 URI baseUri,
                                                 String originalUrl) {
        while (matcher.find()) {
            String candidate = absolutize(matcher.group(group), baseUri);
            if (candidate == null) {
                continue;
            }
            candidates.merge(candidate, scoreCandidate(candidate, baseUri, originalUrl), Math::max);
        }
    }

    private static String absolutize(String rawUrl, URI baseUri) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        String trimmed = rawUrl.trim();
        if (trimmed.startsWith("data:") || trimmed.startsWith("mailto:") || trimmed.startsWith("javascript:")) {
            return null;
        }
        try {
            if (trimmed.startsWith("//")) {
                return baseUri.getScheme() + ":" + trimmed;
            }
            URI uri = URI.create(trimmed);
            if (uri.isAbsolute()) {
                return uri.toString();
            }
            return baseUri.resolve(trimmed).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static int scoreCandidate(String candidate, URI baseUri, String originalUrl) {
        String lower = candidate.toLowerCase(Locale.ROOT);
        int score = 0;

        // ── Hard penalties ─────────────────────────────────────────────────────────
        // Known non-document file types: images, scripts, fonts, media.
        // These can never be the target document — drop them out of contention.
        if (lower.endsWith(".png")  || lower.endsWith(".jpg")  || lower.endsWith(".jpeg") ||
            lower.endsWith(".gif")  || lower.endsWith(".svg")  || lower.endsWith(".webp") ||
            lower.endsWith(".ico")  || lower.endsWith(".bmp")  || lower.endsWith(".tiff") ||
            lower.endsWith(".mp4")  || lower.endsWith(".mp3")  || lower.endsWith(".css")  ||
            lower.endsWith(".js")   || lower.endsWith(".woff") || lower.endsWith(".woff2")) {
            return -500;
        }

        // ── Positive signals ───────────────────────────────────────────────────────
        if (lower.contains(".pdf"))        score += 60;
        if (lower.contains("/getmedia/"))  score += 90;
        if (lower.contains("pdf"))         score += 20;
        if (lower.contains("pb_"))         score += 35;
        if (lower.contains("bulletin"))    score += 25;
        if (lower.contains("performance")) score += 20;

        // Getmedia link with no recognised file extension → typical Yamaha bulletin
        // pattern (e.g. pb_spt_open_212_f150xb..._occ has no extension at all).
        // This is a strong signal even when we cannot slug-match due to encoding.
        if (lower.contains("/getmedia/") &&
            !lower.contains(".pdf") && !lower.contains(".docx") &&
            !lower.contains(".txt") && !lower.contains(".doc")) {
            score += 150;
        }

        // ── Strongest signal: slug match ───────────────────────────────────────────
        // The candidate's filename/slug contains the same normalised token as the
        // original URL's last path segment.  Weight this very heavily so the matching
        // document beats any other PDF found on the same landing page.
        String originalToken = normalizeToken(lastPathSegment(originalUrl));
        String candidateToken = normalizeToken(candidate);
        if (!originalToken.isBlank() && candidateToken.contains(originalToken)) {
            score += 500;
        }

        if (baseUri.getHost() != null && lower.contains(baseUri.getHost().toLowerCase(Locale.ROOT))) {
            score += 5;
        }

        return score;
    }

    private static String normalizeToken(String value) {
        if (value == null) {
            return "";
        }
        try {
            // Decode percent-encoded characters first so %20 (space) is treated the
            // same as a hyphen or underscore — all become empty after stripping
            // non-alphanumerics.  Without this, "pb_spt_open%20232" normalises to
            // "pbsptopen20232" instead of "pbsptopen232" and the slug match fails.
            value = URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String lastPathSegment(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path == null || path.isBlank()) {
                return "";
            }
            List<String> parts = new ArrayList<>();
            for (String part : path.split("/")) {
                if (!part.isBlank()) {
                    parts.add(part);
                }
            }
            return parts.isEmpty() ? "" : parts.getLast();
        } catch (Exception e) {
            return "";
        }
    }

    public record ResolvedRemoteDocument(
            String originalUrl,
            String resolvedUrl,
            byte[] bytes,
            String mimeType,
            String filename
    ) {}
}

