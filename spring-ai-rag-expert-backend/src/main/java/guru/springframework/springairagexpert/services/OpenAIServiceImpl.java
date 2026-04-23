package guru.springframework.springairagexpert.services;

import guru.springframework.springairagexpert.model.Answer;
import guru.springframework.springairagexpert.model.Question;
import lombok.RequiredArgsConstructor;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Service
public class OpenAIServiceImpl implements OpenAIService {

    final ChatModel chatModel;
    final VectorStore vectorStore;

    @Value("classpath:/templates/rag-prompt-template.st")
    private Resource ragPromptTemplate;

    @Override
    public Answer getAnswer(Question question) {

        List<Document> documents = vectorStore.similaritySearch(
                SearchRequest.builder()
                             .query(question.question())
                             .topK(5)
                             .build()
        );

        List<String> contentList = documents == null ? List.of() : documents.stream()
                                                            .map(doc -> doc.getText() != null ? doc.getText() : "")
                                                            .filter(t -> !t.isBlank())
                                                            .toList();

        List<String> sourceUrls = documents == null ? List.of() : documents.stream()
                .map(doc -> (String) doc.getMetadata().get("document_url"))
                .filter(url -> url != null && !url.isBlank())
                .distinct()
                .toList();

        PromptTemplate promptTemplate = new PromptTemplate(ragPromptTemplate);

        Prompt prompt = promptTemplate.create(
                Map.of("input", question.question(), "documents", String.join("\n", contentList))
        );

        ChatResponse response = chatModel.call(prompt);
        String answerText = response.getResult().getOutput().getText();

        // Second LLM call: extract the exact verbatim passage(s) from the source documents
        // that most directly support the answer. These are used for precise PDF highlighting.
        List<String> highlights = extractHighlights(answerText, contentList);

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

        ChatResponse highlightResponse = chatModel.call(new Prompt(extractPrompt));
        String raw = highlightResponse.getResult().getOutput().getText();
        if (raw == null || raw.isBlank()) return List.of();

        return Arrays.stream(raw.split("\n"))
                .map(String::trim)
                .filter(s -> s.length() > 10)
                .limit(2)
                .toList();
    }
}
