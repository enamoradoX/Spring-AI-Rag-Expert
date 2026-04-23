import { Component, OnInit, HostListener, ElementRef, ViewChild } from '@angular/core';
import { ChatService } from './services/chat.service';
import { DocumentService } from './services/document.service';
import { PdfViewerComponent } from './pdf-viewer/pdf-viewer.component';

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

  // Document viewer
  viewerDocumentUrl = '';
  viewerContent = '';
  viewerLoading = false;
  viewerPdfUrl = '';
  lastAnswer = '';
  highlightedSources: string[] = [];
  pdfHighlights: string[] = [];
  viewerSegments: { text: string; highlighted: boolean }[] = [];

  get viewerIsPdf(): boolean {
    return this.viewerDocumentUrl.toLowerCase().endsWith('.pdf');
  }

  // Document management
  loadedDocuments: string[] = [];
  showDocuments = false;
  isRefreshing = false;
  deletingUrl = '';
  pdfHighlightCount = 0;

  @ViewChild('pdfViewerRef') pdfViewerRef?: PdfViewerComponent;

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

  loadViewerDocument(url: string): void {
    if (!url) { this.viewerSegments = []; this.viewerContent = ''; this.viewerPdfUrl = ''; return; }
    this.viewerLoading = true;
    this.viewerSegments = [];
    this.viewerPdfUrl = '';

    if (url.toLowerCase().endsWith('.pdf')) {
      this.viewerPdfUrl = this.documentService.getRawDocumentUrl(url);
      this.viewerLoading = false;
      return;
    }

    this.documentService.getDocumentContent(url).subscribe({
      next: (content) => {
        this.viewerContent = content;
        // Prefer LLM-extracted highlights; fall back to raw source chunks
        const sources = this.pdfHighlights?.length > 0 ? this.pdfHighlights : this.highlightedSources;
        this.buildSegments(sources);
        this.viewerLoading = false;
      },
      error: () => { this.viewerLoading = false; }
    });
  }

  private buildSegments(sources: string[]): void {
    if (!this.viewerContent) { this.viewerSegments = []; return; }
    if (!sources || sources.length === 0) {
      this.viewerSegments = [{ text: this.viewerContent, highlighted: false }];
      return;
    }

    // Build a whitespace-normalized version of viewerContent plus a mapping
    // from each normalized index back to its original index.
    // This handles TokenTextSplitter collapsing whitespace in stored chunks.
    const { normalized, toOriginal } = this.buildNormalized(this.viewerContent);

    const segments: { text: string; highlighted: boolean }[] = [];

    // Sort sources by their position in the normalized document
    const positions = sources
      .map(s => {
        const normSource = this.normalizeWS(s.trim());
        const idx = normalized.indexOf(normSource);
        return { normSource, idx };
      })
      .filter(p => p.idx !== -1)
      .sort((a, b) => a.idx - b.idx);

    let cursor = 0; // cursor in original text
    for (const { normSource, idx } of positions) {
      const origStart = toOriginal[idx];
      const lastNormIdx = idx + normSource.length - 1;
      const origEnd = lastNormIdx < toOriginal.length ? toOriginal[lastNormIdx] + 1 : this.viewerContent.length;
      if (origStart < cursor) continue;
      if (origStart > cursor) {
        segments.push({ text: this.viewerContent.slice(cursor, origStart), highlighted: false });
      }
      segments.push({ text: this.viewerContent.slice(origStart, origEnd), highlighted: true });
      cursor = origEnd;
    }
    if (cursor < this.viewerContent.length) {
      segments.push({ text: this.viewerContent.slice(cursor), highlighted: false });
    }

    this.viewerSegments = segments.length ? segments : [{ text: this.viewerContent, highlighted: false }];
  }

  /** Collapse all whitespace runs to a single space for fuzzy matching. */
  private normalizeWS(s: string): string {
    return s.replace(/\s+/g, ' ');
  }

  /**
   * Build a whitespace-normalized copy of `text` together with a `toOriginal`
   * array where toOriginal[i] is the index in the original string that
   * corresponds to position i in the normalized string.
   */
  private buildNormalized(text: string): { normalized: string; toOriginal: number[] } {
    const toOriginal: number[] = [];
    let normalized = '';
    let i = 0;
    while (i < text.length) {
      if (/\s/.test(text[i])) {
        toOriginal.push(i);
        normalized += ' ';
        while (i < text.length && /\s/.test(text[i])) { i++; }
      } else {
        toOriginal.push(i);
        normalized += text[i];
        i++;
      }
    }
    return { normalized, toOriginal };
  }

  scrollToFirstHighlight(): void {
    if (this.viewerIsPdf) {
      this.pdfViewerRef?.scrollToHighlight();
      return;
    }
    setTimeout(() => {
      const el = this.elementRef.nativeElement.querySelector('.viewer-highlight');
      if (el) {
        el.scrollIntoView({ behavior: 'smooth', block: 'center' });
      }
    }, 100);
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
        this.lastAnswer = response.answer;
        // Auto-load the first source document and highlight relevant passages
        if (response.sources && response.sources.length > 0) {
          this.highlightedSources = response.sources;
          // LLM-extracted verbatim passages for precise PDF highlighting
          this.pdfHighlights = response.highlights?.length > 0 ? response.highlights : [];
          const firstUrl = response.sourceDocumentUrls?.[0];
          if (firstUrl) {
            this.viewerDocumentUrl = firstUrl;
            this.viewerLoading = true;
            this.viewerSegments = [];
            this.viewerPdfUrl = '';

            if (firstUrl.toLowerCase().endsWith('.pdf')) {
              this.viewerPdfUrl = this.documentService.getRawDocumentUrl(firstUrl);
              this.pdfHighlightCount = 0;
              this.viewerLoading = false;
            } else {
              this.documentService.getDocumentContent(firstUrl).subscribe({
                next: (content) => {
                  this.viewerContent = content;
                  // Prefer LLM-extracted highlights; fall back to raw source chunks
                  const sources = response.highlights?.length > 0 ? response.highlights : response.sources;
                  this.buildSegments(sources);
                  this.viewerLoading = false;
                  this.scrollToFirstHighlight();
                },
                error: () => { this.viewerLoading = false; }
              });
            }
          } else if (this.viewerDocumentUrl) {
            const sources = this.pdfHighlights?.length > 0 ? this.pdfHighlights : this.highlightedSources;
            this.buildSegments(sources);
            this.scrollToFirstHighlight();
          }
        }
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
