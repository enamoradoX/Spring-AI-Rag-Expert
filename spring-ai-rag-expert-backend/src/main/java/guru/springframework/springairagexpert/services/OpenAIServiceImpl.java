package guru.springframework.springairagexpert.services;

import guru.springframework.springairagexpert.model.Answer;
import guru.springframework.springairagexpert.model.Question;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Service
@Slf4j
public class OpenAIServiceImpl implements OpenAIService {

    final ChatModel chatModel;
    final VectorStore vectorStore;
    final ChatUsageAnalyticsService chatUsageAnalyticsService;

    @Value("classpath:/templates/rag-prompt-template.st")
    private Resource ragPromptTemplate;

    @Value("${chat.analytics.provider:unknown}")
    private String analyticsProvider;

    @Value("${chat.analytics.model:unknown}")
    private String configuredChatModel;

    @Override
    public Answer getAnswer(Question question) {
        log.info("Processing question: {}", question.question());
        long start = System.currentTimeMillis();

        log.debug("Searching vector store for relevant documents...");
        List<Document> documents = vectorStore.similaritySearch(
                SearchRequest.builder()
                             .query(question.question())
                             .topK(5)
                             .build()
        );
        log.info("Vector search returned {} chunks in {}ms",
                documents == null ? 0 : documents.size(), System.currentTimeMillis() - start);

        // ── Group ranked chunks by source document URL ──────────────────────────────
        LinkedHashMap<String, List<Document>> docsByUrl = new LinkedHashMap<>();
        if (documents != null) {
            for (Document doc : documents) {
                String url = (String) doc.getMetadata().get("document_url");
                if (url != null && !url.isBlank()) {
                    docsByUrl.computeIfAbsent(url, k -> new ArrayList<>()).add(doc);
                }
            }
        }

        String primaryUrl = docsByUrl.entrySet().stream()
                .max(Comparator.comparingInt(e -> e.getValue().size()))
                .map(Map.Entry::getKey)
                .orElse(null);
        log.info("Primary source document: {}", primaryUrl);

        List<String> sourceUrls = new ArrayList<>();
        if (primaryUrl != null) sourceUrls.add(primaryUrl);
        docsByUrl.keySet().stream()
                .filter(u -> !u.equals(primaryUrl))
                .forEach(sourceUrls::add);

        List<String> contentList = documents == null ? List.of() : documents.stream()
                .map(doc -> doc.getText() != null ? doc.getText() : "")
                .filter(t -> !t.isBlank())
                .toList();

        List<String> primaryDocChunks = primaryUrl != null
                ? docsByUrl.get(primaryUrl).stream()
                        .map(doc -> doc.getText() != null ? doc.getText() : "")
                        .filter(t -> !t.isBlank())
                        .toList()
                : contentList;

        PromptTemplate promptTemplate = new PromptTemplate(ragPromptTemplate);
        Prompt prompt = promptTemplate.create(
                Map.of("input", question.question(), "documents", String.join("\n", contentList))
        );

        log.info("Sending prompt to chat model (this may take a while with local models)...");
        ChatResponse response = callChatModel(prompt, "answer");
        String answerText = response.getResult().getOutput().getText();

        log.info("Extracting highlights from primary document chunks...");
        long hlStart = System.currentTimeMillis();
        List<String> highlights = extractHighlights(answerText, primaryDocChunks);
        log.info("Highlight extraction completed in {}ms", System.currentTimeMillis() - hlStart);

        log.info("Total question processing time: {}ms", System.currentTimeMillis() - start);
        return new Answer(answerText, contentList, sourceUrls, highlights);
    }

    /**
     * Ask the LLM to find the verbatim sentence(s) in the source documents that
     * best support the given answer. Returns them as a list (one sentence per entry).
     */
    private List<String> extractHighlights(String answer, List<String> sourceDocs) {
        if (sourceDocs.isEmpty()) return List.of();

        String extractPrompt = """
                You are a document search assistant. Your only job is to find and copy exact text.

                Given the ANSWER below, find the sentence or short passage in the SOURCE DOCUMENTS \
                that most directly states or supports that answer.

                Rules:
                - Copy the text EXACTLY as it appears in the source — do not paraphrase or change any words.
                - Return at most 2 passages, each on its own line.
                - Do NOT include bullet points, numbers, labels, or any explanation — only the raw copied text.
                - If no relevant passage exists, return an empty response.

                ANSWER:
                %s

                SOURCE DOCUMENTS:
                %s
                """.formatted(answer, String.join("\n---\n", sourceDocs));

        ChatResponse highlightResponse = callChatModel(new Prompt(extractPrompt), "highlight");
        String raw = highlightResponse.getResult().getOutput().getText();
        if (raw == null || raw.isBlank()) return List.of();

        return Arrays.stream(raw.split("\n"))
                .map(String::trim)
                .filter(s -> s.length() > 10)
                .limit(2)
                .toList();
    }

    private ChatResponse callChatModel(Prompt prompt, String operation) {
        long startedAt = System.currentTimeMillis();
        try {
            ChatResponse response = chatModel.call(prompt);
            long latencyMs = System.currentTimeMillis() - startedAt;
            recordChatUsage(response, operation, latencyMs, true);
            log.info("Chat model responded in {}ms for {}", latencyMs, operation);
            return response;
        }
        catch (RuntimeException ex) {
            long latencyMs = System.currentTimeMillis() - startedAt;
            chatUsageAnalyticsService.recordUsage(
                    analyticsProvider,
                    configuredChatModel,
                    operation,
                    0,
                    0,
                    0,
                    latencyMs,
                    false,
                    null,
                    null
            );
            throw ex;
        }
    }

    private void recordChatUsage(ChatResponse response, String operation, long latencyMs, boolean success) {
        ChatResponseMetadata metadata = response != null ? response.getMetadata() : null;
        Usage usage = metadata != null ? metadata.getUsage() : null;
        ChatGenerationMetadata generationMetadata = response != null && response.getResult() != null
                ? response.getResult().getMetadata()
                : null;

        String model = metadata != null && metadata.getModel() != null && !metadata.getModel().isBlank()
                ? metadata.getModel()
                : configuredChatModel;

        chatUsageAnalyticsService.recordUsage(
                analyticsProvider,
                model,
                operation,
                usage != null ? usage.getPromptTokens() : null,
                usage != null ? usage.getCompletionTokens() : null,
                usage != null ? usage.getTotalTokens() : null,
                latencyMs,
                success,
                generationMetadata != null ? generationMetadata.getFinishReason() : null,
                metadata != null ? metadata.getId() : null
        );
    }
}
