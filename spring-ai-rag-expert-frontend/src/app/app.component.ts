import { Component, OnInit, HostListener, ElementRef, ViewChild } from '@angular/core';
import { ChatService } from './services/chat.service';
import {
  AnalyticsService,
  ChatAnalyticsModelSummary,
  ChatAnalyticsOperationSummary,
  ChatAnalyticsSummaryResponse,
  ChatUsageEvent
} from './services/analytics.service';
import { DocumentService, S3Config, S3ConfigUpdateRequest } from './services/document.service';
import { PdfViewerComponent } from './pdf-viewer/pdf-viewer.component';
import { DocxViewerComponent } from './docx-viewer/docx-viewer.component';
import { firstValueFrom } from 'rxjs';

interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  timestamp: Date;
}

interface ExplorerEntry {
  file: File;
  displayPath: string;
  typeLabel: string;
  sizeLabel: string;
}

interface S3ConfigForm {
  bucketName: string;
  region: string;
  accessKeyId: string;
  secretAccessKey: string;
  endpointOverride: string;
  pathStyleAccess: boolean;
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
  documentUrlInput = '';
  documentS3KeyInput = '';

  // Shared input
  inputText = '';
  mode: 'chat' | 'document' | 'configuration' | 'analytics' = 'chat';

  // Analytics
  analyticsSummary: ChatAnalyticsSummaryResponse | null = null;
  isAnalyticsLoading = false;
  analyticsError = '';
  analyticsLastUpdated: Date | null = null;

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
  isSavingS3Config = false;
  showS3Settings = false;
  s3Config: S3Config | null = null;
  s3ConfigError = false;
  s3ConfigForm: S3ConfigForm = this.createDefaultS3ConfigForm();

  // Explorer upload
  isExplorerLoading = false;
  selectedExplorerFiles: ExplorerEntry[] = [];
  explorerLocationLabel = 'Documents';

  @ViewChild('pdfViewerRef') pdfViewerRef?: PdfViewerComponent;
  @ViewChild('docxViewerRef') docxViewerRef?: DocxViewerComponent;
  @ViewChild('filePickerRef') filePickerRef?: ElementRef<HTMLInputElement>;
  @ViewChild('folderPickerRef') folderPickerRef?: ElementRef<HTMLInputElement>;

  constructor(
    private chatService: ChatService,
    private analyticsService: AnalyticsService,
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
      next: (config) => {
        this.s3Config = config;
        this.s3ConfigForm = this.createS3ConfigForm(config);
        this.s3ConfigError = false;
      },
      error: () => { this.s3Config = null; this.s3ConfigError = true; }
    });
  }


  openS3Configuration(): void {
    this.mode = 'configuration';
    this.showS3Settings = true;
  }

  saveS3Config(): void {
    const request: S3ConfigUpdateRequest = {
      bucketName: this.s3ConfigForm.bucketName.trim(),
      region: this.s3ConfigForm.region.trim() || 'us-east-1',
      accessKeyId: this.s3ConfigForm.accessKeyId.trim(),
      secretAccessKey: this.s3ConfigForm.secretAccessKey.trim(),
      endpointOverride: this.s3ConfigForm.endpointOverride.trim(),
      pathStyleAccess: this.s3ConfigForm.pathStyleAccess,
      keepSecretAccessKey: !!this.s3Config?.hasSecretAccessKey && !this.s3ConfigForm.secretAccessKey.trim()
    };

    this.isSavingS3Config = true;
    this.documentService.updateS3Config(request).subscribe({
      next: (config) => {
        this.s3Config = config;
        this.s3ConfigForm = this.createS3ConfigForm(config);
        this.s3ConfigError = false;
        this.isSavingS3Config = false;
        this.showStatus(config.configured ? 'S3 configuration saved.' : 'S3 configuration cleared.', 'success');
      },
      error: (error) => {
        const message = error?.error?.message || error?.error?.detail || 'Failed to save S3 configuration.';
        this.showStatus(message, 'error');
        this.isSavingS3Config = false;
      }
    });
  }

  private loadFromS3Key(key: string): Promise<{ success: boolean }> {
    if (!key.trim()) {
      this.showStatus('Please enter an S3 key', 'error');
      return Promise.resolve({ success: false });
    }

    if (!this.s3Config?.configured) {
      this.showStatus('Configure your S3 bucket before uploading by S3 key.', 'error');
      return Promise.resolve({ success: false });
    }

    this.isS3Loading = true;
    return firstValueFrom(this.documentService.loadFromS3(key.trim()))
      .then((response) => ({ success: response.success }))
      .catch(() => {
        this.showStatus('Failed to load from S3. Check the key and connection.', 'error');
        return { success: false };
      })
      .finally(() => {
        this.isS3Loading = false;
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
    return 'Enter document URL (e.g., https://example.com/document.pdf)';
  }

  get isLoading(): boolean {
    if (this.mode === 'document' || this.mode === 'configuration') {
      return this.isDocumentLoading || this.isExplorerLoading || this.isS3Loading || this.isSavingS3Config;
    }
    if (this.mode === 'analytics') {
      return this.isAnalyticsLoading;
    }
    return this.isChatLoading;
  }

  get analyticsOverview() {
    return this.analyticsSummary?.overview ?? null;
  }

  get analyticsOperations(): ChatAnalyticsOperationSummary[] {
    return this.analyticsSummary?.byOperation ?? [];
  }

  get analyticsModels(): ChatAnalyticsModelSummary[] {
    return this.analyticsSummary?.byModel ?? [];
  }

  get analyticsRecentCalls(): ChatUsageEvent[] {
    return this.analyticsSummary?.recentCalls ?? [];
  }

  get analyticsHasData(): boolean {
    return !!this.analyticsSummary && (this.analyticsOverview?.totalCalls ?? 0) > 0;
  }

  get s3HostLabel(): string {
    return this.s3Config?.endpointOverride || 'AWS S3';
  }

  get s3CredentialLabel(): string {
    return !this.s3Config || this.s3Config.usesDefaultCredentials ? 'Default AWS credentials' : 'Stored access key';
  }

  switchToDocument() {
    this.mode = 'document';
    this.inputText = '';
    this.showS3Settings = false;
    this.documentUrlInput = '';
    this.documentS3KeyInput = '';
    this.selectedExplorerFiles = [];
  }
  switchToChat()     {
    this.mode = 'chat';
    this.inputText = '';
    this.showS3Settings = false;
  }

  switchToAnalytics(): void {
    this.mode = 'analytics';
    this.showS3Settings = false;
    if (!this.analyticsSummary && !this.isAnalyticsLoading) {
      this.loadAnalyticsSummary();
    }
  }

  refreshAnalytics(): void {
    this.loadAnalyticsSummary(true);
  }

  handleSend() {
    if (this.mode === 'chat') this.askQuestion();
    else if (this.mode === 'document') this.loadDocument();
  }

  private loadAnalyticsSummary(isManualRefresh = false): void {
    this.isAnalyticsLoading = true;
    this.analyticsError = '';

    this.analyticsService.getChatAnalytics().subscribe({
      next: (summary) => {
        this.analyticsSummary = summary;
        this.analyticsLastUpdated = new Date();
        this.isAnalyticsLoading = false;
        if (isManualRefresh) {
          this.showStatus('Analytics refreshed.', 'success');
        }
      },
      error: (error) => {
        this.isAnalyticsLoading = false;
        this.analyticsError = error?.error?.message || 'Failed to load analytics.';
        if (isManualRefresh) {
          this.showStatus(this.analyticsError, 'error');
        }
      }
    });
  }

  openExplorerFiles(): void {
    const nativePicker = (window as any).showOpenFilePicker;
    if (typeof nativePicker === 'function') {
      this.pickFilesViaNativeApi();
      return;
    }
    this.filePickerRef?.nativeElement.click();
  }

  openExplorerFolder(): void {
    const nativeFolderPicker = (window as any).showDirectoryPicker;
    if (typeof nativeFolderPicker === 'function') {
      this.pickFolderViaNativeApi();
      return;
    }
    this.folderPickerRef?.nativeElement.click();
  }

  async uploadExplorerFiles(showStatus = true): Promise<{ successCount: number; failureCount: number }> {
    if (this.selectedExplorerFiles.length === 0) {
      if (showStatus) this.showStatus('Choose at least one file to add to the vector store.', 'error');
      return { successCount: 0, failureCount: 0 };
    }

    this.isExplorerLoading = true;
    let successCount = 0;
    let failureCount = 0;

    for (const entry of this.selectedExplorerFiles) {
      try {
        const response = await firstValueFrom(this.documentService.uploadDocumentFile(entry.file));
        if (response.success) successCount++;
        else failureCount++;
      } catch {
        failureCount++;
      }
    }

    this.isExplorerLoading = false;
    if (showStatus) {
      if (failureCount === 0) {
        this.showStatus(`Added ${successCount} file(s) to the vector store.`, 'success');
      } else if (successCount > 0) {
        this.showStatus(`Added ${successCount} file(s); ${failureCount} failed.`, 'success');
      } else {
        this.showStatus('Failed to add selected files.', 'error');
      }
    }
    return { successCount, failureCount };
  }

  onExplorerFilesSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const files = Array.from(input.files ?? []);
    this.mergeExplorerFiles(files, false);
    input.value = '';
  }

  onExplorerFolderSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const files = Array.from(input.files ?? []);
    this.mergeExplorerFiles(files, true);
    input.value = '';
  }

  removeExplorerEntry(index: number): void {
    this.selectedExplorerFiles.splice(index, 1);
    this.selectedExplorerFiles = [...this.selectedExplorerFiles];
  }

  clearExplorerSelection(): void {
    this.selectedExplorerFiles = [];
  }

  get selectedFilesSummary(): string {
    if (this.selectedExplorerFiles.length === 0) return '';
    if (this.selectedExplorerFiles.length === 1) return this.selectedExplorerFiles[0].displayPath;
    return `${this.selectedExplorerFiles.length} files selected`;
  }

  private async pickFilesViaNativeApi(): Promise<void> {
    try {
      const handles = await (window as any).showOpenFilePicker({
        multiple: true,
        startIn: 'documents',
        types: [{
          description: 'Supported documents',
          accept: {
            'application/pdf': ['.pdf'],
            'application/vnd.openxmlformats-officedocument.wordprocessingml.document': ['.docx'],
            'application/msword': ['.doc'],
            'text/plain': ['.txt']
          }
        }]
      });
      const files: File[] = [];
      for (const handle of handles) {
        files.push(await handle.getFile());
      }
      this.explorerLocationLabel = 'Documents';
      this.mergeExplorerFiles(files, false);
    } catch {
      // User canceled picker — no-op
    }
  }

  private async pickFolderViaNativeApi(): Promise<void> {
    try {
      const dirHandle = await (window as any).showDirectoryPicker({ startIn: 'documents' });
      this.explorerLocationLabel = dirHandle?.name || 'Selected Folder';
      const files = await this.collectDirectoryFiles(dirHandle, '');
      this.mergeExplorerFiles(files, true);
    } catch {
      // User canceled picker — no-op
    }
  }

  private async collectDirectoryFiles(dirHandle: any, prefix: string): Promise<File[]> {
    const files: File[] = [];
    for await (const [name, handle] of dirHandle.entries()) {
      const nextPrefix = prefix ? `${prefix}/${name}` : name;
      if (handle.kind === 'file') {
        const file = await handle.getFile();
        const relativePath = nextPrefix;
        Object.defineProperty(file, 'webkitRelativePath', { value: relativePath, configurable: true });
        files.push(file);
      } else if (handle.kind === 'directory') {
        files.push(...await this.collectDirectoryFiles(handle, nextPrefix));
      }
    }
    return files;
  }

  private mergeExplorerFiles(files: File[], fromFolder: boolean): void {
    const accepted = files
      .filter(file => this.isAcceptedExplorerFile(file.name))
      .map(file => ({
        file,
        displayPath: fromFolder ? ((file as any).webkitRelativePath || file.name) : file.name,
        typeLabel: this.getExplorerTypeLabel(file.name),
        sizeLabel: this.formatFileSize(file.size)
      }));

    if (accepted.length === 0) {
      this.showStatus('No supported files found. Allowed: .pdf, .docx, .doc, .txt', 'error');
      return;
    }

    const byPath = new Map(this.selectedExplorerFiles.map(entry => [entry.displayPath, entry]));
    for (const entry of accepted) {
      byPath.set(entry.displayPath, entry);
    }
    this.selectedExplorerFiles = Array.from(byPath.values()).sort((a, b) => a.displayPath.localeCompare(b.displayPath));
  }

  private isAcceptedExplorerFile(name: string): boolean {
    const lower = name.toLowerCase();
    return lower.endsWith('.pdf') || lower.endsWith('.docx') || lower.endsWith('.doc') || lower.endsWith('.txt');
  }

  private getExplorerTypeLabel(name: string): string {
    const lower = name.toLowerCase();
    if (lower.endsWith('.pdf')) return 'PDF';
    if (lower.endsWith('.docx')) return 'DOCX';
    if (lower.endsWith('.doc')) return 'DOC';
    if (lower.endsWith('.txt')) return 'TXT';
    return 'FILE';
  }

  private formatFileSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
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
    void this.runDocumentUploads();
  }

  private async runDocumentUploads(): Promise<void> {
    const hasUrl = !!this.documentUrlInput.trim();
    const hasS3Key = !!this.documentS3KeyInput.trim();
    const hasSelectedFiles = this.selectedExplorerFiles.length > 0;

    if (!hasUrl && !hasS3Key && !hasSelectedFiles) {
      this.showStatus('Please enter a document URL, an S3 key, or choose files below.', 'error');
      return;
    }

    this.isDocumentLoading = true;
    let successCount = 0;
    let failureCount = 0;

    try {
      if (hasUrl) {
        try {
          const response = await firstValueFrom(this.documentService.loadDocument(this.documentUrlInput.trim()));
          if (response.success) successCount++; else failureCount++;
        } catch {
          failureCount++;
        }
      }

      if (hasS3Key) {
        try {
          const result = await this.loadFromS3Key(this.documentS3KeyInput.trim());
          if (result.success) successCount++; else failureCount++;
        } catch {
          failureCount++;
        }
      }

      if (hasSelectedFiles) {
        const fileResults = await this.uploadExplorerFiles(false);
        successCount += fileResults.successCount;
        failureCount += fileResults.failureCount;
      }
    } finally {
      this.isDocumentLoading = false;
    }

    if (successCount > 0) {
      this.documentUrlInput = '';
      this.documentS3KeyInput = '';
      this.selectedExplorerFiles = [];
      this.refreshDocuments();
    }

    if (failureCount === 0) {
      this.showStatus(`Uploaded ${successCount} document source(s) successfully.`, 'success');
    } else if (successCount > 0) {
      this.showStatus(`Uploaded ${successCount} source(s); ${failureCount} failed.`, 'success');
    } else {
      this.showStatus('Failed to upload document source(s).', 'error');
    }
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
    this.mergeExplorerFiles(Array.from(files), false);
  }

  onKeyPress(event: KeyboardEvent) {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      this.handleSend();
    }
  }

  private createDefaultS3ConfigForm(): S3ConfigForm {
    return {
      bucketName: '',
      region: 'us-east-1',
      accessKeyId: '',
      secretAccessKey: '',
      endpointOverride: '',
      pathStyleAccess: false
    };
  }

  private createS3ConfigForm(config: S3Config): S3ConfigForm {
    return {
      bucketName: config.bucketName ?? '',
      region: config.region || 'us-east-1',
      accessKeyId: config.accessKeyId ?? '',
      secretAccessKey: '',
      endpointOverride: config.endpointOverride ?? '',
      pathStyleAccess: config.pathStyleAccess
    };
  }
}
