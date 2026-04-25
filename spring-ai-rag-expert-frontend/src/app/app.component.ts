import { Component, OnInit, HostListener, ElementRef, ViewChild } from '@angular/core';
import { ChatService } from './services/chat.service';
import { DocumentService, S3Config } from './services/document.service';
import { PdfViewerComponent } from './pdf-viewer/pdf-viewer.component';
import { DocxViewerComponent } from './docx-viewer/docx-viewer.component';

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
  isDragOverDocumentInput = false;

  // Shared input
  inputText = '';
  mode: 'chat' | 'document' | 's3' = 'chat';

  // Single unified status alert
  statusMessage = '';
  statusMessageType: 'success' | 'error' = 'success';
  private statusTimer: any;

  // Document viewer
  viewerDocumentUrl = '';
  viewerContent = '';
  viewerLoading = false;
  viewerPdfUrl = '';
  viewerDocxUrl = '';
  /** Explicitly set type so *ngIf conditions never depend on URL-string parsing. */
  viewerType: 'pdf' | 'docx' | 'text' | '' = '';
  lastAnswer = '';
  highlightedSources: string[] = [];
  pdfHighlights: string[] = [];
  viewerSegments: { text: string; highlighted: boolean }[] = [];

  get viewerIsPdf(): boolean  { return this.viewerType === 'pdf';  }
  get viewerIsDocx(): boolean { return this.viewerType === 'docx'; }

  // Document management
  loadedDocuments: string[] = [];
  showDocuments = false;
  isRefreshing = false;
  deletingUrl = '';
  pdfHighlightCount = 0;
  viewerZoom = 1.0;

  // S3 upload
  isS3Loading = false;
  s3Config: S3Config | null = null;
  s3ConfigError = false;

  @ViewChild('pdfViewerRef') pdfViewerRef?: PdfViewerComponent;
  @ViewChild('docxViewerRef') docxViewerRef?: DocxViewerComponent;

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
    this.loadS3Config();
  }

  loadS3Config(): void {
    this.documentService.getS3Config().subscribe({
      next: (config) => { this.s3Config = config; this.s3ConfigError = false; },
      error: () => { this.s3Config = null; this.s3ConfigError = true; }
    });
  }

  toggleS3Panel(): void { this.switchToS3(); }

  loadFromS3(): void {
    if (!this.inputText.trim()) {
      this.showStatus('Please enter an S3 key', 'error');
      return;
    }
    this.isS3Loading = true;
    this.documentService.loadFromS3(this.inputText.trim()).subscribe({
      next: (response) => {
        this.showStatus(response.message, response.success ? 'success' : 'error');
        this.isS3Loading = false;
        if (response.success) {
          this.inputText = '';
          this.switchToChat();
          this.refreshDocuments();
        }
      },
      error: () => {
        this.showStatus('Failed to load from S3. Check the key and connection.', 'error');
        this.isS3Loading = false;
      }
    });
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
    if (this.mode === 'chat') return 'Ask a question about your documents...';
    if (this.mode === 's3')   return 'Enter S3 key (e.g., docs/help/en-us/my-file.pdf)';
    return 'Enter document URL (e.g., https://example.com/document.pdf)';
  }

  get isLoading(): boolean {
    if (this.mode === 's3') return this.isS3Loading;
    return this.mode === 'chat' ? this.isChatLoading : this.isDocumentLoading;
  }

  switchToDocument() { this.mode = 'document'; this.inputText = ''; }
  switchToChat()     { this.mode = 'chat';     this.inputText = ''; }
  switchToS3()       { this.mode = 's3';       this.inputText = ''; }

  handleSend() {
    if (this.mode === 'chat')     this.askQuestion();
    else if (this.mode === 's3')  this.loadFromS3();
    else                          this.loadDocument();
  }

  loadViewerDocument(url: string): void {
    if (!url) {
      this.viewerSegments = []; this.viewerContent = '';
      this.viewerPdfUrl = ''; this.viewerDocxUrl = ''; this.viewerType = '';
      return;
    }

    this.viewerLoading = true;
    this.viewerType = '';
    this.viewerSegments = [];
    this.viewerPdfUrl = '';
    this.viewerDocxUrl = '';

    const lower = url.toLowerCase();
    const hasPdfExt  = lower.endsWith('.pdf');
    const hasDocxExt = lower.endsWith('.docx');
    const hasTextExt = lower.endsWith('.txt') || lower.endsWith('.doc');

    if (hasPdfExt) {
      this.applyViewerForUrl(url, 'pdf');
    } else if (hasDocxExt) {
      this.applyViewerForUrl(url, 'docx');
    } else if (hasTextExt) {
      this.applyViewerForUrl(url, 'text');
    } else {
      // Extensionless URL — ask the backend to detect the real type via Tika
      this.documentService.getFileType(url).subscribe({
        next: (ft) => {
          const t: 'pdf' | 'docx' | 'text' = ft.type === 'pdf' ? 'pdf'
                                            : ft.type === 'docx' ? 'docx'
                                            : 'text';
          this.applyViewerForUrl(url, t);
        },
        error: () => this.applyViewerForUrl(url, 'text')
      });
    }
  }

  private applyViewerForUrl(url: string, type: 'pdf' | 'docx' | 'text'): void {
    this.viewerType = type;

    if (type === 'pdf') {
      this.viewerPdfUrl = this.documentService.getRawDocumentUrl(url);
      this.viewerLoading = false;
      return;
    }

    if (type === 'docx') {
      this.viewerDocxUrl = this.documentService.getRawDocumentUrl(url);
      this.viewerLoading = false;
      return;
    }

    // text
    this.documentService.getDocumentContent(url).subscribe({
      next: (content) => {
        this.viewerContent = content;
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

  zoomIn():    void { this.viewerZoom = Math.min(3.0, +(this.viewerZoom + 0.1).toFixed(1)); }
  zoomOut():   void { this.viewerZoom = Math.max(0.5, +(this.viewerZoom - 0.1).toFixed(1)); }
  resetZoom(): void { this.viewerZoom = 1.0; }

  scrollToFirstHighlight(): void {
    if (this.viewerIsPdf) {
      this.pdfViewerRef?.scrollToHighlight();
      return;
    }
    if (this.viewerIsDocx) {
      this.docxViewerRef?.scrollToHighlight();
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
            this.pdfHighlightCount = 0;
            this.loadViewerDocument(firstUrl);
            // Always schedule scroll-to-highlight after the viewer has had time to
            // render.  For extensionless URLs (e.g. Yamaha bulletin) type detection
            // is asynchronous, so use a longer delay than for .pdf/.docx URLs.
            const scrollDelay = (firstUrl.toLowerCase().endsWith('.pdf') ||
                                 firstUrl.toLowerCase().endsWith('.docx')) ? 400 : 1500;
            setTimeout(() => this.scrollToFirstHighlight(), scrollDelay);
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

  onDocumentDragOver(event: DragEvent): void {
    if (this.mode !== 'document') return;
    event.preventDefault();
    event.stopPropagation();
    this.isDragOverDocumentInput = true;
  }

  onDocumentDragLeave(event: DragEvent): void {
    if (this.mode !== 'document') return;
    event.preventDefault();
    event.stopPropagation();
    this.isDragOverDocumentInput = false;
  }

  onDocumentDrop(event: DragEvent): void {
    if (this.mode !== 'document') return;
    event.preventDefault();
    event.stopPropagation();
    this.isDragOverDocumentInput = false;

    const files = event.dataTransfer?.files;
    if (!files || files.length === 0) return;
    if (files.length > 1) {
      this.showStatus('Please drop one document at a time.', 'error');
      return;
    }

    this.uploadDroppedFile(files[0]);
  }

  private uploadDroppedFile(file: File): void {
    const allowedExtensions = ['pdf', 'docx', 'doc', 'txt'];
    const extension = file.name.includes('.') ? file.name.split('.').pop()!.toLowerCase() : '';
    if (!allowedExtensions.includes(extension)) {
      this.showStatus('Unsupported file type. Allowed: .pdf, .docx, .doc, .txt', 'error');
      return;
    }

    this.isDocumentLoading = true;
    this.documentService.uploadDocumentFile(file).subscribe({
      next: (response) => {
        this.showStatus(response.message, response.success ? 'success' : 'error');
        this.isDocumentLoading = false;
        if (response.success) {
          this.inputText = '';
          this.refreshDocuments();
        }
      },
      error: () => {
        this.showStatus('Failed to upload document. Please try again.', 'error');
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
