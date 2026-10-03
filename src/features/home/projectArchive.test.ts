import { describe, expect, it } from 'vitest'
import {
  ARCHIVE_SCHEMA_VERSION,
  MANIFEST_FILENAME,
  isConsistentTree,
  nodesFromManifest,
  nodesFromNested,
  parseManifest,
  parseProjectArchive,
  safeArchiveFolderName,
  serializeProject,
  serializeProjectFlat,
  serializeProjectNested,
  type ArchiveEntry,
  type ArchiveManifest,
} from './projectArchive'
import {
  childrenOf,
  createFileNode,
  createFolderNode,
  createProjectRecord,
  nodePath,
  type ProjectNode,
  type ProjectRecord,
} from './projectStore'

/**
 * 造一棵三层的样例树：
 *   裂境营火GDD
 *   ├── 大纲类
 *   │   └── 世界观
 *   │       └── 剧情.md
 *   └── 设定集
 */
function buildSample(): ProjectRecord {
  const root = createProjectRecord('裂境营火GDD')
  let nodes: ProjectNode[] = []

  const outline = createFolderNode(nodes, null, '大纲类') as ProjectNode
  nodes = [...nodes, outline]

  const worldview = createFolderNode(nodes, outline.id, '世界观') as ProjectNode
  nodes = [...nodes, worldview]

  const story = createFileNode(nodes, worldview.id, '剧情.md') as ProjectNode
  nodes = [...nodes, story]

  const settings = createFolderNode(nodes, null, '设定集') as ProjectNode
  nodes = [...nodes, settings]

  return { ...root, nodes }
}

/** 把项目树映射成「路径 → 节点」的稳定快照，便于往返断言。 */
function snapshot(project: ProjectRecord): string[] {
  return project.nodes
    .map(node => `${node.kind}:${nodePath(project.nodes, node)}`)
    .sort()
}

describe('projectArchive 导出', () => {
  it('safeArchiveFolderName 清洗非法字符并兜底', () => {
    expect(safeArchiveFolderName('正常名字')).toBe('正常名字')
    expect(safeArchiveFolderName('a/b\\c:d?e')).toBe('a_b_c_d_e')
    expect(safeArchiveFolderName('   ')).toBe('project')
  })

  it('nested 格式写出目录条目与文件条目', () => {
    const entries = serializeProjectNested(buildSample())
    const paths = entries.map(entry => `${entry.isDirectory ? 'D' : 'F'}:${entry.path}`)
    expect(paths).toContain('D:裂境营火GDD/')
    expect(paths).toContain('D:裂境营火GDD/大纲类/')
    expect(paths).toContain('D:裂境营火GDD/大纲类/世界观/')
    expect(paths).toContain('F:裂境营火GDD/大纲类/世界观/剧情.md')
    expect(paths).toContain('D:裂境营火GDD/设定集/')
  })

  it('flat 格式写出 manifest 并给文件加序号，目录只进 manifest', () => {
    const entries = serializeProjectFlat(buildSample())
    const manifestEntry = entries.find(entry => entry.path.endsWith(MANIFEST_FILENAME))
    expect(manifestEntry).toBeTruthy()

    const manifest = JSON.parse(manifestEntry!.content) as ArchiveManifest
    expect(manifest.format).toBe('perspicuity-project')
    expect(manifest.schemaVersion).toBe(ARCHIVE_SCHEMA_VERSION)
    expect(manifest.projectName).toBe('裂境营火GDD')

    const files = manifest.nodes.filter(node => node.kind === 'file')
    expect(files).toHaveLength(1)
    expect(files[0].path).toMatch(/裂境营火GDD\/files\/001-剧情\.md$/)

    // 目录在 flat 格式下不产生独立条目，只靠 manifest 描述。
    const folders = manifest.nodes.filter(node => node.kind === 'folder')
    expect(folders.map(node => node.name).sort()).toEqual(['世界观', '大纲类', '设定集'])

    // 文件条目确实进了 entries。
    expect(entries.some(entry => !entry.isDirectory && entry.path.endsWith('剧情.md'))).toBe(true)
  })

  it('serializeProject 按格式分派', () => {
    const project = buildSample()
    const nested = serializeProject(project, 'nested')
    const flat = serializeProject(project, 'flat')
    expect(nested.some(entry => entry.isDirectory)).toBe(true)
    expect(flat.some(entry => entry.path.endsWith(MANIFEST_FILENAME))).toBe(true)
  })
})

describe('projectArchive 导入', () => {
  it('parseManifest 拒绝非本项目 / 坏 JSON', () => {
    expect(parseManifest('not json')).toBeNull()
    expect(parseManifest('{"format":"other","nodes":[]}')).toBeNull()
    expect(parseManifest('{"format":"perspicuity-project","nodes":[]}')?.format).toBe(
      'perspicuity-project',
    )
  })

  it('nested 往返后层级与顺序保持一致', () => {
    const project = buildSample()
    const entries = serializeProjectNested(project)
    const restored = parseProjectArchive(entries)

    expect(restored.name).toBe('裂境营火GDD')
    expect(snapshot(restored)).toEqual(snapshot(project))
    expect(isConsistentTree(restored.nodes)).toBe(true)

    // 第 3 层文件确实落在第 3 层。
    const story = restored.nodes.find(node => node.name === '剧情.md')!
    expect(nodePath(restored.nodes, story)).toBe('大纲类/世界观/剧情.md')
  })

  it('flat 往返后层级与顺序保持一致', () => {
    const project = buildSample()
    const entries = serializeProjectFlat(project)
    const restored = parseProjectArchive(entries)

    expect(restored.name).toBe('裂境营火GDD')
    expect(snapshot(restored)).toEqual(snapshot(project))
    expect(isConsistentTree(restored.nodes)).toBe(true)
  })

  it('nodesFromManifest 对 parentIndex 指向未建节点时兜底挂根', () => {
    const manifest: ArchiveManifest = {
      format: 'perspicuity-project',
      schemaVersion: ARCHIVE_SCHEMA_VERSION,
      projectName: 'X',
      nodes: [
        // 父下标 5 不存在 → 应挂到根，而不是崩。
        { path: '', name: '孤儿目录', kind: 'folder', parentIndex: 5 },
      ],
    }
    const nodes = nodesFromManifest(manifest)
    expect(nodes).toHaveLength(1)
    expect(nodes[0].parentId).toBeNull()
  })

  it('nodesFromNested 取第一段作为项目根名', () => {
    const entries: ArchiveEntry[] = [
      { path: '我的本子/', content: '', isDirectory: true },
      { path: '我的本子/章节/一.md', content: '', isDirectory: false },
    ]
    const { nodes, rootName } = nodesFromNested(entries)
    expect(rootName).toBe('我的本子')
    const top = childrenOf(nodes, null)
    expect(top.map(node => node.name)).toEqual(['章节'])
    const chapter = top[0]
    expect(childrenOf(nodes, chapter.id).map(node => node.name)).toEqual(['一.md'])
  })

  it('没有 manifest 时 parseProjectArchive 走目录结构推断', () => {
    const entries: ArchiveEntry[] = [
      { path: '本子/', content: '', isDirectory: true },
      { path: '本子/伏笔.md', content: '', isDirectory: false },
    ]
    const project = parseProjectArchive(entries)
    expect(project.name).toBe('本子')
    expect(project.nodes.map(node => node.name)).toEqual(['伏笔.md'])
    expect(project.schemaVersion).toBe(2)
  })

  it('空归档也能得到合法的空项目', () => {
    const project = parseProjectArchive([])
    expect(project.nodes).toEqual([])
    expect(project.name).toBe('导入的项目')
    expect(isConsistentTree(project.nodes)).toBe(true)
  })

  it('重复同名文件在 flat 格式下不会互相覆盖', () => {
    const root = createProjectRecord('重名测试')
    let nodes: ProjectNode[] = []
    const a = createFileNode(nodes, null, '同名.md') as ProjectNode
    nodes = [...nodes, a]
    const b = createFileNode(nodes, null, '同名.md') as ProjectNode
    nodes = [...nodes, b]
    const project = { ...root, nodes }

    const entries = serializeProjectFlat(project)
    const fileEntries = entries.filter(entry => entry.path.includes('/files/'))
    expect(new Set(fileEntries.map(entry => entry.path)).size).toBe(2)

    const restored = parseProjectArchive(entries)
    expect(restored.nodes.filter(node => node.name === '同名.md')).toHaveLength(2)
  })
})
