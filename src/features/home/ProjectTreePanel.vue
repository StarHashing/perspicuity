<script setup lang="ts">
/**
 * 虚拟项目树面板。
 *
 * 数据形态是扁平的 ProjectNode[]（parentId 描述层级），所以这里**不递归渲染**，
 * 而是按「展开状态」把可见节点摊平成一维列表再 v-for——递归组件在 Vue 里要处理
 * 组件自引用与 key 冲突，摊平后一切都只是数组操作，拖拽落点计算也直观得多。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from '../../lib/i18n'
import {
  checkWorkspacePermission,
  createWorkspaceDirectory,
  createWorkspaceFile,
  deleteWorkspaceDocument,
  getWorkspaceErrorCode,
  isWorkspaceAvailable,
  listWorkspaceDocuments,
  probeWorkspaceWrite,
  moveWorkspaceDocument,
  pickWorkspaceDirectory,
  pickWorkspaceFile,
  renameWorkspaceDocument,
} from '../../lib/workspace'
import {
  exportProjectArchive as writeArchiveToDir,
  importProjectArchive as readArchiveFromFile,
  isProjectArchiveAvailable,
  onProjectArchiveOpened,
} from '../../lib/projectArchiveBridge'
import {
  parseProjectArchive,
  safeArchiveFolderName,
  serializeProject,
} from './projectArchive'
import {
  boundFileRef,
  boundFolderUri,
  buildMirrorNodes,
  canHostChildren,
  canHostFiles,
  canMoveNode,
  childrenOf,
  createFileNode,
  createFolderNode,
  createProjectRecord,
  findNode,
  isBoundProject,
  moveNode,
  readProjects,
  removeNode,
  removeProject,
  renameNode,
  sortProjects,
  toggleExpanded,
  togglePinned,
  toIndexedFileRef,
  upsertProject,
  writeProjects,
  type ProjectBoundTree,
  type ProjectNode,
  type ProjectRecord,
} from './projectStore'
import { projectTreeText } from './projectTreeText'
import { useLongPress } from './useLongPress'
import { appLogger } from '../../lib/logger'
import type { WorkspaceOpenResult } from '../../lib/workspace'
import PromptDialog from './PromptDialog.vue'
import type { PromptDialogApi } from './PromptDialog.vue'

const props = defineProps<{
  /** 打开一个已索引的文件（交给编辑器）。 */
  disabled?: boolean
}>()

const emit = defineEmits<{
  /** 打开文件：把 SAF 结果透传给上层（复用 FilesTab 的通道）。 */
  openIndexedFile: [uri: string]
  /** 打开导入归档来的虚拟文件：内容在内存里，直接交给编辑器。 */
  openArchivedFile: [payload: WorkspaceOpenResult]
}>()

const { locale } = useI18n()
const T = computed(() => projectTreeText(locale.value))
/** 自绘输入/确认弹窗（替代 window.prompt / window.confirm）。 */
const dialogRef = ref<PromptDialogApi | null>(null)

const projects = ref<ProjectRecord[]>([])
const activeProjectId = ref<string | null>(null)
const busy = ref(false)
const notice = ref<string | null>(null)
/**
 * 只读诊断信息（临时排查用）。
 *
 * 当某个绑定项目被判定为只读时，把 probeWrite 的完整结果缓存在这里，
 * 界面会常驻显示「canWrite / grantWrite / reason / treeUri」。这能让用户
 * 一眼看出「只读」到底是授权记录判错、还是 provider 真的拒绝写。
 */
const readOnlyDiagnostic = ref<string | null>(null)
/** 外部分享归档事件监听句柄，卸载时摘除。 */
let archiveListener: { remove: () => Promise<void> } | null = null

/** 正在长按 / 打开菜单的节点。 */
const menuNodeId = ref<string | null>(null)
/** 菜单归属的项目 id（菜单可能在非 active 项目里打开）。 */
const menuProjectId = ref<string | null>(null)
/**
 * 拖拽模式开关。进入后，按住任意节点即可直接拖动。
 * 注意：这是"模式"，与"正在拖哪个节点"是两件事——之前把二者混成 draggingId，
 * 导致进入模式后再按节点时事件仍走长按流程，拖不动。
 */
const dragArmed = ref(false)
/** 正在被拖动的节点 id；null 表示当前没有按下拖动。 */
const draggingId = ref<string | null>(null)
/** 拖拽过程中指针悬停到的目标节点 id（含 'ROOT' 特例哨兵）。 */
const dropTargetId = ref<string | null>(null)
/** 当前拖拽是否合法（决定高亮成"可放"还是"禁止"）。 */
const dropLegal = ref(true)

const ROOT_SENTINEL = '__project_root__'

const activeProject = computed(
  () => projects.value.find(item => item.id === activeProjectId.value) ?? null,
)

const sortedProjects = computed(() => sortProjects(projects.value))

/** 摊平后的可见行：[{node, depth}]。depth 从 1 起（根的子节点）。 */
interface VisibleRow {
  node: ProjectNode
  depth: number
  hasChildren: boolean
}

const visibleRows = computed<VisibleRow[]>(() => {
  const project = activeProject.value
  if (!project) {
    return []
  }
  const rows: VisibleRow[] = []
  const walk = (parentId: string | null, depth: number) => {
    for (const node of childrenOf(project.nodes, parentId)) {
      const hasChildren = project.nodes.some(child => child.parentId === node.id)
      rows.push({ node, depth, hasChildren })
      if (node.kind === 'folder' && node.expanded && hasChildren) {
        walk(node.id, depth + 1)
      }
    }
  }
  walk(null, 1)
  return rows
})

const fileCountOf = (project: ProjectRecord) =>
  project.nodes.filter(node => node.kind === 'file').length

function persist() {
  writeProjects(projects.value, window.localStorage)
}

function load() {
  projects.value = readProjects(window.localStorage)
  activeProjectId.value = projects.value.length ? sortProjects(projects.value)[0].id : null
}

function updateProject(id: string, mutate: (project: ProjectRecord) => ProjectRecord) {
  const target = projects.value.find(item => item.id === id)
  if (!target) {
    return
  }
  projects.value = upsertProject(projects.value, mutate(target))
  persist()
}

function setNodes(projectId: string, nodes: ProjectNode[]) {
  updateProject(projectId, project => ({ ...project, nodes }))
}

// ---------------------------------------------------------------------------
// 项目管理
// ---------------------------------------------------------------------------

async function newProject() {
  const dialog = dialogRef.value
  if (!dialog) {
    return
  }
  const name = await dialog.askText({
    title: T.value.projectNamePrompt,
    initial: `${T.value.projects} ${projects.value.length + 1}`,
    confirmLabel: T.value.confirm,
    cancelLabel: T.value.cancel,
  })
  if (!name || !name.trim()) {
    return
  }
  const record = createProjectRecord(name.trim())
  projects.value = upsertProject(projects.value, record)
  persist()
  activeProjectId.value = record.id
}

function toggleProjectPin(project: ProjectRecord) {
  updateProject(project.id, item => ({ ...item, pinned: !item.pinned }))
}

function openProjectMenu(project: ProjectRecord) {
  activeProjectId.value = project.id
  menuProjectId.value = project.id
  menuNodeId.value = ROOT_SENTINEL
}

// ---------------------------------------------------------------------------
// 长按识别
//
// 复用 features/home/useLongPress（已有单测覆盖）：位移超阈值取消、长按后
// 吞掉一次 click。项目 chip 和树节点各用一个实例，因为回调要带不同类型。
// ---------------------------------------------------------------------------

const rootLongPress = useLongPress({
  onLongPress: id => {
    const project = projects.value.find(item => item.id === id)
    if (project) {
      openProjectMenu(project)
    }
  },
})

const nodeLongPress = useLongPress({
  onLongPress: id => {
    const project = activeProject.value
    const node = project ? findNode(project.nodes, id) : null
    if (project && node) {
      openNodeMenu(project, node)
    }
  },
})

/** 节点按下分流：拖拽模式 → 拖动；普通模式 → 长按检测（弹菜单）。 */
function onNodePointerDown(event: PointerEvent, nodeId: string) {
  if (dragArmed.value) {
    startDrag(event, nodeId)
    return
  }
  nodeLongPress.onPointerDown(event, nodeId)
}

/**
 * 节点指针移动分流。
 *
 * 修复：以前这里无论什么模式都只接拖拽用的 onPointerMove，导致**普通模式**下
 * 长按计时器既不因滑动取消、也不因抬手清理——用户上下滑动列表时手指压住不动
 * 超过 500ms，就误弹出「一开始按到的那个节点」的菜单。
 * 现在普通模式走 longpress 的位移取消逻辑；拖拽模式走落点判定。
 */
function onNodePointerMove(event: PointerEvent) {
  if (dragArmed.value) {
    onPointerMove(event)
    return
  }
  nodeLongPress.onPointerMove(event)
}

/** 节点指针抬起分流：拖拽模式提交移动；普通模式结束长按计时。 */
function onNodePointerUp(event: PointerEvent) {
  if (dragArmed.value) {
    void onPointerUp(event)
    return
  }
  nodeLongPress.onPointerEnd(event)
}

/** 节点指针被系统打断分流：两种模式都只做清理，不提交。 */
function onNodePointerCancel(event: PointerEvent) {
  if (dragArmed.value) {
    onPointerCancel(event)
    return
  }
  nodeLongPress.onPointerEnd(event)
}
/** 项目 chip 的 click：长按弹过菜单时吞掉这次 click，否则切到该项目。 */
function consumeRootClick(project: ProjectRecord) {
  if (rootLongPress.consumeLongPressClick()) {
    return
  }
  activeProjectId.value = project.id
}
/** 树节点的 click：长按弹过菜单时吞掉；目录折叠/展开，文件打开。 */
function consumeNodeClick(node: ProjectNode) {
  // 拖拽模式下点击不触发折叠/打开，避免与拖动混淆。
  if (dragArmed.value) {
    return
  }
  if (nodeLongPress.consumeLongPressClick()) {
    return
  }
  if (node.kind === 'folder') {
    toggleNodeExpanded(node)
    return
  }
  void openFile(node)
}

// ---------------------------------------------------------------------------
// 节点操作
// ---------------------------------------------------------------------------

function openNodeMenu(project: ProjectRecord, node: ProjectNode) {
  activeProjectId.value = project.id
  menuProjectId.value = project.id
  menuNodeId.value = node.id
}

const menuNode = computed<ProjectNode | null>(() => {
  const project = projects.value.find(item => item.id === menuProjectId.value)
  if (!project || !menuNodeId.value || menuNodeId.value === ROOT_SENTINEL) {
    return null
  }
  return findNode(project.nodes, menuNodeId.value)
})

const menuIsProjectRoot = computed(() => menuNodeId.value === ROOT_SENTINEL)

/** 菜单里"创建子目录/子文件"是否还可用（目录已到第 2 层就禁用）。 */
const menuCanHostChildren = computed(() => {
  const project = projects.value.find(item => item.id === menuProjectId.value)
  if (!project) {
    return false
  }
  if (menuIsProjectRoot.value) {
    return true
  }
  const node = menuNode.value
  if (!node || node.kind !== 'folder') {
    return false
  }
  return canHostChildren(project.nodes, node.id)
})
/** 能否在此处放「文件」：比目录多允许一层，最深目录内也能放文件。 */
const menuCanHostFiles = computed(() => {
  const project = projects.value.find(item => item.id === menuProjectId.value)
  if (!project) {
    return false
  }
  if (menuIsProjectRoot.value) {
    return true
  }
  const node = menuNode.value
  if (!node || node.kind !== 'folder') {
    return false
  }
  return canHostFiles(project.nodes, node.id)
})

/**
 * 当前菜单所属项目是「绑定项目且实体目录不可写」时为 true。
 *
 * 只读绑定目录下禁止创建/删除/重命名/移动——provider 没给写权限，
 * 写了也会失败，不如直接把按钮置灰并提示，避免用户白忙一场。
 */
const menuBoundReadOnly = computed(() => {
  const project = projects.value.find(item => item.id === menuProjectId.value)
  if (!project || !isBoundProject(project)) {
    return false
  }
  return project.boundTree?.canWrite !== true
})

function closeMenu() {
  menuNodeId.value = null
  menuProjectId.value = null
}

function toggleNodeExpanded(node: ProjectNode) {
  const project = activeProject.value
  if (!project || node.kind !== 'folder') {
    return
  }
  setNodes(project.id, toggleExpanded(project.nodes, node.id))
}

function toggleNodePinned() {
  const project = projects.value.find(item => item.id === menuProjectId.value)
  const node = menuNode.value
  if (!project || !node) {
    return
  }
  setNodes(project.id, togglePinned(project.nodes, node.id))
  closeMenu()
}

async function renameMenuNode() {
  const dialog = dialogRef.value
  if (!dialog) {
    return
  }
  const project = projects.value.find(item => item.id === menuProjectId.value)
  if (!project) {
    return
  }
  if (menuIsProjectRoot.value) {
    const renamed = await dialog.askText({
      title: T.value.renamePrompt,
      initial: project.name,
      confirmLabel: T.value.confirm,
      cancelLabel: T.value.cancel,
    })
    if (renamed && renamed.trim()) {
      updateProject(project.id, item => ({ ...item, name: renamed.trim() }))
    }
    closeMenu()
    return
  }
  const node = menuNode.value
  if (!node) {
    return
  }
  const next = await dialog.askText({
    title: T.value.renamePrompt,
    initial: node.name,
    confirmLabel: T.value.confirm,
    cancelLabel: T.value.cancel,
  })
  if (!next || !next.trim() || next.trim() === node.name) {
    closeMenu()
    return
  }
  const nextName = next.trim()
  // 绑定项目：先在硬盘上重命名实体项，成功再改镜像节点名。
  if (isBoundProject(project)) {
    closeMenu()
    const uri = boundNodeUri(project, node)
    if (!uri || !boundCanWrite(project)) {
      notice.value = T.value.writeDenied
      return
    }
    busy.value = true
    try {
      const renamed = await renameWorkspaceDocument(uri, nextName)
      if (!renamed) {
        notice.value = T.value.writeFailed
        return
      }
      // 文件节点还要同步 fileRef（uri 可能因 provider 实现而变）。
      setNodes(
        project.id,
        renameNode(project.nodes, node.id, nextName).map(item =>
          item.id === node.id && item.kind === 'file' && item.fileRef
            ? {
                ...item,
                fileRef: boundFileRef(project.boundTree!.treeUri, {
                  uri: renamed.uri,
                  name: nextName,
                }),
              }
            : item,
        ),
      )
      notice.value = T.value.writeDone.replace('{name}', nextName)
    } catch (error) {
      notice.value = writeFailureText(error, node.name)
    } finally {
      busy.value = false
    }
    return
  }
  setNodes(project.id, renameNode(project.nodes, node.id, nextName))
  closeMenu()
}

async function createFolderUnderMenu() {
  const dialog = dialogRef.value
  if (!dialog) {
    return
  }
  const project = projects.value.find(item => item.id === menuProjectId.value)
  if (!project) {
    return
  }
  const parentId = menuIsProjectRoot.value ? null : menuNode.value?.id ?? null
  const name = await dialog.askText({
    title: T.value.folderNamePrompt,
    confirmLabel: T.value.confirm,
    cancelLabel: T.value.cancel,
  })
  if (!name || !name.trim()) {
    closeMenu()
    return
  }
  const folderName = name.trim()
  // 绑定项目：先落盘、成功再改镜像树——写盘失败时内存树保持不变。
  if (isBoundProject(project)) {
    closeMenu()
    const parentUri = boundFolderUri(project, parentId)
    if (!parentUri || !boundCanWrite(project)) {
      notice.value = T.value.writeDenied
      return
    }
    busy.value = true
    try {
      const created = await createWorkspaceDirectory(parentUri, folderName)
      if (!created) {
        appLogger.warn('createFolderUnderMenu result', {
          projectId: project.id,
          parentUri,
          folderName,
          ok: false,
          reason: 'null-result',
        })
        notice.value = T.value.writeFailed
        return
      }
      const node = createFolderNode(project.nodes, parentId, folderName)
      if (!node) {
        notice.value = T.value.depthLimit
        return
      }
      // 目录节点记住自己的 document URI，后续才能往里放东西。
      node.dirUri = created.uri
      setNodes(project.id, [...project.nodes, node])
      appLogger.info('createFolderUnderMenu result', {
        projectId: project.id,
        parentUri,
        folderName,
        ok: true,
        uri: created.uri,
      })
      notice.value = T.value.writeDone.replace('{name}', folderName)
    } catch (error) {
      appLogger.warn('createFolderUnderMenu error', {
        projectId: project.id,
        parentUri,
        folderName,
        code: getWorkspaceErrorCode(error),
        message: error instanceof Error ? error.message : String(error),
      })
      notice.value = writeFailureText(error, folderName)
    } finally {
      busy.value = false
    }
    return
  }
  const node = createFolderNode(project.nodes, parentId, folderName)
  if (!node) {
    notice.value = T.value.depthLimit
    closeMenu()
    return
  }
  setNodes(project.id, [...project.nodes, node])
  closeMenu()
}
async function createFileUnderMenu() {
  const dialog = dialogRef.value
  if (!dialog) {
    return
  }
  const project = projects.value.find(item => item.id === menuProjectId.value)
  if (!project) {
    return
  }
  const parentId = menuIsProjectRoot.value ? null : menuNode.value?.id ?? null
  const name = await dialog.askText({
    title: T.value.fileNamePrompt,
    confirmLabel: T.value.confirm,
    cancelLabel: T.value.cancel,
  })
  if (!name || !name.trim()) {
    closeMenu()
    return
  }
  const fileName = /\.\w+$/.test(name.trim()) ? name.trim() : `${name.trim()}.md`
  // 绑定项目：先在硬盘上建实体文件，成功再登记镜像节点。
  if (isBoundProject(project)) {
    closeMenu()
    const parentUri = boundFolderUri(project, parentId)
    if (!parentUri || !boundCanWrite(project)) {
      notice.value = T.value.writeDenied
      return
    }
    busy.value = true
    try {
      const created = await createWorkspaceFile(parentUri, fileName, '')
      if (!created) {
        notice.value = T.value.writeFailed
        return
      }
      const ref = boundFileRef(project.boundTree!.treeUri, created)
      const node = createFileNode(project.nodes, parentId, fileName, ref)
      if (!node) {
        notice.value = T.value.depthLimit
        return
      }
      setNodes(project.id, [...project.nodes, node])
      notice.value = T.value.writeDone.replace('{name}', fileName)
    } catch (error) {
      notice.value = writeFailureText(error, fileName)
    } finally {
      busy.value = false
    }
    return
  }
  const node = createFileNode(project.nodes, parentId, fileName)
  if (!node) {
    notice.value = T.value.depthLimit
    closeMenu()
    return
  }
  setNodes(project.id, [...project.nodes, node])
  closeMenu()
}

/** 添加已有文件：走原生单文件授权，只存索引，不复制内容。 */
async function addExistingFileUnderMenu() {
  const project = projects.value.find(item => item.id === menuProjectId.value)
  if (!project || busy.value) {
    closeMenu()
    return
  }
  const parentId = menuIsProjectRoot.value ? null : menuNode.value?.id ?? null
  busy.value = true
  notice.value = null
  try {
    const picked = await pickWorkspaceFile()
    if (!picked) {
      return
    }
    const ref = toIndexedFileRef(picked)
    const node = createFileNode(project.nodes, parentId, picked.name, ref)
    if (!node) {
      notice.value = T.value.depthLimit
      return
    }
    setNodes(project.id, [...project.nodes, node])
  } catch (error) {
    const code = getWorkspaceErrorCode(error)
    notice.value = code === 'FOLDER_PICKER_UNAVAILABLE' ? T.value.noPicker : T.value.pickFailed
  } finally {
    busy.value = false
    closeMenu()
  }
}

// ---------------------------------------------------------------------------
// 绑定实体目录（单向镜像 + 直读直写）
//
// 设计：绑定后项目树 = 该目录的「镜像」——结构由扫描结果决定，文件内容直读
// 直写（不存副本）。实体文件始终躺在用户存储上，App 只记「它在哪个目录里」。
// 「刷新」把硬盘上的增删单向同步进项目树；「解除绑定」保留当前树为普通项目。
// ---------------------------------------------------------------------------

/** 以绑定目录为源，重新扫描并覆盖项目树的节点（保留项目名与绑定信息）。 */
async function syncBoundTree(project: ProjectRecord, bound: ProjectBoundTree): Promise<number> {
  const { files } = await listWorkspaceDocuments(bound.treeUri)
  const nodes = buildMirrorNodes(files)
  updateProject(project.id, item => ({
    ...item,
    nodes,
    boundTree: { ...bound, scannedAt: new Date().toISOString() },
  }))
  return nodes.filter(node => node.kind === 'file').length
}

/** 把当前项目绑定到一个实体目录，并立即扫描生成镜像树。 */
async function bindProjectToDirectory() {
  const project = projects.value.find(item => item.id === menuProjectId.value)
  closeMenu()
  if (!project || busy.value) {
    return
  }
  if (!isWorkspaceAvailable()) {
    notice.value = T.value.noPicker
    return
  }
  busy.value = true
  notice.value = null
  try {
    const dir = await pickWorkspaceDirectory()
    if (!dir) {
      return
    }
    const bound: ProjectBoundTree = {
      treeUri: dir.treeUri,
      displayName: dir.displayName,
      canWrite: dir.canWrite,
      scannedAt: new Date().toISOString(),
    }
    notice.value = T.value.bindingWait
    const { files } = await listWorkspaceDocuments(dir.treeUri)
    const nodes = buildMirrorNodes(files)
    updateProject(project.id, item => ({
      ...item,
      name: item.name || dir.displayName,
      nodes,
      boundTree: { ...bound, scannedAt: new Date().toISOString() },
    }))
    const count = nodes.filter(node => node.kind === 'file').length
    notice.value = count
      ? T.value.bindDone.replace('{name}', dir.displayName).replace('{count}', String(count))
      : T.value.bindEmpty
  } catch (error) {
    const code = getWorkspaceErrorCode(error)
    notice.value = code === 'FOLDER_PICKER_UNAVAILABLE' ? T.value.noPicker : T.value.bindFailed
  } finally {
    busy.value = false
  }
}

/** 刷新已绑定项目：先校验授权仍在，再重扫硬盘同步树。 */
async function refreshBoundProject() {
  const project = projects.value.find(item => item.id === menuProjectId.value)
  closeMenu()
  if (!project || !project.boundTree || busy.value) {
    return
  }
  busy.value = true
  notice.value = T.value.bindingWait
  try {
    const state = await checkWorkspacePermission(project.boundTree.treeUri)
    if (!state.granted) {
      notice.value = T.value.refreshFailed
      return
    }
    const count = await syncBoundTree(project, project.boundTree)
    notice.value = T.value.refreshDone.replace('{count}', String(count))
  } catch {
    notice.value = T.value.refreshFailed
  } finally {
    busy.value = false
  }
}

/**
 * 重新授权：在当前绑定的**同一目录**上重新走一次 SAF 目录选择，
 * 目的是补申请写权限。
 *
 * 背景：Android 上「补一个写权限」没有 API 可直接申请——SAF 的授权来自
 * `ACTION_OPEN_DOCUMENT_TREE` 那次用户点击。若最初绑定某个 provider 时系统
 * 只给了读，或用户当时没勾「允许修改」，唯一正规途径就是让用户重选一次目录，
 * 系统会带上写 flag 重新弹确认框；用户同意后我们 `takePersistableUriPermission`
 * 升级为读写，再刷新 canWrite 与镜像树。
 *
 * 用户重选的时候**需选同一个目录**，否则相当于换绑，这里用 displayName 做提示。
 */
async function reauthorizeBoundProject() {
  const project = projects.value.find(item => item.id === menuProjectId.value)
  closeMenu()
  if (!project || !project.boundTree || busy.value) {
    return
  }
  if (!isWorkspaceAvailable()) {
    notice.value = T.value.noPicker
    return
  }
  const previousUri = project.boundTree.treeUri
  const previousName = project.boundTree.displayName
  busy.value = true
  notice.value = T.value.reauthorizeWait.replace('{name}', previousName)
  try {
    const dir = await pickWorkspaceDirectory()
    if (!dir) {
      // 用户取消：保留原绑定，静默恢复提示。
      notice.value = null
      return
    }
    // 用户可能换成别的目录了——那就当一次普通换绑处理，不强行判定错误。
    const bound: ProjectBoundTree = {
      treeUri: dir.treeUri,
      displayName: dir.displayName,
      canWrite: dir.canWrite,
      scannedAt: new Date().toISOString(),
    }
    // 拿新授权后立刻做一次真实写探测：授权记录可能因 provider 语义差异仍判只读，
    // 探测能给出「到底能不能写」这个最终答案，并据此修正 canWrite。
    const probe = await probeWorkspaceWrite(dir.treeUri)
    bound.canWrite = probe.canWrite
    readOnlyDiagnostic.value = [
      `source=reauthorize`,
      `reason=${probe.reason}`,
      `canWrite=${probe.canWrite}`,
      `grantWrite=${probe.grantWrite}`,
      (probe as { detail?: string }).detail ? `detail=${(probe as { detail?: string }).detail}` : '',
      `treeUri=${dir.treeUri}`,
    ]
      .filter(Boolean)
      .join(' | ')
    appLogger.warn('reauthorizeBoundProject result', {
      previousUri,
      treeUri: dir.treeUri,
      displayName: dir.displayName,
      reason: probe.reason,
      canWrite: probe.canWrite,
      grantWrite: probe.grantWrite,
      detail: (probe as { detail?: string }).detail,
    })

    const { files } = await listWorkspaceDocuments(dir.treeUri)
    const nodes = buildMirrorNodes(files)
    updateProject(project.id, item => ({
      ...item,
      nodes,
      boundTree: { ...bound, scannedAt: new Date().toISOString() },
    }))
    const changed = dir.treeUri !== previousUri
    notice.value = probe.canWrite
      ? changed
        ? T.value.reauthorizeRebound.replace('{name}', dir.displayName)
        : T.value.reauthorizeDone
      : T.value.reauthorizeStillReadOnly
  } catch (error) {
    const code = getWorkspaceErrorCode(error)
    notice.value = code === 'FOLDER_PICKER_UNAVAILABLE' ? T.value.noPicker : T.value.reauthorizeFailed
  } finally {
    busy.value = false
  }
}

/**
 * 写探测修正：在刷新后顺带验一次「是否真能写」，把假只读翻正。
 *
 * 与重新授权的区别：这里不弹任何系统框，纯后台探测；若能写则静默把
 * boundTree.canWrite 修正为 true，让写按钮立刻可用。
 */
async function probeAndFixReadOnly(projectId: string) {
  const project = projects.value.find(item => item.id === projectId)
  if (!project || !project.boundTree || project.boundTree.canWrite) {
    return
  }
  const probe = await probeWorkspaceWrite(project.boundTree.treeUri)
  // 把探测结果记录下来，界面上常驻展示，便于用户/开发者定位「只读」根因。
  const detail = (probe as { detail?: string }).detail
  const uri = (probe as { treeUri?: string }).treeUri
  readOnlyDiagnostic.value = [
    `reason=${probe.reason}`,
    `canWrite=${probe.canWrite}`,
    `grantWrite=${probe.grantWrite}`,
    detail ? `detail=${detail}` : '',
    uri ? `treeUri=${uri}` : '',
  ]
    .filter(Boolean)
    .join(' | ')
  // 落盘日志：用户「设置 → 导出日志」时这条会进 ZIP 的 web/debug-logs.jsonl，
  // 让开发者不必依赖界面横幅也能看到只读探测的真实结论。
  appLogger.warn('probeAndFixReadOnly result', {
    projectId,
    treeUri: uri ?? project.boundTree.treeUri,
    displayName: project.boundTree.displayName,
    reason: probe.reason,
    canWrite: probe.canWrite,
    grantWrite: probe.grantWrite,
    detail,
  })
  if (probe.canWrite) {
    updateProject(projectId, item =>
      item.boundTree
        ? { ...item, boundTree: { ...item.boundTree, canWrite: true } }
        : item,
    )
  }
}

/** 解除绑定：仅清掉 boundTree 元数据，当前树原样保留为普通虚拟项目。 */
async function unbindProject() {
  const dialog = dialogRef.value
  const project = projects.value.find(item => item.id === menuProjectId.value)
  closeMenu()
  if (!project || !project.boundTree || busy.value) {
    return
  }
  if (dialog) {
    const confirmed = await dialog.askConfirm({
      title: T.value.unbindConfirm.replace('{name}', project.name),
      confirmLabel: T.value.confirm,
      cancelLabel: T.value.cancel,
      danger: false,
    })
    if (!confirmed) {
      return
    }
  }
  updateProject(project.id, item => ({ ...item, boundTree: null }))
  notice.value = T.value.unbindDone
}

/** 项目列表里「已绑定」的判定（供模板用）。 */
function projectIsBound(project: ProjectRecord): boolean {
  return isBoundProject(project)
}

// ---------------------------------------------------------------------------
// 双向可写：绑定项目下的写盘辅助
//
// 约定：所有写操作「先落盘、成功再改镜像树」。任一步失败都保持内存树不变，
// 并向 UI 报错——绝不允许出现「界面上删了、硬盘上还在」的假象。
// ---------------------------------------------------------------------------

/** 绑定目录是否可写（SAF 授权里 provider 是否给了写权限）。 */
function boundCanWrite(project: ProjectRecord): boolean {
  return project.boundTree?.canWrite === true
}

/**
 * 一个镜像节点对应的实体 document URI。
 *   - 目录节点：用自身 dirUri（扫描时存下的）。
 *   - 文件节点：用 fileRef.uri。
 * 都没有时返回 null（调用方应拒绝写操作）。
 */
function boundNodeUri(project: ProjectRecord, node: ProjectNode): string | null {
  if (node.kind === 'folder') {
    return node.dirUri ?? boundFolderUri(project, node.id)
  }
  return node.fileRef?.uri ?? null
}

/** 收集某节点的全部后代节点（用于级联删除目录内容）。 */
function collectDescendants(nodes: readonly ProjectNode[], id: string): ProjectNode[] {
  const out: ProjectNode[] = []
  const queue = [id]
  while (queue.length) {
    const current = queue.shift() as string
    for (const node of nodes) {
      if (node.parentId === current) {
        out.push(node)
        queue.push(node.id)
      }
    }
  }
  return out
}

/**
 * 写盘失败时的提示文案。
 *
 * 权限丢失 / provider 拒绝 / IO 错误分别提示；对「移动（拖拽）」这一类操作，
 * 原生会把失败原因细分成稳定错误码（WORKSPACE_MOVE_*），这里按码给出
 * 「为什么这个文件拖不动」的具体说明，而不是笼统的「写入失败」。
 *
 * @param error 原生 reject 出来的错误对象（带 code）。
 * @param name  相关项的名称，用于文案插值（可选）。
 */
function writeFailureText(error: unknown, name?: string): string {
  const code = getWorkspaceErrorCode(error)
  const withName = (text: string): string =>
    name ? text.replace('{name}', name) : text.replace(/「\{name\}」|“\{name\}”/g, '')
  switch (code) {
    case 'WORKSPACE_PERMISSION_LOST':
      return T.value.permissionLost
    case 'WORKSPACE_WRITE_REJECTED':
      return T.value.writeDenied
    // —— 移动失败的具体原因（原生 moveDocument / relocateByCopy 细分上报）——
    case 'WORKSPACE_MOVE_SOURCE_UNREADABLE':
      return withName(T.value.moveFailSourceUnreadable)
    case 'WORKSPACE_MOVE_SOURCE_TOO_LARGE':
      return withName(T.value.moveFailSourceTooLarge)
    case 'WORKSPACE_MOVE_TARGET_NAME_CONFLICT':
      return withName(T.value.moveFailNameConflict)
    case 'WORKSPACE_MOVE_TARGET_CREATE_FAILED':
      return T.value.moveFailTargetCreate
    case 'WORKSPACE_MOVE_TARGET_WRITE_FAILED':
      return T.value.moveFailTargetWrite
    case 'WORKSPACE_MOVE_SOURCE_DELETE_FAILED':
      return withName(T.value.moveFailSourceDelete)
    case 'WORKSPACE_MOVE_CHILD_FAILED':
      return withName(T.value.moveFailChild)
    case 'WORKSPACE_MOVE_NATIVE_FAILED':
      return T.value.moveFailNative
    default:
      return T.value.writeFailed
  }
}

// ---------------------------------------------------------------------------
// 导入 / 导出（zip）
//
// 分工：前端把项目树序列化成条目列表（projectArchive.ts），原生负责写/读 zip
// （ProjectArchiveCodec.java）。这样中文文件名走 UTF-8、大归档不进 JS 内存，
// 且不需要任何第三方 zip 库。
// ---------------------------------------------------------------------------

/** 时间戳后缀，让每次导出的文件名不重样。 */
function archiveTimestamp(): string {
  const now = new Date()
  const pad = (value: number) => String(value).padStart(2, '0')
  return (
    `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}` +
    `-${pad(now.getHours())}${pad(now.getMinutes())}`
  )
}

/** 导出当前项目为 zip：先选目标目录，再把序列化条目交给原生写入。 */
async function exportProjectArchive() {
  const project = projects.value.find(item => item.id === menuProjectId.value)
  closeMenu()
  if (!project || busy.value) {
    return
  }
  if (!isProjectArchiveAvailable()) {
    notice.value = T.value.archiveUnavailable
    return
  }
  busy.value = true
  notice.value = T.value.exportingWait
  try {
    const dir = await pickWorkspaceDirectory()
    if (!dir) {
      notice.value = null
      return
    }
    const entries = serializeProject(project, 'nested')
    const fileName = `${safeArchiveFolderName(project.name)}-${archiveTimestamp()}.zip`
    const result = await writeArchiveToDir({
      treeUri: dir.treeUri,
      fileName,
      entries,
    })
    notice.value = T.value.exportDone.replace('{name}', result.name)
  } catch (error) {
    const code = getWorkspaceErrorCode(error)
    notice.value = code === 'FOLDER_PICKER_UNAVAILABLE' ? T.value.noPicker : T.value.exportFailed
  } finally {
    busy.value = false
  }
}

/** 导入 zip：选一个归档文件 → 原生读出条目 → 前端还原成新项目。 */
async function importProjectArchive() {
  if (busy.value || props.disabled) {
    return
  }
  if (!isProjectArchiveAvailable()) {
    notice.value = T.value.archiveUnavailable
    return
  }
  busy.value = true
  notice.value = T.value.importingWait
  try {
    // accept='archive'：不筛类型，否则系统文件选择器会把 .zip 灰掉选不中。
    const picked = await pickWorkspaceFile('archive')
    if (!picked) {
      notice.value = null
      return
    }
    await ingestArchive(picked.uri)
  } catch (error) {
    notice.value = importFailureText(error)
  } finally {
    busy.value = false
  }
}

/**
 * 把一个归档 URI 读出来、判格式、还原成项目并落库。
 *
 * 手动「导入项目」和「外部分享打开」两条路径共用这里，保证行为一致。
 * 格式判断始终靠 parseProjectArchive 拆内部结构，不信扩展名。
 */
async function ingestArchive(uri: string): Promise<void> {
  const result = await readArchiveFromFile(uri)
  const project = parseProjectArchive(result.entries)
  projects.value = upsertProject(projects.value, project)
  persist()
  activeProjectId.value = project.id
  notice.value = T.value.importDone.replace('{name}', project.name)
}

/** 导入失败时的提示文案：区分「不是项目归档」和其它错误。 */
function importFailureText(error: unknown): string {
  const code = getWorkspaceErrorCode(error)
  if (code === 'FOLDER_PICKER_UNAVAILABLE') {
    return T.value.noPicker
  }
  if (code === 'INVALID_ARCHIVE') {
    return T.value.importInvalidArchive
  }
  return T.value.importFailed
}

/**
 * 外部应用（QQ / 微信）把 zip 分享/打开进来时的处理。
 *
 * 与手动导入的区别只有入口：这里是系统递 URI 过来，所以先提示「正在判断」，
 * 失败时提示「不是项目归档」——因为用户很可能误把普通 zip 递进来了。
 */
async function handleIncomingArchive(event: { uri: string }): Promise<void> {
  if (!isProjectArchiveAvailable()) {
    return
  }
  busy.value = true
  notice.value = T.value.importingWait
  try {
    await ingestArchive(event.uri)
  } catch (error) {
    notice.value = importFailureText(error)
  } finally {
    busy.value = false
  }
}

async function removeMenuNode() {
  const dialog = dialogRef.value
  if (!dialog) {
    return
  }
  const project = projects.value.find(item => item.id === menuProjectId.value)
  if (!project) {
    return
  }
  if (menuIsProjectRoot.value) {
    const confirmed = await dialog.askConfirm({
      title: T.value.removeProjectConfirm.replace('{name}', project.name),
      confirmLabel: T.value.remove,
      cancelLabel: T.value.cancel,
      danger: true,
    })
    if (confirmed) {
      projects.value = removeProject(projects.value, project.id)
      persist()
      if (activeProjectId.value === project.id) {
        activeProjectId.value = projects.value.length ? sortedProjects.value[0].id : null
      }
    }
    closeMenu()
    return
  }
  const node = menuNode.value
  if (!node) {
    return
  }
  const confirmed = await dialog.askConfirm({
    title: T.value.removeConfirm.replace('{name}', node.name),
    confirmLabel: T.value.remove,
    cancelLabel: T.value.cancel,
    danger: true,
  })
  if (!confirmed) {
    closeMenu()
    return
  }
  // 绑定项目：真正从硬盘上删除（目录会连同其内容一起删）。
  if (isBoundProject(project)) {
    closeMenu()
    if (!boundCanWrite(project)) {
      notice.value = T.value.writeDenied
      return
    }
    busy.value = true
    try {
      const doomed = [node, ...collectDescendants(project.nodes, node.id)]
      let allGone = true
      for (const item of doomed) {
        const uri = boundNodeUri(project, item)
        if (!uri) {
          continue
        }
        // 文件夹由其自身 dirUri 删除；文件由其 fileRef.uri 删除。
        const ok = await deleteWorkspaceDocument(uri)
        if (!ok) {
          allGone = false
        }
      }
      if (!allGone) {
        notice.value = T.value.writeFailed
        return
      }
      setNodes(project.id, removeNode(project.nodes, node.id))
      notice.value = T.value.writeDone.replace('{name}', node.name)
    } catch (error) {
      notice.value = writeFailureText(error)
    } finally {
      busy.value = false
    }
    return
  }
  setNodes(project.id, removeNode(project.nodes, node.id))
  closeMenu()
}
/** 进入/退出拖拽模式时锁死页面滚动，避免拖动时页面跟着上下滑。 */
function lockPageScroll(locked: boolean) {
  const body = document.body
  if (locked) {
    body.dataset.ptreeScrollLock = body.style.overflow || ''
    body.style.overflow = 'hidden'
    body.style.touchAction = 'none'
  } else {
    body.style.overflow = body.dataset.ptreeScrollLock ?? ''
    body.style.touchAction = ''
    delete body.dataset.ptreeScrollLock
  }
}

function enterDragMode() {
  // 进入拖拽模式：只是打开开关，不预定节点。
  // 之后用户按住哪个节点，就拖哪个节点。
  dragArmed.value = true
  draggingId.value = null
  dropTargetId.value = null
  dropLegal.value = true
  lockPageScroll(true)
  closeMenu()
}

// ---------------------------------------------------------------------------
// 拖拽（pointer 事件，不依赖 HTML5 drag API——Android WebView 支持不稳）
//
// 流程：进入拖拽模式(dragArmed) → 在某行按下(startDrag) → 移动更新落点
//      → 抬起(onPointerUp) 提交，或被系统打断(onPointerCancel) 放弃。
//
// 两个必须同时满足的前置条件（缺一个就「拖不动」）：
//   1. touch-action: none —— 否则垂直拖动被解释成页面滚动，事件被 pointercancel 打断；
//   2. setPointerCapture  —— 否则手指移出起始行后收不到 pointermove。
//   （第 1 点在 <style> 里，靠 .is-drag-armed 生效。）
// ---------------------------------------------------------------------------

/** 节点行按下：仅当处于拖拽模式时生效，锁定指针并开始拖动。 */
function startDrag(event: PointerEvent, nodeId: string) {
  if (!dragArmed.value) {
    return
  }
  event.preventDefault()
  event.stopPropagation()
  const target = event.currentTarget as HTMLElement | null
  try {
    target?.setPointerCapture?.(event.pointerId)
  } catch {
    // 某些 WebView 在指针已被隐式捕获时会抛错，忽略即可。
  }
  draggingId.value = nodeId
  dropTargetId.value = null
  dropLegal.value = true
}

/** 拖拽模式的落点判定：把指针位置映射到某个节点行。 */
function onPointerMove(event: PointerEvent) {
  if (!draggingId.value) {
    return
  }
  // 阻断默认行为，双保险避免页面滚动接管手势。
  event.preventDefault()
  const el = document.elementFromPoint(event.clientX, event.clientY)
  const row = el?.closest<HTMLElement>('[data-node-id]')
  const targetId = row ? row.dataset.nodeId ?? null : null
  const project = activeProject.value
  if (!project) {
    return
  }
  if (!targetId) {
    // 空白区域视作「放到项目根」。
    dropTargetId.value = ROOT_SENTINEL
    dropLegal.value = true
    return
  }
  // 跨父级移动时用「目标行的父节点」作为落点更直观：
  // 但当前实现按「放进目标节点内部」处理，保持与 projectStore.canMoveNode 一致。
  const nextParentId = targetId === ROOT_SENTINEL ? null : targetId
  const check = canMoveNode(project.nodes, draggingId.value, nextParentId)
  dropTargetId.value = targetId
  dropLegal.value = check.ok
}

/** 清理一次拖动过程（不清除 dragArmed 模式）。 */
function clearDrag() {
  draggingId.value = null
  dropTargetId.value = null
  dropLegal.value = true
}

/** 指针抬起：提交移动（合法时）或放弃。 */
async function onPointerUp(event?: PointerEvent) {
  const target = event?.currentTarget as HTMLElement | null
  if (target && event) {
    try {
      target.releasePointerCapture?.(event.pointerId)
    } catch {
      // ignore
    }
  }
  const project = activeProject.value
  const movingId = draggingId.value
  const dropId = dropTargetId.value
  if (!movingId || !project || !dropId) {
    clearDrag()
    return
  }
  const nextParentId = dropId === ROOT_SENTINEL ? null : dropId
  const check = canMoveNode(project.nodes, movingId, nextParentId)
  if (!check.ok) {
    notice.value = rejectionText(check.reason)
    clearDrag()
    return
  }
  const movingNode = findNode(project.nodes, movingId)
  clearDrag()
  if (!movingNode) {
    return
  }
  // 绑定项目：真正把实体项移动到硬盘上的目标目录，成功再改镜像树。
  if (isBoundProject(project)) {
    if (!boundCanWrite(project)) {
      notice.value = T.value.writeDenied
      return
    }
    const sourceUri = boundNodeUri(project, movingNode)
    const targetUri = boundFolderUri(project, nextParentId)
    // 源项当前所在目录的 document URI——必须显式算出来传给原生。
    // SAF 的 documentId 不含父信息，原生无法从 sourceUri 反推父目录，
    // 反推失败会把 tree URI 当父目录传进 moveDocument 导致移动失败。
    const sourceParentUri = boundFolderUri(project, movingNode.parentId ?? null)
    if (!sourceUri || !targetUri) {
      notice.value = T.value.writeDenied
      return
    }
    busy.value = true
    try {
      appLogger.info('moveNode result', {
        projectId: project.id,
        nodeId: movingId,
        kind: movingNode.kind,
        sourceUri,
        sourceParentUri,
        targetUri,
      })
      const moved = await moveWorkspaceDocument(sourceUri, targetUri, sourceParentUri)
      if (!moved) {
        notice.value = T.value.writeFailed
        return
      }
      setNodes(project.id, moveNode(project.nodes, movingId, nextParentId))
      notice.value = T.value.writeDone.replace('{name}', movingNode.name)
    } catch (error) {
      notice.value = writeFailureText(error, movingNode.name)
      appLogger.error('moveNode failed', {
        projectId: project.id,
        nodeId: movingId,
        code: getWorkspaceErrorCode(error),
        error,
      })
    } finally {
      busy.value = false
    }
    return
  }
  setNodes(project.id, moveNode(project.nodes, movingId, nextParentId))
}

/**
 * 指针被系统打断（滚动手势、来电等）：放弃本次拖动，不提交。
 * 之前这里错接成 onPointerUp，导致「刚开始拖就被判到自己身上」。
 */
function onPointerCancel(event?: PointerEvent) {
  const target = event?.currentTarget as HTMLElement | null
  if (target && event) {
    try {
      target.releasePointerCapture?.(event.pointerId)
    } catch {
      // ignore
    }
  }
  clearDrag()
}

/** 退出拖拽模式。 */
function exitDragMode() {
  clearDrag()
  dragArmed.value = false
  lockPageScroll(false)
}

/** 组件卸载时确保滚动锁被解除，并摘掉外部分享监听。 */
onBeforeUnmount(() => {
  if (dragArmed.value) {
    lockPageScroll(false)
  }
  void archiveListener?.remove()
})

function rejectionText(reason?: string) {
  switch (reason) {
    case 'SELF':
      return T.value.dragSelf
    case 'INTO_DESCENDANT':
      return T.value.dragDescendant
    case 'TOO_DEEP':
      return T.value.dragTooDeep
    default:
      return T.value.dragNotFound
  }
}

/** 触发原生文件打开。 */
async function openFile(node: ProjectNode) {
  // 1) 外部索引文件（真实磁盘上的文件，有 SAF 授权）：走原生读取通道。
  if (node.fileRef) {
    emit('openIndexedFile', node.fileRef.uri)
    return
  }
  // 2) 导入归档来的虚拟文件：内容随项目存在内存里，没有磁盘 URI。
  //    直接合成一个打开结果交给编辑器，不再误报「授权已失效」。
  if (typeof node.content === 'string') {
    emit('openArchivedFile', {
      sourceUri: '',
      displayName: node.name,
      markdown: node.content,
      encoding: 'utf-8',
      providerName: '',
      canWrite: false,
      persisted: false,
    })
    return
  }
  // 3) 既无 fileRef 又无内容：真正无法定位（历史坏数据 / 授权确实丢了）。
  notice.value = T.value.permissionLost
}

/** 是否为拖拽悬停的目标行（用于高亮）。 */
function isDropTarget(nodeId: string | null) {
  if (!draggingId.value || !dropTargetId.value) {
    return false
  }
  return dropTargetId.value === nodeId
}

function fileRefLabel(node: ProjectNode) {
  const source = node.fileRef?.source
  if (source === 'private') {
    return T.value.private
  }
  if (source === 'created') {
    return T.value.created
  }
  return T.value.indexed
}

onMounted(async () => {
  load()
  // 启动即对「被判定为只读」的绑定项目做一次真实写探测：历史 bug 可能把
  // 明明可写的目录记成了只读，导致所有写按钮被灰掉。探测能自动翻正。
  for (const project of projects.value) {
    if (project.boundTree && project.boundTree.canWrite !== true) {
      void probeAndFixReadOnly(project.id)
    }
  }
  // 监听「外部应用把项目归档 zip 分享/打开进来」——QQ / 微信长按 zip →
  // 打开方式 → 本 App。收到后立刻读取、判格式、导入。
  archiveListener = await onProjectArchiveOpened(event => {
    void handleIncomingArchive(event)
  })
})
</script>

<template>
  <section class="ptree" :class="{ 'is-drag-armed': dragArmed }" data-testid="project-tree">
    <!-- 项目切换 + 新建 -->
    <div class="ptree-bar">
      <div class="ptree-chips" role="tablist" :aria-label="T.projects" data-testid="project-chip-row">
        <button
          v-for="project in sortedProjects"
          :key="project.id"
          class="ptree-chip"
          :class="{ 'is-active': project.id === activeProjectId, 'is-pinned': project.pinned }"
          type="button"
          role="tab"
          :aria-selected="project.id === activeProjectId"
          :data-testid="`project-chip-${project.id}`"
          @click="consumeRootClick(project)"
          @contextmenu.prevent="openProjectMenu(project)"
          @pointerdown="rootLongPress.onPointerDown($event, project.id)"
          @pointermove="rootLongPress.onPointerMove"
          @pointerup="rootLongPress.onPointerEnd"
          @pointercancel="rootLongPress.onPointerEnd"
        >
          <span class="ptree-chip-pin" aria-hidden="true" v-if="project.pinned">★</span>
          <span class="ptree-chip-bound" aria-hidden="true" v-if="projectIsBound(project)">🔗</span>
          <span class="ptree-chip-name">{{ project.name }}</span>
          <span class="ptree-chip-count">{{ fileCountOf(project) }}</span>
        </button>
      </div>
      <div class="ptree-actions">
        <button
          class="ptree-new"
          type="button"
          data-testid="project-new-button"
          :disabled="busy || props.disabled"
          @click="newProject"
        >
          <svg viewBox="0 0 24 24" focusable="false" aria-hidden="true">
            <path d="M12 5v14M5 12h14" />
          </svg>
          <span>{{ T.newProject }}</span>
        </button>
        <button
          class="ptree-new is-ghost"
          type="button"
          data-testid="project-import-button"
          :disabled="busy || props.disabled"
          @click="importProjectArchive"
        >
          <svg viewBox="0 0 24 24" focusable="false" aria-hidden="true">
            <path d="M12 3v12m0 0l-4-4m4 4l4-4M5 19h14" />
          </svg>
          <span>{{ T.importProject }}</span>
        </button>
      </div>
    </div>

    <p v-if="notice" class="ptree-notice" data-testid="project-notice">{{ notice }}</p>

    <!-- 只读诊断横幅（临时排查用）：把 probeWrite 的结果直接暴露给用户 -->
    <pre
      v-if="readOnlyDiagnostic"
      class="ptree-diagnostic"
      data-testid="project-readonly-diagnostic"
    >只读诊断：{{ readOnlyDiagnostic }}</pre>

    <!-- 空态 -->
    <div v-if="!activeProject" class="ptree-empty" data-testid="project-empty">
      <h3>{{ T.emptyTitle }}</h3>
      <p>{{ T.emptyBody }}</p>
    </div>

    <!-- 树 -->
    <ul v-else class="ptree-list" data-testid="project-node-list">
      <li
        v-for="row in visibleRows"
        :key="row.node.id"
        class="ptree-row"
        :class="{
          'is-dragging': draggingId === row.node.id,
          'is-drop-legal': isDropTarget(row.node.id) && dropLegal,
          'is-drop-illegal': isDropTarget(row.node.id) && !dropLegal,
        }"
        :data-node-id="row.node.id"
        :style="{ paddingLeft: `${8 + (row.depth - 1) * 18}px` }"
      >
        <button
          v-if="row.node.kind === 'folder'"
          class="ptree-twisty"
          type="button"
          :aria-label="row.node.expanded ? 'collapse' : 'expand'"
          :data-testid="`project-twisty-${row.node.id}`"
          @click="toggleNodeExpanded(row.node)"
        >
          <svg viewBox="0 0 24 24" focusable="false"
            :class="{ 'is-open': row.node.expanded }" aria-hidden="true">
            <path d="M9 6l6 6-6 6" />
          </svg>
        </button>
        <span v-else class="ptree-twisty is-leaf" aria-hidden="true"></span>

        <button
          class="ptree-node"
          type="button"
          :data-testid="`project-node-${row.node.id}`"
          @click="consumeNodeClick(row.node)"
          @contextmenu.prevent="!dragArmed && openNodeMenu(activeProject!, row.node)"
          @pointerdown="onNodePointerDown($event, row.node.id)"
          @pointermove="onNodePointerMove"
          @pointerup="onNodePointerUp"
          @pointercancel="onNodePointerCancel"
        >
          <span class="ptree-node-mark" aria-hidden="true">
            <svg v-if="row.node.kind === 'folder'" viewBox="0 0 24 24" focusable="false">
              <path d="M3 7h6l2 2h10v10H3z" />
            </svg>
            <svg v-else viewBox="0 0 24 24" focusable="false">
              <path d="M6 3h8l4 4v14H6z" />
              <path d="M14 3v4h4" />
            </svg>
          </span>
          <span class="ptree-node-name">{{ row.node.name }}</span>
          <span v-if="row.node.pinned" class="ptree-node-pin" aria-hidden="true">★</span>
          <span v-if="row.node.kind === 'file' && row.node.fileRef" class="ptree-node-tag">
            {{ fileRefLabel(row.node) }}
          </span>
        </button>
      </li>
    </ul>

    <!-- 长按菜单 -->
    <div v-if="menuNodeId" class="ptree-menu-backdrop" @click="closeMenu"></div>
    <div
      v-if="menuNodeId"
      class="ptree-menu"
      role="menu"
      data-testid="project-node-menu"
    >
      <p class="ptree-menu-title">
        {{ menuIsProjectRoot
          ? (projects.find(p => p.id === menuProjectId)?.name ?? T.projects)
          : (menuNode?.name ?? '') }}
      </p>
      <button
        v-if="menuIsProjectRoot && !projects.find(p => p.id === menuProjectId)?.boundTree"
        class="ptree-menu-item is-accent"
        type="button"
        data-testid="project-menu-bind"
        @click="bindProjectToDirectory"
      >
        🔗 {{ T.bindTree }}
      </button>
      <button
        v-if="menuIsProjectRoot && projects.find(p => p.id === menuProjectId)?.boundTree"
        class="ptree-menu-item is-accent"
        type="button"
        data-testid="project-menu-refresh"
        @click="refreshBoundProject"
      >
        ⟳ {{ T.refreshTree }}
      </button>
      <!-- 只读绑定时出现：重新走一遍目录选择器补申请写权限。这是 Android 上
           「补写权限」的唯一正规途径（SAF 授权只能靠用户重选目录拿到）。 -->
      <button
        v-if="menuIsProjectRoot && menuBoundReadOnly"
        class="ptree-menu-item is-accent"
        type="button"
        data-testid="project-menu-reauthorize"
        @click="reauthorizeBoundProject"
      >
        🔓 {{ T.reauthorize }}
      </button>
      <button
        v-if="menuIsProjectRoot && projects.find(p => p.id === menuProjectId)?.boundTree"
        class="ptree-menu-item"
        type="button"
        data-testid="project-menu-unbind"
        @click="unbindProject"
      >
        {{ T.unbindTree }}
      </button>
      <!-- 创建子目录：绑定项目走实体目录创建（写盘），虚拟项目走本地节点 -->
      <button
        v-if="menuCanHostChildren"
        class="ptree-menu-item"
        type="button"
        data-testid="project-menu-create-folder"
        :disabled="menuBoundReadOnly"
        @click="createFolderUnderMenu"
      >
        {{ T.createFolder }}
      </button>
      <button
        v-if="menuCanHostFiles"
        class="ptree-menu-item"
        type="button"
        data-testid="project-menu-create-file"
        :disabled="menuBoundReadOnly"
        @click="createFileUnderMenu"
      >
        {{ T.createFile }}
      </button>
      <button
        v-if="menuCanHostFiles"
        class="ptree-menu-item"
        type="button"
        data-testid="project-menu-add-existing"
        @click="addExistingFileUnderMenu"
      >
        {{ T.addExisting }}
      </button>
      <button
        class="ptree-menu-item"
        type="button"
        data-testid="project-menu-rename"
        @click="renameMenuNode"
      >
        {{ T.rename }}
      </button>
      <button
        v-if="!menuIsProjectRoot"
        class="ptree-menu-item"
        type="button"
        data-testid="project-menu-pin"
        @click="toggleNodePinned"
      >
        {{ menuNode?.pinned ? T.unpin : T.pin }}
      </button>
      <button
        v-if="menuIsProjectRoot"
        class="ptree-menu-item"
        type="button"
        data-testid="project-menu-project-pin"
        @click="toggleProjectPin(projects.find(p => p.id === menuProjectId)!); closeMenu()"
      >
        {{ projects.find(p => p.id === menuProjectId)?.pinned ? T.unpin : T.pin }}
      </button>
      <button
        v-if="menuIsProjectRoot"
        class="ptree-menu-item"
        type="button"
        data-testid="project-menu-export"
        @click="exportProjectArchive"
      >
        {{ T.exportProject }}
      </button>
      <button
        v-if="!menuIsProjectRoot"
        class="ptree-menu-item is-accent"
        type="button"
        data-testid="project-menu-drag"
        @click="enterDragMode"
      >
        {{ T.dragMode }}
      </button>
      <button
        class="ptree-menu-item is-danger"
        type="button"
        data-testid="project-menu-remove"
        @click="removeMenuNode"
      >
        {{ T.remove }}
      </button>
    </div>

    <!-- 拖拽模式提示条：进入模式即常驻显示 -->
    <div
      v-if="dragArmed"
      class="ptree-drag-layer"
      data-testid="project-drag-layer"
    >
      <span class="ptree-drag-label" data-testid="project-drag-hint">
        {{ T.dragHint }}
      </span>
      <button class="ptree-drag-cancel" type="button" @click="exitDragMode">
        {{ T.dragModeOn }}
      </button>
    </div>
    <!-- 自绘输入 / 确认弹窗（替代原生 prompt / confirm） -->
    <PromptDialog ref="dialogRef" />
  </section>
</template>

<style scoped>
.ptree {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* 两行布局：第一行只放项目切换 chips（可横向滚），第二行放新建/导入。
   原来单行时按钮是 flex:none，会把项目名挤到只剩一点空间。 */
.ptree-bar {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.ptree-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ptree-chips {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding-bottom: 2px;
  scrollbar-width: none;
}

.ptree-chips::-webkit-scrollbar {
  display: none;
}

.ptree-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex: none;
  height: 34px;
  padding: 0 12px;
  border: var(--hairline) solid var(--border);
  border-radius: 999px;
  background: var(--surface-raised);
  color: var(--text-muted);
  font-size: 13px;
  cursor: pointer;
}

.ptree-chip.is-active {
  border-color: var(--accent);
  background: var(--accent-tint-10, var(--surface-raised));
  color: var(--text);
}

.ptree-chip-pin {
  color: var(--accent-strong);
  font-size: 11px;
}

.ptree-chip-bound {
  font-size: 11px;
  line-height: 1;
}

/* 单个 chip 限宽，超长项目名走省略号；chip 本身 flex:none，
   横排超出时由 .ptree-chips 横向滚动兜底。 */
.ptree-chip-name {
  overflow: hidden;
  max-width: 46vw;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ptree-chip-count {
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 999px;
  background: var(--surface-muted);
  color: var(--text-faint);
  font-size: 11px;
  line-height: 18px;
  text-align: center;
}

.ptree-new {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  flex: none;
  height: 34px;
  padding: 0 12px;
  border: var(--hairline) solid var(--border-strong);
  border-radius: 999px;
  background: var(--accent);
  color: var(--on-accent);
  font-size: 13px;
  cursor: pointer;
}

.ptree-new:disabled {
  opacity: 0.5;
}

/* 次要按钮：白底描边，跟主行动的实心 accent 区分开。 */
.ptree-new.is-ghost {
  background: var(--surface-raised);
  color: var(--text);
  border-color: var(--border);
}

.ptree-new.is-ghost svg {
  width: 16px;
  height: 16px;
  fill: none;
  stroke: currentColor;
  stroke-width: 2;
  stroke-linecap: round;
}

.ptree-new svg {
  width: 16px;
  height: 16px;
  fill: none;
  stroke: currentColor;
  stroke-width: 2;
  stroke-linecap: round;
}
.ptree-notice {
  margin: 0;
  padding: 8px 12px;
  border-radius: 10px;
  background: var(--surface-muted);
  color: var(--text-muted);
  font-size: 12px;
}

/* 只读诊断横幅（临时排查用）：单色高对比，长文本自动换行。 */
.ptree-diagnostic {
  margin: 0;
  padding: 8px 12px;
  border-radius: 10px;
  border: var(--hairline) dashed var(--accent);
  background: var(--surface-muted);
  color: var(--text-muted);
  font-size: 11px;
  white-space: pre-wrap;
  word-break: break-all;
}


.ptree.is-drag-armed .ptree-list {
  border: var(--hairline) dashed var(--accent);
  border-radius: 14px;
  padding: 6px;
}

.ptree.is-drag-armed .ptree-row {
  cursor: grab;
}

/*
 * 拖拽模式下的关键样式：禁用浏览器对该区域的默认触摸手势。
 * 不加这行，垂直拖动会被 WebView 解释成页面滚动，
 * 事件流会被 pointercancel 打断（表现为「拖不动、只能停在自己身上」）。
 */
.ptree.is-drag-armed .ptree-node,
.ptree.is-drag-armed .ptree-row {
  touch-action: none;
  -webkit-user-select: none;
  user-select: none;
}

.ptree-empty {
  padding: 24px 16px;
  border: var(--hairline) dashed var(--border);
  border-radius: 14px;
  text-align: center;
}

.ptree-empty h3 {
  margin: 0 0 6px;
  font-size: 15px;
}

.ptree-empty p {
  margin: 0;
  color: var(--text-muted);
  font-size: 13px;
}

.ptree-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.ptree-row {
  display: flex;
  align-items: center;
  gap: 2px;
  border-radius: 10px;
}

.ptree-row.is-dragging {
  opacity: 0.5;
}

.ptree-row.is-drop-legal {
  background: var(--accent-tint-10);
  box-shadow: inset 0 0 0 1.5px var(--accent);
}

.ptree-row.is-drop-illegal {
  background: var(--surface-sunken);
  box-shadow: inset 0 0 0 1.5px var(--separator);
}

.ptree-twisty {
  display: inline-grid;
  place-items: center;
  flex: none;
  width: 24px;
  height: 32px;
  border: 0;
  background: transparent;
  color: var(--text-faint);
  cursor: pointer;
}

.ptree-twisty.is-leaf {
  pointer-events: none;
}

.ptree-twisty svg {
  width: 16px;
  height: 16px;
  fill: none;
  stroke: currentColor;
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
  transition: transform 0.15s ease;
}

.ptree-twisty svg.is-open {
  transform: rotate(90deg);
}

.ptree-node {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
  height: 36px;
  padding: 0 10px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: var(--text);
  font-size: 14px;
  text-align: left;
  cursor: pointer;
}

.ptree-node:hover {
  background: var(--surface-muted);
}

.ptree-node-mark {
  display: inline-grid;
  place-items: center;
  flex: none;
  width: 18px;
  height: 18px;
  color: var(--text-faint);
}

.ptree-node-mark svg {
  width: 100%;
  height: 100%;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.7;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.ptree-node-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ptree-node-pin {
  color: var(--accent-strong);
  font-size: 11px;
}

.ptree-node-tag {
  flex: none;
  padding: 1px 6px;
  border-radius: 6px;
  background: var(--surface-muted);
  color: var(--text-faint);
  font-size: 10px;
}

/* Menu -------------------------------------------------------------------- */

.ptree-menu-backdrop {
  position: fixed;
  inset: 0;
  z-index: 40;
  background: transparent;
}

.ptree-menu {
  position: fixed;
  left: 50%;
  bottom: calc(env(safe-area-inset-bottom, 0px) + 110px);
  z-index: 41;
  width: min(320px, calc(100vw - 40px));
  transform: translateX(-50%);
  display: flex;
  flex-direction: column;
  padding: 8px;
  border: var(--hairline) solid var(--border);
  border-radius: 16px;
  background: var(--surface-raised);
  box-shadow: var(--shadow-float);
}

.ptree-menu-title {
  margin: 0 0 4px;
  padding: 6px 12px;
  color: var(--text-faint);
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ptree-menu-item {
  height: 40px;
  padding: 0 12px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: var(--text);
  font-size: 14px;
  text-align: left;
  cursor: pointer;
}

.ptree-menu-item:hover {
  background: var(--surface-muted);
}

.ptree-menu-item.is-accent {
  color: var(--accent-strong);
}

.ptree-menu-item.is-danger {
  color: var(--text);
}

.ptree-menu-item.is-danger:hover {
  background: var(--surface-sunken);
}

/* Drag layer -------------------------------------------------------------- */

.ptree-drag-layer {
  position: fixed;
  left: 50%;
  bottom: calc(env(safe-area-inset-bottom, 0px) + 96px);
  transform: translateX(-50%);
  z-index: 30;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px 8px 14px;
  border: var(--hairline) solid var(--border-strong);
  border-radius: 999px;
  background: var(--surface-raised);
  box-shadow: var(--shadow-float);
  /* 提示条不能拦截树区域的指针事件，否则 elementFromPoint 落点判定失效。 */
  pointer-events: none;
  /* 修复：提示条整体不换行，且宽度不超出屏幕，按钮文字不再溢到按钮外。 */
  max-width: calc(100vw - 24px);
  white-space: nowrap;
}
.ptree-drag-label {
  color: var(--text);
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
}
.ptree-drag-cancel {
  pointer-events: auto;
  /* 修复：原先固定 height:32px 且无 nowrap，长文案（如「退出拖拽模式」）会换行
     溢出到按钮外。改为自适应高度 + 不换行 + 不收缩。 */
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 32px;
  padding: 6px 14px;
  border: var(--hairline) solid var(--border-strong);
  border-radius: 999px;
  background: var(--surface-sunken);
  color: var(--text);
  font-size: 13px;
  line-height: 1.2;
  white-space: nowrap;
  cursor: pointer;
}
</style>
