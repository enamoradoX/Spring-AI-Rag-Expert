import { Component } from '@angular/core';
import { ChatService } from './services/chat.service';
import { DocumentService } from './services/document.service';

interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  timestamp: Date;
}

@Component({
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css']
})
export class AppComponent {
  title = 'Spring AI RAG Expert';

  // Chat
  messages: ChatMessage[] = [];
  isLoading = false;

  // Shared input
  inputText = '';
  mode: 'chat' | 'document' = 'chat';

  // Document feedback
  documentMessage = '';
  documentMessageType: 'success' | 'error' = 'success';

  constructor(
    private chatService: ChatService,
    private documentService: DocumentService
  ) {}

  get placeholder(): string {
    return this.mode === 'chat'
      ? 'Ask a question about your documents...'
      : 'Enter document URL (e.g., https://example.com/document.pdf)';
  }

  switchToDocument() {
    this.mode = 'document';
    this.inputText = '';
    this.documentMessage = '';
  }

  switchToChat() {
    this.mode = 'chat';
    this.inputText = '';
    this.documentMessage = '';
  }

  handleSend() {
    if (this.mode === 'chat') {
      this.askQuestion();
    } else {
      this.loadDocument();
    }
  }

  askQuestion() {
    if (!this.inputText.trim()) return;

    this.messages.push({
      role: 'user',
      content: this.inputText,
      timestamp: new Date()
    });

    const currentQuestion = this.inputText;
    this.inputText = '';
    this.isLoading = true;

    this.chatService.askQuestion(currentQuestion).subscribe({
      next: (response) => {
        this.messages.push({
          role: 'assistant',
          content: response.answer,
          timestamp: new Date()
        });
        this.isLoading = false;
      },
      error: () => {
        this.messages.push({
          role: 'assistant',
          content: 'Sorry, I encountered an error. Please try again.',
          timestamp: new Date()
        });
        this.isLoading = false;
      }
    });
  }

  loadDocument() {
    if (!this.inputText.trim()) {
      this.documentMessage = 'Please enter a valid document URL';
      this.documentMessageType = 'error';
      return;
    }

    this.isLoading = true;
    this.documentMessage = '';

    this.documentService.loadDocument(this.inputText).subscribe({
      next: (response) => {
        this.documentMessage = response.message;
        this.documentMessageType = response.success ? 'success' : 'error';
        this.isLoading = false;
        if (response.success) {
          this.inputText = '';
        }
      },
      error: () => {
        this.documentMessage = 'Failed to load document. Please check the URL and try again.';
        this.documentMessageType = 'error';
        this.isLoading = false;
      }
    });
  }

  onKeyPress(event: KeyboardEvent) {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      this.handleSend();
    }
  }
}
