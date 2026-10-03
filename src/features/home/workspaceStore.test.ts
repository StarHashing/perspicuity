import { describe, expect, it } from 'vitest'
import {
  createWorkspaceRecord,
  parseWorkspaceRecords,
  readWorkspaceRecords,
  removeWorkspaceRecord,
  searchWorkspaceEntries,
  serializeWorkspaceRecords,
  toWorkspaceSearchEntry,
  upsertWorkspaceRecord,
  writeWorkspaceRecords,
  WORKSPACE_STORAGE_KEY,
  type WorkspaceRecord,
  type WorkspaceSearchEntry,
} from './workspaceStore'
import type { WorkspaceDocumentEntry } from '../../lib/workspace'

function makeRecord(id: string, overrides: Partial<WorkspaceRecord> = {}): WorkspaceRecord {
  return {
    id,
    treeUri: `content://com.example.documents/tree/${id}`,
    displayName: id,
    canWrite: true,
    addedAt: '2024-01-01T00:00:00.000Z',
    ...overrides,
  }
}

function makeEntry(overrides: Partial<WorkspaceSearchEntry> = {}): WorkspaceSearchEntry {
  return {
    id: 'workspace:1::content://doc/1',
    workspaceId: 'workspace:1',
    workspaceName: 'GDD',
    name: 'world.md',
    relativePath: 'GDD/world.md',
    uri: 'content://doc/1',
    modified: 0,
    ...overrides,
  }
}

describe('createWorkspaceRecord', () => {
  it('derives a stable id from the tree URI so re-picking the same folder updates, not duplicates', () => {
    const directory = {
      treeUri: 'content://com.example.documents/tree/primary%3AGDD',
      displayName: 'GDD',
      canWrite: true,
    }
    const record = createWorkspaceRecord(directory, '2024-05-05T00:00:00.000Z')
    expect(record).toEqual({
      id: 'workspace:content://com.example.documents/tree/primary%3AGDD',
      treeUri: directory.treeUri,
      displayName: 'GDD',
      canWrite: true,
      addedAt: '2024-05-05T00:00:00.000Z',
    })
  })

  it('falls back to the tree URI when the provider reports no display name', () => {
    const record = createWorkspaceRecord({
      treeUri: 'content://doc/tree/xyz',
      displayName: '',
      canWrite: false,
    })
    expect(record.displayName).toBe('content://doc/tree/xyz')
  })
})

describe('parseWorkspaceRecords', () => {
  it('returns an empty list for null, empty, malformed or non-array payloads', () => {
    expect(parseWorkspaceRecords(null)).toEqual([])
    expect(parseWorkspaceRecords('')).toEqual([])
    expect(parseWorkspaceRecords('{not json')).toEqual([])
    expect(parseWorkspaceRecords('{"a":1}')).toEqual([])
  })

  it('drops entries without a usable tree URI instead of failing the whole read', () => {
    const value = JSON.stringify([
      { treeUri: 'content://doc/tree/ok', displayName: 'OK', canWrite: true },
      { displayName: 'No URI' },
      { treeUri: 42 },
      null,
      'nope',
    ])
    const records = parseWorkspaceRecords(value)
    expect(records).toHaveLength(1)
    expect(records[0].treeUri).toBe('content://doc/tree/ok')
  })

  it('backfills the id from the tree URI when it is missing', () => {
    const value = JSON.stringify([{ treeUri: 'content://doc/tree/a', displayName: 'A' }])
    const records = parseWorkspaceRecords(value)
    expect(records[0].id).toBe('workspace:content://doc/tree/a')
  })

  it('de-duplicates records that resolve to the same id, keeping the first', () => {
    const value = JSON.stringify([
      { id: 'dup', treeUri: 'content://doc/tree/a', displayName: 'First' },
      { id: 'dup', treeUri: 'content://doc/tree/b', displayName: 'Second' },
    ])
    const records = parseWorkspaceRecords(value)
    expect(records).toHaveLength(1)
    expect(records[0].displayName).toBe('First')
  })

  it('never reports canWrite for a record that did not persist it', () => {
    const value = JSON.stringify([{ treeUri: 'content://doc/tree/a', canWrite: 'yes' }])
    expect(parseWorkspaceRecords(value)[0].canWrite).toBe(false)
  })
})

describe('read/write workspace records', () => {
  it('round-trips records through storage', () => {
    const store = new Map<string, string>()
    const storage = {
      getItem: (key: string) => store.get(key) ?? null,
      setItem: (key: string, value: string) => void store.set(key, value),
    }
    const records = [makeRecord('workspace:1')]
    writeWorkspaceRecords(records, storage)
    expect(store.get(WORKSPACE_STORAGE_KEY)).toBe(serializeWorkspaceRecords(records))
    expect(readWorkspaceRecords(storage)).toEqual(records)
  })

  it('survives a storage that throws (private mode / quota)', () => {
    const hostile = {
      getItem() {
        throw new Error('denied')
      },
      setItem() {
        throw new Error('denied')
      },
    }
    expect(readWorkspaceRecords(hostile)).toEqual([])
    expect(() => writeWorkspaceRecords([makeRecord('a')], hostile)).not.toThrow()
  })
})

describe('upsertWorkspaceRecord', () => {
  it('appends a new record and preserves the original addedAt on update', () => {
    const original = makeRecord('workspace:1', { addedAt: '2024-01-01T00:00:00.000Z' })
    const added = upsertWorkspaceRecord([], original)
    expect(added).toEqual([original])

    const updated = upsertWorkspaceRecord(added, {
      ...original,
      displayName: 'Renamed',
      addedAt: '2030-12-31T00:00:00.000Z',
    })
    expect(updated).toHaveLength(1)
    expect(updated[0].displayName).toBe('Renamed')
    expect(updated[0].addedAt).toBe('2024-01-01T00:00:00.000Z')
  })

  it('does not mutate the input list', () => {
    const records = [makeRecord('workspace:1')]
    upsertWorkspaceRecord(records, makeRecord('workspace:2'))
    expect(records).toHaveLength(1)
  })
})

describe('removeWorkspaceRecord', () => {
  it('removes by id and leaves the rest untouched', () => {
    const records = [makeRecord('a'), makeRecord('b')]
    expect(removeWorkspaceRecord(records, 'a')).toEqual([records[1]])
  })
})

describe('toWorkspaceSearchEntry', () => {
  it('qualifies the entry id with the workspace so two folders cannot collide', () => {
    const workspace = makeRecord('workspace:1', { displayName: 'GDD' })
    const file: WorkspaceDocumentEntry = {
      uri: 'content://doc/1',
      name: 'world.md',
      relativePath: 'GDD/world.md',
      size: 10,
      modified: 123,
      extension: '.md',
    }
    expect(toWorkspaceSearchEntry(workspace, file)).toEqual({
      id: 'workspace:1::content://doc/1',
      workspaceId: 'workspace:1',
      workspaceName: 'GDD',
      name: 'world.md',
      relativePath: 'GDD/world.md',
      uri: 'content://doc/1',
      modified: 123,
    })
  })
})

describe('searchWorkspaceEntries', () => {
  it('returns nothing for an empty query — "no input" is not "everything"', () => {
    const entries = [makeEntry()]
    expect(searchWorkspaceEntries(entries, '')).toEqual([])
    expect(searchWorkspaceEntries(entries, '   ')).toEqual([])
  })

  it('ranks a file-name prefix above a mid-name hit above a path-only hit', () => {
    const prefix = makeEntry({ id: 'p', name: 'world.md', relativePath: 'world.md' })
    const mid = makeEntry({ id: 'm', name: 'my-world-notes.md', relativePath: 'my-world-notes.md' })
    const pathOnly = makeEntry({ id: 'x', name: 'notes.md', relativePath: 'world/notes.md' })
    const results = searchWorkspaceEntries([pathOnly, mid, prefix], 'world')
    expect(results.map(result => result.entry.id)).toEqual(['p', 'm', 'x'])
  })

  it('matches case-insensitively', () => {
    const results = searchWorkspaceEntries([makeEntry({ name: 'GDD.md' })], 'gdd')
    expect(results).toHaveLength(1)
  })

  it('prefers the shorter name when both hit at the start', () => {
    const short = makeEntry({ id: 'short', name: 'gdd.md', relativePath: 'gdd.md' })
    const long = makeEntry({ id: 'long', name: 'gdd-design-document-v2.md', relativePath: 'x.md' })
    const results = searchWorkspaceEntries([long, short], 'gdd')
    expect(results[0].entry.id).toBe('short')
  })

  it('reports a score so callers can show or weight matches', () => {
    const results = searchWorkspaceEntries([makeEntry({ name: 'world.md' })], 'world')
    expect(results[0].score).toBeGreaterThan(0)
  })

  it('honours the result limit', () => {
    const entries = Array.from({ length: 5 }, (_, index) =>
      makeEntry({ id: `e${index}`, name: `note-${index}.md`, relativePath: `note-${index}.md` }),
    )
    expect(searchWorkspaceEntries(entries, 'note', 2)).toHaveLength(2)
  })
})
