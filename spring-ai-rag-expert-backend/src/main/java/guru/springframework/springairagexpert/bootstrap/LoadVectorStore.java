package guru.springframework.springairagexpert.bootstrap;

import guru.springframework.springairagexpert.config.VectorStoreProperties;
import guru.springframework.springairagexpert.services.DocumentLoaderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class LoadVectorStore implements CommandLineRunner {

    @Autowired
    DocumentLoaderService documentLoaderService;

    @Autowired
    VectorStoreProperties vectorStoreProperties;

    @Override
    public void run(String... args) throws Exception {

        // Load startup documents — skipped automatically if already in the store
        log.info("Loading startup documents");
        vectorStoreProperties.getDocumentsToLoad().forEach(resource -> {
            try {
                String url = resource.getURI().toString();
                if (documentLoaderService.isDocumentLoaded(url)) {
                    log.info("Startup document already in store, skipping: {}", url);
                } else {
                    log.info("Loading startup document: {}", url);
                    documentLoaderService.loadDocument(url);
                }
            } catch (Exception e) {
                log.error("Failed to load startup document: {}", e.getMessage(), e);
            }
        });

        log.info("Vector store ready. Documents loaded: {}", documentLoaderService.listDocuments().size());
    }
}
