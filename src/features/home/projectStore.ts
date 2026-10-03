/**
 * 虚拟项目树的数据模型。
 *
 * 与旧的 workspaceStore（扁平「绑定目录」清单）的区别：这里存的是**一棵树**。
 * 项目根不需要实体文件夹——节点可以是纯逻辑目录，也可以是索引到外部文件的
 * 引用。实体文件始终躺在用户自己的存储上，App 只保存「它在哪里」。
 *
 * 存储形态刻意选「扁平数组 + parentId」，而不是嵌套对象：
 *   - 拖拽调整只需要改一个节点的 parentId / order，不必在多层数组里搬来搬去；
 *   - 纯函数化处理，单测友好；
 *   - 渲染时按 parentId 现算 children，得到的树和存储永远一致，不会出现
 *     「嵌套副本忘了同步」的脏数据。
 *
 * 深度约束：根为第 0 层，其下最多再嵌套 2 层（即最深 folder 在第 2 层，文件
 * 挂在该 folder 下）。等价于用户举的例子 `裂境营火GDD/大纲类/世界观/剧情.md`。
 */

/** 树的最大嵌套层数（不含项目根）。根的子节点为第 1 层。 */
export const MAX_NEST_DEPTH = 2

/** 项目内一个节点：目录或文件。 */
export interface ProjectNode {
  id: string
  name: string
  kind: 'folder' | 'file'
  /** null 表示直接挂在项目根下。 */
  parentId: string | null
  /** 同级置顶标记；排序时置顶项永远排在非置顶项之前。 */
  pinned: boolean
  /** 同级内的顺序，越小越靠前。 */
  order: number
  /** 目录的展开状态；文件忽略此字段。 */
  expanded: boolean
  /**
   * 文件节点的外部引用。虚拟目录没有这个字段。
   *
   * source 三态：
   *   - 'indexed'：外部文件的 SAF 单文件授权，文件本体留在原处；
   *   - 'private'：App 私有目录里的副本，卸载即消失但不会因系统清授权而失联；
   *   - 'created'：在本项目内新建的空文件。
   */
  fileRef?: ProjectFileRef | null
  /**
   * 仅「绑定实体目录」的镜像目录节点会带：该目录在硬盘上的 document URI。
   *
   * 有了它，双向可写时才能直接在这个 URI 下 createDocument / moveDocument，
   * 不必从文件 URI 反推父目录（SAF 的 documentId 由 provider 内部编码，
   * 父目录 id 无法从子项 id 可靠还原）。空目录也能显示、也能往里放东西。
   */
  dirUri?: string | null
}

export interface ProjectFileRef {
  /** SAF document URI；私有副本则为私有目录内的相对路径。 */
  uri: string
  /** 真实文件名（含扩展名），重命名节点不改变它。 */
  fileName: string
  /** 内容提供方 authority，用于展示来源。 */
  providerName: string
  /** 授权是否仍然持久有效。 */
  persisted: boolean
  source: 'indexed' | 'private' | 'created'
}

/**
 * 绑定到一个实体目录（SAF tree URI）后留下的元数据。
 *
 * 语义：绑定后项目树是**该目录的镜像**——结构由扫描结果决定，文件内容直读直写
 * （不存副本）。实体文件始终躺在用户存储上，App 只记「它在哪个目录里」。
 * 用户随时可「刷新」把硬盘上的新增/删除单向同步进项目树。
 */
export interface ProjectBoundTree {
  /** SAF tree URI（持久化授权）。 */
  treeUri: string
  /** 目录展示名（系统返回，通常就是末级文件夹名）。 */
  displayName: string
  /** 是否可写；只读目录下禁用「写回」。 */
  canWrite: boolean
  /** 最近一次扫描时间（ISO），用于 UI 提示「上次同步」。 */
  scannedAt: string
}

/** 一个虚拟项目。 */
export interface ProjectRecord {
  id: string
  name: string
  pinned: boolean
  expanded: boolean
  nodes: ProjectNode[]
  createdAt: string
  schemaVersion: number
  /**
   * 若该项目绑定了实体目录，这里记录目录信息；纯虚拟项目为 null/缺省。
   * 可选字段，老数据（v2 无此字段）解析后为 null。
   */
  boundTree?: ProjectBoundTree | null
}

export interface ProjectStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
}

export const PROJECT_STORAGE_KEY = 'perspicuity.projects.v2'

export const PROJECT_SCHEMA_VERSION = 2

/** 生成一个本地唯一 id。不引第三方库——时间戳 + 计数器足够本地去重。 */
let idCounter = 0
export function createNodeId(seed: number = Date.now()): string {
  idCounter += 1
  return `node-${seed.toString(36)}-${idCounter.toString(36)}`
}

export function createProjectId(seed: number = Date.now()): string {
  idCounter += 1
  return `project-${seed.toString(36)}-${idCounter.toString(36)}`
}

/** 新建一个空项目。 */
export function createProjectRecord(
  name: string,
  options: { pinned?: boolean; createdAt?: string; boundTree?: ProjectBoundTree | null } = {},
): ProjectRecord {
  return {
    id: createProjectId(),
    name,
    pinned: options.pinned === true,
    expanded: true,
    nodes: [],
    createdAt: options.createdAt ?? new Date().toISOString(),
    schemaVersion: PROJECT_SCHEMA_VERSION,
    boundTree: options.boundTree ?? null,
  }
}

// ---------------------------------------------------------------------------
// 树的派生视图
// ---------------------------------------------------------------------------

/** 项目根 + 全部节点组成的可寻址集合（根用 parentId=null 的隐式节点表示）。 */
export function findNode(nodes: readonly ProjectNode[], id: string): ProjectNode | null {
  return nodes.find(node => node.id === id) ?? null
}

/** 直接子节点，按「置顶优先 + order」排序。 */
export function childrenOf(
  nodes: readonly ProjectNode[],
  parentId: string | null,
): ProjectNode[] {
  return nodes
    .filter(node => node.parentId === parentId)
    .sort(compareSiblings)
}

function compareSiblings(left: ProjectNode, right: ProjectNode): number {
  if (left.pinned !== right.pinned) {
    return left.pinned ? -1 : 1
  }
  if (left.order !== right.order) {
    return left.order - right.order
  }
  // order 撞车时按名称兜底，保证渲染顺序稳定（否则同名 order 会抖动）。
  return left.name.localeCompare(right.name)
}

/**
 * 节点在树里的层级：根的子节点为第 1 层。
 *
 * 返回 1 起的深度；找不到节点或形成环时返回 0——环数据是坏数据，
 * 当成顶层处理比死循环强。
 */
export function depthOf(nodes: readonly ProjectNode[], id: string): number {
  const start = findNode(nodes, id)
  if (!start) {
    return 0
  }
  let depth = 1
  let current: ProjectNode = start
  const guard = new Set<string>([start.id])
  while (current.parentId !== null) {
    const parent: ProjectNode | null = findNode(nodes, current.parentId)
    // 父节点不存在（悬挂）时当前节点就是顶层，已由 depth=1 覆盖。
    if (!parent) {
      break
    }
    // 环：沿 parent 链回到了走过的节点，判为坏数据。
    if (guard.has(parent.id)) {
      return 0
    }
    guard.add(parent.id)
    depth += 1
    current = parent
  }
  return depth
}

/**
 * 某目录下面还能不能再放「目录」（不超过 MAX_NEST_DEPTH 层目录）。
 * 项目根 → 第 1 层目录 → 第 2 层目录，到此为止。
 */
export function canHostChildren(nodes: readonly ProjectNode[], parentId: string | null): boolean {
  if (parentId === null) {
    return true
  }
  return depthOf(nodes, parentId) < MAX_NEST_DEPTH
}
/**
 * 某目录下面还能不能再放「文件」。
 *
 * 文件比目录多允许一层：目录最多 MAX_NEST_DEPTH 层，文件可落在最深目录内，
 * 也就是 depth = MAX_NEST_DEPTH + 1。否则最深层目录就是空壳，毫无意义。
 * 判定的是「父级层数」：depthOf(parent) <= MAX_NEST_DEPTH 即可。
 */
export function canHostFiles(nodes: readonly ProjectNode[], parentId: string | null): boolean {
  if (parentId === null) {
    return true
  }
  return depthOf(nodes, parentId) <= MAX_NEST_DEPTH
}

/** 某节点的全部后代 id（不含自身）。 */
export function descendantIds(nodes: readonly ProjectNode[], id: string): Set<string> {
  const out = new Set<string>()
  const queue = [id]
  while (queue.length) {
    const current = queue.shift() as string
    for (const node of nodes) {
      if (node.parentId === current && !out.has(node.id)) {
        out.add(node.id)
        queue.push(node.id)
      }
    }
  }
  return out
}

// ---------------------------------------------------------------------------
// 变更操作（全部返回新数组，不 mutate 入参）
// ---------------------------------------------------------------------------

/** 同级末尾的下一个 order 值。 */
export function nextOrder(nodes: readonly ProjectNode[], parentId: string | null): number {
  const siblings = nodes.filter(node => node.parentId === parentId)
  if (!siblings.length) {
    return 0
  }
  return Math.max(...siblings.map(node => node.order)) + 1
}

export function addNode(
  nodes: readonly ProjectNode[],
  node: ProjectNode,
): ProjectNode[] {
  return [...nodes, node]
}

/** 创建目录节点。超出深度上限时返回 null。 */
export function createFolderNode(
  nodes: readonly ProjectNode[],
  parentId: string | null,
  name: string,
  options: { pinned?: boolean; id?: string } = {},
): ProjectNode | null {
  if (!canHostChildren(nodes, parentId)) {
    return null
  }
  return {
    id: options.id ?? createNodeId(),
    name,
    kind: 'folder',
    parentId,
    pinned: options.pinned === true,
    order: nextOrder(nodes, parentId),
    expanded: true,
  }
}

/** 创建文件节点（含新建 / 索引外部 / 私有副本三种来源）。 */
export function createFileNode(
  nodes: readonly ProjectNode[],
  parentId: string | null,
  name: string,
  fileRef: ProjectFileRef | null = null,
  options: { id?: string } = {},
): ProjectNode | null {
  // 文件允许挂在最深一层目录下（比目录多一层），否则最深层目录就是空壳。
  if (!canHostFiles(nodes, parentId)) {
    return null
  }
  return {
    id: options.id ?? createNodeId(),
    name,
    kind: 'file',
    parentId,
    pinned: false,
    order: nextOrder(nodes, parentId),
    expanded: false,
    fileRef,
  }
}

/** 重命名节点。 */
export function renameNode(
  nodes: readonly ProjectNode[],
  id: string,
  name: string,
): ProjectNode[] {
  return nodes.map(node => (node.id === id ? { ...node, name } : node))
}

/** 切换目录展开状态。 */
export function toggleExpanded(
  nodes: readonly ProjectNode[],
  id: string,
): ProjectNode[] {
  return nodes.map(node =>
    node.id === id ? { ...node, expanded: !node.expanded } : node,
  )
}

/** 同级置顶（同级内把该节点移到最前；再点一次取消）。 */
export function togglePinned(
  nodes: readonly ProjectNode[],
  id: string,
): ProjectNode[] {
  const target = findNode(nodes, id)
  if (!target) {
    return [...nodes]
  }
  return nodes.map(node => (node.id === id ? { ...node, pinned: !node.pinned } : node))
}

/** 删除节点连同其全部后代。 */
export function removeNode(
  nodes: readonly ProjectNode[],
  id: string,
): ProjectNode[] {
  const doomed = descendantIds(nodes, id)
  doomed.add(id)
  return nodes.filter(node => !doomed.has(node.id))
}

/** 更新文件节点的 fileRef（例如授权状态刷新）。 */
export function updateFileRef(
  nodes: readonly ProjectNode[],
  id: string,
  fileRef: ProjectFileRef,
): ProjectNode[] {
  return nodes.map(node => (node.id === id ? { ...node, fileRef } : node))
}

// ---------------------------------------------------------------------------
// 拖拽
// ---------------------------------------------------------------------------

export type MoveRejection =
  | 'SELF'
  | 'INTO_DESCENDANT'
  | 'TOO_DEEP'
  | 'NOT_FOUND'

export interface MoveCheck {
  ok: boolean
  reason?: MoveRejection
}

/**
 * 判断把 `nodeId` 挂到 `nextParentId` 下是否合法。
 *
 * 三条规则：
 *   1. 不能拖到自己身上；
 *   2. 不能拖进自己的后代里（否则子树脱离树根成为环）；
 *   3. 挂过去之后**整棵子树逐层**都不得超过各自类型的深度上限——目录 ≤
 *      MAX_NEST_DEPTH，文件 ≤ MAX_NEST_DEPTH + 1。注意是逐节点按类型判，
 *      不是拿一个统一的 maxDepth 去卡子树高度。
 */
export function canMoveNode(
  nodes: readonly ProjectNode[],
  nodeId: string,
  nextParentId: string | null,
): MoveCheck {
  const node = findNode(nodes, nodeId)
  if (!node) {
    return { ok: false, reason: 'NOT_FOUND' }
  }
  if (nextParentId === nodeId) {
    return { ok: false, reason: 'SELF' }
  }
  if (nextParentId !== null) {
    const descendants = descendantIds(nodes, nodeId)
    if (descendants.has(nextParentId)) {
      return { ok: false, reason: 'INTO_DESCENDANT' }
    }
  }
  // 目标父级层数（挂到根下记为 0 层）。
  const parentDepth = nextParentId === null ? 0 : depthOf(nodes, nextParentId)
  // 被拖节点移动后的新层数 = 父级层数 + 1（挂根下则为第 1 层）。
  const newBaseDepth = parentDepth + 1
  // 递归检查整棵子树在新位置的每一层是否都未超各自类型的上限。
  if (!fitsDepthLimit(nodes, nodeId, newBaseDepth)) {
    return { ok: false, reason: 'TOO_DEEP' }
  }
  return { ok: true }
}
/** 以某节点为根，其子树的最大相对深度（叶子为 0，有一个子目录为 1）。 */
export function subtreeDepth(nodes: readonly ProjectNode[], id: string): number {
  const children = nodes.filter(node => node.parentId === id)
  if (!children.length) {
    return 0
  }
  return 1 + Math.max(...children.map(child => subtreeDepth(nodes, child.id)))
}
/**
 * 某节点（连同它整棵子树）放到 `depth` 层里是否合规。
 *
 * 深度上限是**按每个节点自己的类型**判的：目录 ≤ MAX_NEST_DEPTH，
 * 文件 ≤ MAX_NEST_DEPTH + 1。所以要递归整棵子树逐节点检查，
 * 而不是拿一个统一的 maxDepth 去卡子树高度——否则「目录里带一个文件」
 * 拖到第 1 层目录下时，会被误判成 3 > 2（其实目录落在第 2 层、文件落在第 3 层，
 * 两个都刚好达标，应当放行）。
 */
function fitsDepthLimit(
  nodes: readonly ProjectNode[],
  id: string,
  depth: number,
): boolean {
  const node = findNode(nodes, id)
  if (!node) {
    return true
  }
  const limit = node.kind === 'file' ? MAX_NEST_DEPTH + 1 : MAX_NEST_DEPTH
  if (depth > limit) {
    return false
  }
  return nodes
    .filter(child => child.parentId === id)
    .every(child => fitsDepthLimit(nodes, child.id, depth + 1))
}


/**
 * 执行移动：改 parentId，并把 order 放到新同级末尾。
 *
 * 非法移动原样返回副本（调用方应先跑 canMoveNode 给用户反馈）。
 */
export function moveNode(
  nodes: readonly ProjectNode[],
  nodeId: string,
  nextParentId: string | null,
): ProjectNode[] {
  const check = canMoveNode(nodes, nodeId, nextParentId)
  if (!check.ok) {
    return [...nodes]
  }
  const order = nextOrder(nodes, nextParentId)
  return nodes.map(node =>
    node.id === nodeId ? { ...node, parentId: nextParentId, order } : node,
  )
}

// ---------------------------------------------------------------------------
// 容错解析 / 序列化
// ---------------------------------------------------------------------------

/** 解析 boundTree 元数据；字段不完整（缺 treeUri）时返回 null。 */
function parseBoundTree(raw: unknown): ProjectBoundTree | null {
  if (typeof raw !== 'object' || raw === null) {
    return null
  }
  const candidate = raw as Partial<ProjectBoundTree>
  if (typeof candidate.treeUri !== 'string' || candidate.treeUri.length === 0) {
    return null
  }
  return {
    treeUri: candidate.treeUri,
    displayName:
      typeof candidate.displayName === 'string' && candidate.displayName.length > 0
        ? candidate.displayName
        : '',
    canWrite: candidate.canWrite === true,
    scannedAt:
      typeof candidate.scannedAt === 'string' && candidate.scannedAt.length > 0
        ? candidate.scannedAt
        : new Date().toISOString(),
  }
}

function parseFileRef(raw: unknown): ProjectFileRef | null {
  if (typeof raw !== 'object' || raw === null) {
    return null
  }
  const candidate = raw as Partial<ProjectFileRef>
  if (typeof candidate.uri !== 'string' || candidate.uri.length === 0) {
    return null
  }
  const source =
    candidate.source === 'private' || candidate.source === 'created'
      ? candidate.source
      : 'indexed'
  return {
    uri: candidate.uri,
    fileName:
      typeof candidate.fileName === 'string' && candidate.fileName.length > 0
        ? candidate.fileName
        : candidate.uri,
    providerName: typeof candidate.providerName === 'string' ? candidate.providerName : '',
    persisted: candidate.persisted === true,
    source,
  }
}

function parseNode(raw: unknown): ProjectNode | null {
  if (typeof raw !== 'object' || raw === null) {
    return null
  }
  const candidate = raw as Partial<ProjectNode>
  if (typeof candidate.id !== 'string' || candidate.id.length === 0) {
    return null
  }
  const kind = candidate.kind === 'file' ? 'file' : 'folder'
  return {
    id: candidate.id,
    name:
      typeof candidate.name === 'string' && candidate.name.length > 0
        ? candidate.name
        : kind === 'folder'
          ? '未命名目录'
          : '未命名文件',
    kind,
    parentId: typeof candidate.parentId === 'string' ? candidate.parentId : null,
    pinned: candidate.pinned === true,
    order: typeof candidate.order === 'number' && Number.isFinite(candidate.order)
      ? candidate.order
      : 0,
    expanded: candidate.expanded !== false,
    ...(kind === 'file'
      ? { fileRef: parseFileRef(candidate.fileRef) }
      : // 镜像目录的实体 URI 必须持久化，否则重启后无法再对它写盘。
        typeof candidate.dirUri === 'string' && candidate.dirUri.length > 0
        ? { dirUri: candidate.dirUri }
        : {}),
  }
}

/**
 * 解析存储里的项目清单。
 *
 * 容错优先）：坏 JSON、非数组、缺 id 的节点一律丢弃；parentId 指向不存在的
 * 节点时把该节点提升到顶层，而不是连带整棵树一起丢——用户至少还能看到文件。
 */
export function parseProjects(value: string | null): ProjectRecord[] {
  if (!value) {
    return []
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(value)
  } catch {
    return []
  }
  if (!Array.isArray(parsed)) {
    return []
  }
  const seenProjects = new Set<string>()
  const projects: ProjectRecord[] = []
  for (const entry of parsed) {
    if (typeof entry !== 'object' || entry === null) {
      continue
    }
    const candidate = entry as Partial<ProjectRecord>
    if (typeof candidate.id !== 'string' || candidate.id.length === 0) {
      continue
    }
    if (seenProjects.has(candidate.id)) {
      continue
    }
    seenProjects.add(candidate.id)

    const rawNodes = Array.isArray(candidate.nodes) ? candidate.nodes : []
    const nodes: ProjectNode[] = []
    const seenNodes = new Set<string>()
    for (const rawNode of rawNodes) {
      const node = parseNode(rawNode)
      if (!node || seenNodes.has(node.id)) {
        continue
      }
      seenNodes.add(node.id)
      nodes.push(node)
    }
    // 悬挂的 parentId（父节点被丢弃了）提升为顶层。
    const repaired = nodes.map(node =>
      node.parentId !== null && !seenNodes.has(node.parentId)
        ? { ...node, parentId: null }
        : node,
    )

    const boundTree = parseBoundTree(candidate.boundTree)

    projects.push({
      id: candidate.id,
      name:
        typeof candidate.name === 'string' && candidate.name.length > 0
          ? candidate.name
          : '未命名项目',
      pinned: candidate.pinned === true,
      expanded: candidate.expanded !== false,
      nodes: repaired,
      createdAt:
        typeof candidate.createdAt === 'string' && candidate.createdAt.length > 0
          ? candidate.createdAt
          : new Date().toISOString(),
      schemaVersion: PROJECT_SCHEMA_VERSION,
      ...(boundTree ? { boundTree } : {}),
    })
  }
  return projects
}

export function serializeProjects(projects: readonly ProjectRecord[]): string {
  return JSON.stringify(projects)
}

export function readProjects(storage: ProjectStorage): ProjectRecord[] {
  try {
    return parseProjects(storage.getItem(PROJECT_STORAGE_KEY))
  } catch {
    return []
  }
}

export function writeProjects(
  projects: readonly ProjectRecord[],
  storage: ProjectStorage,
): void {
  try {
    storage.setItem(PROJECT_STORAGE_KEY, serializeProjects(projects))
  } catch {
    // 存储不可用时静默降级：项目只在本次会话有效，不阻断使用。
  }
}

/** 加入或整条替换一个项目（按 id）。 */
export function upsertProject(
  projects: readonly ProjectRecord[],
  record: ProjectRecord,
): ProjectRecord[] {
  const index = projects.findIndex(item => item.id === record.id)
  if (index < 0) {
    return [...projects, record]
  }
  const next = [...projects]
  next[index] = record
  return next
}

export function removeProject(
  projects: readonly ProjectRecord[],
  id: string,
): ProjectRecord[] {
  return projects.filter(item => item.id !== id)
}

/** 项目列表的排序：置顶优先。 */
export function sortProjects(projects: readonly ProjectRecord[]): ProjectRecord[] {
  return [...projects].sort((left, right) => {
    if (left.pinned !== right.pinned) {
      return left.pinned ? -1 : 1
    }
    return left.createdAt.localeCompare(right.createdAt)
  })
}

/**
 * 把项目内的文件节点摊平成搜索条目池，供顶部搜索框复用。
 *
 * 搜索结果需要展示「它在哪个项目 / 哪条路径下」，所以这里把目录路径拼出来。
 */
export interface ProjectSearchEntry {
  id: string
  projectId: string
  projectName: string
  nodeId: string
  name: string
  relativePath: string
  fileRef: ProjectFileRef | null
}

export function flattenProjectFiles(
  project: ProjectRecord,
): ProjectSearchEntry[] {
  const out: ProjectSearchEntry[] = []
  for (const node of project.nodes) {
    if (node.kind !== 'file') {
      continue
    }
    out.push({
      id: `${project.id}::${node.id}`,
      projectId: project.id,
      projectName: project.name,
      nodeId: node.id,
      name: node.name,
      relativePath: nodePath(project.nodes, node),
      fileRef: node.fileRef ?? null,
    })
  }
  return out
}

/** 节点的完整路径（相对项目根），例如 `大纲类/世界观/剧情.md`。 */
export function nodePath(nodes: readonly ProjectNode[], node: ProjectNode): string {
  const segments = [node.name]
  let current: ProjectNode | null = node
  const guard = new Set<string>([node.id])
  while (current && current.parentId !== null) {
    const parent: ProjectNode | null = findNode(nodes, current.parentId)
    if (!parent || guard.has(parent.id)) {
      break
    }
    guard.add(parent.id)
    segments.unshift(parent.name)
    current = parent
  }
  return segments.join('/')
}

/** 从 SAF 扫描结果（或单文件选择结果）构造一个「索引外部文件」的引用。
 *  只依赖 uri / name 两个字段，故签名放宽到最小结构，两种来源都能传。 */
export function toIndexedFileRef(file: { uri: string; name: string }): ProjectFileRef {
  return {
    uri: file.uri,
    fileName: file.name,
    providerName: file.uri.split('/')[2] ?? '',
    persisted: true,
    source: 'indexed',
  }
}

/**
 * 把一个「已绑定实体目录」里扫描到的文件列表，还原成项目树节点数组。
 *
 * 输入来自原生 `listWorkspaceDocuments`（递归扫描，带 relativePath）。这里按
 * relativePath 的各级目录名建虚拟目录、末级挂文件，结构与硬盘一一对应。
 *
 * 关键约束：
 *   - 纯函数，不依赖任何原生调用，便于单测；
 *   - 目录最多 MAX_NEST_DEPTH 层——超出部分整体丢弃（避免出现「不可能编辑到」
 *     的深层节点），但不超过限制的正常平移；
 *   - 同路径复用同一个目录节点（按 posix 路径归一，兼容 Windows 反斜杠）；
 *   - 返回的节点里文件都带 fileRef（source='indexed'），uri 即 SAF document URI，
 *     可直接被 readWorkspaceFileByUri / writeWorkspaceFileByUri 消费。
 *
 * @param entries 原生扫描条目：{ uri, name, relativePath }。
 * @param options.idFactory 可注入的 id 生成器（单测里给确定性 id）。
 */
export interface MirrorEntry {
  uri: string
  name: string
  /** 相对绑定目录的路径（目录分隔符可能是 '/' 或 '\'）。 */
  relativePath: string
  /** 该条目是不是目录；缺省视为文件（老数据兼容）。 */
  isDirectory?: boolean
}

export function buildMirrorNodes(
  entries: readonly MirrorEntry[],
  options: { idFactory?: () => string } = {},
): ProjectNode[] {
  const makeId =
    options.idFactory ??
    (() => {
      idCounter += 1
      return `node-mirror-${idCounter.toString(36)}`
    })

  const out: ProjectNode[] = []
  /** 已创建目录的相对路径 → 节点；'' 代表项目根。 */
  const folderByPath = new Map<string, ProjectNode>()
  /** 硬盘上目录的相对路径 → 其 document URI（用于给镜像目录补 dirUri）。 */
  const dirUriByPath = new Map<string, string>()

  /** 与原生扫描保持一致的忽略目录：路径里任何一段命中即整条丢弃。 */
  const isIgnoredRelative = (relative: string): boolean =>
    relative
      .split('/')
      .some(segment => segment.toLowerCase() === '.versions')

  /** 依次确保各级目录存在，返回末级目录节点（深空则返回 null）。 */
  const ensureFolderChain = (segments: readonly string[]): ProjectNode | null => {
    if (segments.length === 0) {
      return null
    }
    // 超过目录深度上限：整条丢弃。
    if (segments.length > MAX_NEST_DEPTH) {
      return null
    }
    let parentPath = ''
    let lastNode: ProjectNode | null = null
    for (const segment of segments) {
      const path = parentPath ? `${parentPath}/${segment}` : segment
      const existing = folderByPath.get(path)
      if (existing) {
        lastNode = existing
        parentPath = path
        continue
      }
      const parentId = parentPath ? folderByPath.get(parentPath)?.id ?? null : null
      const node: ProjectNode = {
        id: makeId(),
        name: segment,
        kind: 'folder',
        parentId,
        pinned: false,
        order: out.filter(item => item.parentId === parentId).length,
        expanded: true,
      }
      out.push(node)
      folderByPath.set(path, node)
      lastNode = node
      parentPath = path
    }
    return lastNode
  }

  // 第一遍：先登记所有目录条目的 URI，这样即使目录条目排在文件之后，
  // 生成目录节点时也能立刻带上 dirUri。
  for (const entry of entries) {
    if (entry.isDirectory !== true) {
      continue
    }
    const path = normalizeRelative(entry.relativePath || entry.name)
    if (path && !isIgnoredRelative(path)) {
      dirUriByPath.set(path, entry.uri)
    }
  }

  /** 给刚建好的目录节点补 dirUri（如果硬盘扫描里有它的地址）。 */
  const attachDirUri = (node: ProjectNode, path: string) => {
    const uri = dirUriByPath.get(path)
    if (uri) {
      node.dirUri = uri
    }
  }

  for (const entry of entries) {
    const relative = normalizeRelative(entry.relativePath || entry.name)
    if (!relative || isIgnoredRelative(relative)) {
      continue
    }
    const rawSegments = relative.split('/').filter(segment => segment.length > 0)
    if (rawSegments.length === 0) {
      continue
    }

    if (entry.isDirectory === true) {
      // 目录条目：建目录链，并给它补上 dirUri。
      if (rawSegments.length > MAX_NEST_DEPTH) {
        continue
      }
      const node = ensureFolderChain(rawSegments)
      if (node) {
        attachDirUri(node, relative)
      }
      continue
    }

    const name = rawSegments[rawSegments.length - 1]
    const parentSegments = rawSegments.slice(0, -1)
    // 目录链深度 = 文件所在层级；目录允许 MAX_NEST_DEPTH 层，文件挂在其下。
    if (parentSegments.length > MAX_NEST_DEPTH) {
      continue
    }
    const parentNode = parentSegments.length ? ensureFolderChain(parentSegments) : null
    // ensureFolderChain 返回 null 有两种情况：根（parentSegments 空）或超深被丢。
    if (parentSegments.length > 0 && parentNode === null) {
      continue
    }
    const parentId = parentNode?.id ?? null
    const fileNode: ProjectNode = {
      id: makeId(),
      name,
      kind: 'file',
      parentId,
      pinned: false,
      order: out.filter(item => item.parentId === parentId).length,
      expanded: false,
      fileRef: toIndexedFileRef({ uri: entry.uri, name }),
    }
    out.push(fileNode)
  }
  return out
}

/** 归一化相对路径：反斜杠转正斜杠、去首斜杠、去空白。空串返回 ''。 */
function normalizeRelative(value: string | undefined): string {
  if (!value) {
    return ''
  }
  return value.replace(/\\/g, '/').replace(/^\/+/, '').trim()
}

/** 判断项目是否处于「已绑定实体目录」状态（有树 URI 即算）。 */
export function isBoundProject(project: ProjectRecord): boolean {
  return Boolean(project.boundTree && project.boundTree.treeUri)
}

/**
 * 绑定项目下，一个「目录节点」对应硬盘上的哪个 document URI。
 *
 * 镜像目录节点在 buildMirrorNodes 里就把自己的 document URI 存进了 `dirUri`
 * （来自原生扫描的目录条目），所以这里直接读即可——**不做任何从文件 URI 反推
 * 父目录的猜测**：SAF 的 documentId 由 provider 内部编码，父目录 id 与子项 id
 * 无必然联系，反推必然不可靠。
 *
 * 根目录（folderId === null）返回绑定树自己的 treeUri。
 *
 * @returns 该目录对应的 document URI；未知目录返回 null（调用方应拒绝写操作）。
 */
export function boundFolderUri(project: ProjectRecord, folderId: string | null): string | null {
  const rootUri = project.boundTree?.treeUri ?? null
  if (folderId === null) {
    return rootUri
  }
  const node = findNode(project.nodes, folderId)
  if (!node || node.kind !== 'folder') {
    return rootUri
  }
  // 镜像目录带 dirUri：直接用。老的镜像数据（升级前建的）没有该字段，
  // 退回根 URI——虽然不够精确，但至少让用户能在根下继续操作，不至于卡死。
  return node.dirUri ?? rootUri
}

/**
 * 为「在绑定目录里新建的实体文件」构造 fileRef。
 *
 * 与 toIndexedFileRef 的区别：providerName 从绑定树 URI 取（新建文件必然在
 * 绑定树内），source 仍是 'indexed'（它确实是硬盘上的实体文件，不是副本）。
 */
export function boundFileRef(
  treeUri: string,
  file: { uri: string; name: string },
): ProjectFileRef {
  const authority = treeUri.split('/')[2] ?? ''
  return {
    uri: file.uri,
    fileName: file.name,
    providerName: authority,
    persisted: true,
    source: 'indexed',
  }
}
