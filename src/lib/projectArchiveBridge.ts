import { Capacitor, registerPlugin } from '@capacitor/core'
import type { PluginListenerHandle } from '@capacitor/core'
import type { ArchiveEntry } from '../features/home/projectArchive'

/**
 * 项目归档（zip）桥。
 *
 * 对应原生 `WorkspacePlugin` 的 `exportProjectArchive` / `importProjectArchive`。
 * 前端只传「条目列表」（路径 + 文本内容），二进制全在原生侧处理——这样中文
 * 文件名走 java.util.zip 的 UTF-8，可靠且不用把整包塞进 JS 内存。
 */
export interface ProjectArchiveWriteResult {
  ok: true
  /** 写好的 zip 的 SAF document URI。 */
  uri: string
  /** 实际写入的文件名（已强制 .zip 后缀）。 */
  name: string
  entryCount: number
}

export interface ProjectArchiveReadResult {
  ok: true
  entries: ArchiveEntry[]
  entryCount: number
}

interface ProjectArchivePlugin {
  exportProjectArchive(options: {
    treeUri: string
    fileName: string
    entries: ArchiveEntry[]
  }): Promise<ProjectArchiveWriteResult>
  importProjectArchive(options: { fileUri: string }): Promise<ProjectArchiveReadResult>
  addListener(
    eventName: 'projectArchiveOpened',
    listener: (event: ProjectArchiveOpenedEvent) => void,
  ): Promise<PluginListenerHandle>
  removeAllListeners(): Promise<void>
}

/** 外部应用（QQ / 微信 / 文件管理器）把 zip 「打开方式」递进来时的事件载荷。 */
export interface ProjectArchiveOpenedEvent {
  /** zip 的 content:// 或 file:// URI，只带一次性读授权，需立刻读取。 */
  uri: string
  /** 建议显示名（可能为空）。 */
  name: string
  /** 来源应用声明的 MIME，可能为空。 */
  mimeType: string
  persisted: boolean
}

const ProjectArchive = registerPlugin<ProjectArchivePlugin>('Workspace')

export function isProjectArchiveAvailable(): boolean {
  return Capacitor.isNativePlatform()
}

/** 把条目列表写成 zip，落在用户选中的目录（treeUri）里。 */
export async function exportProjectArchive(options: {
  treeUri: string
  fileName: string
  entries: ArchiveEntry[]
}): Promise<ProjectArchiveWriteResult> {
  return ProjectArchive.exportProjectArchive(options)
}

/** 读一个 zip 归档，拿回条目列表。 */
export async function importProjectArchive(fileUri: string): Promise<ProjectArchiveReadResult> {
  return ProjectArchive.importProjectArchive({ fileUri })
}

/**
 * 监听「外部应用把 zip 递进来」事件。
 *
 * 场景：用户在 QQ / 微信长按一个项目归档 zip → 打开方式 → 选本 App。
 * 收到事件后用 importProjectArchive(uri) 读取，再 parseProjectArchive 判格式。
 * 非原生平台直接返回空句柄，调用方无需特判。
 */
export async function onProjectArchiveOpened(
  listener: (event: ProjectArchiveOpenedEvent) => void,
): Promise<PluginListenerHandle> {
  if (!isProjectArchiveAvailable()) {
    return { remove: async () => undefined }
  }
  return ProjectArchive.addListener('projectArchiveOpened', listener)
}