/**
 * Perspicuity 排版系统 —— 分块字体 + 中英分离
 *
 * 这是本项目相对上游的第一处「新开路线」：
 *
 * 上游只有 4 个固定的全局字体选项（open-sans/system/serif/monospace），
 * 整篇文档共用一个 font-family。本模块把它升级为一套真正的排版系统：
 *
 *   1. 字体栈 = 「拉丁字体」+「中文（CJK）字体」+ 通用兜底
 *      —— 利用 CSS font-family 的 fallback 机制自动分流：
 *         英文/数字命中第一个字体，汉字自动落到第二个字体。
 *         这与 VS Code / Notion 的做法一致，无需任何 JS 切字。
 *
 *   2. 按「内容块类型」分别指定字体与字号：
 *      body(正文/段落) / heading(标题) / code(代码) / table(表格) /
 *      quote(引用) / list(列表)
 *      —— 依据 Muya 输出的语义化标签（p / h1-h6 / pre / table / blockquote / li）
 *         用 CSS 选择器精确命中，纯样式层实现，不动编辑内核。
 *
 * 设计原则：所有值都能安全降级回上游默认外观。用户没动过的块沿用全局设置，
 * 因此「不开高级排版」时表现与上游完全一致。
 */

import type { SettingsValue } from './settingsState'
import { getImportedFonts, resolveFontFamilyFragment, toFontFamilyName } from './importedFontRegistry'

/** 可独立设置的「内容块」类型。 */
export type TypographyBlockKind =
  | 'body'
  | 'heading'
  | 'code'
  | 'table'
  | 'quote'
  | 'list'

export const TYPOGRAPHY_BLOCK_KINDS = [
  'body',
  'heading',
  'code',
  'table',
  'quote',
  'list',
] as const satisfies readonly TypographyBlockKind[]

export const TYPOGRAPHY_BLOCK_LABELS: Record<TypographyBlockKind, string> = {
  body: '正文',
  heading: '标题',
  code: '代码',
  table: '表格',
  quote: '引用',
  list: '列表',
}

/**
 * 单个块的排版覆盖项。
 * 所有字段可选：未设置 = 继承全局 / 上游默认。
 */
export interface TypographyBlockOverride {
  /** 拉丁（英数）字体族名，例如 "Inter"。 */
  latinFont?: string
  /** 中文（CJK）字体族名，例如 "霞鹜文楷"。 */
  cjkFont?: string
  /** 字号（px）。0 或未设 = 继承。 */
  fontSize?: number
  /** 行高倍数。0 或未设 = 继承。 */
  lineHeight?: number
  /** 字重（100-900）。0 或未设 = 继承。 */
  fontWeight?: number
  /** 是否启用斜体。 */
  italic?: boolean
}

export interface TypographySettings {
  /** 是否启用高级排版（关闭时完全等同上游默认）。 */
  enabled: boolean
  /** 全局拉丁字体。 */
  globalLatinFont: string
  /** 全局中文（CJK）字体。 */
  globalCjkFont: string
  /** 各块覆盖项。 */
  blocks: Partial<Record<TypographyBlockKind, TypographyBlockOverride>>
}

// ---------------------------------------------------------------------------
// 内置字体清单
// ---------------------------------------------------------------------------

export interface FontChoice {
  /** 存进设置的标识（即 CSS font-family 首选项名）。 */
  id: string
  /** 界面显示名。 */
  label: string
  /** 实际 CSS font-family 值（可能包含 fallback）。 */
  stack: string
  /** 归类，用于选择器分组。 */
  group: 'latin' | 'cjk' | 'mono' | 'system' | 'imported'
}

/**
 * 字体候选表。
 *
 * 说明：这些都是「字体族名」，最终能否渲染取决于设备是否安装了该字体。
 * 未安装时 CSS 会沿 font-family 链自动回落，不会报错、不会乱码。
 * 项目自带字体放在 public/fonts/ 并通过 @font-face 注入时优先级更高，
 * 但为保持首版轻量，这里先走「系统字体族名 + 链式兜底」策略。
 */
export const FONT_CHOICES: readonly FontChoice[] = [
  // --- 拉丁 ---
  { id: 'Inter', label: 'Inter', stack: 'Inter', group: 'latin' },
  { id: 'Open Sans', label: 'Open Sans', stack: '"Open Sans"', group: 'latin' },
  { id: 'Roboto', label: 'Roboto', stack: 'Roboto', group: 'latin' },
  { id: 'Lora', label: 'Lora（衬线）', stack: 'Lora', group: 'latin' },
  { id: 'Georgia', label: 'Georgia（衬线）', stack: 'Georgia', group: 'latin' },
  { id: 'Times New Roman', label: 'Times New Roman', stack: '"Times New Roman"', group: 'latin' },

  // --- 中文 CJK ---
  { id: 'LXGW WenKai', label: '霞鹜文楷', stack: '"LXGW WenKai"', group: 'cjk' },
  { id: 'Source Han Sans SC', label: '思源黑体', stack: '"Source Han Sans SC"', group: 'cjk' },
  { id: 'Source Han Serif SC', label: '思源宋体', stack: '"Source Han Serif SC"', group: 'cjk' },
  { id: 'Noto Sans CJK SC', label: 'Noto Sans CJK', stack: '"Noto Sans CJK SC"', group: 'cjk' },
  { id: 'Noto Serif CJK SC', label: 'Noto Serif CJK', stack: '"Noto Serif CJK SC"', group: 'cjk' },
  { id: 'HarmonyOS Sans SC', label: '鸿蒙黑体', stack: '"HarmonyOS Sans SC"', group: 'cjk' },
  { id: 'MiSans', label: 'MiSans（小米）', stack: 'MiSans', group: 'cjk' },
  { id: 'PingFang SC', label: '苹方', stack: '"PingFang SC"', group: 'cjk' },

  // --- 等宽 ---
  { id: 'JetBrains Mono', label: 'JetBrains Mono', stack: '"JetBrains Mono"', group: 'mono' },
  { id: 'Fira Code', label: 'Fira Code', stack: '"Fira Code"', group: 'mono' },
  { id: 'Source Code Pro', label: 'Source Code Pro', stack: '"Source Code Pro"', group: 'mono' },
  { id: 'Consolas', label: 'Consolas', stack: 'Consolas', group: 'mono' },
  { id: 'DejaVu Sans Mono', label: 'DejaVu Sans Mono', stack: '"DejaVu Sans Mono"', group: 'mono' },

  // --- 系统 ---
  { id: 'system-ui', label: '系统默认', stack: 'system-ui, -apple-system, sans-serif', group: 'system' },
] as const

const FONT_CHOICE_BY_ID = new Map(FONT_CHOICES.map((f) => [f.id, f]))
/**
 * 内置字体 + 当前已导入字体的合并清单（给设置页的选择器用）。
 *
 * 导入字体用 importedFontRegistry 的族名（Perspicuity 前缀）作为 id，
 * 与 resolveFontStack 的查找键保持完全一致。
 * 每次调用重新计算，不缓存：导入/删除字体后选择器要立刻跟着变。
 */
export function getFontChoices(): FontChoice[] {
  const imported: FontChoice[] = getImportedFonts().map(font => {
    const family = toFontFamilyName(font)
    return {
      id: family,
      label: font.displayName || family,
      stack: '"' + family + '"',
      group: 'imported' as const,
    }
  })
  return [...FONT_CHOICES, ...imported]
}

/** 通用兜底链：所有自定义字体之后都会接上这些，确保永不出现「字的方框」。 */
export const GENERIC_FALLBACK =
  'system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, "Noto Sans", sans-serif, "Apple Color Emoji", "Segoe UI Emoji"'

/** 等宽兜底链。 */
export const MONO_FALLBACK =
  '"JetBrains Mono", "Fira Code", "Source Code Pro", "DejaVu Sans Mono", Consolas, monospace'

// ---------------------------------------------------------------------------
// 默认值 / 归一化
// ---------------------------------------------------------------------------

export const DEFAULT_TYPOGRAPHY_SETTINGS: TypographySettings = {
  enabled: false,
  globalLatinFont: 'Inter',
  globalCjkFont: 'Noto Sans CJK SC',
  blocks: {},
}

/**
 * 设置页 row id ↔ 存储 key 的归属声明。
 *
 * 注意：开关行叫 `typographyEnabled`（不是 `typography.enabled`），
 * 各块参数用 `typography.<block>.<field>` 的形式，见下面 ROW 默认值表。
 */
export const TYPOGRAPHY_SETTING_KEYS = [
  'typographyEnabled',
  ...TYPOGRAPHY_BLOCK_KINDS.flatMap(kind => [
    `typography.${kind}.fontSize`,
    `typography.${kind}.lineHeight`,
    `typography.${kind}.latinFont`,
    `typography.${kind}.cjkFont`,
    `typography.${kind}.italic`,
  ]),
] as const

/** 各块在设置页上的默认值（与 settingsContent.ts 的 descriptor 必须一致）。 */
const TYPOGRAPHY_BLOCK_ROW_DEFAULTS = {
  body: { fontSize: 16, lineHeight: 1.6 },
  heading: { fontSize: 24, lineHeight: 1.3 },
  code: { fontSize: 14 },
  quote: { fontSize: 16, italic: true },
  list: { fontSize: 16, lineHeight: 1.6 },
  table: { fontSize: 15 },
} as const satisfies Readonly<
  Record<TypographyBlockKind, Partial<Record<'fontSize' | 'lineHeight' | 'italic', number | boolean>>>
>

/** 设置页 row id → 默认值（供治理测试与桥接读取共用）。 */
export const DEFAULT_TYPOGRAPHY_ROW_VALUES: Readonly<Record<string, SettingsValue>> =
  (() => {
    const out: Record<string, SettingsValue> = { typographyEnabled: false }
    for (const kind of TYPOGRAPHY_BLOCK_KINDS) {
      const block = TYPOGRAPHY_BLOCK_ROW_DEFAULTS[kind] as Partial<
        Record<'fontSize' | 'lineHeight' | 'italic', number | boolean>
      >
      out[`typography.${kind}.latinFont`] = ''
      out[`typography.${kind}.cjkFont`] = ''
      if (block.fontSize != null) out[`typography.${kind}.fontSize`] = block.fontSize
      if (block.lineHeight != null) out[`typography.${kind}.lineHeight`] = block.lineHeight
      if (block.italic != null) out[`typography.${kind}.italic`] = block.italic
    }
    return out
  })()

function normalizeNumber(value: unknown, fallback: number) {
  const n = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(n) ? n : fallback
}

function clamp(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), max)
}

/** 空字符串 / 未知名 → '' 表示「继承」，不报错。 */
export function normalizeFontId(value: unknown, fallback: string): string {
  if (typeof value !== 'string') return fallback
  const trimmed = value.trim()
  if (trimmed === '') return fallback
  // 允许任意自定义字体族名（用户可能手输），但限制长度与危险字符。
  //
  // ⚠️ 字符类里的 `-` 必须放在**最后**，否则会被当成范围符解析。
  // 早期写法 `[\w\s"'-,.()·...]` 中，`-,` 被解析成「从 ' (0x27) 到 , (0x2C)」，
  // 连字符 (0x2D) 落在范围之外 —— 结果**任何含 `-` 的族名都被拒绝**。
  // 而导入字体的族名是「Perspicuity· + UUID + '-' + 名字」，必然含 `-`，
  // 于是用户选中的导入字体存进设置后立刻被这里静默换成默认字体，
  // 表现为「选了字体但编辑器里不生效」。把 `-` 挪到末尾即可修复。
  return /^[\w\s"'(),.\u3000-\u303f\u4e00-\u9fff\uff00-\uffef·-]{1,96}$/.test(trimmed)
    ? trimmed
    : fallback
}

export function normalizeTypographySettings(value: unknown): TypographySettings {
  const raw = (value ?? {}) as Partial<TypographySettings>
  const blocks: Partial<Record<TypographyBlockKind, TypographyBlockOverride>> = {}

  if (raw.blocks && typeof raw.blocks === 'object') {
    for (const kind of TYPOGRAPHY_BLOCK_KINDS) {
      const src = (raw.blocks as Record<string, unknown>)[kind]
      if (!src || typeof src !== 'object') continue
      const b = src as Record<string, unknown>
      const out: TypographyBlockOverride = {}
      if (typeof b.latinFont === 'string' && b.latinFont.trim()) {
        out.latinFont = normalizeFontId(b.latinFont, '')
      }
      if (typeof b.cjkFont === 'string' && b.cjkFont.trim()) {
        out.cjkFont = normalizeFontId(b.cjkFont, '')
      }
      if (b.fontSize != null && Number(b.fontSize) > 0) {
        out.fontSize = Math.round(clamp(normalizeNumber(b.fontSize, 16), 8, 96))
      }
      if (b.lineHeight != null && Number(b.lineHeight) > 0) {
        out.lineHeight = Number(clamp(normalizeNumber(b.lineHeight, 1.6), 1, 3).toFixed(2))
      }
      if (b.fontWeight != null && Number(b.fontWeight) > 0) {
        out.fontWeight = Math.round(clamp(normalizeNumber(b.fontWeight, 400), 100, 900) / 100) * 100
      }
      if (typeof b.italic === 'boolean') out.italic = b.italic
      if (Object.keys(out).length) blocks[kind] = out
    }
  }

  return {
    enabled: raw.enabled === true,
    globalLatinFont: normalizeFontId(raw.globalLatinFont, DEFAULT_TYPOGRAPHY_SETTINGS.globalLatinFont),
    globalCjkFont: normalizeFontId(raw.globalCjkFont, DEFAULT_TYPOGRAPHY_SETTINGS.globalCjkFont),
    blocks,
  }
}

// ---------------------------------------------------------------------------
// 生成 CSS
// ---------------------------------------------------------------------------

/**
 * 把字体标识解析成完整 CSS font-family 链。
 *
 * 这是「中英分字」的核心：
 *   latin 在前 → 英文/数字/符号优先命中
 *   cjk   在后 → 汉字命中中文体
 *   再接通用兜底 → 永不出现缺字方框
 */
export function resolveFontStack(fontId: string, fallbackGeneric = GENERIC_FALLBACK): string {
  const known = FONT_CHOICE_BY_ID.get(fontId)
  // 内置字体用自己声明的 stack；导入字体与用户手输的族名走引号包裹。
  // 导入字体的 @font-face 由 importedFontRegistry 注入，这里只需正确引用族名。
  const head = known ? known.stack : (resolveFontFamilyFragment(fontId) ?? `"${fontId}"`)
  return `${head}, ${fallbackGeneric}`
}

/**
 * 取「字体首选项」本身（不含兜底链），用于拼装多段式字体链。
 *
 * 内置字体用自己声明的 stack（可能是 `"Open Sans"` 这种已经带引号的），
 * 导入字体与用户手输族名走 resolveFontFamilyFragment 加引号。
 */
function resolveFontHead(fontId: string): string {
  const known = FONT_CHOICE_BY_ID.get(fontId)
  return known ? known.stack : (resolveFontFamilyFragment(fontId) ?? `"${fontId}"`)
}

/**
 * 解析一个块的最终 font-family。
 *
 * ⚠️ 这里刻意**不给每段单独接兜底链**，而是「拉丁首选项 → CJK 首选项 → 通用兜底」。
 * 早期实现是 `resolveFontStack(latin) + ', ' + resolveFontStack(cjk)`，展开后变成：
 *
 *   "Inter", system-ui, ..., sans-serif, "Noto Sans CJK SC", system-ui, ...
 *
 * 其中 `sans-serif` 排在 CJK 字体**前面**，而通用兜底必然命中系统默认字体，
 * 于是所有汉字都被系统字体吃掉，用户选的中文字体永远轮不到。
 * 这正是「选了字体但编辑器不生效」的根因。
 *
 * 正确做法：两段首选项先排完，兜底链只在最末尾出现一次。
 * 另外：导入字体是用户自备的（通常含全字库），当它被选为拉丁或 CJK 时，
 * 直接把它放在对应位置即可，同样的族名出现两次不产生额外开销。
 */
export function resolveBlockFont(
  block: TypographyBlockOverride,
  global: TypographySettings,
  isMono = false,
): string {
  const latin = block.latinFont || global.globalLatinFont
  const cjk = block.cjkFont || global.globalCjkFont
  const generic = isMono ? MONO_FALLBACK : GENERIC_FALLBACK
  const heads = [resolveFontHead(latin)]
  // 同一族名重复出现没有意义（CSS 会自己去重，但把结果弄脏），跳过。
  if (cjk && cjk !== latin) {
    heads.push(resolveFontHead(cjk))
  }
  // 拉丁 → 中文 → 通用兜底，兜底只在最末尾出现一次。
  return `${heads.join(', ')}, ${generic}`
}

/** Muya 语义标签 → 各块的 CSS 选择器。 */
const BLOCK_SELECTORS: Record<TypographyBlockKind, string[]> = {
  body: [
    '.mu-editor .mu-paragraph',
    '.mu-editor .mu-paragraph-content',
    '.mu-editor p',
  ],
  heading: [
    '.mu-editor h1', '.mu-editor h2', '.mu-editor h3',
    '.mu-editor h4', '.mu-editor h5', '.mu-editor h6',
    '.mu-editor .mu-heading',
  ],
  code: [
    '.mu-editor pre', '.mu-editor pre code', '.mu-editor .mu-code-block',
    '.mu-editor .mu-inline-code', '.mu-editor code',
  ],
  table: [
    '.mu-editor table', '.mu-editor thead', '.mu-editor tbody',
    '.mu-editor tr', '.mu-editor th', '.mu-editor td',
    '.mu-editor .mu-table-cell',
  ],
  quote: [
    '.mu-editor blockquote', '.mu-editor .mu-block-quote',
  ],
  list: [
    '.mu-editor li', '.mu-editor .mu-list-item',
  ],
}

/** 从设置生成一段 CSS 文本。enabled=false 时返回空串。 */
export function buildTypographyCss(settings: TypographySettings): string {
  if (!settings.enabled) return ''

  const rules: string[] = []

  for (const kind of TYPOGRAPHY_BLOCK_KINDS) {
    const override = settings.blocks[kind]
    if (!override) continue

    const isMono = kind === 'code'
    // font-family 加 !important：
    // Muya 在 .mu-editor 根节点用内联 style 设了 --mu-font-family，
    // 而该变量会被块元素**继承**。虽然子元素显式声明通常能覆盖继承值，
    // 但某些块（如列表 marker、表格内联文本）由浏览器默认样式接管，
    // 优先级不稳定。用 !important 让分块字体永远是最终裁决者。
    const decls: string[] = [`font-family: ${resolveBlockFont(override, settings, isMono)} !important;`]

    if (override.fontSize) decls.push(`font-size: ${override.fontSize}px;`)
    if (override.lineHeight) decls.push(`line-height: ${override.lineHeight};`)
    if (override.fontWeight) decls.push(`font-weight: ${override.fontWeight};`)
    if (override.italic) decls.push('font-style: italic;')

    const selector = BLOCK_SELECTORS[kind].join(',\n')
    rules.push(`${selector} {\n  ${decls.join('\n  ')}\n}`)
  }

  return rules.length ? `/* Perspicuity typography (auto-generated) */\n${rules.join('\n\n')}\n` : ''
}

/** 从 settings store 读取。
 *
 * 设置页写的是分散 key（typographyEnabled / typography.body.fontSize / ...），
 * 这里把它们汇总成 TypographySettings；旧版聚合 key `typography` 仍作兜底。
 */
export function getTypographySettings(
  getValue: <T extends SettingsValue>(key: string, defaultValue: T) => T,
): TypographySettings {
  const legacy = normalizeTypographySettings(
    getValue('typography', DEFAULT_TYPOGRAPHY_SETTINGS as unknown as SettingsValue),
  )

  if (getValue('typographyEnabled', '__none__') === '__none__') {
    return legacy
  }

  const blocks: Partial<Record<TypographyBlockKind, TypographyBlockOverride>> = {}
  for (const kind of TYPOGRAPHY_BLOCK_KINDS) {
    const block: TypographyBlockOverride = {}
    const prefix = `typography.${kind}.`

    const fontSize = getValue(`${prefix}fontSize`, 0)
    if (typeof fontSize === 'number' && fontSize > 0) block.fontSize = fontSize

    const lineHeight = getValue(`${prefix}lineHeight`, 0)
    if (typeof lineHeight === 'number' && lineHeight > 0) block.lineHeight = lineHeight

    const latinFont = getValue(`${prefix}latinFont`, '')
    if (typeof latinFont === 'string' && latinFont.trim()) block.latinFont = latinFont.trim()

    const cjkFont = getValue(`${prefix}cjkFont`, '')
    if (typeof cjkFont === 'string' && cjkFont.trim()) block.cjkFont = cjkFont.trim()

    const italic = getValue(`${prefix}italic`, '__none__')
    if (typeof italic === 'boolean') block.italic = italic

    if (Object.keys(block).length) blocks[kind] = block
  }

  return normalizeTypographySettings({
    enabled: getValue('typographyEnabled', false),
    globalLatinFont: legacy.globalLatinFont,
    globalCjkFont: legacy.globalCjkFont,
    blocks,
  } as unknown as Partial<TypographySettings>)
}
