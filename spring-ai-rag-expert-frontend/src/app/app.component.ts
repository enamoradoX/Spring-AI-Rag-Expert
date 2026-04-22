import { Component, OnInit, HostListener, ElementRef } from '@angular/core';
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
export class AppComponent implements OnInit {
  title = 'Spring AI RAG Expert';

  // Chat
  messages: ChatMessage[] = [];
  isChatLoading = false;

  // Document upload
  isDocumentLoading = false;

  // Shared input
  inputText = '';
  mode: 'chat' | 'document' = 'chat';

  // Single unified status alert
  statusMessage = '';
  statusMessageType: 'success' | 'error' = 'success';
  private statusTimer: any;

  // Document management
  loadedDocuments: string[] = [];
  showDocuments = false;
  isRefreshing = false;
  deletingUrl = '';

  constructor(
    private chatService: ChatService,
    private documentService: DocumentService,
    private elementRef: ElementRef
  ) {}

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent): void {
    if (this.showDocuments && !this.elementRef.nativeElement.querySelector('.docs-toggle-wrapper')?.contains(event.target)) {
      this.showDocuments = false;
    }
  }

  ngOnInit(): void {
    this.refreshDocuments();
  }

  private showStatus(message: string, type: 'success' | 'error') {
    clearTimeout(this.statusTimer);
    this.statusMessage = message;
    this.statusMessageType = type;
    this.statusTimer = setTimeout(() => { this.statusMessage = ''; }, 3200);
  }

  refreshDocuments(): void {
    this.documentService.getDocuments().subscribe({
      next: (docs) => { this.loadedDocuments = docs; },
      error: () => {}
    });
  }

  onRefreshClick(): void {
    // Force animation restart by toggling off then on in next frame
    this.isRefreshing = false;
    requestAnimationFrame(() => {
      this.isRefreshing = true;
      this.documentService.getDocuments().subscribe({
        next: (docs) => {
          this.loadedDocuments = docs;
          setTimeout(() => { this.isRefreshing = false; }, 600);
        },
        error: () => { this.isRefreshing = false; }
      });
    });
  }

  deleteDocument(url: string): void {
    this.deletingUrl = url;
    // Wait for the fade-out animation to finish, then call the API
    setTimeout(() => {
      this.documentService.deleteDocument(url).subscribe({
        next: (response) => {
          this.deletingUrl = '';
          this.showStatus(response.message, response.success ? 'success' : 'error');
          if (response.success) { this.refreshDocuments(); }
        },
        error: () => {
          this.deletingUrl = '';
          this.showStatus('Failed to delete document.', 'error');
        }
      });
    }, 350);
  }

  get placeholder(): string {
    return this.mode === 'chat'
      ? 'Ask a question about your documents...'
      : 'Enter document URL (e.g., https://example.com/document.pdf)';
  }

  get isLoading(): boolean {
    return this.mode === 'chat' ? this.isChatLoading : this.isDocumentLoading;
  }

  switchToDocument() {
    this.mode = 'document';
    this.inputText = '';
  }

  switchToChat() {
    this.mode = 'chat';
    this.inputText = '';
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
    this.messages.push({ role: 'user', content: this.inputText, timestamp: new Date() });
    const currentQuestion = this.inputText;
    this.inputText = '';
    this.isChatLoading = true;
    this.chatService.askQuestion(currentQuestion).subscribe({
      next: (response) => {
        this.messages.push({ role: 'assistant', content: response.answer, timestamp: new Date() });
        this.isChatLoading = false;
      },
      error: () => {
        this.messages.push({ role: 'assistant', content: 'Sorry, I encountered an error. Please try again.', timestamp: new Date() });
        this.isChatLoading = false;
      }
    });
  }

  loadDocument() {
    if (!this.inputText.trim()) {
      this.showStatus('Please enter a valid document URL', 'error');
      return;
    }
    this.isDocumentLoading = true;
    this.documentService.loadDocument(this.inputText).subscribe({
      next: (response) => {
        this.showStatus(response.message, response.success ? 'success' : 'error');
        this.isDocumentLoading = false;
        if (response.success) {
          this.inputText = '';
          this.refreshDocuments();
        }
      },
      error: () => {
        this.showStatus('Failed to load document. Please check the URL and try again.', 'error');
        this.isDocumentLoading = false;
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
