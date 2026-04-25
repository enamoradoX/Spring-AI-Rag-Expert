package guru.springframework.springairagexpert.controllers;

import guru.springframework.springairagexpert.model.DocumentLoadRequest;
import guru.springframework.springairagexpert.model.DocumentLoadResponse;
import guru.springframework.springairagexpert.services.DocumentLoaderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/documents")
@Slf4j
public class DocumentLoaderController {

    private static final Set<String> ALLOWED_UPLOAD_EXTENSIONS = Set.of("pdf", "docx", "doc", "txt");

    private final DocumentLoaderService documentLoaderService;

    @GetMapping
    public List<String> listDocuments() {
        return documentLoaderService.listDocuments();
    }

    @DeleteMapping
    public DocumentLoadResponse deleteDocument(@RequestParam String url) {
        try {
            documentLoaderService.deleteDocument(url);
            return new DocumentLoadResponse("Document deleted successfully: " + url, true);
        } catch (IllegalArgumentException e) {
            return new DocumentLoadResponse(e.getMessage(), false);
        } catch (Exception e) {
            log.error("Error deleting document: {}", e.getMessage(), e);
            return new DocumentLoadResponse("Failed to delete document: " + e.getMessage(), false);
        }
    }

    @PostMapping("/load")
    public DocumentLoadResponse loadDocument(@RequestBody DocumentLoadRequest request) {
        try {
            if (request.documentUrl() != null && !request.documentUrl().isBlank()) {
                if (request.metadata() != null && !request.metadata().isEmpty()) {
                    documentLoaderService.loadDocumentWithMetadata(request.documentUrl(), request.metadata());
                } else {
                    documentLoaderService.loadDocument(request.documentUrl());
                }
                return new DocumentLoadResponse("Document loaded successfully from: " + request.documentUrl(), true);
            } else if (request.documentUrls() != null && !request.documentUrls().isEmpty()) {
                documentLoaderService.loadDocuments(request.documentUrls());
                return new DocumentLoadResponse("Loaded " + request.documentUrls().size() + " documents", true);
            } else {
                return new DocumentLoadResponse("No document URL(s) provided", false);
            }
        } catch (IllegalStateException e) {
            return new DocumentLoadResponse(e.getMessage(), false);
        } catch (Exception e) {
            log.error("Error loading document: {}", e.getMessage(), e);
            return new DocumentLoadResponse("Failed to load document: " + e.getMessage(), false);
        }
    }

    @PostMapping("/load-single")
    public DocumentLoadResponse loadSingleDocument(@RequestParam String url) {
        try {
            documentLoaderService.loadDocument(url);
            return new DocumentLoadResponse("Document loaded successfully from: " + url, true);
        } catch (IllegalStateException e) {
            return new DocumentLoadResponse(e.getMessage(), false);
        } catch (Exception e) {
            log.error("Error loading document: {}", e.getMessage(), e);
            return new DocumentLoadResponse("Failed to load document: " + e.getMessage(), false);
        }
    }

    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentLoadResponse uploadDocument(@RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return new DocumentLoadResponse("Uploaded file is empty", false);
            }

            String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "uploaded-file";
            String extension = filename.contains(".") ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase() : "";
            if (!ALLOWED_UPLOAD_EXTENSIONS.contains(extension)) {
                return new DocumentLoadResponse("Unsupported file type. Allowed: .pdf, .docx, .doc, .txt", false);
            }

            String documentUrl = documentLoaderService.loadUploadedDocument(
                    filename,
                    file.getBytes(),
                    Map.of("uploaded_filename", filename)
            );

            return new DocumentLoadResponse("Document uploaded successfully: " + documentUrl, true);
        } catch (Exception e) {
            log.error("Error uploading document: {}", e.getMessage(), e);
            return new DocumentLoadResponse("Failed to upload document: " + e.getMessage(), false);
        }
    }
}
