/**
 * Diagnostic probe for the "same-color rectangle floating over the text"
 * artifact seen on Android when a text selection is active.
 *
 * Background (2026-10): the editor hides Chromium's native floating
 * ActionMode (see HiddenSelectionActionModeCallback) and shows its own
 * toolbar instead. On some OEM WebViews a visible, theme-colored popup
 * plate still flashes / lingers over the selection. This probe does NOT
 * change anything — it only observes the DOM while a selection is active
 * and reports every plausible overlay so the real culprit can be identified
 * from an exported log.
 *
 * Enabled exclusively by the "selection input diagnostics" advanced setting.
 */
import { createLogger } from '../../lib/logger'

const probeLogger = createLogger('selection-overlay')

const PROBE_DEBOUNCE_MS = 300
const PROBE_MAX_ELEMENTS = 40

interface OverlayReport {
  tag: string
  id: string | null
  className: string | null
  position: string
  zIndex: string
  background: string
  borderColor: string
  opacity: string
  pointerEvents: string
  rect: { x: number; y: number; w: number; h: number }
  text: string
  dataTestId: string | null
}

function describeElement(el: Element): OverlayReport | null {
  if (!(el instanceof HTMLElement)) {
    return null
  }
  const style = window.getComputedStyle(el)
  if (style.display === 'none' || style.visibility === 'hidden') {
    return null
  }
  const rect = el.getBoundingClientRect()
  if (rect.width < 4 || rect.height < 4) {
    return null
  }

  return {
    tag: el.tagName.toLowerCase(),
    id: el.id || null,
    className: typeof el.className === 'string' ? el.className.slice(0, 120) : null,
    position: style.position,
    zIndex: style.zIndex,
    background: style.backgroundColor,
    borderColor: style.borderTopColor,
    opacity: style.opacity,
    pointerEvents: style.pointerEvents,
    rect: {
      x: Math.round(rect.x),
      y: Math.round(rect.y),
      w: Math.round(rect.width),
      h: Math.round(rect.height),
    },
    text: (el.textContent ?? '').trim().slice(0, 40),
    dataTestId: el.getAttribute('data-testid'),
  }
}

/**
 * Collect every fixed/absolute positioned element that overlaps the editor
 * viewport but is not the app-owned selection toolbar. These are the only
 * candidates for a stray floating plate.
 */
function collectOverlayCandidates(host: HTMLElement | null): OverlayReport[] {
  const viewportW = window.innerWidth
  const viewportH = window.innerHeight
  const out: OverlayReport[] = []
  const seen = new Set<Element>()

  for (const el of Array.from(document.body.querySelectorAll('*'))) {
    if (seen.has(el)) {
      continue
    }
    const style = window.getComputedStyle(el)
    if (style.position !== 'fixed' && style.position !== 'absolute') {
      continue
    }
    // Skip the app's own toolbar and anything inside the editor host we wrote.
    if (el.closest('[data-testid="mobile-selection-toolbar"]')) {
      continue
    }
    if (host && (el === host || host.contains(el))) {
      continue
    }
    const rect = el.getBoundingClientRect()
    // Must actually sit inside the viewport.
    if (rect.bottom < 0 || rect.top > viewportH || rect.right < 0 || rect.left > viewportW) {
      continue
    }
    const report = describeElement(el)
    if (!report) {
      continue
    }
    seen.add(el)
    out.push(report)
    if (out.length >= PROBE_MAX_ELEMENTS) {
      break
    }
  }

  return out
}

let active = false
let debounceTimer: number | null = null
let selectionListener: (() => void) | null = null

function runProbe(host: HTMLElement | null, reason: string) {
  const selection = document.getSelection()
  const selectionState = {
    reason,
    rangeCount: selection?.rangeCount ?? 0,
    collapsed: selection?.isCollapsed ?? true,
    selectionText: (selection?.toString() ?? '').slice(0, 40),
    overlayCount: 0,
  }

  const candidates = collectOverlayCandidates(host)
  selectionState.overlayCount = candidates.length
  probeLogger.info('selection overlay probe', selectionState)

  for (const candidate of candidates) {
    probeLogger.info('selection overlay candidate', candidate)
  }
}

function scheduleProbe(host: HTMLElement | null, reason: string) {
  if (debounceTimer !== null) {
    window.clearTimeout(debounceTimer)
  }
  debounceTimer = window.setTimeout(() => {
    debounceTimer = null
    if (active) {
      runProbe(host, reason)
    }
  }, PROBE_DEBOUNCE_MS)
}

export function startSelectionOverlayProbe(host: HTMLElement | null): void {
  if (active) {
    return
  }
  active = true
  selectionListener = () => scheduleProbe(host, 'selectionchange')
  document.addEventListener('selectionchange', selectionListener)
  probeLogger.info('selection overlay probe installed')
}

export function stopSelectionOverlayProbe(): void {
  active = false
  if (debounceTimer !== null) {
    window.clearTimeout(debounceTimer)
    debounceTimer = null
  }
  if (selectionListener) {
    document.removeEventListener('selectionchange', selectionListener)
    selectionListener = null
  }
}
