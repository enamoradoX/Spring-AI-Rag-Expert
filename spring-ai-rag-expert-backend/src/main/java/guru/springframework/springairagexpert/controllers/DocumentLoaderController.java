package guru.springframework.springairagexpert.controllers;

import guru.springframework.springairagexpert.model.DocumentLoadRequest;
import guru.springframework.springairagexpert.model.DocumentLoadResponse;
import guru.springframework.springairagexpert.services.DocumentLoaderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Created by jt, Spring Framework Guru.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/documents")
@Slf4j
public class DocumentLoaderController {

    private final DocumentLoaderService documentLoaderService;

    @PostMapping("/load-single")
    public DocumentLoadResponse loadSingleDocument(@RequestParam String url) {
        try {
            boolean loaded = documentLoaderService.loadDocument(url);
            if (loaded) {
                return new DocumentLoadResponse("Document loaded successfully!", true, false);
            } else {
                return new DocumentLoadResponse("Document has already been uploaded!", true, true);
            }
        } catch (Exception e) {
            log.error("Error loading document: {}", e.getMessage(), e);
            return new DocumentLoadResponse("Failed to load document: " + e.getMessage(), false, false);
        }
    }

    @PostMapping("/load")
    public DocumentLoadResponse loadDocument(@RequestBody DocumentLoadRequest request) {
        try {
            if (request.documentUrl() != null && !request.documentUrl().isBlank()) {
                boolean loaded;
                if (request.metadata() != null && !request.metadata().isEmpty()) {
                    documentLoaderService.loadDocumentWithMetadata(request.documentUrl(), request.metadata());
                    loaded = true;
                } else {
                    loaded = documentLoaderService.loadDocument(request.documentUrl());
                }
                if (loaded) {
                    return new DocumentLoadResponse("Document loaded successfully!", true, false);
                } else {
                    return new DocumentLoadResponse("Document has already been uploaded!", true, true);
                }
            } else if (request.documentUrls() != null && !request.documentUrls().isEmpty()) {
                documentLoaderService.loadDocuments(request.documentUrls());
                return new DocumentLoadResponse("Loaded " + request.documentUrls().size() + " documents", true, false);
            } else {
                return new DocumentLoadResponse("No document URL(s) provided", false, false);
            }
        } catch (Exception e) {
            log.error("Error loading document: {}", e.getMessage(), e);
            return new DocumentLoadResponse("Failed to load document: " + e.getMessage(), false, false);
        }
    }

    @GetMapping("/list")
    public List<String> listDocuments() {
        return documentLoaderService.listDocuments();
    }

    @DeleteMapping("/delete")
    public DocumentLoadResponse deleteDocument(@RequestParam String source) {
        try {
            documentLoaderService.deleteDocument(source);
            return new DocumentLoadResponse("Document deleted: " + source, true, false);
        } catch (Exception e) {
            log.error("Error deleting document: {}", e.getMessage(), e);
            return new DocumentLoadResponse("Failed to delete: " + e.getMessage(), false, false);
        }
    }
}
