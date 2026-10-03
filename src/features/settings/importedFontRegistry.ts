import { toLoadableFontUrl, type ImportedFontFile } from '../../lib/fontImport'

/**
 * 已导入字体的前端注册表。
 *
 * 职责：
 *   1. 缓存「上次从原生列出来的字体清单」，让设置页不用每次进页都打一次桥；
 *   2. 把清单生成成 @font-face CSS，供排版系统用字体族名引用；
 *   3. 给出「族名 -> 完整 font-family 链」的解析入口。
 *
 * 存在哪里：只在内存，不写 localStorage。
 * 理由：真相源是设备上的 files/fonts/ 目录，原生 listImportedFonts() 随时能重建。
 * 再存一份 localStorage 只会多出一个「两边不一致」的 bug 面（卸载重装后
 * 缓存里还留着已经不存在的字体）。启动时打一次桥，成本极低。
 */

const FONT_FACE_STYLE_ID = 'perspicuity-imported-fonts'

/** 导入字体的族名统一加前缀，避免和系统同名字体撞车。 */
export const IMPORTED_FONT_FAMILY_PREFIX = 'Perspicuity·'

let registry: ImportedFontFile[] = []

/** 把落盘文件名转成 @font-face 用的族名。 */
export function toFontFamilyName(font: ImportedFontFile) {
  return `${IMPORTED_FONT_FAMILY_PREFIX}${font.familyName}`
}

export function getImportedFonts() {
  return registry
}

export function setImportedFonts(fonts: readonly ImportedFontFile[]) {
  registry = [...fonts]
  return registry
}

export function findImportedFontByFamilyName(familyName: string) {
  return registry.find(font => toFontFamilyName(font) === familyName) ?? null
}

/** 判断某字体族名是否来自导入字体（用于生成 @font-face 与选择器分组）。 */
export function isImportedFontFamily(familyName: string) {
  return familyName.startsWith(IMPORTED_FONT_FAMILY_PREFIX)
}

/**
 * 生成 @font-face 规则。
 *
 * format() 提示值按扩展名给出；给错不会导致加载失败（浏览器只是失去预筛优化），
 * 但给对能让 WebView 跳过一些探测，加载更快。
 * woff2 放最前是因为体积最小。
 */
export function buildImportedFontFaceCss(fonts: readonly ImportedFontFile[] = registry) {
  if (fonts.length === 0) {
    return ''
  }

  const rules: string[] = []
  for (const font of fonts) {
    const family = toFontFamilyName(font)
    // fileUri 已经是原生给的 file:// 绝对路径，不能再前缀一次 file://。
    const url = font.fileUri ? toLoadableFontUrl(font.fileUri) : ''
    if (!url) {
      continue
    }
    const format = fontFormatHint(font.fileName)
    rules.push(
      `@font-face {\n` +
        `  font-family: "${escapeCssString(family)}";\n` +
        `  src: url("${escapeCssString(url)}")${format};\n` +
        `  font-display: swap;\n` +
        `  font-style: normal;\n` +
        `  font-weight: 100 900;\n` +
        `}`,
    )
  }

  return `/* Perspicuity imported fonts (device-local, not shipped) */\n${rules.join('\n\n')}\n`
}

/**
 * 把 @font-face 写入独立的 <style> 标签。
 *
 * 单独一个标签（与 perspicuity-typography 分开）是为了让「改排版设置」和
 * 「改字体清单」互不影响：重排一次字体清单不需要重建整段排版 CSS。
 */
export function applyImportedFontFaces(fonts: readonly ImportedFontFile[] = registry) {
  if (typeof document === 'undefined') {
    return
  }
  const css = buildImportedFontFaceCss(fonts)
  let el = document.getElementById(FONT_FACE_STYLE_ID) as HTMLStyleElement | null
  if (!css) {
    // 没有导入字体时移除标签，避免残留失效的 @font-face。
    if (el) {
      el.remove()
    }
    return
  }
  if (!el) {
    el = document.createElement('style')
    el.id = FONT_FACE_STYLE_ID
    document.head.appendChild(el)
  }
  if (el.textContent !== css) {
    el.textContent = css
  }
}

/**
 * 把族名解析成可塞进 font-family 的片段。
 *
 * 系统字体族名（Inter、霞鹜文楷 等）保持原样加引号；导入字体则直接引用其
 * 加前缀后的族名，因为 @font-face 已经把真实文件绑上去了。
 */
export function resolveFontFamilyFragment(fontId: string) {
  if (!fontId) {
    return null
  }
  // 系统字体族名（Inter、霞鹜文楷 等）与导入字体族名（Perspicuity*）
  // 在 CSS 里的表达完全一致：都是加引号的族名，真实文件由 @font-face 绑定。
  return `"${escapeCssString(fontId)}"`
}

function fontFormatHint(fileName: string) {
  const lower = fileName.toLowerCase()
  if (lower.endsWith('.woff2')) return ' format("woff2")'
  if (lower.endsWith('.woff')) return ' format("woff")'
  if (lower.endsWith('.otf')) return ' format("opentype")'
  if (lower.endsWith('.ttf')) return ' format("truetype")'
  // ttc / otc 是集合文件，CSS 没有对应的 format 关键字，留空最安全。
  return ''
}

function escapeCssString(value: string) {
  return value.replace(/["\\\n\r]/g, ch => (ch === '\n' || ch === '\r' ? ' ' : `\\${ch}`))
}
