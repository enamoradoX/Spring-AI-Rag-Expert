package guru.springframework.springairagexpert.bootstrap;

import guru.springframework.springairagexpert.config.VectorStoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@Slf4j
public class LoadVectorStore implements CommandLineRunner {

    @Autowired
    VectorStore vectorStore;

    @Autowired
    VectorStoreProperties vectorStoreProperties;

    @Override
    public void run(String... args) throws Exception {
        log.info("Checking documents to load into vector store...");
        vectorStoreProperties.getDocumentsToLoad().forEach(this::loadIfNotExists);
        log.info("Vector store initialization complete");
    }

    private void loadIfNotExists(Resource resource) {
        String filename = resource.getFilename();

        List<Document> existing = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(filename)
                        .topK(1)
                        .filterExpression("source == '" + filename + "'")
                        .build()
        );

        if (!existing.isEmpty()) {
            log.info("Document already exists in vector store, skipping: [{}]", filename);
            return;
        }

        log.info("Document not found in vector store, loading now: [{}]", filename);

        TikaDocumentReader documentReader = new TikaDocumentReader(resource);
        List<Document> documents = documentReader.get();

        // Tag all chunks with source filename for future duplicate checks
        documents.forEach(doc -> doc.getMetadata().put("source", filename));

        TextSplitter textSplitter = new TokenTextSplitter();
        List<Document> splitDocuments = textSplitter.apply(documents);

        // Ensure split chunks also carry the source metadata
        splitDocuments.forEach(doc -> doc.getMetadata().put("source", filename));

        vectorStore.add(splitDocuments);
        log.info("Successfully loaded [{}] into vector store", filename);
    }
}