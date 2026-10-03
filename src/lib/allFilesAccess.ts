import { Capacitor, registerPlugin } from '@capacitor/core'

/**
 * 「所有文件访问权限」（MANAGE_EXTERNAL_STORAGE）桥。
 *
 * 对应原生 `AllFilesAccessPlugin`。这是 SAF 目录授权之外的一条「高级」路径：
 * 授予后可直接读写共享存储任意路径，代价是敏感（Play 拒审、部分 ROM 受限），
 * 因此它是**默认关闭的可选开关**，不进默认流程。
 *
 * 特别注意：release 包在 Manifest 层已剥离该权限（见 src/release），所以正式
 * 上架版里 `isGranted` 永远为 false——组件层据 `supported` 字段决定是否显示开关。
 */
export interface AllFilesAccessState {
  granted: boolean
  /** 当前系统版本是否支持该权限（Android 11 / API 30 起）。 */
  supported?: boolean
}

export interface AllFilesAccessRequestResult {
  /** 是否成功拉起了系统设置页。 */
  opened: boolean
  /** 拉起后的即时状态（多半仍为 false，真正结果由用户返回后重新查询）。 */
  granted: boolean
  reason?: string
}

export interface AllFilesAccessRoot {
  path: string
  name: string
  kind: 'primary' | 'secondary'
  readable: boolean
  writable: boolean
}

interface AllFilesAccessPlugin {
  isGranted(): Promise<AllFilesAccessState>
  requestAccess(): Promise<AllFilesAccessRequestResult>
  listRoots(): Promise<{ roots: AllFilesAccessRoot[]; granted: boolean }>
}

const AllFilesAccess = registerPlugin<AllFilesAccessPlugin>('AllFilesAccess')

export function isAllFilesAccessAvailable() {
  return Capacitor.isNativePlatform()
}

/** 查询「所有文件访问」权限是否已授予。非原生环境恒为未授予。 */
export async function isAllFilesAccessGranted(): Promise<AllFilesAccessState> {
  if (!isAllFilesAccessAvailable()) {
    return { granted: false, supported: false }
  }
  try {
    const state = await AllFilesAccess.isGranted()
    return { granted: Boolean(state.granted), supported: Boolean(state.supported) }
  } catch {
    return { granted: false, supported: false }
  }
}

/**
 * 跳转系统「所有文件访问」设置页，由用户手动开启。
 *
 * 系统不允许应用代码「申请」该权限——只能引导用户自己开。返回后请重新调用
 * {@link isAllFilesAccessGranted} 校验结果（页面切回是异步的）。
 */
export async function requestAllFilesAccess(): Promise<AllFilesAccessRequestResult> {
  if (!isAllFilesAccessAvailable()) {
    return { opened: false, granted: false, reason: 'NOT_NATIVE' }
  }
  try {
    return await AllFilesAccess.requestAccess()
  } catch {
    return { opened: false, granted: false, reason: 'OPEN_FAILED' }
  }
}

/** 列出共享存储的物理根目录（仅在已授予权限时非空）。 */
export async function listAllFilesAccessRoots(): Promise<AllFilesAccessRoot[]> {
  if (!isAllFilesAccessAvailable()) {
    return []
  }
  try {
    const result = await AllFilesAccess.listRoots()
    return result.roots ?? []
  } catch {
    return []
  }
}
