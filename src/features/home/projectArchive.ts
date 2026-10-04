/**
 * 项目树的导入 / 导出序列化层。
 *
 * 这里只负责「项目树 ⇄ 归档条目列表」的纯逻辑转换，不碰文件系统：
 * 真正的 zip 读写交给原生 WorkspacePlugin（java.util.zip，UTF-8 文件名可靠），
 * 这样既避免把整个归档塞进 JS 内存，也避免引第三方 zip 库。
 *
 * 两种导出格式：
 *   - nested：按真实目录结构写 `<项目名>/大纲类/世界观/剧情.md`，直观、
 *     任何解压工具都能看，适合「给人看 / 丢进 git」；
 *   - flat：所有文件平铺在一个目录 + 一份 manifest.json 描述整棵树，
 *     对不支持深层路径的工具（或后续同步）更友好。
 *
 * 导入时自动识别：有 manifest.json 就按它还原（flat 或 nested 均可），
 * 否则按目录结构反推（nested）。
 */
import {
  createNodeId,
  createProjectId,
  findNode,
  nodePath,
  toPrivateFileRef,
  type ProjectNode,
  type ProjectRecord,
} from './projectStore'

/** 归档里的一个条目。目录用 `isDirectory: true` 且 content 为空表示。 */
export interface ArchiveEntry {
  /** 归档内的相对路径，用 `/` 分隔，不以 `/` 开头。文件条目不含结尾 `/`。 */
  path: string
  /** 文本内容；目录条目为空字符串。 */
  content: string
  isDirectory: boolean
}

export type ArchiveFormat = 'nested' | 'flat'

/** flat 格式的清单文件名。 */
export const MANIFEST_FILENAME = 'manifest.json'

/** manifest.json 的结构（版本化，为将来扩展留位）。 */
export interface ArchiveManifest {
  format: 'perspicuity-project'
  schemaVersion: number
  projectName: string
  /** 扁平条目列表：路径 + 节点层级关系。 */
  nodes: ManifestNode[]
}

export interface ManifestNode {
  /** 归档内文件路径（flat 格式下是 `files/<序号>-<文件名>`）。 */
  path: string
  name: string
  kind: 'folder' | 'file'
  /** 父节点在 nodes 数组里的下标；-1 表示挂在项目根。 */
  parentIndex: number
}

export const ARCHIVE_SCHEMA_VERSION = 1

/** 归档内统一使用的项目根目录名（避免用户项目名里带 `/` 或非法字符）。 */
export function safeArchiveFolderName(projectName: string): string {
  const cleaned = projectName.replace(/[\\/:*?"<>|]/g, '_').trim()
  return cleaned.length ? cleaned : 'project'
}

/** 把归档内路径里可能出现的非法片段清洗掉（保留层级分隔符）。 */
function sanitizeSegment(segment: string): string {
  const cleaned = segment.replace(/[\\:*?"<>|]/g, '_').replace(/\//g, '_').trim()
  return cleaned.length ? cleaned : 'untitled'
}

// ---------------------------------------------------------------------------
// 导出
// ---------------------------------------------------------------------------

/** 按层级顺序遍历整棵树（父在前），返回 [node, parentIndex][]。 */
function walkTree(project: ProjectRecord): Array<{ node: ProjectNode; parentIndex: number }> {
  const ordered: ProjectNode[] = []
  const byParent = new Map<string | null, ProjectNode[]>()
  for (const node of project.nodes) {
    const bucket = byParent.get(node.parentId) ?? []
    bucket.push(node)
    byParent.set(node.parentId, bucket)
  }
  const visit = (parentId: string | null) => {
    const children = (byParent.get(parentId) ?? []).slice().sort((a, b) => {
      if (a.pinned !== b.pinned) {
        return a.pinned ? -1 : 1
      }
      if (a.order !== b.order) {
        return a.order - b.order
      }
      return a.name.localeCompare(b.name)
    })
    for (const child of children) {
      ordered.push(child)
      visit(child.id)
    }
  }
  visit(null)
  const indexOf = new Map<string, number>()
  ordered.forEach((node, index) => indexOf.set(node.id, index))
  return ordered.map(node => ({
    node,
    parentIndex: node.parentId === null ? -1 : indexOf.get(node.parentId) ?? -1,
  }))
}

/**
 * 把项目树序列化成归档条目（nested 格式）。
 *
 * 目录写成以 `/` 结尾的空条目，保证空目录也能被还原；
 * 文件条目路径 = `<项目名>/<nodePath>`，中文原样保留。
 */
export function serializeProjectNested(project: ProjectRecord): ArchiveEntry[] {
  const root = safeArchiveFolderName(project.name)
  const entries: ArchiveEntry[] = [{ path: `${root}/`, content: '', isDirectory: true }]
  for (const node of project.nodes) {
    const relative = nodePath(project.nodes, node)
      .split('/')
      .map(sanitizeSegment)
      .join('/')
    const full = `${root}/${relative}`
    if (node.kind === 'folder') {
      entries.push({ path: `${full}/`, content: '', isDirectory: true })
    } else {
      entries.push({ path: full, content: '', isDirectory: false })
    }
  }
  return entries
}

/**
 * 把项目树序列化成归档条目（flat 格式）。
 *
 * 所有文件平铺在 `<项目名>/files/` 下，文件名加序号避免重名冲突；
 * manifest.json 记录每个条目的原始名字与父子关系，供导入时还原。
 */
export function serializeProjectFlat(project: ProjectRecord): ArchiveEntry[] {
  const root = safeArchiveFolderName(project.name)
  const walked = walkTree(project)
  const nodes: ManifestNode[] = []
  const entries: ArchiveEntry[] = [{ path: `${root}/${MANIFEST_FILENAME}`, content: '', isDirectory: false }]
  let fileSeq = 0
  walked.forEach(({ node, parentIndex }, index) => {
    if (node.kind === 'file') {
      fileSeq += 1
      const seq = String(fileSeq).padStart(3, '0')
      const path = `${root}/files/${seq}-${sanitizeSegment(node.name)}`
      entries.push({ path, content: '', isDirectory: false })
      nodes.push({ path, name: node.name, kind: 'file', parentIndex })
    } else {
      // 目录在 flat 格式里没有独立文件，路径留空，仅靠 manifest 描述。
      nodes.push({ path: '', name: node.name, kind: 'folder', parentIndex })
    }
    void index
  })
  const manifest: ArchiveManifest = {
    format: 'perspicuity-project',
    schemaVersion: ARCHIVE_SCHEMA_VERSION,
    projectName: project.name,
    nodes,
  }
  entries[0] = {
    path: `${root}/${MANIFEST_FILENAME}`,
    content: JSON.stringify(manifest, null, 2),
    isDirectory: false,
  }
  return entries
}

/** 按格式导出。 */
export function serializeProject(project: ProjectRecord, format: ArchiveFormat): ArchiveEntry[] {
  return format === 'flat' ? serializeProjectFlat(project) : serializeProjectNested(project)
}

// ---------------------------------------------------------------------------
// 导入
// ---------------------------------------------------------------------------

/** 解析 manifest.json；失败返回 null（则退回按目录结构推断）。 */
export function parseManifest(raw: string): ArchiveManifest | null {
  try {
    const parsed = JSON.parse(raw) as ArchiveManifest
    if (parsed && parsed.format === 'perspicuity-project' && Array.isArray(parsed.nodes)) {
      return parsed
    }
    return null
  } catch {
    return null
  }
}

/** 从路径里取文件名（最后一段）。 */
function baseName(path: string): string {
  const trimmed = path.replace(/\/+$/, '')
  const segments = trimmed.split('/')
  return segments[segments.length - 1] ?? ''
}

/**
 * 由 flat manifest 还原一棵 ProjectNode[]。
 *
 * manifest.nodes 已按父在前排序；为稳健起见这里做一次拓扑展开——
 * 先解析所有目录，再解析文件，保证父节点一定先于子节点被创建。
 *
 * @param privateProjectId 传入时，文件节点带 source:'private' 的 fileRef，
 *   uri 指向私有目录内该文件（`perspicuity-private://<id>/<entry.path>`）；
 *   为 null/缺省时退回「内容随节点携带」的内存方案（旧行为，仅作兜底）。
 */
export function nodesFromManifest(
  manifest: ArchiveManifest,
  entries: readonly ArchiveEntry[] = [],
  privateProjectId: string | null = null,
): ProjectNode[] {
  // 归档里「路径 → 文本内容」。仅在非私有（内存兜底）模式下才需要。
  const contentByPath = new Map<string, string>()
  if (!privateProjectId) {
    for (const entry of entries) {
      if (!entry.isDirectory) {
        contentByPath.set(entry.path, entry.content)
      }
    }
  }
  const nodes: ProjectNode[] = []
  const idByIndex = new Map<number, string>()
  const orderByParent = new Map<string | null, number>()

  const nextOrder = (parentId: string | null) => {
    const current = orderByParent.get(parentId) ?? 0
    orderByParent.set(parentId, current + 1)
    return current
  }

  // 两遍：目录优先。parentIndex 指向的节点若还没建，视为挂在根（容错）。
  const make = (
    index: number,
    kind: 'folder' | 'file',
    name: string,
    parentIndex: number,
    filePath?: string,
    content?: string,
  ) => {
    const parentId = parentIndex >= 0 ? idByIndex.get(parentIndex) ?? null : null
    const id = createNodeId()
    idByIndex.set(index, id)
    nodes.push({
      id,
      name,
      kind,
      parentId,
      pinned: false,
      order: nextOrder(parentId),
      expanded: true,
      ...(kind === 'file'
        ? privateProjectId && filePath
          ? { fileRef: toPrivateFileRef(privateProjectId, filePath, name) }
          : { fileRef: null, content: content ?? '' }
        : { fileRef: null }),
    })
  }

  manifest.nodes.forEach((entry, index) => {
    if (entry.kind === 'folder') {
      make(index, 'folder', entry.name || baseName(entry.path) || 'untitled', entry.parentIndex)
    }
  })
  manifest.nodes.forEach((entry, index) => {
    if (entry.kind === 'file') {
      make(
        index,
        'file',
        entry.name || baseName(entry.path) || 'untitled.md',
        entry.parentIndex,
        entry.path,
        contentByPath.get(entry.path) ?? '',
      )
    }
  })
  return nodes
}

/**
 * 由目录结构还原 ProjectNode[]（nested 格式，或没有 manifest 时的兜底）。
 *
 * entries 里的路径形如 `<项目名>/大纲类/世界观/剧情.md`，取第一段作为根目录名，
 * 其余逐级建目录，最后一段建文件。
 *
 * @param privateProjectId 传入时，文件节点带 source:'private' 的 fileRef，
 *   uri = `perspicuity-private://<id>/<entry.path>`（entry.path 原样，含项目根
 *   段——与原生落盘时写入私有目录的相对路径一致）。为 null/缺省时退回内存
 *   方案（内容随节点携带，旧行为）。
 */
export function nodesFromNested(
  entries: ArchiveEntry[],
  privateProjectId: string | null = null,
): { nodes: ProjectNode[]; rootName: string | null } {
  const nodes: ProjectNode[] = []
  // 目录路径（不含根）→ 节点 id。
  const folderIdByPath = new Map<string, string>()
  // 每个父级下已用的 order 计数。
  const orderByParent = new Map<string | null, number>()
  let rootName: string | null = null

  const nextOrder = (parentId: string | null) => {
    const current = orderByParent.get(parentId) ?? 0
    orderByParent.set(parentId, current + 1)
    return current
  }

  const ensureFolder = (segments: string[]): string | null => {
    if (!segments.length) {
      return null
    }
    let parentId: string | null = null
    let acc = ''
    for (const segment of segments) {
      acc = acc ? `${acc}/${segment}` : segment
      const existing = folderIdByPath.get(acc)
      if (existing) {
        parentId = existing
        continue
      }
      const id = createNodeId()
      folderIdByPath.set(acc, id)
      nodes.push({
        id,
        name: segment,
        kind: 'folder',
        parentId,
        pinned: false,
        order: nextOrder(parentId),
        expanded: true,
        fileRef: null,
      })
      parentId = id
    }
    return parentId
  }

  for (const entry of entries) {
    const clean = entry.path.replace(/^\/+/, '')
    if (!clean) {
      continue
    }
    const segments = clean.split('/').filter(Boolean)
    if (!rootName && segments.length) {
      rootName = segments[0]
    }
    const rest = segments.slice(1)
    if (!rest.length) {
      continue
    }
    if (entry.isDirectory) {
      ensureFolder(rest)
      continue
    }
    const fileName = rest[rest.length - 1]
    const parentId = rest.length > 1 ? ensureFolder(rest.slice(0, -1)) : null
    // 私有模式：文件落盘在 `perspicuity-private://<id>/<entry.path>`，条目路径
    // 原样保留（含项目根段），与原生写入时的相对路径逐字符一致。
    const trimmedPath = entry.path.replace(/^\/+/, '')
    nodes.push({
      id: createNodeId(),
      name: fileName,
      kind: 'file',
      parentId,
      pinned: false,
      order: nextOrder(parentId),
      expanded: true,
      ...(privateProjectId
        ? { fileRef: toPrivateFileRef(privateProjectId, trimmedPath, fileName) }
        : {
            fileRef: null,
            // 内容随节点携带：内存兜底模式下没有磁盘 URI，只能靠它打开。
            content: entry.content,
          }),
    })
  }
  return { nodes, rootName }
}

/**
 * 从归档条目还原出项目树。
 *
 * 优先读 manifest.json 精确还原层级；没有则按目录结构推。
 * 返回 projectName 兜底为归档第一段目录名或「导入的项目」。
 *
 * @param privateProjectId 传入时走「落盘私有目录」模式：文件节点带
 *   source:'private' 的 fileRef，project.privateRoot 记下该标识。为 null/缺省
 *   时退回旧的「纯内存虚拟树」（文件节点携带 content）。
 */
export function parseProjectArchive(
  entries: ArchiveEntry[],
  privateProjectId: string | null = null,
): ProjectRecord {
  const manifestEntry = entries.find(entry => baseName(entry.path) === MANIFEST_FILENAME)
  if (manifestEntry) {
    const manifest = parseManifest(manifestEntry.content)
    if (manifest) {
      return {
        id: createProjectId(),
        name: manifest.projectName?.trim() || '导入的项目',
        pinned: false,
        expanded: true,
        nodes: nodesFromManifest(manifest, entries, privateProjectId),
        createdAt: new Date().toISOString(),
        schemaVersion: 2,
        ...(privateProjectId ? { privateRoot: privateProjectId } : {}),
      }
    }
  }
  const { nodes, rootName } = nodesFromNested(entries, privateProjectId)
  return {
    id: createProjectId(),
    name: rootName?.trim() || '导入的项目',
    pinned: false,
    expanded: true,
    nodes,
    createdAt: new Date().toISOString(),
    schemaVersion: 2,
    ...(privateProjectId ? { privateRoot: privateProjectId } : {}),
  }
}

/** 校验：一棵树里所有 parentId 都能找到（用于导入后自检，防止坏归档）。 */
export function isConsistentTree(nodes: readonly ProjectNode[]): boolean {
  return nodes.every(node => node.parentId === null || findNode(nodes, node.parentId) !== null)
}
