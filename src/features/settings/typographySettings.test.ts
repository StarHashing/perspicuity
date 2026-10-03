import { describe, expect, it } from 'vitest'
import {
  buildTypographyCss,
  DEFAULT_TYPOGRAPHY_SETTINGS,
  GENERIC_FALLBACK,
  MONO_FALLBACK,
  normalizeTypographySettings,
  resolveBlockFont,
  type TypographyBlockOverride,
} from './typographySettings'

/**
 * 这组测试锁住一个真实踩过的坑：
 *
 * 早期 `resolveBlockFont` 是 `resolveFontStack(latin) + ', ' + resolveFontStack(cjk)`，
 * 每一段各自接上兜底链，展开后 `sans-serif` 排在 CJK 字体**前面**，
 * 导致汉字全部被系统默认字体吃掉 —— 用户「选了中文字体但编辑器里不生效」。
 *
 * 契约：拉丁首选项 → CJK 首选项 → 通用兜底，**兜底链只出现一次且在最后**。
 */

/** 用给定 body 块构造一份已启用高级排版的设置。 */
function settingsWithBody(block: TypographyBlockOverride) {
  return normalizeTypographySettings({
    ...DEFAULT_TYPOGRAPHY_SETTINGS,
    enabled: true,
    blocks: { body: block },
  })
}

describe('resolveBlockFont', () => {
  it('把通用兜底只放在最末尾一次（拉丁与 CJK 之后）', () => {
    const settings = settingsWithBody({ latinFont: 'Inter', cjkFont: 'LXGW WenKai' })
    const stack = resolveBlockFont(settings.blocks.body!, settings)

    // 拉丁在前，中文居中
    expect(stack.indexOf('Inter')).toBeLessThan(stack.indexOf('LXGW WenKai'))

    // 关键：兜底链里的 sans-serif 必须出现在 CJK 字体之后。
    const genericAt = stack.indexOf(GENERIC_FALLBACK)
    expect(genericAt).toBeGreaterThan(stack.indexOf('LXGW WenKai'))

    // 兜底链只出现一次。
    expect(stack.split(GENERIC_FALLBACK).length - 1).toBe(1)
  })

  it('同一族名同时用作拉丁与 CJK 时不重复输出', () => {
    const settings = settingsWithBody({ latinFont: 'Inter', cjkFont: 'Inter' })
    const stack = resolveBlockFont(settings.blocks.body!, settings)
    expect(stack.split('Inter').length - 1).toBe(1)
  })

  it('只设了拉丁字体时，CJK 段回落到全局中文字体且顺序正确', () => {
    const settings = normalizeTypographySettings({
      ...DEFAULT_TYPOGRAPHY_SETTINGS,
      enabled: true,
      blocks: { body: { latinFont: 'Inter' } },
    })
    const stack = resolveBlockFont(settings.blocks.body!, settings)
    // 全局中文字体默认是 Noto Sans CJK SC，应排在拉丁之后、兜底之前。
    expect(stack.indexOf('Inter')).toBeLessThan(stack.indexOf('Noto Sans CJK SC'))
    expect(stack.indexOf('Noto Sans CJK SC')).toBeLessThan(stack.indexOf(GENERIC_FALLBACK))
  })

  it('代码块用等宽兜底链', () => {
    const settings = normalizeTypographySettings({
      ...DEFAULT_TYPOGRAPHY_SETTINGS,
      enabled: true,
      blocks: { code: { latinFont: 'JetBrains Mono' } },
    })
    const stack = resolveBlockFont(settings.blocks.code!, settings, true)
    expect(stack).toContain(MONO_FALLBACK)
    expect(stack).not.toContain(GENERIC_FALLBACK)
  })

  it('导入字体（Perspicuity·前缀）加引号引用，不加兜底前缀', () => {
    const settings = normalizeTypographySettings({
      ...DEFAULT_TYPOGRAPHY_SETTINGS,
      enabled: true,
      blocks: { body: { latinFont: 'Perspicuity·abc12345-cute', cjkFont: '' } },
    })
    const stack = resolveBlockFont(settings.blocks.body!, settings)
    expect(stack).toContain('"Perspicuity·abc12345-cute"')
    expect(stack.endsWith(GENERIC_FALLBACK)).toBe(true)
  })
})

describe('buildTypographyCss', () => {
  it('关闭高级排版时输出空串', () => {
    expect(buildTypographyCss({ ...DEFAULT_TYPOGRAPHY_SETTINGS, enabled: false })).toBe('')
  })

  it('font-family 带 !important，压得住 Muya 根节点的继承值', () => {
    const settings = normalizeTypographySettings({
      ...DEFAULT_TYPOGRAPHY_SETTINGS,
      enabled: true,
      blocks: { body: { latinFont: 'Inter', fontSize: 16 } },
    })
    const css = buildTypographyCss(settings)
    expect(css).toContain('font-family:')
    expect(css).toContain('!important')
    // 选择器仍命中 Muya 的段落块
    expect(css).toContain('.mu-editor .mu-paragraph')
  })
})
