import { describe, expect, it } from 'vitest'
import {
  MAX_NEST_DEPTH,
  PROJECT_STORAGE_KEY,
  canHostChildren,
  canHostFiles,
  canMoveNode,
  childrenOf,
  createFileNode,
  createFolderNode,
  createProjectRecord,
  depthOf,
  descendantIds,
  findNode,
  flattenProjectFiles,
  moveNode,
  nodePath,
  parseProjects,
  removeNode,
  removeProject,
  renameNode,
  sortProjects,
  subtreeDepth,
  toIndexedFileRef,
  toggleExpanded,
  togglePinned,
  upsertProject,
  writeProjects,
  readProjects,
  type ProjectNode,
  type ProjectRecord,
} from './projectStore'

/** 快速搭一棵树：folder 用名字建，返回值可直接用于断言。 */
function buildTree() {
  const root = createProjectRecord('裂境营火GDD')
  let nodes: ProjectNode[] = []

  const outline = createFolderNode(nodes, null, '大纲类')
  expect(outline).not.toBeNull()
  nodes = [...nodes, outline as ProjectNode]

  const worldview = createFolderNode(nodes, outline!.id, '世界观')
  expect(worldview).not.toBeNull()
  nodes = [...nodes, worldview as ProjectNode]

  const story = createFileNode(nodes, worldview!.id, '剧情.md')
  expect(story).not.toBeNull()
  nodes = [...nodes, story as ProjectNode]

  return { root: { ...root, nodes }, outline: outline!, worldview: worldview!, story: story! }
}

describe('projectStore 树的派生视图', () => {
  it('childrenOf 只返回直接子节点', () => {
    const { root, outline, worldview } = buildTree()
    expect(childrenOf(root.nodes, null).map(n => n.id)).toEqual([outline.id])
    expect(childrenOf(root.nodes, outline.id).map(n => n.id)).toEqual([worldview.id])
  })

  it('同级排序：置顶优先，其次 order，最后名称兜底', () => {
    let nodes: ProjectNode[] = []
    const a = createFolderNode(nodes, null, 'A')!
    nodes = [...nodes, a]
    const b = createFolderNode(nodes, null, 'B')!
    nodes = [...nodes, b]
    const c = createFolderNode(nodes, null, 'C')!
    nodes = [...nodes, c]

    // C 置顶后应排最前。
    const pinned = togglePinned(nodes, c.id)
    expect(childrenOf(pinned, null).map(n => n.name)).toEqual(['C', 'A', 'B'])
  })

  it('depthOf：根的子节点为第 1 层，嵌套更深依次递增', () => {
    const { root, outline, worldview, story } = buildTree()
    expect(depthOf(root.nodes, outline.id)).toBe(1)
    expect(depthOf(root.nodes, worldview.id)).toBe(2)
    expect(depthOf(root.nodes, story.id)).toBe(3)
  })

  it('depthOf 对环数据返回 0 而不是死循环', () => {
    const a: ProjectNode = { id: 'a', name: 'a', kind: 'folder', parentId: 'b', pinned: false, order: 0, expanded: true }
    const b: ProjectNode = { id: 'b', name: 'b', kind: 'folder', parentId: 'a', pinned: false, order: 0, expanded: true }
    expect(depthOf([a, b], 'a')).toBe(0)
  })

  it('canHostChildren 在第 2 层目录上返回 false（到达嵌套上限）', () => {
    const { root, outline, worldview, story } = buildTree()
    expect(canHostChildren(root.nodes, null)).toBe(true)
    expect(canHostChildren(root.nodes, outline.id)).toBe(true)
    // 世界观是第 2 层目录，再往下放就超限了。
    expect(canHostChildren(root.nodes, worldview.id)).toBe(false)
    // 文件节点同样不能当父级。
    expect(canHostChildren(root.nodes, story.id)).toBe(false)
  })
  it('canHostFiles 比目录多允许一层：第 2 层目录仍能放文件', () => {
    const { root, outline, worldview, story } = buildTree()
    expect(canHostFiles(root.nodes, null)).toBe(true)
    expect(canHostFiles(root.nodes, outline.id)).toBe(true)
    // 世界观是第 2 层目录：不能再放目录，但可以放文件（文件落在第 3 层）。
    expect(canHostChildren(root.nodes, worldview.id)).toBe(false)
    expect(canHostFiles(root.nodes, worldview.id)).toBe(true)
    // 文件节点自身不是容器，两种能力都必须是 false。
    expect(canHostFiles(root.nodes, story.id)).toBe(false)
    expect(canHostChildren(root.nodes, story.id)).toBe(false)
  })

  it('descendantIds 收集整棵子树', () => {
    const { root, outline, worldview, story } = buildTree()
    expect([...descendantIds(root.nodes, outline.id)].sort()).toEqual(
      [worldview.id, story.id].sort(),
    )
  })

  it('subtreeDepth 描述子树高度', () => {
    const { root, outline, story, worldview } = buildTree()
    expect(subtreeDepth(root.nodes, story.id)).toBe(0)
    expect(subtreeDepth(root.nodes, worldview.id)).toBe(1)
    expect(subtreeDepth(root.nodes, outline.id)).toBe(2)
  })
})

describe('projectStore 变更操作', () => {
  it('createFolderNode 超过深度上限时返回 null', () => {
    const { root, worldview } = buildTree()
    expect(createFolderNode(root.nodes, worldview.id, '再一层')).toBeNull()
  })

  it('createFileNode 允许在第 2 层目录下建文件（文件落在第 3 层）', () => {
    const { root, worldview } = buildTree()
    // 世界观（第 2 层目录）不能再放目录，但可以放文件。
    expect(createFolderNode(root.nodes, worldview.id, '再一层')).toBeNull()
    const deepFile = createFileNode(root.nodes, worldview.id, '结局.md')
    expect(deepFile).not.toBeNull()
    expect(deepFile!.kind).toBe('file')
    expect(depthOf([...root.nodes, deepFile!], deepFile!.id)).toBe(MAX_NEST_DEPTH + 1)
  })

  it('createFileNode 拒绝把文件挂到文件节点或无 parent 之外的非容器上', () => {
    const { root, story } = buildTree()
    // 文件不是容器，不能当父级。
    expect(createFileNode(root.nodes, story.id, '嵌套.md')).toBeNull()
  })

  it('renameNode 只改目标节点，不 mutate 入参', () => {
    const { root, outline } = buildTree()
    const before = JSON.stringify(root.nodes)
    const next = renameNode(root.nodes, outline.id, '大纲')
    expect(findNode(next, outline.id)!.name).toBe('大纲')
    expect(JSON.stringify(root.nodes)).toBe(before)
  })

  it('toggleExpanded 翻转展开态', () => {
    const { root, outline } = buildTree()
    expect(findNode(root.nodes, outline.id)!.expanded).toBe(true)
    const next = toggleExpanded(root.nodes, outline.id)
    expect(findNode(next, outline.id)!.expanded).toBe(false)
  })

  it('togglePinned 在开与关之间切换', () => {
    const { root, outline } = buildTree()
    const on = togglePinned(root.nodes, outline.id)
    expect(findNode(on, outline.id)!.pinned).toBe(true)
    const off = togglePinned(on, outline.id)
    expect(findNode(off, outline.id)!.pinned).toBe(false)
  })

  it('removeNode 连带删除整棵子树', () => {
    const { root, outline, worldview, story } = buildTree()
    const next = removeNode(root.nodes, outline.id)
    expect(findNode(next, outline.id)).toBeNull()
    expect(findNode(next, worldview.id)).toBeNull()
    expect(findNode(next, story.id)).toBeNull()
  })

  it('addNode 语义由 createFileNode 覆盖：文件挂到目录下且 order 递增', () => {
    let nodes: ProjectNode[] = []
    const f1 = createFolderNode(nodes, null, 'A')!
    nodes = [...nodes, f1]
    const f2 = createFolderNode(nodes, null, 'B')!
    expect(f2.order).toBe(f1.order + 1)
  })
})

describe('projectStore 拖拽合法性', () => {
  it('不能拖到自己身上', () => {
    const { root, outline } = buildTree()
    expect(canMoveNode(root.nodes, outline.id, outline.id)).toEqual({
      ok: false,
      reason: 'SELF',
    })
  })

  it('不能拖进自己的后代里（防成环）', () => {
    const { root, outline, worldview } = buildTree()
    expect(canMoveNode(root.nodes, outline.id, worldview.id)).toEqual({
      ok: false,
      reason: 'INTO_DESCENDANT',
    })
  })

  it('拖到已经是第 2 层的目录下会被拒绝（超深）', () => {
    const { root, worldview } = buildTree()
    const extra = createFolderNode([], null, '游离')!
    const nodes = [...root.nodes, extra]
    expect(canMoveNode(nodes, extra.id, worldview.id)).toEqual({
      ok: false,
      reason: 'TOO_DEEP',
    })
  })

  it('带子树拖到第 1 层目录下：子树高度会顶破上限，拒绝', () => {
    // 大纲类(1) -> 世界观(2) -> 剧情(3)，把「大纲类」拖进另一个第 1 层目录，
    // 会使世界观变第 3 层、剧情变第 4 层，必须拒绝。
    const { root, outline } = buildTree()
    const peer = createFolderNode(root.nodes, null, '其他')!
    const nodes = [...root.nodes, peer]
    expect(canMoveNode(nodes, outline.id, peer.id)).toEqual({
      ok: false,
      reason: 'TOO_DEEP',
    })
  })

  it('目录带一个文件拖到第 1 层目录下：各层刚好达标，允许（回归用例）', () => {
    // 场景：一个目录 dir 里面挂着一个文件 a.md（子树高度 1）。
    // 把 dir 拖到另一个第 1 层目录 peer 下 → dir 变第 2 层（目录上限 2，刚好），
    // a.md 变第 3 层（文件上限 3，刚好）。逐层按类型判应放行，
    // 不能再被「2 + 1 > 2」这种统一 maxDepth 误拒。
    let nodes: ProjectNode[] = []
    const peer = createFolderNode(nodes, null, '目标层')!
    nodes = [...nodes, peer]
    const dir = createFolderNode(nodes, null, '搬运目录')!
    nodes = [...nodes, dir]
    const file = createFileNode(nodes, dir.id, 'a.md')!
    nodes = [...nodes, file]
    // 预校验：dir 在第 1 层、file 在第 2 层。
    expect(depthOf(nodes, dir.id)).toBe(1)
    expect(depthOf(nodes, file.id)).toBe(2)

    expect(canMoveNode(nodes, dir.id, peer.id)).toEqual({ ok: true })
    const moved = moveNode(nodes, dir.id, peer.id)
    expect(findNode(moved, dir.id)!.parentId).toBe(peer.id)
    // 移动后 dir 在第 2 层、file 在第 3 层，均为各自类型上限内。
    expect(depthOf(moved, dir.id)).toBe(2)
    expect(depthOf(moved, file.id)).toBe(MAX_NEST_DEPTH + 1)
  })

  it('目录连拖两层文件会超限：拒绝', () => {
    // 构造：dir(1) 带子目录 inner(2)；把 dir 拖到第 1 层目录 peer 下，
    // dir 变第 2 层（目录上限 2，刚好），inner 变第 3 层（目录超过上限 2）→ 拒绝。
    let nodes: ProjectNode[] = []
    const peer = createFolderNode(nodes, null, '目标层')!
    nodes = [...nodes, peer]
    const dir = createFolderNode(nodes, null, '搬运目录')!
    nodes = [...nodes, dir]
    const inner = createFolderNode(nodes, dir.id, '内层')!
    nodes = [...nodes, inner]
    expect(canMoveNode(nodes, dir.id, peer.id)).toEqual({
      ok: false,
      reason: 'TOO_DEEP',
    })
  })

  it('合法移动：把文件从深层拖到顶层目录', () => {
    const { root, story, outline } = buildTree()
    const check = canMoveNode(root.nodes, story.id, outline.id)
    // 世界观(2) 下的文件拖到 大纲类(1) 下是合法的。
    expect(check).toEqual({ ok: true })
    const moved = moveNode(root.nodes, story.id, outline.id)
    expect(findNode(moved, story.id)!.parentId).toBe(outline.id)
  })

  it('非法移动时 moveNode 原样返回，不改数据', () => {
    const { root, outline, worldview } = buildTree()
    const next = moveNode(root.nodes, outline.id, worldview.id)
    expect(findNode(next, outline.id)!.parentId).toBe(null)
  })

  it('节点不存在时返回 NOT_FOUND', () => {
    const { root } = buildTree()
    expect(canMoveNode(root.nodes, '不存在', null)).toEqual({
      ok: false,
      reason: 'NOT_FOUND',
    })
  })

  it('MAX_NEST_DEPTH 约束与用户给的路径深度一致', () => {
    // 裂境营火GDD / 大纲类 / 世界观 / 剧情.md  = 根(0) + 两层目录 + 文件
    const { root, story } = buildTree()
    expect(depthOf(root.nodes, story.id)).toBe(MAX_NEST_DEPTH + 1)
  })
})

describe('projectStore 持久化容错', () => {
  it('坏 JSON 返回空数组', () => {
    expect(parseProjects('{不是 json')).toEqual([])
  })

  it('非数组返回空数组', () => {
    expect(parseProjects('{"a":1}')).toEqual([])
  })

  it('缺 id 的项目被丢弃', () => {
    expect(parseProjects(JSON.stringify([{ name: 'x' }]))).toEqual([])
  })

  it('缺 id 的节点被丢弃，合法节点保留', () => {
    const parsed = parseProjects(
      JSON.stringify([
        {
          id: 'p1',
          name: '项目',
          nodes: [
            { name: '坏节点' },
            { id: 'n1', name: '好节点', kind: 'folder' },
          ],
        },
      ]),
    )
    expect(parsed).toHaveLength(1)
    expect(parsed[0].nodes.map(n => n.id)).toEqual(['n1'])
  })

  it('悬挂的 parentId 被提升到顶层，而不是丢节点', () => {
    const parsed = parseProjects(
      JSON.stringify([
        {
          id: 'p1',
          name: '项目',
          nodes: [{ id: 'n1', name: '孤儿', kind: 'folder', parentId: '不存在的父' }],
        },
      ]),
    )
    expect(parsed[0].nodes[0].parentId).toBe(null)
  })

  it('缺字段的节点被补上安全默认值', () => {
    const parsed = parseProjects(
      JSON.stringify([{ id: 'p1', nodes: [{ id: 'n1' }] }]),
    )
    const node = parsed[0].nodes[0]
    expect(node.kind).toBe('folder')
    expect(node.pinned).toBe(false)
    expect(node.order).toBe(0)
    expect(node.expanded).toBe(true)
    expect(node.name).toBe('未命名目录')
  })

  it('archive 往返：写进去的能原样读出来', () => {
    const { root } = buildTree()
    const store = new Map<string, string>()
    const storage = {
      getItem: (key: string) => store.get(key) ?? null,
      setItem: (key: string, value: string) => {
        store.set(key, value)
      },
    }
    writeProjects([root], storage)
    expect(store.has(PROJECT_STORAGE_KEY)).toBe(true)
    const back = readProjects(storage)
    expect(back[0].nodes.length).toBe(root.nodes.length)
    expect(back[0].name).toBe('裂境营火GDD')
  })

  it('存储抛异常时 read/write 静默降级', () => {
    const hostile = {
      getItem: () => {
        throw new Error('boom')
      },
      setItem: () => {
        throw new Error('boom')
      },
    }
    expect(readProjects(hostile)).toEqual([])
    expect(() => writeProjects([], hostile)).not.toThrow()
  })

  it('重复 project id 只保留第一条', () => {
    const parsed = parseProjects(
      JSON.stringify([
        { id: 'p1', name: '第一' },
        { id: 'p1', name: '第二' },
      ]),
    )
    expect(parsed).toHaveLength(1)
    expect(parsed[0].name).toBe('第一')
  })
})

describe('projectStore 项目管理', () => {
  it('upsertProject 新增或整条替换', () => {
    const a = createProjectRecord('A')
    const b = createProjectRecord('B')
    expect(upsertProject([a], b)).toHaveLength(2)
    const renamed = { ...a, name: 'A2' }
    const next = upsertProject([a], renamed)
    expect(next).toHaveLength(1)
    expect(next[0].name).toBe('A2')
  })

  it('removeProject 按 id 删除', () => {
    const a = createProjectRecord('A')
    const b = createProjectRecord('B')
    expect(removeProject([a, b], a.id).map(p => p.id)).toEqual([b.id])
  })

  it('sortProjects 置顶项目排前面', () => {
    const lo = { ...createProjectRecord('lo'), createdAt: '2026-01-01T00:00:00.000Z' }
    const hi = {
      ...createProjectRecord('hi'),
      pinned: true,
      createdAt: '2026-12-01T00:00:00.000Z',
    }
    expect(sortProjects([lo, hi]).map(p => p.name)).toEqual(['hi', 'lo'])
  })

  it('flattenProjectFiles 只收文件节点，并给出完整路径', () => {
    const { root, story } = buildTree()
    const flat = flattenProjectFiles(root)
    expect(flat).toHaveLength(1)
    expect(flat[0].nodeId).toBe(story.id)
    expect(flat[0].relativePath).toBe('大纲类/世界观/剧情.md')
    expect(flat[0].projectName).toBe('裂境营火GDD')
  })

  it('nodePath 拼出目录路径', () => {
    const { root, story } = buildTree()
    expect(nodePath(root.nodes, findNode(root.nodes, story.id)!)).toBe(
      '大纲类/世界观/剧情.md',
    )
  })

  it('toIndexedFileRef 保留 uri 与文件名', () => {
    const ref = toIndexedFileRef({
      uri: 'content://com.android.providers.downloads.documents/document/1',
      name: '剧情.md',
    })
    expect(ref.source).toBe('indexed')
    expect(ref.fileName).toBe('剧情.md')
    expect(ref.persisted).toBe(true)
    expect(ref.providerName).toBe('com.android.providers.downloads.documents')
  })
})

describe('projectStore 类型守卫', () => {
  it('ProjectRecord 可序列化且字段稳定', () => {
    const record: ProjectRecord = createProjectRecord('测试')
    const round = JSON.parse(JSON.stringify(record)) as ProjectRecord
    expect(round.schemaVersion).toBe(2)
    expect(round.expanded).toBe(true)
    expect(round.nodes).toEqual([])
  })
})