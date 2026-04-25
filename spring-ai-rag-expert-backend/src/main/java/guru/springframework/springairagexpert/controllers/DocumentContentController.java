package guru.springframework.springairagexpert.controllers;

import guru.springframework.springairagexpert.config.S3ClientFactory;
import guru.springframework.springairagexpert.config.S3RuntimeConfigService;
import guru.springframework.springairagexpert.services.DocumentLoaderService;
import guru.springframework.springairagexpert.services.RemoteDocumentResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.net.URI;
import java.net.URLConnection;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/documents")
@Slf4j
public class DocumentContentController {

    private static final Pattern S3_URI_PATTERN = Pattern.compile("s3://([^/]+)/(.+)");

    private final ResourceLoader resourceLoader;
    private final DocumentLoaderService documentLoaderService;
    private final RemoteDocumentResolver remoteDocumentResolver;
    private final S3ClientFactory s3ClientFactory;
    private final S3RuntimeConfigService s3RuntimeConfigService;

    private static final Tika TIKA = new Tika();

    /**
     * Fetches raw bytes for a URL.  For HTTP(S) URLs uses a proper HttpClient
     * with redirect-following and a User-Agent header so servers that would
     * otherwise return an HTML redirect page (e.g. Yamaha brochure URLs) serve
     * the actual file bytes instead.  Classpath / file resources fall back to
     * Spring's ResourceLoader.
     */
    private byte[] fetchBytes(String url) throws Exception {
        byte[] cached = documentLoaderService.getCachedBytes(url);
        if (cached != null) {
            log.debug("Serving {} bytes from cache for {}", cached.length, url);
            return cached;
        }

        if (url.startsWith("s3://")) {
            // Fall back to live S3 download
            Matcher m = S3_URI_PATTERN.matcher(url);
            if (!m.matches()) throw new IllegalArgumentException("Invalid S3 URI: " + url);
            String bucket = m.group(1);
            String key = m.group(2);
            log.debug("Fetching s3://{}/{} from S3", bucket, key);
            try (S3Client s3Client = s3ClientFactory.createClient(s3RuntimeConfigService.getConfig())) {
                return s3Client.getObjectAsBytes(
                        GetObjectRequest.builder().bucket(bucket).key(key).build()
                ).asByteArray();
            }
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            RemoteDocumentResolver.ResolvedRemoteDocument resolved = remoteDocumentResolver.resolve(url);
            log.debug("Resolved remote view request {} -> {} ({})", url, resolved.resolvedUrl(), resolved.mimeType());
            return resolved.bytes();
        }
        // Classpath / file resources
        Resource resource = resourceLoader.getResource(url);
        if (!resource.exists()) throw new IllegalArgumentException("Resource not found: " + url);
        return resource.getContentAsByteArray();
    }

    /**
     * Returns the detected file type for a document URL.
     * Returns: { "type": "pdf" | "docx" | "text", "mimeType": "..." }
     */
    @GetMapping("/filetype")
    public Map<String, String> getFileType(@RequestParam String url) {
        try {
            byte[] bytes = fetchBytes(url);
            String filename = extractFilename(url);
            String mimeType = TIKA.detect(bytes, filename);
            if (mimeType == null) mimeType = "application/octet-stream";

            String type = "text";
            if (mimeType.contains("pdf")) type = "pdf";
            else if (mimeType.contains("wordprocessingml") || mimeType.contains("msword")) type = "docx";

            log.debug("Detected file type for {}: {} ({})", url, type, mimeType);
            return Map.of("type", type, "mimeType", mimeType);
        } catch (Exception e) {
            log.error("Failed to detect file type for {}: {}", url, e.getMessage(), e);
            return Map.of("type", "text", "mimeType", "");
        }
    }

    /**
     * Streams the raw file bytes with the correct Content-Type for browser rendering.
     */
    @GetMapping("/raw")
    public ResponseEntity<byte[]> getRawDocument(@RequestParam String url) {
        try {
            byte[] bytes = fetchBytes(url);
            String filename = extractFilename(url);

            // Try filename extension first, then fall back to magic-byte detection
            String mimeType = URLConnection.guessContentTypeFromName(filename);
            if (mimeType == null || mimeType.equals("application/octet-stream")) {
                mimeType = TIKA.detect(bytes, filename);
            }
            if (mimeType == null) mimeType = "application/octet-stream";

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mimeType))
                    .header("Content-Disposition", "inline; filename=\"" + filename + "\"")
                    .body(bytes);
        } catch (Exception e) {
            log.error("Failed to serve raw document for {}: {}", url, e.getMessage(), e);
            throw new RuntimeException("Failed to serve document: " + e.getMessage());
        }
    }

    /**
     * Returns the plain text content of a document (used for plain-text viewer).
     */
    @GetMapping("/content")
    public String getDocumentContent(@RequestParam String url) {
        try {
            byte[] bytes = fetchBytes(url);
            String filename = extractFilename(url);
            org.springframework.core.io.ByteArrayResource resource =
                    new org.springframework.core.io.ByteArrayResource(bytes) {
                        @Override public String getFilename() { return filename; }
                    };
            TikaDocumentReader reader = new TikaDocumentReader(resource);
            StringBuilder sb = new StringBuilder();
            reader.get().forEach(doc -> sb.append(doc.getText()).append("\n\n"));
            return sb.toString().trim();
        } catch (Exception e) {
            log.error("Failed to read document content for {}: {}", url, e.getMessage(), e);
            throw new RuntimeException("Failed to read document: " + e.getMessage());
        }
    }

    /** Extracts the last path segment of a URL as a filename hint for Tika. */
    private static String extractFilename(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path != null && path.contains("/")) {
                String last = path.substring(path.lastIndexOf('/') + 1);
                if (!last.isBlank()) return last;
            }
        } catch (Exception ignored) {}
        return "file";
    }
}
