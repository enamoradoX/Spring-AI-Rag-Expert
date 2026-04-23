package guru.springframework.springairagexpert.model;

import java.util.List;

public record Answer(String answer, List<String> sources, List<String> sourceDocumentUrls, List<String> highlights) {
}
