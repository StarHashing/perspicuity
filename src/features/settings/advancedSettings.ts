import type { SettingsValue } from './settingsState'
import type { LineEnding } from '../../lib/documentState'

export type MarkdownEncoding =
  | 'ascii'
  | 'utf8'
  | 'utf16be'
  | 'utf16le'
  | 'utf32be'
  | 'utf32le'
  | 'latin3'
  | 'iso885915'
  | 'cp1252'
  | 'arabic'
  | 'cp1256'
  | 'latin4'
  | 'cp1257'
  | 'iso88592'
  | 'windows1250'
  | 'cp866'
  | 'iso88595'
  | 'koi8r'
  | 'koi8u'
  | 'cp1251'
  | 'iso885913'
  | 'greek'
  | 'cp1253'
  | 'hebrew'
  | 'cp1255'
  | 'latin5'
  | 'cp1254'
  | 'gb2312'
  | 'gb18030'
  | 'gbk'
  | 'big5'
  | 'big5hkscs'
  | 'shiftjis'
  | 'eucjp'
  | 'euckr'
  | 'latin6'

export type AdvancedLineEnding = 'default' | LineEnding
export type TrailingNewlineMode = 0 | 1 | 2
export type AdvancedMaintenanceActionId =
  | 'exportLogs'
  | 'clearLogs'
  | 'cleanImportedImages'
  | 'clearDrafts'
  | 'resetSettings'

export interface AdvancedMaintenanceActionResult {
  message?: string
}

export type AdvancedMaintenanceActionHandler = (
  action: AdvancedMaintenanceActionId,
) => Promise<AdvancedMaintenanceActionResult | void> | AdvancedMaintenanceActionResult | void

export type AdvancedSettingKey =
  | 'defaultEncoding'
  | 'autoGuessEncoding'
  | 'endOfLine'
  | 'trimTrailingNewline'
  | 'collapseSelectionMenuRect'
  | 'selectionInputDiagnostics'
  | 'allFilesAccess'

export interface AdvancedSettings {
  defaultEncoding: MarkdownEncoding
  autoGuessEncoding: boolean
  endOfLine: AdvancedLineEnding
  trimTrailingNewline: TrailingNewlineMode
  /**
   * Perspicuity: 把系统「浮动选区菜单」的定位矩形塌缩到屏幕外。
   *
   * 背景：Android 14 上 WebView 选区的系统 FloatingActionMode 弹出的同色矩形
   * 无法用反射/View 树清除（popup 挂在独立 WindowManager 窗口里）。绕行方案是
   * 在 {@code ActionMode.Callback2.onGetContentRect} 回一个屏幕外的零尺寸矩形，
   * 让系统算不出 popup 位置，整个菜单窗口因此不生成。
   *
   * 代价：系统自带的复制/粘贴/全选等浮动菜单不会出现（App 有自绘选区工具栏，
   * 功能不受影响）。开启时为默认行为；关闭后恢复系统菜单。
   */
  collapseSelectionMenuRect: boolean
  selectionInputDiagnostics: boolean
  /**
   * 「所有文件访问权限」（MANAGE_EXTERNAL_STORAGE）开关意向。
   *
   * 注意：这是一个**展示态的镜像**——真正的权限状态以系统为准（原生
   * `AllFilesAccessPlugin.isGranted`）。持久化这个布尔只是为了记住用户
   * 「是否曾主动点开这个高级开关」，从而决定设置页要不要展开说明/根目录列表。
   */
  allFilesAccess: boolean
}

export interface AndroidMarkdownSettings {
  defaultEncoding: MarkdownEncoding
  autoDetectEncoding: boolean
}

export interface MarkdownSaveSettings {
  encoding: MarkdownEncoding
  lineEnding: AdvancedLineEnding
  trimTrailingNewline: TrailingNewlineMode
}

export const ADVANCED_SETTING_KEYS = [
  'defaultEncoding',
  'autoGuessEncoding',
  'endOfLine',
  'trimTrailingNewline',
  'collapseSelectionMenuRect',
  'selectionInputDiagnostics',
  'allFilesAccess',
] as const satisfies readonly AdvancedSettingKey[]

export const ADVANCED_MAINTENANCE_ACTION_IDS = [
  'exportLogs',
  'clearLogs',
  'cleanImportedImages',
  'clearDrafts',
  'resetSettings',
] as const satisfies readonly AdvancedMaintenanceActionId[]

export function isAdvancedMaintenanceActionId(value: string): value is AdvancedMaintenanceActionId {
  return ADVANCED_MAINTENANCE_ACTION_IDS.includes(value as AdvancedMaintenanceActionId)
}

export const DEFAULT_ADVANCED_SETTINGS = {
  defaultEncoding: 'utf8',
  autoGuessEncoding: true,
  endOfLine: 'default',
  trimTrailingNewline: 2,
  collapseSelectionMenuRect: true,
  selectionInputDiagnostics: false,
  allFilesAccess: false,
} as const satisfies AdvancedSettings

export const DEFAULT_MARKDOWN_SAVE_SETTINGS = {
  encoding: DEFAULT_ADVANCED_SETTINGS.defaultEncoding,
  lineEnding: DEFAULT_ADVANCED_SETTINGS.endOfLine,
  trimTrailingNewline: DEFAULT_ADVANCED_SETTINGS.trimTrailingNewline,
} as const satisfies MarkdownSaveSettings

const MARKDOWN_ENCODINGS = new Set<MarkdownEncoding>([
  'ascii',
  'utf8',
  'utf16be',
  'utf16le',
  'utf32be',
  'utf32le',
  'latin3',
  'iso885915',
  'cp1252',
  'arabic',
  'cp1256',
  'latin4',
  'cp1257',
  'iso88592',
  'windows1250',
  'cp866',
  'iso88595',
  'koi8r',
  'koi8u',
  'cp1251',
  'iso885913',
  'greek',
  'cp1253',
  'hebrew',
  'cp1255',
  'latin5',
  'cp1254',
  'gb2312',
  'gb18030',
  'gbk',
  'big5',
  'big5hkscs',
  'shiftjis',
  'eucjp',
  'euckr',
  'latin6',
])
const ADVANCED_LINE_ENDINGS = new Set<AdvancedLineEnding>(['default', 'lf', 'crlf'])
const TRAILING_NEWLINE_MODES = new Set<TrailingNewlineMode>([0, 1, 2])

function normalizeBoolean(value: unknown, fallback: boolean) {
  return typeof value === 'boolean' ? value : fallback
}

function normalizeChoice<T extends string>(value: unknown, options: Set<T>, fallback: T): T {
  return options.has(value as T) ? (value as T) : fallback
}

function normalizeTrailingNewlineMode(value: unknown): TrailingNewlineMode {
  const numericValue = typeof value === 'number' ? value : Number(value)
  return TRAILING_NEWLINE_MODES.has(numericValue as TrailingNewlineMode)
    ? (numericValue as TrailingNewlineMode)
    : DEFAULT_ADVANCED_SETTINGS.trimTrailingNewline
}

export function normalizeMarkdownEncoding(value: unknown): MarkdownEncoding {
  return normalizeChoice(
    value,
    MARKDOWN_ENCODINGS,
    DEFAULT_ADVANCED_SETTINGS.defaultEncoding,
  )
}

export function normalizeAdvancedSettingValue(key: AdvancedSettingKey, value: SettingsValue) {
  switch (key) {
    case 'defaultEncoding':
      return normalizeMarkdownEncoding(value)
    case 'autoGuessEncoding':
      return normalizeBoolean(value, DEFAULT_ADVANCED_SETTINGS.autoGuessEncoding)
    case 'endOfLine':
      return normalizeChoice(value, ADVANCED_LINE_ENDINGS, DEFAULT_ADVANCED_SETTINGS.endOfLine)
    case 'trimTrailingNewline':
      return normalizeTrailingNewlineMode(value)
    case 'collapseSelectionMenuRect':
      return normalizeBoolean(value, DEFAULT_ADVANCED_SETTINGS.collapseSelectionMenuRect)
    case 'selectionInputDiagnostics':
      return normalizeBoolean(value, DEFAULT_ADVANCED_SETTINGS.selectionInputDiagnostics)
    case 'allFilesAccess':
      return normalizeBoolean(value, DEFAULT_ADVANCED_SETTINGS.allFilesAccess)
  }
}

export function getAdvancedSettings(
  getValue: <T extends SettingsValue>(key: string, defaultValue: T) => T,
): AdvancedSettings {
  return {
    defaultEncoding: normalizeMarkdownEncoding(
      getValue('defaultEncoding', DEFAULT_ADVANCED_SETTINGS.defaultEncoding),
    ),
    autoGuessEncoding: normalizeBoolean(
      getValue('autoGuessEncoding', DEFAULT_ADVANCED_SETTINGS.autoGuessEncoding),
      DEFAULT_ADVANCED_SETTINGS.autoGuessEncoding,
    ),
    endOfLine: normalizeChoice(
      getValue('endOfLine', DEFAULT_ADVANCED_SETTINGS.endOfLine),
      ADVANCED_LINE_ENDINGS,
      DEFAULT_ADVANCED_SETTINGS.endOfLine,
    ),
    trimTrailingNewline: normalizeTrailingNewlineMode(
      getValue('trimTrailingNewline', DEFAULT_ADVANCED_SETTINGS.trimTrailingNewline),
    ),
    collapseSelectionMenuRect: normalizeBoolean(
      getValue(
        'collapseSelectionMenuRect',
        DEFAULT_ADVANCED_SETTINGS.collapseSelectionMenuRect,
      ),
      DEFAULT_ADVANCED_SETTINGS.collapseSelectionMenuRect,
    ),
    selectionInputDiagnostics: normalizeBoolean(
      getValue(
        'selectionInputDiagnostics',
        DEFAULT_ADVANCED_SETTINGS.selectionInputDiagnostics,
      ),
      DEFAULT_ADVANCED_SETTINGS.selectionInputDiagnostics,
    ),
    allFilesAccess: normalizeBoolean(
      getValue('allFilesAccess', DEFAULT_ADVANCED_SETTINGS.allFilesAccess),
      DEFAULT_ADVANCED_SETTINGS.allFilesAccess,
    ),
  }
}

export function getAndroidMarkdownSettings(
  settings: Pick<AdvancedSettings, 'defaultEncoding' | 'autoGuessEncoding'>,
): AndroidMarkdownSettings {
  return {
    defaultEncoding: settings.defaultEncoding,
    autoDetectEncoding: settings.autoGuessEncoding,
  }
}

export function getMarkdownSaveSettings(
  settings: AdvancedSettings,
  documentEncoding?: string,
): MarkdownSaveSettings {
  const detectedEncoding = documentEncoding
    ? normalizeMarkdownEncoding(documentEncoding)
    : settings.defaultEncoding

  return {
    encoding: settings.autoGuessEncoding ? detectedEncoding : settings.defaultEncoding,
    lineEnding: settings.endOfLine,
    trimTrailingNewline: settings.trimTrailingNewline,
  }
}
