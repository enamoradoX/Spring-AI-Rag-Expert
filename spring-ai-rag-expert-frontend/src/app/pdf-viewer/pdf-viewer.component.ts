import {
  Component, Input, OnChanges, SimpleChanges, ElementRef, ViewChild, AfterViewInit, NgZone, Output, EventEmitter
} from '@angular/core';
import * as pdfjsLib from 'pdfjs-dist';

// v3 worker path (.js not .mjs)
(pdfjsLib as any).GlobalWorkerOptions.workerSrc = 'assets/pdf.worker.min.js';

@Component({
  selector: 'app-pdf-viewer',
  // The scaler div is the scroll container — its content always reflects
  // the true rendered size so scrollbars work correctly at any zoom level.
  template: `<div #scaler style="width:100%; overflow-x:auto;"></div>`
})
export class PdfViewerComponent implements OnChanges, AfterViewInit {
  @Input() pdfUrl = '';
  @Input() answerText = '';
  @Input() sources: string[] = [];
  @Input() zoom = 1.0;
  @Output() highlightCount = new EventEmitter<number>();
  @ViewChild('scaler') scalerRef!: ElementRef<HTMLDivElement>;

  private isViewInit = false;
  private renderedZoom = 1.0;
  private activeDiv: HTMLDivElement | null = null;
  private pages: { canvas: HTMLCanvasElement; overlay: HTMLCanvasElement; textItems: any[]; viewport: any }[] = [];
  private firstHighlightWrapper: HTMLElement | null = null;
  private zoomDebounceTimer: any = null;  // debounce handle for zoom-triggered re-renders

  constructor(private zone: NgZone) {}

  ngAfterViewInit(): void {
    this.isViewInit = true;
    if (this.pdfUrl) this.zone.runOutsideAngular(() => this.renderPdf());
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (!this.isViewInit) return;

    if (changes['pdfUrl']) {
      // New document — re-render immediately
      clearTimeout(this.zoomDebounceTimer);
      this.zone.runOutsideAngular(() => this.renderPdf());
    } else if (changes['zoom']) {
      // Instant CSS scale for immediate visual feedback while user is still clicking
      if (this.activeDiv) {
        const ratio = (this.zoom ?? 1.0) / this.renderedZoom;
        Object.assign(this.activeDiv.style, {
          transform: `scale(${ratio})`,
          transformOrigin: 'top left',
          transition: 'transform 0.1s ease'
        });
      }
      // Debounce the actual re-render — only fires 300ms after user stops zooming
      clearTimeout(this.zoomDebounceTimer);
      this.zoomDebounceTimer = setTimeout(() => {
        this.zone.runOutsideAngular(() => this.renderPdf());
      }, 300);
    }

    if ((changes['sources'] || changes['answerText']) && this.pages.length > 0) {
      this.zone.runOutsideAngular(() => this.drawAllHighlights());
    }
  }

  private async renderPdf(): Promise<void> {
    const scaler = this.scalerRef?.nativeElement;
    if (!scaler || !this.pdfUrl) return;

    const newDiv = document.createElement('div');
    newDiv.style.cssText = 'width:100%; opacity:0;';
    scaler.appendChild(newDiv);
    const newPages: { canvas: HTMLCanvasElement; overlay: HTMLCanvasElement; textItems: any[]; viewport: any }[] = [];
    const oldDiv = this.activeDiv;

    try {
      const pdf = await (pdfjsLib as any).getDocument({ url: this.pdfUrl, withCredentials: true }).promise;
      const containerWidth = scaler.clientWidth || 580;

      for (let pageNum = 1; pageNum <= pdf.numPages; pageNum++) {
        const page = await pdf.getPage(pageNum);
        const baseScale = containerWidth / page.getViewport({ scale: 1 }).width;
        const scale = Math.min(baseScale, 2) * (this.zoom ?? 1.0);
        const viewport = page.getViewport({ scale });

        const wrapper = document.createElement('div');
        Object.assign(wrapper.style, {
          position: 'relative',
          width: viewport.width + 'px',
          height: viewport.height + 'px',
          margin: '0 auto 16px auto',
          boxShadow: '0 2px 8px rgba(0,0,0,0.15)',
          background: 'white',
          opacity: '0',
          transition: 'opacity 0.2s ease'
        });

        const canvas = document.createElement('canvas');
        canvas.width = viewport.width;
        canvas.height = viewport.height;
        Object.assign(canvas.style, { position: 'absolute', top: '0', left: '0' });
        wrapper.appendChild(canvas);

        const overlay = document.createElement('canvas');
        overlay.width = viewport.width;
        overlay.height = viewport.height;
        Object.assign(overlay.style, {
          position: 'absolute', top: '0', left: '0', pointerEvents: 'none', zIndex: '2'
        });
        wrapper.appendChild(overlay);
        newDiv.appendChild(wrapper);

        await page.render({ canvasContext: canvas.getContext('2d')!, viewport }).promise;
        const textContent = await page.getTextContent();
        newPages.push({ canvas, overlay, textItems: textContent.items, viewport });

        // Reveal this page immediately — no waiting for all pages
        requestAnimationFrame(() => { wrapper.style.opacity = '1'; });

        // After PAGE 1 is ready: swap divs so content appears without any blank gap
        if (pageNum === 1) {
          this.activeDiv = newDiv;
          requestAnimationFrame(() => {
            newDiv.style.transition = 'opacity 0.2s ease';
            newDiv.style.opacity = '1';
            if (oldDiv) {
              oldDiv.style.transition = 'opacity 0.2s ease';
              oldDiv.style.opacity = '0';
              setTimeout(() => oldDiv.remove(), 220);
            }
          });
        }
      }

      this.pages = newPages;
      this.renderedZoom = this.zoom ?? 1.0;

      if (this.answerText || this.sources?.length > 0) {
        this.drawAllHighlights();
      }
    } catch (e: any) {
      newDiv.remove();
      console.error('[PdfViewer] Error:', e?.message || e);
      if (this.activeDiv) {
        this.activeDiv.innerHTML = `<p style="color:#ef4444;padding:16px;font-size:13px">
          Failed to load PDF: ${e?.message || 'Unknown error'}
        </p>`;
      }
    }
  }

  private drawAllHighlights(): void {
    if (this.sources?.length > 0) {
      const found = this.drawHighlightsFromSources(this.sources);
      // If the LLM-extracted passages couldn't be matched against PDF.js text
      // (e.g. different character encoding between Tika and PDF.js), fall back
      // to answer-text keyword scoring which is encoding-agnostic.
      if (!found && this.answerText?.trim()) {
        this.drawHighlightsFromAnswer(this.answerText.trim());
      }
    } else if (this.answerText?.trim()) {
      this.drawHighlightsFromAnswer(this.answerText.trim());
    }
  }

  /**
   * Match LLM-extracted verbatim passages against PDF.js text items.
   * Returns true if at least one text item was highlighted.
   *
   * PDFBox (indexer) and PDF.js (renderer) produce different text, so we
   * bridge the gap by tokenizing both sides identically (lowercase, strip
   * punctuation, expand ligatures) then searching for:
   *   1. The full tokenized passage as one substring  → most precise
   *   2. Overlapping 3-word windows as fallback        → handles partial extraction diffs
   *
   * Items are highlighted only if they are directly covered by a match.
   */
  private drawHighlightsFromSources(highlights: string[]): boolean {
    for (const { overlay } of this.pages) {
      overlay.getContext('2d')!.clearRect(0, 0, overlay.width, overlay.height);
    }
    if (!highlights?.length) return false;

    // Tokenize each highlight as one full string for exact matching,
    // and also build 3-word windows as fallback.
    const fullPassages: string[] = [];
    const windowPhrases = new Set<string>();
    const highlightWords = new Set<string>();

    for (const h of highlights) {
      const toks = this.tokenize(h);
      for (const t of toks) {
        if (t.length >= 3 && !STOPWORDS.has(t)) highlightWords.add(t);
      }
      if (toks.length >= 2) fullPassages.push(toks.join(' '));
      // 3-word windows as fallback (smaller window = higher coverage, handles encoding differences)
      const w = Math.min(3, toks.length);
      for (let i = 0; i <= toks.length - w; i++) {
        windowPhrases.add(toks.slice(i, i + w).join(' '));
      }
    }

    if (fullPassages.length === 0 && windowPhrases.size === 0) {
      return false;
    }

    this.firstHighlightWrapper = null;
    let highlightedCount = 0;

    for (const { overlay, textItems, viewport } of this.pages) {
      const ctx = overlay.getContext('2d')!;
      if (!textItems.length) continue;

      // Build one flat token string for the page with per-item start/end positions.
      const itemTokStrs: string[] = textItems.map((it: any) => this.tokenize(it.str ?? '').join(' '));
      let pageStr = '';
      const starts: number[] = [];
      const ends: number[] = [];
      for (const s of itemTokStrs) {
        starts.push(pageStr.length);
        pageStr += s ? s + ' ' : ' ';
        ends.push(pageStr.length);
      }

      // ── Pass 1: try full tokenized passage (most precise) ──
      const matchedRanges: [number, number][] = [];
      for (const passage of fullPassages) {
        let pos = 0;
        while ((pos = pageStr.indexOf(passage, pos)) !== -1) {
          if (pos === 0 || pageStr[pos - 1] === ' ') {
            const end = pos + passage.length;
            if (end >= pageStr.length || pageStr[end] === ' ') {
              matchedRanges.push([pos, end]);
            }
          }
          pos++;
        }
      }

      // ── Pass 2: fallback to 5-word windows if no full match found ──
      if (matchedRanges.length === 0) {
        for (const phrase of windowPhrases) {
          let pos = 0;
          while ((pos = pageStr.indexOf(phrase, pos)) !== -1) {
            if (pos === 0 || pageStr[pos - 1] === ' ') {
              const end = pos + phrase.length;
              if (end >= pageStr.length || pageStr[end] === ' ') {
                matchedRanges.push([pos, end]);
              }
            }
            pos++;
          }
        }
      }

      if (matchedRanges.length === 0) continue;

      // Mark items directly covered by a match + bridge gaps of ≤ 3
      const directHits = new Set<number>();
      for (let i = 0; i < textItems.length; i++) {
        if (matchedRanges.some(([rs, re]) => ends[i] > rs && starts[i] < re)) {
          directHits.add(i);
        }
      }
      // Only highlight items that share a highlight word (prevents section
      // headers / punctuation-only items from being coloured)
      for (const i of directHits) {
        const item = textItems[i];
        if (!item.str?.trim()) continue;
        const toks = this.tokenize(item.str);
        if (!toks.some((t: string) => highlightWords.has(t))) continue;

        this.drawHighlightRect(ctx, item, viewport);
        if (!this.firstHighlightWrapper) this.firstHighlightWrapper = overlay.parentElement;
        highlightedCount++;
      }
    }

    this.zone.run(() => this.highlightCount.emit(highlightedCount > 0 ? 1 : 0));
    if (this.firstHighlightWrapper) {
      setTimeout(() => this.firstHighlightWrapper!.scrollIntoView({ behavior: 'smooth', block: 'center' }), 150);
    }
    return highlightedCount > 0;
  }

  /**
   * Weighted token + phrase scoring fallback used when no LLM highlights are available.
   */
  private drawHighlightsFromAnswer(answer: string): void {
    const answerTokens = this.tokenize(answer);
    if (answerTokens.length < 3) return;

    const tokenWeights = new Map<string, number>();
    for (const t of answerTokens) {
      if (t.length >= 3 && !STOPWORDS.has(t)) {
        const w = Math.max(1, t.length - 2);
        tokenWeights.set(t, (tokenWeights.get(t) ?? 0) + w);
      }
    }
    if (tokenWeights.size === 0) return;

    const answerWords = new Set(tokenWeights.keys());

    const phrases = new Set<string>();
    for (let w = 3; w <= 4 && w <= answerTokens.length; w++) {
      for (let i = 0; i <= answerTokens.length - w; i++) {
        const slice = answerTokens.slice(i, i + w);
        if (slice.some(t => tokenWeights.has(t))) phrases.add(slice.join(' '));
      }
    }

    for (const { overlay } of this.pages) {
      overlay.getContext('2d')!.clearRect(0, 0, overlay.width, overlay.height);
    }

    const WINDOW = 25;
    let bestScore = 0, bestPageIdx = -1, bestStart = 0, bestEnd = 0;

    for (let pi = 0; pi < this.pages.length; pi++) {
      const { textItems } = this.pages[pi];
      if (!textItems.length) continue;
      const itemTokStrs = textItems.map((it: any) => this.tokenize(it.str ?? '').join(' '));
      const rawScores = itemTokStrs.map((s: string) =>
        s.split(' ').reduce((acc: number, t: string) => acc + (tokenWeights.get(t) ?? 0), 0)
      );
      for (let start = 0; start < textItems.length; start++) {
        const end = Math.min(start + WINDOW, textItems.length);
        const windowStr = itemTokStrs.slice(start, end).join(' ');
        let score = (rawScores as number[]).slice(start, end).reduce((a: number, b: number) => a + b, 0);
        for (const phrase of phrases) { if (windowStr.includes(phrase)) score += 4; }
        if (score > bestScore) { bestScore = score; bestPageIdx = pi; bestStart = start; bestEnd = end; }
      }
    }

    if (bestPageIdx === -1 || bestScore === 0) return;

    const { overlay, textItems, viewport } = this.pages[bestPageIdx];
    const itemToks: string[][] = textItems.map((it: any) => this.tokenize(it.str ?? ''));
    const itemScores = itemToks.map((toks: string[]) =>
      toks.reduce((s: number, t: string) => s + (tokenWeights.get(t) ?? 0), 0)
    );

    let rangeStart = -1, rangeEnd = -1, gapCount = 0;
    for (let i = bestStart; i < bestEnd; i++) {
      if (itemScores[i] > 0) { if (rangeStart === -1) rangeStart = i; rangeEnd = i; gapCount = 0; }
      else if (rangeStart !== -1) {
        if (++gapCount > 4 && !itemScores.slice(i + 1, bestEnd).some((s: number) => s > 0)) break;
      }
    }
    if (rangeStart === -1) return;

    const ctx = overlay.getContext('2d')!;
    this.firstHighlightWrapper = null;
    let highlightedCount = 0;

    for (let i = rangeStart; i <= rangeEnd; i++) {
      const item = textItems[i];
      if (!item.str?.trim() || !itemToks[i].some((t: string) => answerWords.has(t))) continue;
      this.drawHighlightRect(ctx, item, viewport);
      if (!this.firstHighlightWrapper) this.firstHighlightWrapper = overlay.parentElement;
      highlightedCount++;
    }

    this.zone.run(() => this.highlightCount.emit(highlightedCount > 0 ? 1 : 0));
    if (this.firstHighlightWrapper) {
      setTimeout(() => this.firstHighlightWrapper!.scrollIntoView({ behavior: 'smooth', block: 'center' }), 150);
    }
  }

  private drawHighlightRect(ctx: CanvasRenderingContext2D, item: any, viewport: any): void {
    const tx = (pdfjsLib as any).Util.transform(viewport.transform, item.transform);
    const x = tx[4], y = tx[5];
    const w = item.width * (viewport.scale ?? 1);
    const h = Math.abs(item.height * viewport.scale) || 12;
    ctx.fillStyle = 'rgba(254, 240, 138, 0.70)';
    ctx.fillRect(x, y - h, w, h + 2);
    ctx.strokeStyle = 'rgba(234, 179, 8, 0.95)';
    ctx.lineWidth = 1;
    ctx.strokeRect(x, y - h, w, h + 2);
  }

  /** Scroll to the first highlighted region — callable from the parent via @ViewChild. */
  scrollToHighlight(): void {
    if (this.firstHighlightWrapper) {
      this.firstHighlightWrapper.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
  }

  private tokenize(text: string): string[] {
    return text
      .replace(/\uFB00/g, 'ff').replace(/\uFB01/g, 'fi').replace(/\uFB02/g, 'fl')
      .replace(/\uFB03/g, 'ffi').replace(/\uFB04/g, 'ffl')
      .toLowerCase()
      .replace(/[^a-z0-9+#]/g, ' ')
      .split(/\s+/)
      .filter(w => w.length > 0);
  }
}

const STOPWORDS = new Set([
  'the','and','for','are','but','not','you','all','can','had','her','was','one','our',
  'out','day','get','has','him','his','how','its','may','new','now','old','see','two',
  'way','who','did','let','put','say','she','too','use','that','this','with','have',
  'from','they','will','been','more','when','than','what','some','time','very','just',
  'also','into','over','then','your','about','would','there','their','these','which',
  'other','after','first','could','should','where','while','those',
]);
