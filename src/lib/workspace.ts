import { Capacitor, registerPlugin } from '@capacitor/core'

/**
 * 工作区（绑定目录）桥。
 *
 * 对应原生 `WorkspacePlugin`。它让用户可以把手上的某个文件夹「绑定」成
 * 一个写作工作区：目录授权会被持久化，App 重启后依然能列文件、开文件。
 *
 * 与 FontImport 的差别：字体导入是「复制进来」，工作区是「就地读写」——
 * 文档始终留在用户自己的存储上，App 不产生副本。
 */
export interface WorkspaceDirectory {
  treeUri: string
  displayName: string
  canWrite: boolean
}

export interface WorkspaceDocumentEntry {
  /** SAF document URI，可直接交给原生打开。 */
  uri: string
  name: string
  /** 相对绑定目录根的路径，例如 `GDD/世界观/魔法.md`。 */
  relativePath: string
  /** 字节数；未知时为 -1。 */
  size: number
  /** 毫秒时间戳；未知时为 -1。 */
  modified: number
  extension: string
}

export interface WorkspaceOpenResult {
  sourceUri: string
  displayName: string
  markdown: string
  encoding: string
  /**
   * 文档所在目录的绝对路径（带尾斜杠，如 `/storage/emulated/0/Docs/`），由原生
   * 从 SAF documentId 反解得到，供前端把 Markdown 里的相对图片路径锚定到真实
   * 文件。内容提供方拿不到文件系统路径时为空/缺失，前端会跳过相对路径解析。
   */
  dirPath?: string
  /** 内容提供方标识（SAF authority），用于在最近列表里区分来源。 */
  providerName: string
  /** 是否能写回原文件（取决于绑定目录是否拿到了写授权）。 */
  canWrite: boolean
  /** 目录授权是否仍是持久化的（绑定未被撤销）。 */
  persisted: boolean
  /** 原文件是否带 UTF-8 BOM（私有副本读取时由原生回传）。 */
  hasEncodingBom?: boolean
}

export interface WorkspacePermissionState {
  granted: boolean
  canWrite: boolean
}

/**
 * 单文件授权结果。
 *
 * 与目录绑定不同，这里只拿一条文档 URI 的持久化权限——用于「把已有文件
 * 索引进项目树」：文件留在原地，App 只记住它在哪（不复制内容）。
 */
export interface WorkspacePickedFile {
  uri: string
  name: string
  providerName: string
  canWrite: boolean
  persisted: boolean
  size: number
  modified: number
  extension: string
}

interface CanceledResult {
  canceled: true
}

interface WorkspacePlugin {
  pickDirectory(): Promise<(WorkspaceDirectory & { canceled?: false }) | CanceledResult>
  pickDocument(options?: {
    accept?: 'document' | 'archive'
  }): Promise<(WorkspacePickedFile & { canceled?: false }) | CanceledResult>
  checkPermission(options: { treeUri: string }): Promise<WorkspacePermissionState>
  readByUri(options: { fileUri: string }): Promise<
    (WorkspaceOpenResult & { canceled?: false }) | CanceledResult
  >
  writeByUri(options: {
    fileUri: string
    markdown: string
    encoding?: string
  }): Promise<{ ok: true; size: number } | { ok: false; code: string }>
  listDocuments(options: { treeUri: string; limit?: number }): Promise<
    | (CanceledResult & { files?: WorkspaceDocumentEntry[] })
    | {
        canceled?: false
        treeUri: string
        displayName: string
        files: WorkspaceDocumentEntry[]
        truncated: boolean
      }
  >
  openDocument(options: { fileUri: string }): Promise<
    WorkspaceOpenResult | CanceledResult
  >
  createDirectory(options: { parentUri: string; name: string }): Promise<{
    ok: true
    uri: string
    name: string
  }>
  createFile(options: {
    parentUri: string
    name: string
    markdown?: string
    encoding?: string
  }): Promise<{ ok: true; uri: string; name: string; size: number }>
  deleteDocument(options: { uri: string }): Promise<{ ok: true }>
  renameDocument(options: { uri: string; newName: string }): Promise<{
    ok: true
    uri: string
    name: string
  }>
  moveDocument(options: {
    uri: string
    newParentUri: string
    sourceParentUri?: string
  }): Promise<{
    ok: true
    uri: string
  }>
  probeWrite(options: { treeUri: string }): Promise<WorkspaceWriteProbe>
  /** 把整棵导入树落盘到 App 私有目录，返回落盘后的文件相对路径清单。 */
  importProjectToPrivate(options: {
    projectId: string
    entries: Array<{ path: string; content: string; isDirectory: boolean }>
  }): Promise<{ ok: true; projectId: string; rootPath: string; files: string[] }>
  /** 读私有项目里的一个文件，返回与 openDocument 同构的载荷。 */
  readPrivateFile(options: {
    projectId: string
    relPath: string
  }): Promise<WorkspaceOpenResult>
  /** 原子写回私有项目里的一个文件。 */
  writePrivateFile(options: {
    projectId: string
    relPath: string
    markdown: string
    encoding?: string
  }): Promise<{ ok: true; size: number }>
  /** 删除整个私有项目目录。 */
  deletePrivateProject(options: { projectId: string }): Promise<{ ok: true }>
}

/**
 * 写权限探测结果。
 *
 * - `canWrite`  探测结论：真的能写吗（前端据此修正 UI 只读态）；
 * - `grantWrite` 授权记录的静态结论，仅供诊断（可能因历史 bug 与 canWrite 不一致）；
 * - `reason`     失败分类：OK / INVALID_TREE / PERMISSION_DENIED / PROVIDER_REJECTED。
 */
export interface WorkspaceWriteProbe {
  canWrite: boolean
  grantWrite: boolean
  reason: 'OK' | 'INVALID_TREE' | 'PERMISSION_DENIED' | 'PROVIDER_REJECTED'
  /** 可选诊断字段：原生实现可能回传异常消息与 treeUri，便于定位只读根因。 */
  detail?: string
  treeUri?: string
}

const Workspace = registerPlugin<WorkspacePlugin>('Workspace')

export function isWorkspaceAvailable() {
  return Capacitor.isNativePlatform()
}

/** 弹系统目录选择器，用户选定后目录授权被持久化。 */
export async function pickWorkspaceDirectory(): Promise<WorkspaceDirectory | null> {
  if (!isWorkspaceAvailable()) {
    return null
  }
  const result = await Workspace.pickDirectory()
  if (result.canceled) {
    return null
  }
  return {
    treeUri: result.treeUri,
    displayName: result.displayName,
    canWrite: result.canWrite,
  }
}

/** 检查一条历史绑定是否还有效（用户可能已在系统设置里撤销授权）。 */
export async function checkWorkspacePermission(
  treeUri: string,
): Promise<WorkspacePermissionState> {
  if (!isWorkspaceAvailable()) {
    return { granted: false, canWrite: false }
  }
  try {
    return await Workspace.checkPermission({ treeUri })
  } catch {
    return { granted: false, canWrite: false }
  }
}

/**
 * 真实写权限探测：在绑定根下创建再删除一个临时文件。
 *
 * 与 {@link checkWorkspacePermission} 的静态判断互补——它能识破两种情况：
 *   1) 授权记录被判错（历史 bug），其实能写 → 前端应把 canWrite 修正为 true；
 *   2) 授权记录说能写，但 provider 实际拒绝（只读挂载/网盘只读套餐/SD 卡写保护）
 *      → 前端应把 canWrite 修正为 false 并给专门文案。
 *
 * 非原生环境返回保守的「不可写」结果，避免误改 UI。
 */
export async function probeWorkspaceWrite(
  treeUri: string,
): Promise<WorkspaceWriteProbe> {
  if (!isWorkspaceAvailable()) {
    return { canWrite: false, grantWrite: false, reason: 'PERMISSION_DENIED' }
  }
  try {
    return await Workspace.probeWrite({ treeUri })
  } catch {
    return { canWrite: false, grantWrite: false, reason: 'PROVIDER_REJECTED' }
  }
}

/** 递归列出绑定目录下的文档；权限失效时抛出可识别的错误。 */
export async function listWorkspaceDocuments(
  treeUri: string,
  limit?: number,
): Promise<{ files: WorkspaceDocumentEntry[]; truncated: boolean }> {
  const result = await Workspace.listDocuments({ treeUri, limit })
  if (result.canceled) {
    return { files: [], truncated: false }
  }
  return { files: result.files, truncated: result.truncated }
}

/** 打开绑定目录内的文档，返回内容。 */
export async function openWorkspaceDocument(
  fileUri: string,
): Promise<WorkspaceOpenResult | null> {
  if (!isWorkspaceAvailable()) {
    return null
  }
  const result = await Workspace.openDocument({ fileUri })
  // 判别联合收窄：CanceledResult 只带 canceled，WorkspaceOpenResult 带 sourceUri。
  // 不能用 `result.canceled` 直接判——非取消分支上该属性并不存在。
  if ('canceled' in result && result.canceled) {
    return null
  }
  return result as WorkspaceOpenResult
}
/** 把原生错误码翻译成可展示的原因，供 UI 区分「权限没了」和「读失败了」。 */
export function getWorkspaceErrorCode(error: unknown): string | null {
  if (typeof error === 'object' && error !== null && 'code' in error) {
    const code = (error as { code?: unknown }).code
    return typeof code === 'string' ? code : null
  }
  return null
}

const PROVIDER_FALLBACK = 'unknown'
const EXTENSION_FALLBACK = 'md'

/**
 * 弹系统单文件选择器，拿到一条可持久化的文档授权。
 *
 * 用于项目树的「添加已有文件」：用户自己挑一个 .md，App 只登记 URI，
 * 内容仍留在原处。取消或非原生环境返回 null。
 *
 * @param accept `document`（默认）只筛可编辑的 Markdown / 文本；
 *   `archive` 不筛类型，任意文件可选（项目归档 zip 导入用）——
 *   因为系统文件选择器的 MIME 白名单会把 .zip 灰掉，只能放开让用户选。
 */
export async function pickWorkspaceFile(
  accept: 'document' | 'archive' = 'document',
): Promise<WorkspacePickedFile | null> {
  if (!isWorkspaceAvailable()) {
    return null
  }
  const result = await Workspace.pickDocument({ accept })
  if ('canceled' in result && result.canceled) {
    return null
  }
  const picked = result as WorkspacePickedFile
  return {
    uri: picked.uri,
    name: picked.name,
    providerName: picked.providerName || PROVIDER_FALLBACK,
    canWrite: Boolean(picked.canWrite),
    persisted: Boolean(picked.persisted),
    size: typeof picked.size === 'number' ? picked.size : -1,
    modified: typeof picked.modified === 'number' ? picked.modified : -1,
    extension: picked.extension || EXTENSION_FALLBACK,
  }
}

/** 通过持久化的单文件授权读取内容（编辑已有文件用）。 */
export async function readWorkspaceFileByUri(
  fileUri: string,
): Promise<WorkspaceOpenResult | null> {
  if (!isWorkspaceAvailable()) {
    return null
  }
  const result = await Workspace.readByUri({ fileUri })
  if ('canceled' in result && result.canceled) {
    return null
  }
  return result as WorkspaceOpenResult
}

/** 通过持久化的单文件授权写回内容；失败时返回错误码而不是抛异常。 */
export async function writeWorkspaceFileByUri(
  fileUri: string,
  markdown: string,
  encoding = 'utf-8',
): Promise<{ ok: boolean; code?: string }> {
  if (!isWorkspaceAvailable()) {
    return { ok: false, code: 'NOT_NATIVE' }
  }
  const result = await Workspace.writeByUri({ fileUri, markdown, encoding })
  return result.ok ? { ok: true } : { ok: false, code: result.code }
}

// ---------------------------------------------------------------------------
// 双向可写：绑定目录内的目录级操作
//
// 这些操作直接作用于用户的实体文件夹（不是内存里的虚拟树）。统一约定：
// 返回 null 表示失败（错误码可从抛出的异常里用 getWorkspaceErrorCode 取），
// 失败时调用方必须保持内存树不变——「先写盘，成功再改树」。
// ---------------------------------------------------------------------------

/** 新建子目录；成功返回新目录的 URI 与名称，失败返回 null。 */
export async function createWorkspaceDirectory(
  parentUri: string,
  name: string,
): Promise<{ uri: string; name: string } | null> {
  if (!isWorkspaceAvailable()) {
    return null
  }
  const result = await Workspace.createDirectory({ parentUri, name })
  if (!result.ok || !result.uri) {
    return null
  }
  return { uri: result.uri, name: result.name }
}

export interface WorkspaceCreatedFile {
  uri: string
  name: string
  size: number
}

/** 新建 Markdown 文件（可带初始内容）；成功返回文件登记信息，失败返回 null。 */
export async function createWorkspaceFile(
  parentUri: string,
  name: string,
  markdown = '',
  encoding = 'utf-8',
): Promise<WorkspaceCreatedFile | null> {
  if (!isWorkspaceAvailable()) {
    return null
  }
  const result = await Workspace.createFile({ parentUri, name, markdown, encoding })
  if (!result.ok || !result.uri) {
    return null
  }
  return { uri: result.uri, name: result.name, size: result.size }
}

/** 删除一个文档或目录（实体硬盘上的真实删除）。 */
export async function deleteWorkspaceDocument(uri: string): Promise<boolean> {
  if (!isWorkspaceAvailable()) {
    return false
  }
  const result = await Workspace.deleteDocument({ uri })
  return Boolean(result.ok)
}

/** 重命名实体文档/目录；成功返回可能已变化的新 URI 与新名称，失败返回 null。 */
export async function renameWorkspaceDocument(
  uri: string,
  newName: string,
): Promise<{ uri: string; name: string } | null> {
  if (!isWorkspaceAvailable()) {
    return null
  }
  const result = await Workspace.renameDocument({ uri, newName })
  if (!result.ok || !result.uri) {
    return null
  }
  return { uri: result.uri, name: result.name }
}

/**
 * 把实体文档/目录移动到另一个目录；成功返回新 URI，失败返回 null。
 *
 * `sourceParentUri` 是源项**当前所在目录**的 document URI。SAF 的 documentId
 * 由 provider 内部编码，无法从源项 URI 反推父目录（`.../document/<docId>` 里
 * 不含父信息），因此调用方必须显式传入。传 null 时由原生按老逻辑尽力反推。
 */
export async function moveWorkspaceDocument(
  uri: string,
  newParentUri: string,
  sourceParentUri?: string | null,
): Promise<{ uri: string } | null> {
  if (!isWorkspaceAvailable()) {
    return null
  }
  const result = await Workspace.moveDocument({
    uri,
    newParentUri,
    ...(sourceParentUri ? { sourceParentUri } : {}),
  })
  if (!result.ok || !result.uri) {
    return null
  }
  return { uri: result.uri }
}

// ---------------------------------------------------------------------------
// 私有目录项目：把导入的项目整树落盘到 App 私有目录
//
// 导入的 zip 项目不再做成「纯内存虚拟树」，而是整树写进 App 私有目录，文件
// 节点持有真实相对路径。读写都走真实磁盘，因此编辑器保存 / 返回 / 重启全部
// 复用既有 SAF 文档链路——canWrite 恒为 true，sourceUri 用自定义 scheme 前缀
// `perspicuity-private://<projectId>/<relPath>`，前端据此路由到私有 IO。
// 与 SAF 的差别：卸载 App 连数据一起消失（副本语义），但永不因清授权失联。
// ---------------------------------------------------------------------------

/** 私有项目 sourceUri 的 scheme 前缀；与原生 WorkspacePlugin 保持一致。 */
export const PRIVATE_FILE_SCHEME = 'perspicuity-private://'

/** 判断一个 sourceUri 是不是 App 私有目录里的项目文件。 */
export function isPrivateProjectUri(sourceUri: string | null | undefined): boolean {
  return typeof sourceUri === 'string' && sourceUri.startsWith(PRIVATE_FILE_SCHEME)
}

/** 从私有 sourceUri 里拆出 projectId 与相对路径；解析失败返回 null。 */
export function parsePrivateProjectUri(
  sourceUri: string,
): { projectId: string; relPath: string } | null {
  if (!isPrivateProjectUri(sourceUri)) {
    return null
  }
  const rest = sourceUri.slice(PRIVATE_FILE_SCHEME.length)
  const slash = rest.indexOf('/')
  if (slash <= 0) {
    return null
  }
  return { projectId: rest.slice(0, slash), relPath: rest.slice(slash + 1) }
}

/**
 * 把整棵导入树写进 App 私有目录。
 *
 * 调用前应先生成 projectId（用于目录名），entries 是归档条目列表。
 * 非原生环境返回 null（调用方据此降级回内存方案）。
 */
export async function importProjectToPrivate(
  projectId: string,
  entries: Array<{ path: string; content: string; isDirectory: boolean }>,
): Promise<{ projectId: string; rootPath: string; files: string[] } | null> {
  if (!isWorkspaceAvailable()) {
    return null
  }
  const result = await Workspace.importProjectToPrivate({ projectId, entries })
  if (!result.ok) {
    return null
  }
  return { projectId: result.projectId, rootPath: result.rootPath, files: result.files }
}

/** 读私有项目里的一个文件；失败抛出可识别错误（调用方按错误码提示）。 */
export async function readPrivateProjectFile(
  projectId: string,
  relPath: string,
): Promise<WorkspaceOpenResult | null> {
  if (!isWorkspaceAvailable()) {
    return null
  }
  return await Workspace.readPrivateFile({ projectId, relPath })
}

/** 写回私有项目里的一个文件；失败返回错误码而不是抛异常。 */
export async function writePrivateProjectFile(
  projectId: string,
  relPath: string,
  markdown: string,
  encoding = 'utf-8',
): Promise<{ ok: boolean; code?: string }> {
  if (!isWorkspaceAvailable()) {
    return { ok: false, code: 'NOT_NATIVE' }
  }
  const result = await Workspace.writePrivateFile({ projectId, relPath, markdown, encoding })
  return result.ok ? { ok: true } : { ok: false }
}

/** 删除整个私有项目目录（删项目 / 重导时调用），失败静默返回 false。 */
export async function deletePrivateProject(projectId: string): Promise<boolean> {
  if (!isWorkspaceAvailable()) {
    return false
  }
  try {
    const result = await Workspace.deletePrivateProject({ projectId })
    return Boolean(result.ok)
  } catch {
    return false
  }
}
