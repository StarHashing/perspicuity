import { Capacitor, registerPlugin } from '@capacitor/core'

/**
 * 全局便签浮窗桥。
 *
 * 对应原生 `FloatingWindowPlugin` + `FloatingNoteService`。把当前文档（markdown）
 * 「钉」到其他应用之上，边看教程 / 玩游戏边瞄一眼；也可在应用内当只读参考窗口。
 *
 * 与 `allFilesAccess.ts` 同一套风格：
 *   - `registerPlugin` 拿原生桥
 *   - 每个入口先 `ensureAvailable`，非 Android 构建安全降级
 *   - 原生返回的数据一律 normalize 一遍再交给上层
 *
 * 硬约束（原生层决定，前端无法绕过）：
 *   1. 悬浮窗权限必须用户手动在系统设置页打开（`Settings.ACTION_MANAGE_OVERLAY_PERMISSION`），
 *      应用侧无法用运行时弹框申请；
 *   2. ColorOS 等国产 ROM 可能还需额外开「后台弹出界面」；
 *   3. 点击穿透是整体开/关，做不到「只有某块区域穿透」；
 *   4. 前台服务有常驻通知（Android 8+ 强制，无法去掉）。
 */
export interface FloatingPermissionState {
  granted: boolean
  /** 当前系统是否支持悬浮窗（Android 6 / API 23 起）。 */
  supported?: boolean
}

export interface FloatingPermissionRequestResult {
  /** 是否成功拉起了系统设置页。 */
  opened: boolean
  /** 拉起后的即时状态（多半仍为 false，真正结果由用户返回后重新查询）。 */
  granted: boolean
  reason?: string
}

export interface FloatingShowResult {
  granted: boolean
  shown: boolean
  reason?: string
  message?: string
}

export interface FloatingCountResult {
  count: number
  running: boolean
  granted: boolean
}

export interface FloatingShowOptions {
  title?: string
  markdown: string
  /** 0.1 ~ 1.0，默认 0.9。 */
  opacity?: number
}

interface FloatingWindowPlugin {
  isGranted(): Promise<FloatingPermissionState>
  requestPermission(): Promise<FloatingPermissionRequestResult>
  showNote(options: FloatingShowOptions): Promise<FloatingShowResult>
  updateActive(options: { title?: string; markdown: string }): Promise<{
    updated: boolean
    count: number
  }>
  hideAll(): Promise<{ count: number }>
  stop(): Promise<{ stopped: boolean }>
  count(): Promise<FloatingCountResult>
}

const FloatingWindow = registerPlugin<FloatingWindowPlugin>('FloatingWindow')

export function isFloatingWindowAvailable() {
  return Capacitor.isNativePlatform()
}

/** 查询悬浮窗权限是否已授予。非原生环境恒为未授予。 */
export async function isFloatingWindowGranted(): Promise<FloatingPermissionState> {
  if (!isFloatingWindowAvailable()) {
    return { granted: false, supported: false }
  }
  try {
    const state = await FloatingWindow.isGranted()
    return { granted: Boolean(state.granted), supported: Boolean(state.supported) }
  } catch {
    return { granted: false, supported: false }
  }
}

/**
 * 跳系统「显示在其他应用上层」设置页，由用户手动开启。
 *
 * 系统不允许应用代码「申请」该权限——只能引导用户自己开。返回后请重新调用
 * {@link isFloatingWindowGranted} 校验结果（页面切回是异步的）。
 */
export async function requestFloatingWindowPermission(): Promise<FloatingPermissionRequestResult> {
  if (!isFloatingWindowAvailable()) {
    return { opened: false, granted: false, reason: 'NOT_NATIVE' }
  }
  try {
    return await FloatingWindow.requestPermission()
  } catch {
    return { opened: false, granted: false, reason: 'OPEN_FAILED' }
  }
}

/** 新开一个浮窗，渲染给定 markdown。 */
export async function showFloatingNote(
  options: FloatingShowOptions,
): Promise<FloatingShowResult> {
  if (!isFloatingWindowAvailable()) {
    return { granted: false, shown: false, reason: 'NOT_NATIVE' }
  }
  try {
    const result = await FloatingWindow.showNote({
      title: options.title ?? '',
      markdown: options.markdown ?? '',
      opacity: typeof options.opacity === 'number' ? options.opacity : 0.9,
    })
    return {
      granted: Boolean(result.granted),
      shown: Boolean(result.shown),
      reason: result.reason,
      message: result.message,
    }
  } catch {
    return { granted: true, shown: false, reason: 'SHOW_FAILED' }
  }
}

/** 把最后一个浮窗的内容替换为新的文档（「更新为当前文档」）。 */
export async function updateFloatingNote(options: {
  title?: string
  markdown: string
}): Promise<number> {
  if (!isFloatingWindowAvailable()) {
    return 0
  }
  try {
    const result = await FloatingWindow.updateActive({
      title: options.title ?? '',
      markdown: options.markdown ?? '',
    })
    return typeof result.count === 'number' ? result.count : 0
  } catch {
    return 0
  }
}

/** 收起全部浮窗（保留前台服务与常驻通知）。 */
export async function hideAllFloatingNotes(): Promise<number> {
  if (!isFloatingWindowAvailable()) {
    return 0
  }
  try {
    const result = await FloatingWindow.hideAll()
    return typeof result.count === 'number' ? result.count : 0
  } catch {
    return 0
  }
}

/** 收全部浮窗并停止前台服务。 */
export async function stopFloatingNotes(): Promise<void> {
  if (!isFloatingWindowAvailable()) {
    return
  }
  try {
    await FloatingWindow.stop()
  } catch {
    // 停止失败不影响调用方。
  }
}

/** 当前浮窗数量与服务状态。 */
export async function getFloatingNoteCount(): Promise<FloatingCountResult> {
  if (!isFloatingWindowAvailable()) {
    return { count: 0, running: false, granted: false }
  }
  try {
    const result = await FloatingWindow.count()
    return {
      count: typeof result.count === 'number' ? result.count : 0,
      running: Boolean(result.running),
      granted: Boolean(result.granted),
    }
  } catch {
    return { count: 0, running: false, granted: false }
  }
}