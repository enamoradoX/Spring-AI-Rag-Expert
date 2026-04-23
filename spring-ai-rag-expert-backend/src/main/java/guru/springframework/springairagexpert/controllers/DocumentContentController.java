package guru.springframework.springairagexpert.controllers;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLConnection;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/documents")
@Slf4j
public class DocumentContentController {

    private final ResourceLoader resourceLoader;

    /**
     * Returns the plain text content of a document (for .txt, .docx, etc.)
     */
    @GetMapping("/content")
    public String getDocumentContent(@RequestParam String url) {
        try {
            Resource resource = resourceLoader.getResource(url);
            if (!resource.exists()) throw new IllegalArgumentException("Resource not found: " + url);

            TikaDocumentReader reader = new TikaDocumentReader(resource);
            StringBuilder sb = new StringBuilder();
            reader.get().forEach(doc -> sb.append(doc.getText()).append("\n\n"));
            return sb.toString().trim();
        } catch (Exception e) {
            log.error("Failed to read document content for {}: {}", url, e.getMessage(), e);
            throw new RuntimeException("Failed to read document: " + e.getMessage());
        }
    }

    /**
     * Streams the raw file bytes so the browser can render it natively (e.g. PDF).
     */
    @GetMapping("/raw")
    public ResponseEntity<byte[]> getRawDocument(@RequestParam String url) {
        try {
            Resource resource = resourceLoader.getResource(url);
            if (!resource.exists()) throw new IllegalArgumentException("Resource not found: " + url);

            byte[] bytes = resource.getContentAsByteArray();

            String filename = resource.getFilename() != null ? resource.getFilename() : "file";
            String mimeType = URLConnection.guessContentTypeFromName(filename);
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
}

