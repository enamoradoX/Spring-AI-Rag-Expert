import {
  Component, Input, OnChanges, SimpleChanges, ElementRef, ViewChild, AfterViewInit, NgZone, Output, EventEmitter
} from '@angular/core';
import * as pdfjsLib from 'pdfjs-dist';

// v3 worker path (.js not .mjs)
(pdfjsLib as any).GlobalWorkerOptions.workerSrc = 'assets/pdf.worker.min.js';

@Component({
  selector: 'app-pdf-viewer',
  template: `<div #container style="width:100%"></div>`
})
export class PdfViewerComponent implements OnChanges, AfterViewInit {
  @Input() pdfUrl = '';
  @Input() answerText = '';
  @Input() sources: string[] = [];
  @Output() highlightCount = new EventEmitter<number>();
  @ViewChild('container') containerRef!: ElementRef<HTMLDivElement>;

  private isViewInit = false;
  private pages: { canvas: HTMLCanvasElement; overlay: HTMLCanvasElement; textItems: any[]; viewport: any }[] = [];
  private firstHighlightWrapper: HTMLElement | null = null;

  constructor(private zone: NgZone) {}

  ngAfterViewInit(): void {
    this.isViewInit = true;
    if (this.pdfUrl) this.zone.runOutsideAngular(() => this.renderPdf());
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (!this.isViewInit) return;
    if (changes['pdfUrl']) {
      this.zone.runOutsideAngular(() => this.renderPdf());
    } else if ((changes['sources'] || changes['answerText']) && this.pages.length > 0) {
      this.zone.runOutsideAngular(() => this.drawAllHighlights());
    }
  }

  private async renderPdf(): Promise<void> {
    const container = this.containerRef?.nativeElement;
    if (!container || !this.pdfUrl) return;
    container.innerHTML = '';
    this.pages = [];

    try {
      const pdf = await (pdfjsLib as any).getDocument({ url: this.pdfUrl, withCredentials: true }).promise;

      for (let pageNum = 1; pageNum <= pdf.numPages; pageNum++) {
        const page = await pdf.getPage(pageNum);
        const containerWidth = container.clientWidth || 580;
        const scale = Math.min(containerWidth / page.getViewport({ scale: 1 }).width, 2);
        const viewport = page.getViewport({ scale });

        // Wrapper
        const wrapper = document.createElement('div');
        Object.assign(wrapper.style, {
          position: 'relative', width: viewport.width + 'px', height: viewport.height + 'px',
          margin: '0 auto 16px auto', boxShadow: '0 2px 8px rgba(0,0,0,0.15)', background: 'white'
        });

        // Main canvas — PDF content
        const canvas = document.createElement('canvas');
        canvas.width = viewport.width;
        canvas.height = viewport.height;
        Object.assign(canvas.style, { position: 'absolute', top: '0', left: '0' });
        wrapper.appendChild(canvas);

        // Overlay canvas — highlight rectangles drawn here
        const overlay = document.createElement('canvas');
        overlay.width = viewport.width;
        overlay.height = viewport.height;
        Object.assign(overlay.style, {
          position: 'absolute', top: '0', left: '0',
          pointerEvents: 'none', zIndex: '2'
        });
        wrapper.appendChild(overlay);

        container.appendChild(wrapper);

        // Render PDF content
        await page.render({ canvasContext: canvas.getContext('2d')!, viewport }).promise;

        // Collect text items with their positions
        const textContent = await page.getTextContent();
        this.pages.push({ canvas, overlay, textItems: textContent.items, viewport });
      }

      if (this.answerText || this.sources?.length > 0) {
        this.drawAllHighlights();
      }
    } catch (e: any) {
      console.error('[PdfViewer] Error:', e?.message || e);
      container.innerHTML = `<p style="color:#ef4444;padding:16px;font-size:13px">
        Failed to load PDF: ${e?.message || 'Unknown error'}
      </p>`;
    }
  }

  private drawAllHighlights(): void {
    // ── Strategy ──
    // If LLM-extracted verbatim highlights are available (sources[]), use them for
    // direct phrase matching — these are short exact sentences from the document so
    // our tokenized search finds them precisely.
    // Fall back to answer-text weighted scoring only when no highlights are provided.
    if (this.sources?.length > 0) {
      this.drawHighlightsFromSources(this.sources);
    } else if (this.answerText?.trim()) {
      this.drawHighlightsFromAnswer(this.answerText.trim());
    }
  }

  /**
   * Direct phrase matching against LLM-extracted verbatim passages.
   * Each source is a short sentence copied verbatim from the PDFBox text.
   * We tokenize both sides the same way, join into a page token string, and
   * search for every 4-word window from the passage — very precise.
   */
  private drawHighlightsFromSources(highlights: string[]): void {
    // Clear all overlays
    for (const { overlay } of this.pages) {
      overlay.getContext('2d')!.clearRect(0, 0, overlay.width, overlay.height);
    }

    // Each highlight may be a full multi-line section. Flatten all lines into tokens
    // and build overlapping 4-word phrase windows for matching.
    // Also build the vocabulary set for per-item relevance filtering.
    const phrases = new Set<string>();
    const highlightWords = new Set<string>();

    for (const h of highlights) {
      // Treat each line of the section independently so intra-section line breaks
      // don't create bogus cross-line phrase windows
      const lines = h.split('\n').map(l => l.trim()).filter(l => l.length > 0);
      for (const line of lines) {
        const toks = this.tokenize(line);
        // Significant vocab
        for (const t of toks) {
          if (t.length >= 3 && !STOPWORDS.has(t)) highlightWords.add(t);
        }
        // 4-word (or 3-word for short lines) phrase windows
        const wSize = Math.min(4, toks.length);
        if (wSize < 2) continue;
        for (let i = 0; i <= toks.length - wSize; i++) {
          phrases.add(toks.slice(i, i + wSize).join(' '));
        }
      }
    }

    if (phrases.size === 0) {
      // Fall back to answer-text scoring if the LLM returned nothing useful
      if (this.answerText?.trim()) this.drawHighlightsFromAnswer(this.answerText.trim());
      return;
    }

    this.firstHighlightWrapper = null;
    let highlightedCount = 0;

    for (const { overlay, textItems, viewport } of this.pages) {
      const ctx = overlay.getContext('2d')!;
      if (!textItems.length) continue;

      // Build page token string with per-item positions
      const itemTokStrs: string[] = textItems.map((it: any) => this.tokenize(it.str ?? '').join(' '));
      let pageStr = '';
      const starts: number[] = [];
      const ends: number[] = [];
      for (const s of itemTokStrs) {
        starts.push(pageStr.length);
        pageStr += s ? s + ' ' : ' ';
        ends.push(pageStr.length);
      }

      // Find all phrase match ranges on this page
      const matchedRanges: [number, number][] = [];
      for (const phrase of phrases) {
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
      if (matchedRanges.length === 0) continue;

      // Merge overlapping/adjacent ranges and expand to include surrounding items
      // up to a gap of 3 non-matching items — this captures the full section.
      const mergedItemIndices = new Set<number>();
      for (let i = 0; i < textItems.length; i++) {
        const s = starts[i], e = ends[i];
        if (matchedRanges.some(([rs, re]) => e > rs && s < re)) {
          mergedItemIndices.add(i);
        }
      }

      // Expand: bridge gaps of up to 3 items between matched items (captures whole section)
      const sorted = Array.from(mergedItemIndices).sort((a, b) => a - b);
      const expanded = new Set<number>(sorted);
      for (let k = 0; k < sorted.length - 1; k++) {
        const gap = sorted[k + 1] - sorted[k];
        if (gap <= 4) {
          for (let fill = sorted[k] + 1; fill < sorted[k + 1]; fill++) expanded.add(fill);
        }
      }

      for (const i of Array.from(expanded).sort((a, b) => a - b)) {
        const item = textItems[i];
        if (!item.str?.trim()) continue;
        // Only draw items that contain a highlight word (skip pure punctuation/whitespace items)
        const toks = this.tokenize(item.str);
        if (!toks.some(t => highlightWords.has(t))) continue;

        this.drawHighlightRect(ctx, item, viewport);
        if (!this.firstHighlightWrapper) this.firstHighlightWrapper = overlay.parentElement;
        highlightedCount++;
      }
    }

    this.zone.run(() => this.highlightCount.emit(highlightedCount > 0 ? 1 : 0));
    if (this.firstHighlightWrapper) {
      setTimeout(() => this.firstHighlightWrapper!.scrollIntoView({ behavior: 'smooth', block: 'center' }), 150);
    }
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
