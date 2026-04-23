import {
  Component, Input, OnChanges, SimpleChanges,
  ElementRef, ViewChild, AfterViewInit, NgZone, Output, EventEmitter
} from '@angular/core';
import { renderAsync } from 'docx-preview';

@Component({
  selector: 'app-docx-viewer',
  template: `
    <div #container class="docx-render-container">
      <div *ngIf="loading" class="docx-loading">Loading document…</div>
      <div *ngIf="error" class="docx-error">{{ error }}</div>
    </div>
  `,
  styles: [`
    .docx-render-container {
      width: 100%;
      min-height: 200px;
    }
    .docx-loading, .docx-error {
      padding: 40px 20px;
      text-align: center;
      color: #94a3b8;
      font-style: italic;
      font-size: 0.9em;
    }
    .docx-error { color: #ef4444; }

    /* Make docx-preview pages look like Word documents */
    :host ::ng-deep .docx-wrapper {
      background: #e2e8f0;
      padding: 16px 8px;
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 16px;
    }
    :host ::ng-deep .docx-wrapper > section.docx {
      background: white;
      box-shadow: 0 2px 8px rgba(0,0,0,0.15);
      border-radius: 2px;
      width: 100% !important;
      min-height: 400px;
      box-sizing: border-box;
      padding: 60px 72px !important;
    }
    /* Highlight spans injected for source matching */
    :host ::ng-deep .docx-highlight {
      background-color: #fef08a;
      border-radius: 2px;
      border-bottom: 2px solid #eab308;
    }
  `]
})
export class DocxViewerComponent implements OnChanges, AfterViewInit {
  @Input() rawUrl = '';          // URL to fetch raw .docx bytes from
  @Input() highlights: string[] = []; // LLM-extracted passages to highlight
  @Output() highlightCount = new EventEmitter<number>();

  @ViewChild('container') containerRef!: ElementRef<HTMLDivElement>;

  loading = false;
  error = '';
  private isViewInit = false;

  constructor(private zone: NgZone) {}

  ngAfterViewInit(): void {
    this.isViewInit = true;
    if (this.rawUrl) this.renderDoc();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (!this.isViewInit) return;
    if (changes['rawUrl'] && this.rawUrl) {
      this.renderDoc();
    } else if (changes['highlights'] && !changes['rawUrl']) {
      this.applyHighlights();
    }
  }

  private async renderDoc(): Promise<void> {
    const container = this.containerRef?.nativeElement;
    if (!container || !this.rawUrl) return;

    this.loading = true;
    this.error = '';
    // Clear previous render
    container.innerHTML = '';

    try {
      const resp = await fetch(this.rawUrl, { credentials: 'include' });
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
      const blob = await resp.blob();

      await renderAsync(blob, container, undefined, {
        className: 'docx',
        inWrapper: true,
        ignoreWidth: false,
        ignoreHeight: true,      // let CSS control height so it fits the pane
        ignoreFonts: false,
        breakPages: true,
        ignoreLastRenderedPageBreak: true,
        renderHeaders: true,
        renderFooters: true,
        renderFootnotes: true,
        renderEndnotes: true,
        useBase64URL: true,
      });

      this.loading = false;
      if (this.highlights?.length > 0) {
        // Small delay to let the DOM settle after render
        setTimeout(() => this.applyHighlights(), 100);
      } else {
        this.zone.run(() => this.highlightCount.emit(0));
      }
    } catch (e: any) {
      this.loading = false;
      this.error = `Failed to load document: ${e?.message ?? e}`;
      console.error('[DocxViewer]', e);
      this.zone.run(() => this.highlightCount.emit(0));
    }
  }

  /**
   * Walk all text nodes inside the rendered docx DOM, concatenate them into
   * one flat string, find each LLM-extracted passage in that string (with
   * whitespace-normalization so Word run-splits don't break matching), then
   * split the affected text nodes and wrap matched portions in <mark>.
   */
  private applyHighlights(): void {
    const container = this.containerRef?.nativeElement;
    if (!container || !this.highlights?.length) return;

    // Remove any existing highlights before re-applying
    container.querySelectorAll('.docx-highlight').forEach(el => {
      const parent = el.parentNode;
      if (parent) {
        while (el.firstChild) parent.insertBefore(el.firstChild, el);
        parent.removeChild(el);
      }
    });

    const passages = this.highlights
      .map(h => h.replace(/\s+/g, ' ').trim().toLowerCase())
      .filter(h => h.length > 4);

    if (passages.length === 0) {
      this.zone.run(() => this.highlightCount.emit(0));
      return;
    }

    // ── 1. Collect all leaf text nodes in DOM order ──
    const walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT, null);
    const textNodes: Text[] = [];
    let n: Text | null;
    while ((n = walker.nextNode() as Text | null)) textNodes.push(n);

    // ── 2. Build flat text + per-node offsets ──
    const nodeTexts = textNodes.map(tn => tn.textContent ?? '');
    const nodeStarts: number[] = [];
    let pos = 0;
    for (const t of nodeTexts) { nodeStarts.push(pos); pos += t.length; }
    const fullText = nodeTexts.join('');

    // ── 3. Build whitespace-normalised version with back-mapping ──
    const { normalized, toOriginal } = this.buildNormalized(fullText);

    // ── 4. Find each passage in the normalised text ──
    type Range = { start: number; end: number };
    const matchRanges: Range[] = [];

    for (const passage of passages) {
      let sp = 0;
      while (sp < normalized.length) {
        const idx = normalized.indexOf(passage, sp);
        if (idx === -1) break;
        const origStart = toOriginal[idx];
        const lastIdx = idx + passage.length - 1;
        const origEnd = lastIdx < toOriginal.length ? toOriginal[lastIdx] + 1 : fullText.length;
        matchRanges.push({ start: origStart, end: origEnd });
        sp = idx + passage.length;
      }
    }

    if (matchRanges.length === 0) {
      this.zone.run(() => this.highlightCount.emit(0));
      return;
    }

    // ── 5. Map ranges onto text nodes ──
    const nodeMatchMap = new Map<number, Range[]>();
    for (const range of matchRanges) {
      for (let i = 0; i < textNodes.length; i++) {
        const ns = nodeStarts[i];
        const ne = ns + nodeTexts[i].length;
        if (ne <= range.start || ns >= range.end) continue;
        const localStart = Math.max(range.start - ns, 0);
        const localEnd   = Math.min(range.end   - ns, nodeTexts[i].length);
        if (!nodeMatchMap.has(i)) nodeMatchMap.set(i, []);
        nodeMatchMap.get(i)!.push({ start: localStart, end: localEnd });
      }
    }

    // ── 6. Wrap matched portions — process in reverse DOM order ──
    let count = 0;
    const sortedIndices = Array.from(nodeMatchMap.keys()).sort((a, b) => b - a);

    for (const idx of sortedIndices) {
      const textNode = textNodes[idx];
      const ranges = nodeMatchMap.get(idx)!.sort((a, b) => a.start - b.start);
      const text = textNode.textContent ?? '';
      const frag = document.createDocumentFragment();
      let cursor = 0;

      for (const { start, end } of ranges) {
        if (start > cursor) frag.appendChild(document.createTextNode(text.slice(cursor, start)));
        const mark = document.createElement('mark');
        mark.className = 'docx-highlight';
        mark.textContent = text.slice(start, end);
        frag.appendChild(mark);
        cursor = end;
        count++;
      }
      if (cursor < text.length) frag.appendChild(document.createTextNode(text.slice(cursor)));
      textNode.parentNode?.replaceChild(frag, textNode);
    }

    this.zone.run(() => {
      this.highlightCount.emit(count > 0 ? 1 : 0);
      if (count > 0) {
        setTimeout(() => {
          container.querySelector('.docx-highlight')
            ?.scrollIntoView({ behavior: 'smooth', block: 'center' });
        }, 80);
      }
    });
  }

  /**
   * Build a whitespace-normalised (lowercased) copy of `text` and a
   * `toOriginal` array that maps each normalised index back to its
   * position in the original string.
   */
  private buildNormalized(text: string): { normalized: string; toOriginal: number[] } {
    const toOriginal: number[] = [];
    let normalized = '';
    let i = 0;
    while (i < text.length) {
      if (/\s/.test(text[i])) {
        toOriginal.push(i);
        normalized += ' ';
        while (i < text.length && /\s/.test(text[i])) i++;
      } else {
        toOriginal.push(i);
        normalized += text[i].toLowerCase();
        i++;
      }
    }
    return { normalized, toOriginal };
  }

  /** Called from the parent via @ViewChild to jump to the first highlight. */
  scrollToHighlight(): void {
    const el = this.containerRef?.nativeElement?.querySelector('.docx-highlight');
    el?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }
}

