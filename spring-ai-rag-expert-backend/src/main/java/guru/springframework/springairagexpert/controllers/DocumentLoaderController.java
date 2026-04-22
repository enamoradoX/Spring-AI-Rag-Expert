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
}
