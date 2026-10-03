import { Capacitor, registerPlugin } from '@capacitor/core'

/**
 * 字体导入插件的前端包装。
 *
 * 与 androidDocuments.ts 保持同一套风格：
 *   - registerPlugin 拿原生桥
 *   - 每个入口都先 ensureAvailable，非 Android 构建直接抛 UNAVAILABLE
 *   - 原生回来的数据一律 normalize 一遍再交给上层，防止空字段流进 UI
 */

export interface ImportedFontFile {
  /** 落盘文件名，@font-face 的 src 用它 */
  fileName: string
  /** 用户看到的原始文件名（如「汉仪润圆.ttf」） */
  displayName: string
  /** @font-face 里使用的字体族名（界面显示 + CSS 引用都用它） */
  familyName: string
  /** 落盘文件的 file:// URI，@font-face 的 src 用它 */
  fileUri: string
  /** 文件字节数 */
  bytes: number
  /** 导入时间戳（毫秒） */
  importedAt: number
}

export interface FontImportResult {
  /** 本次新导入的字体 */
  imported: ImportedFontFile[]
  /** 因重复（同名同大小）被跳过的文件名 */
  skipped: string[]
  /** 因超限/无法读取被跳过的文件名 */
  rejected: string[]
  /** 导入后 font 目录里的字体总数 */
  totalCount: number
  /** 导入后 font 目录占用的字节数 */
  totalBytes: number
  /** 目录导入时的来源目录名，单文件导入为 null */
  sourceFolderName: string | null
}

interface CanceledFontImport {
  canceled: true
}

interface FontImportPlugin {
  pickFontFiles(): Promise<FontImportResult | CanceledFontImport>
  pickFontDirectory(): Promise<FontImportResult | CanceledFontImport>
  listImportedFonts(): Promise<{ fonts: ImportedFontFile[] }>
  deleteImportedFont(options: { fileName: string }): Promise<{
    deleted: boolean
    totalCount: number
    totalBytes: number
  }>
  renameImportedFont(options: {
    fileName: string
    displayName: string
  }): Promise<{ fileName: string; displayName: string }>
}

export class FontImportError extends Error {
  code: string

  constructor(code: string, message: string) {
    super(message)
    this.name = 'FontImportError'
    this.code = code
  }
}

const FontImport = registerPlugin<FontImportPlugin>('FontImport')

export function isFontImportAvailable() {
  return Capacitor.getPlatform() === 'android' && Capacitor.isNativePlatform()
}

function ensureFontImportAvailable() {
  if (!isFontImportAvailable()) {
    throw new FontImportError('UNAVAILABLE', 'Font import is only available in Android builds')
  }
}

export async function pickFontFiles() {
  ensureFontImportAvailable()
  return normalizeImportResult(await FontImport.pickFontFiles())
}

export async function pickFontDirectory() {
  ensureFontImportAvailable()
  return normalizeImportResult(await FontImport.pickFontDirectory())
}

export async function listImportedFonts() {
  if (!isFontImportAvailable()) {
    return []
  }
  const result = await FontImport.listImportedFonts()
  return Array.isArray(result?.fonts) ? result.fonts.map(normalizeFontFile).filter(isPresent) : []
}

export async function deleteImportedFont(fileName: string) {
  ensureFontImportAvailable()
  return FontImport.deleteImportedFont({ fileName })
}

/**
 * 重命名一个已导入字体的显示名。
 *
 * 只改「显示名」，不动物理文件：@font-face 的 src 与族名都绑在落盘文件名上，
 * 改物理名会让用户选中过的字体全部失效。原生侧同样只更新 font-manifest.json。
 */
export async function renameImportedFont(fileName: string, displayName: string) {
  ensureFontImportAvailable()
  return FontImport.renameImportedFont({ fileName, displayName })
}

/** 把原生回来的 fileUri 转成 WebView 能加载的 URL（用于 @font-face 的 src）。 */
export function toLoadableFontUrl(fileUri: string) {
  return Capacitor.convertFileSrc(fileUri)
}

export function getFontImportUserMessage(error: unknown) {
  const code = error instanceof FontImportError
    ? error.code
    : error && typeof error === 'object' && 'code' in error && typeof (error as { code?: unknown }).code === 'string'
      ? (error as { code: string }).code
      : 'UNKNOWN'

  if (code === 'UNAVAILABLE') return 'Import fonts from the Android app build.'
  if (code === 'NO_FONTS_FOUND') return 'No font files were found in that folder.'
  if (code === 'FONT_PERMISSION_LOST') return 'Choose this folder again from Android.'
  if (code === 'FONT_PICKER_UNAVAILABLE') return 'No Android file picker is available.'
  if (code === 'FOLDER_PICKER_UNAVAILABLE') return 'No Android folder picker is available.'
  if (code === 'FONT_IMPORT_FAILED') return 'Could not import the selected fonts.'
  if (code === 'FONT_DELETE_FAILED') return 'Could not remove this font.'
  if (code === 'FONT_NOT_FOUND') return 'This font is no longer imported.'
  if (code === 'INVALID_FONT_NAME') return 'That name could not be used.'
  return 'Could not import fonts.'
}

function normalizeImportResult(value: FontImportResult | CanceledFontImport | null | undefined) {
  if (!value || (typeof value === 'object' && 'canceled' in value && value.canceled)) {
    return { canceled: true } as const
  }
  // 原生侧字段名：imported / skipped / rejected / totalCount / totalBytes / sourceFolderName。
  // 这里刻意做一层别名兼容（fonts / folderName），因为早期原生实现用的是后者，
  // 一旦两边字段名再次漂移，界面就只会显示「未选择任何字体」而不报错 —— 那种
  // 静默失败比崩溃更难查，所以读取时宁可多认几个名字。
  const raw = value as FontImportResult & {
    fonts?: unknown
    folderName?: unknown
  }
  const importedSource = Array.isArray(raw.imported)
    ? raw.imported
    : Array.isArray(raw.fonts)
      ? raw.fonts
      : []
  return {
    imported: importedSource.map(normalizeFontFile).filter(isPresent),
    skipped: toLengthArray(raw.skipped),
    rejected: toLengthArray(raw.rejected),
    totalCount: toCount(raw.totalCount),
    totalBytes: toCount(raw.totalBytes),
    sourceFolderName: pickFolderName(raw.sourceFolderName ?? raw.folderName),
  } satisfies FontImportResult
}

/**
 * 把原生条目归一化。
 *
 * 原生字段（FontImportPlugin#buildFontEntry）：
 *   fileName / displayName / familyName / fileUri / bytes / importedAt / identity
 * `fileUri` 是落盘文件的 file:// URI，也是 @font-face 唯一可用的来源 ——
 * 原始 picker 的 content:// URI 在导入结束后就失效了，绝不能用它做加载源。
 * 这里对缺失字段做兜底，让上游不必到处判空。
 */
function normalizeFontFile(value: Partial<ImportedFontFile> | null | undefined): ImportedFontFile | null {
  if (!value || !value.fileName) {
    return null
  }
  const familyName = typeof value.familyName === 'string' && value.familyName
    ? value.familyName
    : value.fileName.replace(/\.[^.]+$/, '')
  return {
    fileName: value.fileName,
    displayName: typeof value.displayName === 'string' && value.displayName
      ? value.displayName
      : familyName,
    familyName,
    fileUri: typeof value.fileUri === 'string' ? value.fileUri : '',
    bytes: toCount(value.bytes),
    importedAt: toCount(value.importedAt),
  }
}

/**
 * 只取数组长度。
 *
 * 原生侧 skipped/rejected 里放的是对象（{sourceUri, reason}），而这里 UI 只用
 * 长度做统计，所以保留「有几条」这个信息即可，不做字符串化。
 * 兼容传入字符串数组的老格式。
 */
function toLengthArray(value: unknown): { length: number }[] & string[] {
  if (!Array.isArray(value)) {
    return [] as unknown as { length: number }[] & string[]
  }
  return value as unknown as { length: number }[] & string[]
}

function pickFolderName(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null
}

function toCount(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? Math.floor(value) : 0
}

function isPresent<T>(value: T | null): value is T {
  return value !== null
}
