import { describe, expect, it } from 'vitest'
import {
  MAX_NEST_DEPTH,
  boundFileRef,
  boundFolderUri,
  buildMirrorNodes,
  childrenOf,
  createProjectRecord,
  isBoundProject,
  parseProjects,
  serializeProjects,
  type ProjectBoundTree,
} from './projectStore'

/** 确定性 id 生成器，便于断言。 */
function idGen() {
  let n = 0
  return () => {
    n += 1
    return `n${n}`
  }
}

const entry = (uri: string, name: string, relativePath: string) => ({
  uri,
  name,
  relativePath,
})

/** 目录条目（isDirectory: true）。 */
const dirEntry = (uri: string, name: string, relativePath: string) => ({
  uri,
  name,
  relativePath,
  isDirectory: true,
})

describe('buildMirrorNodes 镜像树构建', () => {
  it('根级文件直接挂在项目根下', () => {
    const nodes = buildMirrorNodes([entry('u1', 'a.md', 'a.md')], { idFactory: idGen() })
    expect(nodes).toHaveLength(1)
    expect(nodes[0]).toMatchObject({ name: 'a.md', kind: 'file', parentId: null })
    expect(nodes[0].fileRef?.uri).toBe('u1')
    expect(nodes[0].fileRef?.source).toBe('indexed')
  })

  it('嵌套目录逐级建虚拟目录，文件挂末级', () => {
    // relativePath 是相对「绑定根」的路径：绑定根裂境本身不在路径里。
    const nodes = buildMirrorNodes(
      [entry('u1', '111.md', '大纲/二三/111.md')],
      { idFactory: idGen() },
    )
    const folders = nodes.filter(n => n.kind === 'folder').map(n => n.name)
    expect(folders).toEqual(['大纲', '二三'])
    const file = nodes.find(n => n.kind === 'file')
    expect(file?.name).toBe('111.md')
    // 文件挂在最深目录「二三」下。
    const deepest = nodes.find(n => n.name === '二三')
    expect(file?.parentId).toBe(deepest?.id)
  })

  it('同目录被多个文件复用（不会重复建目录节点）', () => {
    const nodes = buildMirrorNodes(
      [
        entry('u1', 'a.md', 'x/a.md'),
        entry('u2', 'b.md', 'x/b.md'),
        entry('u3', 'c.md', 'x/y/c.md'),
      ],
      { idFactory: idGen() },
    )
    const xFolders = nodes.filter(n => n.kind === 'folder' && n.name === 'x')
    expect(xFolders).toHaveLength(1)
    const files = nodes.filter(n => n.kind === 'file')
    expect(files).toHaveLength(3)
    // a、b 同父；c 挂在 y 下。
    const a = files.find(f => f.name === 'a.md')!
    const b = files.find(f => f.name === 'b.md')!
    expect(a.parentId).toBe(b.parentId)
  })

  it('超过目录深度上限的条目被整体丢弃', () => {
    // 目录链 3 层 > MAX_NEST_DEPTH(2)。
    const deep = 'a/b/c/d.md'
    expect(MAX_NEST_DEPTH).toBe(2)
    const nodes = buildMirrorNodes([entry('u1', 'd.md', deep)], { idFactory: idGen() })
    expect(nodes).toHaveLength(0)
  })

  it('恰好达到目录深度上限的条目保留', () => {
    // a/b/f.md：目录链 2 层 = MAX_NEST_DEPTH。
    const nodes = buildMirrorNodes([entry('u1', 'f.md', 'a/b/f.md')], { idFactory: idGen() })
    const folders = nodes.filter(n => n.kind === 'folder').map(n => n.name)
    expect(folders).toEqual(['a', 'b'])
    expect(nodes.some(n => n.kind === 'file')).toBe(true)
  })

  it('兼容反斜杠分隔符并去前导斜杠', () => {
    const nodes = buildMirrorNodes(
      [entry('u1', 'a.md', '\\dir\\a.md'), entry('u2', 'b.md', '/top/b.md')],
      { idFactory: idGen() },
    )
    const folderNames = nodes.filter(n => n.kind === 'folder').map(n => n.name).sort()
    expect(folderNames).toEqual(['dir', 'top'])
  })

  it('order 在同级内递增、不重复', () => {
    const nodes = buildMirrorNodes(
      [
        entry('u1', 'a.md', 'a.md'),
        entry('u2', 'b.md', 'b.md'),
        entry('u3', 'c.md', 'c.md'),
      ],
      { idFactory: idGen() },
    )
    const roots = childrenOf(nodes, null)
    expect(roots.map(n => n.order)).toEqual([0, 1, 2])
  })

  it('relativePath 为空时回退用 name', () => {
    const nodes = buildMirrorNodes([entry('u1', 'a.md', '')], { idFactory: idGen() })
    expect(nodes).toHaveLength(1)
    expect(nodes[0]).toMatchObject({ name: 'a.md', kind: 'file' })
  })

  it('relativePath 与 name 都为空时忽略', () => {
    const nodes = buildMirrorNodes([entry('u1', '', '')], { idFactory: idGen() })
    expect(nodes).toHaveLength(0)
  })

  it('忽略 .versions 目录及其整棵子树（不读不显示）', () => {
    const nodes = buildMirrorNodes(
      [
        entry('u1', '正文.md', '正文.md'),
        dirEntry('u2', '.versions', '.versions'),
        entry('u3', 'v1.md', '.versions/00_术语表/v1.md'),
        entry('u4', 'v2.md', '大纲/.versions/v2.md'),
        entry('u5', '111.md', '大纲/111.md'),
      ],
      { idFactory: idGen() },
    )
    // 不含任何 .versions 目录节点。
    expect(nodes.some(n => n.kind === 'folder' && n.name === '.versions')).toBe(false)
    // 不含 .versions 里的快照文件。
    expect(nodes.some(n => n.name === 'v1.md' || n.name === 'v2.md')).toBe(false)
    // 正常内容不受影响。
    expect(nodes.some(n => n.name === '正文.md')).toBe(true)
    expect(nodes.some(n => n.name === '111.md')).toBe(true)
    expect(nodes.some(n => n.kind === 'folder' && n.name === '大纲')).toBe(true)
  })
})

describe('isBoundProject / boundTree 持久化', () => {
  const bound: ProjectBoundTree = {
    treeUri: 'content://com.android.externalstorage.documents/tree/primary%3ADocs',
    displayName: 'Docs',
    canWrite: true,
    scannedAt: '2026-10-01T00:00:00.000Z',
  }

  it('有 boundTree 视为已绑定；无则不绑定', () => {
    const plain = createProjectRecord('纯虚拟')
    expect(isBoundProject(plain)).toBe(false)
    const linked = createProjectRecord('已绑定', { boundTree: bound })
    expect(isBoundProject(linked)).toBe(true)
  })

  it('boundTree 能序列化并原样解析回来', () => {
    const project = createProjectRecord('已绑定', { boundTree: bound })
    const round = parseProjects(serializeProjects([project]))
    expect(round).toHaveLength(1)
    expect(round[0].boundTree).toEqual(bound)
  })

  it('缺 treeUri 的 boundTree 解析为丢弃（不留半残元数据）', () => {
    const raw = JSON.stringify([
      {
        id: 'p1',
        name: 'x',
        pinned: false,
        expanded: true,
        nodes: [],
        createdAt: '2026-01-01T00:00:00.000Z',
        schemaVersion: 2,
        boundTree: { displayName: 'bad', canWrite: true },
      },
    ])
    const round = parseProjects(raw)
    expect(round[0].boundTree).toBeUndefined()
    expect(isBoundProject(round[0])).toBe(false)
  })

  it('旧数据（无 boundTree 字段）解析后不带 boundTree', () => {
    const raw = JSON.stringify([
      {
        id: 'p1',
        name: 'x',
        pinned: false,
        expanded: true,
        nodes: [],
        createdAt: '2026-01-01T00:00:00.000Z',
        schemaVersion: 2,
      },
    ])
    const round = parseProjects(raw)
    expect(round[0].boundTree).toBeUndefined()
  })

  it('绑定生成的镜像树经序列化往返后结构不变', () => {
    const nodes = buildMirrorNodes([entry('u1', 'a.md', 'dir/a.md')], { idFactory: idGen() })
    const project = { ...createProjectRecord('p', { boundTree: bound }), nodes }
    const round = parseProjects(serializeProjects([project]))
    expect(round[0].nodes.map(n => ({ name: n.name, kind: n.kind }))).toEqual([
      { name: 'dir', kind: 'folder' },
      { name: 'a.md', kind: 'file' },
    ])
  })

  it('目录节点的 dirUri 经序列化往返后保留（否则重启后无法写盘）', () => {
    const nodes = buildMirrorNodes([dirEntry('dir-uri-keep', '大纲', '大纲')], {
      idFactory: idGen(),
    })
    const project = { ...createProjectRecord('p', { boundTree: bound }), nodes }
    const round = parseProjects(serializeProjects([project]))
    const folder = round[0].nodes.find(n => n.kind === 'folder')
    expect(folder?.dirUri).toBe('dir-uri-keep')
  })
})

describe('buildMirrorNodes 目录条目（双向可写前提）', () => {
  it('目录条目也会生成目录节点，并带上 dirUri', () => {
    const nodes = buildMirrorNodes(
      [dirEntry('dir-uri-1', '大纲', '大纲')],
      { idFactory: idGen() },
    )
    expect(nodes).toHaveLength(1)
    expect(nodes[0]).toMatchObject({ name: '大纲', kind: 'folder', parentId: null })
    expect(nodes[0].dirUri).toBe('dir-uri-1')
  })

  it('空目录（无任何文件）也能出现', () => {
    const nodes = buildMirrorNodes([dirEntry('dir-uri-2', '空目录', '空目录')], {
      idFactory: idGen(),
    })
    expect(nodes.map(n => n.name)).toEqual(['空目录'])
  })

  it('目录条目即便排在文件之后，目录节点的 dirUri 也不会丢', () => {
    // 文件在前、目录条目在后：第一遍预登记保证 dirUri 仍能补上。
    const nodes = buildMirrorNodes(
      [
        entry('u1', 'a.md', 'log/a.md'),
        dirEntry('dir-uri-3', 'log', 'log'),
      ],
      { idFactory: idGen() },
    )
    const folder = nodes.find(n => n.kind === 'folder' && n.name === 'log')
    expect(folder?.dirUri).toBe('dir-uri-3')
  })

  it('嵌套目录各自记住自己的 dirUri', () => {
    const nodes = buildMirrorNodes(
      [
        dirEntry('d1', 'a', 'a'),
        dirEntry('d2', 'b', 'a/b'),
        entry('u1', 'f.md', 'a/b/f.md'),
      ],
      { idFactory: idGen() },
    )
    const a = nodes.find(n => n.name === 'a')
    const b = nodes.find(n => n.name === 'b')
    expect(a?.dirUri).toBe('d1')
    expect(b?.dirUri).toBe('d2')
  })

  it('超过深度上限的目录条目被丢弃', () => {
    const nodes = buildMirrorNodes(
      [dirEntry('d1', 'c', 'a/b/c')],
      { idFactory: idGen() },
    )
    expect(nodes).toHaveLength(0)
  })

  it('文件条目不带 dirUri（只有目录才需要）', () => {
    const nodes = buildMirrorNodes([entry('u1', 'a.md', 'a.md')], { idFactory: idGen() })
    expect(nodes[0].dirUri).toBeUndefined()
  })
})

describe('boundFolderUri 目录 → 实体 URI', () => {
  const treeUri = 'content://com.android.externalstorage.documents/tree/primary%3ADocs'
  const bound: ProjectBoundTree = {
    treeUri,
    displayName: 'Docs',
    canWrite: true,
    scannedAt: '2026-10-01T00:00:00.000Z',
  }

  it('根目录（folderId=null）返回绑定树 URI', () => {
    const project = createProjectRecord('p', { boundTree: bound })
    expect(boundFolderUri(project, null)).toBe(treeUri)
  })

  it('目录节点返回自身 dirUri', () => {
    const nodes = buildMirrorNodes([dirEntry('dir-uri-x', '大纲', '大纲')], {
      idFactory: idGen(),
    })
    const project = { ...createProjectRecord('p', { boundTree: bound }), nodes }
    const folder = nodes[0]
    expect(boundFolderUri(project, folder.id)).toBe('dir-uri-x')
  })

  it('镜像目录缺 dirUri 时退回根 URI（老数据兜底）', () => {
    const nodes = buildMirrorNodes([dirEntry('dir-uri-y', 'a', 'a')], { idFactory: idGen() })
    // 手动抹掉 dirUri，模拟 v0.4.11 之前生成的镜像数据。
    nodes[0].dirUri = undefined
    const project = { ...createProjectRecord('p', { boundTree: bound }), nodes }
    expect(boundFolderUri(project, nodes[0].id)).toBe(treeUri)
  })

  it('未绑定项目返回 null', () => {
    const project = createProjectRecord('p')
    expect(boundFolderUri(project, null)).toBeNull()
  })
})

describe('boundFileRef 新建实体文件的引用', () => {
  it('provider 从绑定树 URI 取，source 为 indexed', () => {
    const treeUri = 'content://com.android.externalstorage.documents/tree/primary%3ADocs'
    const ref = boundFileRef(treeUri, { uri: 'doc-uri-1', name: '新.md' })
    expect(ref).toMatchObject({
      uri: 'doc-uri-1',
      fileName: '新.md',
      providerName: 'com.android.externalstorage.documents',
      persisted: true,
      source: 'indexed',
    })
  })
})
