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

  documentAlreadyExists = false;

  private dismissTimer: any = null;

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

  private showMessage(message: string, type: 'success' | 'error', alreadyExists = false) {
    this.documentMessage = message;
    this.documentMessageType = type;
    this.documentAlreadyExists = alreadyExists;

    // Clear any existing timer
    if (this.dismissTimer) {
      clearTimeout(this.dismissTimer);
    }

    // Auto-dismiss after 4 seconds
    this.dismissTimer = setTimeout(() => {
      this.documentMessage = '';
      this.documentAlreadyExists = false;
      this.dismissTimer = null;
    }, 4000);
  }

  loadDocument() {
    if (!this.inputText.trim()) {
      this.showMessage('Please enter a valid document URL', 'error');
      return;
    }

    this.isLoading = true;
    this.documentMessage = '';
    this.documentAlreadyExists = false;

    this.documentService.loadDocument(this.inputText).subscribe({
      next: (response) => {
        this.showMessage(response.message, response.success ? 'success' : 'error', response.alreadyExists);
        this.isLoading = false;
        if (response.success && !response.alreadyExists) {
          this.inputText = '';
        }
      },
      error: () => {
        this.showMessage('Failed to load document. Please check the URL and try again.', 'error');
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

  // Document management
  loadedDocuments: string[] = [];
  showDocumentList = false;

  loadDocumentList() {
    this.documentService.listDocuments().subscribe({
      next: (docs) => this.loadedDocuments = docs,
      error: () => console.error('Failed to load document list')
    });
  }

  toggleDocumentList() {
    this.showDocumentList = !this.showDocumentList;
    if (this.showDocumentList) {
      this.loadDocumentList();
    }
  }

  deleteDocument(source: string) {
    this.documentService.deleteDocument(source).subscribe({
      next: (response) => {
        this.showMessage(response.message, 'success');
        this.loadDocumentList();
      },
      error: () => {
        this.showMessage('Failed to delete document.', 'error');
      }
    });
  }
}
