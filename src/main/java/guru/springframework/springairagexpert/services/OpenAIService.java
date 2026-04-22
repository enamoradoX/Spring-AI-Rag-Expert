package guru.springframework.springairagexpert.services;

import guru.springframework.springairagexpert.model.Answer;
import guru.springframework.springairagexpert.model.Question;

public interface OpenAIService {
    Answer getAnswer(Question question);
}
