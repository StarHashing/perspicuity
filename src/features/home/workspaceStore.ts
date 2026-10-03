import type { WorkspaceDirectory, WorkspaceDocumentEntry } from '../../lib/workspace'

/**
 * 绑定目录的持久化清单。
 *
 * 只存「绑定关系」本身（树 URI + 显示名 + 权限状态），不存文件内容。
 * 文件清单每次都从原生实时扫描，因为用户随时可能在别处改动目录内容，
 * 缓存一份就会过期。
 */
export interface WorkspaceRecord {
  /** 稳定标识，由 treeUri 派生，用于列表 key 与去重。 */
  id: string
  treeUri: string
  displayName: string
  canWrite: boolean
  addedAt: string
}

export interface WorkspaceStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
}

export const WORKSPACE_STORAGE_KEY = 'perspicuity.workspaces.v1'

export function createWorkspaceRecord(
  directory: WorkspaceDirectory,
  addedAt: string = new Date().toISOString(),
): WorkspaceRecord {
  return {
    id: `workspace:${directory.treeUri}`,
    treeUri: directory.treeUri,
    displayName: directory.displayName || directory.treeUri,
    canWrite: directory.canWrite,
    addedAt,
  }
}

/**
 * 解析存储里的绑定记录。
 *
 * 容错优先：损坏的 JSON、非数组、缺字段的条目一律丢弃而不是抛异常——
 * 一份坏掉的偏好设置不该让「文件」页整个白屏。
 */
export function parseWorkspaceRecords(value: string | null): WorkspaceRecord[] {
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
  const seen = new Set<string>()
  const records: WorkspaceRecord[] = []
  for (const entry of parsed) {
    if (typeof entry !== 'object' || entry === null) {
      continue
    }
    const candidate = entry as Partial<WorkspaceRecord>
    if (typeof candidate.treeUri !== 'string' || candidate.treeUri.length === 0) {
      continue
    }
    const id =
      typeof candidate.id === 'string' && candidate.id.length > 0
        ? candidate.id
        : `workspace:${candidate.treeUri}`
    if (seen.has(id)) {
      continue
    }
    seen.add(id)
    records.push({
      id,
      treeUri: candidate.treeUri,
      displayName:
        typeof candidate.displayName === 'string' && candidate.displayName.length > 0
          ? candidate.displayName
          : candidate.treeUri,
      canWrite: candidate.canWrite === true,
      addedAt:
        typeof candidate.addedAt === 'string' && candidate.addedAt.length > 0
          ? candidate.addedAt
          : new Date().toISOString(),
    })
  }
  return records
}

export function serializeWorkspaceRecords(records: WorkspaceRecord[]): string {
  return JSON.stringify(records)
}

export function readWorkspaceRecords(
  storage: WorkspaceStorage,
): WorkspaceRecord[] {
  try {
    return parseWorkspaceRecords(storage.getItem(WORKSPACE_STORAGE_KEY))
  } catch {
    return []
  }
}

export function writeWorkspaceRecords(
  records: WorkspaceRecord[],
  storage: WorkspaceStorage,
) {
  try {
    storage.setItem(WORKSPACE_STORAGE_KEY, serializeWorkspaceRecords(records))
  } catch {
    // 存储不可用时静默降级：绑定只在本次会话有效，不阻断使用。
  }
}

/** 加入一条绑定；同一 treeUri 重复绑定视为更新显示名，而不是新增一条。 */
export function upsertWorkspaceRecord(
  records: WorkspaceRecord[],
  record: WorkspaceRecord,
): WorkspaceRecord[] {
  const index = records.findIndex(item => item.id === record.id)
  if (index < 0) {
    return [...records, record]
  }
  const next = [...records]
  next[index] = { ...next[index], ...record, addedAt: next[index].addedAt }
  return next
}

export function removeWorkspaceRecord(
  records: WorkspaceRecord[],
  id: string,
): WorkspaceRecord[] {
  return records.filter(record => record.id !== id)
}

/** 搜索用的扁平条目：绑定目录内的一个文件 + 它属于哪个工作区。 */
export interface WorkspaceSearchEntry {
  /** 全局唯一：workspaceId + 文件 URI。 */
  id: string
  workspaceId: string
  workspaceName: string
  name: string
  relativePath: string
  uri: string
  modified: number
}

export function toWorkspaceSearchEntry(
  workspace: WorkspaceRecord,
  file: WorkspaceDocumentEntry,
): WorkspaceSearchEntry {
  return {
    id: `${workspace.id}::${file.uri}`,
    workspaceId: workspace.id,
    workspaceName: workspace.displayName,
    name: file.name,
    relativePath: file.relativePath,
    uri: file.uri,
    modified: file.modified,
  }
}

export interface WorkspaceSearchResult {
  entry: WorkspaceSearchEntry
  score: number
}

/**
 * 按文件名/相对路径做模糊匹配排序。
 *
 * 规则简单且可预测（不做拼音、不做分词——Markdown 文件名以中英文短词为主）：
 *   1. 命中在文件名开头 > 命中在文件名中段 > 只命中路径；
 *   2. 大小写不敏感；
 *   3. 命中位置越靠前分越高，文件名越短分越高（短名精确命中应该排前面）。
 * 空查询返回空数组——「没输入」不等于「全部」。
 */
export function searchWorkspaceEntries(
  entries: readonly WorkspaceSearchEntry[],
  query: string,
  limit = 40,
): WorkspaceSearchResult[] {
  const needle = query.trim().toLowerCase()
  if (!needle) {
    return []
  }
  const results: WorkspaceSearchResult[] = []
  for (const entry of entries) {
    const name = entry.name.toLowerCase()
    const path = entry.relativePath.toLowerCase()
    let score = 0
    const nameIndex = name.indexOf(needle)
    if (nameIndex === 0) {
      score = 100
    } else if (nameIndex > 0) {
      score = 70 - Math.min(nameIndex, 30)
    } else if (path.includes(needle)) {
      score = 30
    }
    if (score <= 0) {
      continue
    }
    // 同样命中时，短文件名优先——「GDD.md」比「GDD-详细设定-第一版.md」更可能是想要的。
    score += Math.max(0, 20 - Math.floor(name.length / 4))
    results.push({ entry, score })
  }
  results.sort((left, right) => {
    if (right.score !== left.score) {
      return right.score - left.score
    }
    return left.entry.name.localeCompare(right.entry.name)
  })
  return results.slice(0, limit)
}