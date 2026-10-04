<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from '../../lib/i18n'
import {
  getWorkspaceErrorCode,
  isPrivateProjectUri,
  isWorkspaceAvailable,
  listWorkspaceDocuments,
  openWorkspaceDocument,
  parsePrivateProjectUri,
  pickWorkspaceDirectory,
  readPrivateProjectFile,
  type WorkspaceOpenResult,
} from '../../lib/workspace'
import {
  createWorkspaceRecord,
  readWorkspaceRecords,
  removeWorkspaceRecord,
  searchWorkspaceEntries,
  toWorkspaceSearchEntry,
  upsertWorkspaceRecord,
  writeWorkspaceRecords,
  type WorkspaceRecord,
  type WorkspaceSearchEntry,
} from './workspaceStore'
import type { HomeDocumentItem } from './homeDocuments'
import {
  getAndroidDocumentUserMessage,
  shareAndroidMarkdownDocument,
} from '../../lib/androidDocuments'
import { getImageSharingSettings } from '../android-documents/imageSharingSettings'
import { getAdvancedSettings, getMarkdownSaveSettings } from '../settings/advancedSettings'
import { useSettingsState } from '../settings/settingsState'
import { projectTreeText } from './projectTreeText'
import ProjectTreePanel from './ProjectTreePanel.vue'

const props = defineProps<{
  /** 最近打开过的文档（首页「继续写作 / 最近」用的那份），一并纳入搜索。 */
  recentDocuments: HomeDocumentItem[]
}>()

const emit = defineEmits<{
  openFile: []
  /** 打开某个最近文档（复用首页的 openDocument 通道）。 */
  openDocument: [id: string]
  /** 打开绑定目录里的文件：把内容交给编辑器。 */
  openWorkspaceFile: [payload: WorkspaceOpenResult]
}>()

const { t, locale } = useI18n()

/**
 * 分享功能需要的设置读取。项目树面板用的是局部文案字典，这里也读一份
 * （locale 与 App 一致），用于分享提示文案。
 */
const treeText = computed(() => projectTreeText(locale.value))
const { getValue } = useSettingsState()

const workspaces = ref<WorkspaceRecord[]>([])
const entries = ref<WorkspaceSearchEntry[]>([])
const filesByWorkspace = ref<Record<string, WorkspaceSearchEntry[]>>({})
const query = ref('')
const busy = ref(false)
const scanning = ref(false)
const notice = ref<string | null>(null)
const activeWorkspaceId = ref<string | null>(null)

const supportsWorkspaces = isWorkspaceAvailable()

function loadStoredWorkspaces() {
  workspaces.value = readWorkspaceRecords(window.localStorage)
}

function persist() {
  writeWorkspaceRecords(workspaces.value, window.localStorage)
}

/** 把某个工作区的文件索引重建一遍，并刷新全局搜索池。 */
async function refreshWorkspace(workspace: WorkspaceRecord) {
  try {
    const { files } = await listWorkspaceDocuments(workspace.treeUri)
    const mapped = files.map(file => toWorkspaceSearchEntry(workspace, file))
    filesByWorkspace.value = { ...filesByWorkspace.value, [workspace.id]: mapped }
    rebuildEntryPool()
  } catch (error) {
    const code = getWorkspaceErrorCode(error)
    if (code === 'WORKSPACE_PERMISSION_LOST') {
      notice.value = t('files.permissionLost')
      // 权限没了的绑定留在列表里只会一直失败，直接摘掉。
      workspaces.value = removeWorkspaceRecord(workspaces.value, workspace.id)
      persist()
      const next = { ...filesByWorkspace.value }
      delete next[workspace.id]
      filesByWorkspace.value = next
      rebuildEntryPool()
      return
    }
    notice.value = t('files.scanFailed')
  }
}

function rebuildEntryPool() {
  entries.value = Object.values(filesByWorkspace.value).flat()
}

async function refreshAll() {
  if (!workspaces.value.length) {
    return
  }
  scanning.value = true
  for (const workspace of workspaces.value) {
    await refreshWorkspace(workspace)
  }
  scanning.value = false
}

async function bindLocalFolder() {
  if (!supportsWorkspaces || busy.value) {
    return
  }
  busy.value = true
  notice.value = null
  try {
    const directory = await pickWorkspaceDirectory()
    if (!directory) {
      return
    }
    const record = createWorkspaceRecord(directory)
    workspaces.value = upsertWorkspaceRecord(workspaces.value, record)
    persist()
    await refreshWorkspace(record)
    activeWorkspaceId.value = record.id
  } catch (error) {
    notice.value =
      getWorkspaceErrorCode(error) === 'FOLDER_PICKER_UNAVAILABLE'
        ? t('files.noPicker')
        : t('files.bindFailed')
  } finally {
    busy.value = false
  }
}

function unbindWorkspace(workspace: WorkspaceRecord) {
  workspaces.value = removeWorkspaceRecord(workspaces.value, workspace.id)
  persist()
  const next = { ...filesByWorkspace.value }
  delete next[workspace.id]
  filesByWorkspace.value = next
  rebuildEntryPool()
  if (activeWorkspaceId.value === workspace.id) {
    activeWorkspaceId.value = null
  }
}

async function openEntry(entry: WorkspaceSearchEntry) {
  if (busy.value) {
    return
  }
  busy.value = true
  notice.value = null
  try {
    const result = await openWorkspaceDocument(entry.uri)
    if (!result) {
      return
    }
    // 直接透传原生返回的完整结果：编辑器需要 canWrite / persisted 来决定
    // 能否写回、以及要不要重新申请授权。
    emit('openWorkspaceFile', result)
  } catch (error) {
    notice.value =
      getWorkspaceErrorCode(error) === 'WORKSPACE_PERMISSION_LOST'
        ? t('files.permissionLost')
        : t('files.openFailed')
  } finally {
    busy.value = false
  }
}

/** 项目树里点了某个被索引的文件：复用工作区打开通道（原生按 URI 判权读）。 */
async function openIndexedFileByUri(uri: string) {
  if (busy.value) {
    return
  }
  busy.value = true
  notice.value = null
  try {
    // 私有副本：uri 是 `perspicuity-private://<id>/<rel>`，走私有目录读取，
    // 打开后可写（canWrite 恒真），保存/返回都复用既有文档链路。
    if (isPrivateProjectUri(uri)) {
      const parsed = parsePrivateProjectUri(uri)
      if (!parsed) {
        notice.value = t('files.openFailed')
        return
      }
      const result = await readPrivateProjectFile(parsed.projectId, parsed.relPath)
      if (!result) {
        notice.value = t('files.openFailed')
        return
      }
      emit('openWorkspaceFile', result)
      return
    }
    const result = await openWorkspaceDocument(uri)
    if (!result) {
      return
    }
    emit('openWorkspaceFile', result)
  } catch (error) {
    notice.value =
      getWorkspaceErrorCode(error) === 'WORKSPACE_PERMISSION_LOST'
        ? t('files.permissionLost')
        : t('files.openFailed')
  } finally {
    busy.value = false
  }
}

/**
 * 项目树里长按「分享」一个被索引的文件。
 *
 * 与打开文件同源：私有副本走私有目录读取、SAF 走工作区读取，拿到 markdown 后
 * 套用用户的图片/编码设置调起系统分享面板。分享不修改源文件，也不进编辑器。
 */
async function shareIndexedFileByUri(payload: { uri: string; name: string }) {
  if (busy.value) {
    return
  }
  busy.value = true
  notice.value = null
  try {
    const markdown = await readIndexedMarkdown(payload.uri)
    if (markdown === null) {
      notice.value = treeText.value.shareFailed
      return
    }
    await shareMarkdownContent(payload.name, markdown)
  } catch (error) {
    notice.value = getWorkspaceErrorCode(error) === 'WORKSPACE_PERMISSION_LOST'
      ? t('files.permissionLost')
      : treeText.value.shareFailed
  } finally {
    busy.value = false
  }
}

/** 分享一个内存兜底文件：内容已在内存里，直接分享。 */
async function shareArchivedFile(payload: { name: string; markdown: string }) {
  if (busy.value) {
    return
  }
  busy.value = true
  notice.value = null
  try {
    await shareMarkdownContent(payload.name, payload.markdown)
  } catch {
    notice.value = treeText.value.shareFailed
  } finally {
    busy.value = false
  }
}

/**
 * 按 URI 读取 markdown 原文。返回 null 表示无法定位/读取（调用方提示失败）。
 * 私有副本（`perspicuity-private://`）走私有目录；其余走工作区文档读取。
 */
async function readIndexedMarkdown(uri: string): Promise<string | null> {
  if (isPrivateProjectUri(uri)) {
    const parsed = parsePrivateProjectUri(uri)
    if (!parsed) {
      return null
    }
    const result = await readPrivateProjectFile(parsed.projectId, parsed.relPath)
    return result ? result.markdown : null
  }
  const result = await openWorkspaceDocument(uri)
  return result ? result.markdown : null
}

/**
 * 用当前设置把一段 markdown 交给系统分享面板。
 * 与编辑器里的「分享」保持一致：图片分享、编码都沿用用户设置。
 */
async function shareMarkdownContent(name: string, markdown: string) {
  const imageSharingSettings = getImageSharingSettings(getValue)
  const advancedSettings = getAdvancedSettings(getValue)
  const markdownSaveSettings = getMarkdownSaveSettings(advancedSettings)
  const suggestedName = name.toLowerCase().endsWith('.md') ? name : `${name}.md`
  try {
    await shareAndroidMarkdownDocument(markdown, suggestedName, {
      attachImages: imageSharingSettings.shareImages === 'attach',
      encoding: markdownSaveSettings.encoding,
    })
    notice.value = treeText.value.shareDone
  } catch (error) {
    // 转成用户可读文案；具体错误已在底层记录。
    void getAndroidDocumentUserMessage(error)
    throw error
  }
}
const searchPool = computed<WorkspaceSearchEntry[]>(() => {
  const recent: WorkspaceSearchEntry[] = props.recentDocuments.map(item => ({
    id: `recent::${item.id}`,
    workspaceId: 'recent',
    workspaceName: t('files.recentSource'),
    name: item.displayName,
    relativePath: item.title,
    uri: item.id,
    modified: 0,
  }))
  return [...entries.value, ...recent]
})

const results = computed(() => searchWorkspaceEntries(searchPool.value, query.value))
const searching = computed(() => query.value.trim().length > 0)

/** 未搜索时展示当前选中工作区（或第一个）的文件列表。 */
const activeWorkspace = computed(() => {
  if (!workspaces.value.length) {
    return null
  }
  return (
    workspaces.value.find(item => item.id === activeWorkspaceId.value) ?? workspaces.value[0]
  )
})

const activeFiles = computed(() => {
  const workspace = activeWorkspace.value
  if (!workspace) {
    return []
  }
  return filesByWorkspace.value[workspace.id] ?? []
})

const hasWorkspaces = computed(() => workspaces.value.length > 0)

function isRecentResult(entry: WorkspaceSearchEntry) {
  return entry.workspaceId === 'recent'
}

function onResultClick(entry: WorkspaceSearchEntry) {
  if (isRecentResult(entry)) {
    emit('openDocument', entry.uri)
    return
  }
  void openEntry(entry)
}

/** 相对路径的父目录，作为结果行的副标题。 */
function parentPath(entry: WorkspaceSearchEntry) {
  const index = entry.relativePath.lastIndexOf('/')
  return index > 0 ? entry.relativePath.slice(0, index) : ''
}

function formatModified(value: number) {
  if (!value || value <= 0) {
    return ''
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return ''
  }
  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

onMounted(() => {
  loadStoredWorkspaces()
  void refreshAll()
})
</script>

<template>
  <section class="files-tab" data-testid="files-tab">
    <header class="files-tab-header">
      <h1 class="files-tab-title">{{ t('files.title') }}</h1>
      <p class="files-tab-subtitle">{{ t('files.subtitle') }}</p>
    </header>

    <!-- 顶部搜索：搜「已绑定目录里的文件 + 最近打开过的文档」 -->
    <div class="files-search">
      <span class="files-search-icon" aria-hidden="true">
        <svg viewBox="0 0 24 24" focusable="false">
          <circle cx="11" cy="11" r="6.5" />
          <path d="M16 16l4.5 4.5" />
        </svg>
      </span>
      <input
        v-model="query"
        class="files-search-input"
        type="search"
        :placeholder="t('files.searchPlaceholder')"
        :aria-label="t('files.searchPlaceholder')"
        data-testid="files-search-input"
        autocomplete="off"
        autocorrect="off"
        spellcheck="false"
      />
      <button
        v-if="searching"
        class="files-search-clear"
        type="button"
        :aria-label="t('files.searchClear')"
        data-testid="files-search-clear"
        @click="query = ''"
      >
        <svg viewBox="0 0 24 24" focusable="false">
          <path d="M7 7l10 10M17 7L7 17" />
        </svg>
      </button>
    </div>

    <!-- 项目树：虚拟项目（目录可无实体文件夹），长按进菜单/拖拽模式 -->
    <ProjectTreePanel
      :disabled="busy || !supportsWorkspaces"
      @open-indexed-file="openIndexedFileByUri"
      @open-archived-file="payload => emit('openWorkspaceFile', payload)"
      @share-indexed-file="shareIndexedFileByUri"
      @share-archived-file="shareArchivedFile"
    />

    <!-- 搜索结果 -->
    <section v-if="searching" class="files-section" data-testid="files-search-results">
      <h2 class="files-section-title">
        {{ t('files.searchResults') }}
        <span v-if="results.length" class="files-section-count">{{ results.length }}</span>
      </h2>
      <ul v-if="results.length" class="files-list">
        <li v-for="result in results" :key="result.entry.id">
          <button
            class="files-row"
            type="button"
            data-testid="files-search-result"
            @click="onResultClick(result.entry)"
          >
            <span class="files-row-mark" aria-hidden="true">
              <svg viewBox="0 0 24 24" focusable="false">
                <path d="M6 3h8l4 4v14H6z" />
                <path d="M14 3v4h4" />
              </svg>
            </span>
            <span class="files-row-text">
              <strong>{{ result.entry.name }}</strong>
              <small>
                {{ result.entry.workspaceName }}
                <template v-if="parentPath(result.entry)">
                  · {{ parentPath(result.entry) }}
                </template>
              </small>
            </span>
            <span v-if="isRecentResult(result.entry)" class="files-row-tag">
              {{ t('files.recentSource') }}
            </span>
          </button>
        </li>
      </ul>
      <p v-else class="files-hint" data-testid="files-search-empty">
        {{ t('files.searchEmpty') }}
      </p>
    </section>

    <template v-else>
      <!-- 绑定操作 -->
      <div class="files-actions">
        <button
          class="files-action is-primary"
          type="button"
          data-testid="bind-local-folder-button"
          :disabled="busy || !supportsWorkspaces"
          @click="bindLocalFolder"
        >
          <span class="files-action-mark" aria-hidden="true">
            <svg viewBox="0 0 24 24" focusable="false">
              <path d="M3 7h6l2 2h10v10H3z" />
              <path d="M3 7V5h6l2 2" />
            </svg>
          </span>
          <span class="files-action-text">
            <strong>{{ t('files.bindLocal') }}</strong>
            <span>{{ t('files.bindLocalHint') }}</span>
          </span>
          <span v-if="busy" class="files-action-badge">{{ t('files.working') }}</span>
        </button>

        <button
          class="files-action"
          type="button"
          data-testid="create-project-button"
          :disabled="busy || !supportsWorkspaces"
          @click="bindLocalFolder"
        >
          <span class="files-action-mark" aria-hidden="true">
            <svg viewBox="0 0 24 24" focusable="false">
              <path d="M12 5v14M5 12h14" />
            </svg>
          </span>
          <span class="files-action-text">
            <strong>{{ t('files.createProject') }}</strong>
            <span>{{ t('files.createProjectHint') }}</span>
          </span>
        </button>

        <!-- 服务器目录：多人共享编辑的后端还没落地，这里保持禁用态占位，
             等后端接上后只需要打开 disabled 并接上 remote 数据源。 -->
        <button
          class="files-action is-reserved"
          type="button"
          data-testid="bind-remote-folder-button"
          disabled
        >
          <span class="files-action-mark" aria-hidden="true">
            <svg viewBox="0 0 24 24" focusable="false">
              <path d="M7 18a4 4 0 0 1 0-8a5.5 5.5 0 0 1 10.4 1.4A3.8 3.8 0 0 1 17 18z" />
              <path d="M12 14v6" />
              <path d="M9.5 16.5L12 14l2.5 2.5" />
            </svg>
          </span>
          <span class="files-action-text">
            <strong>{{ t('files.bindRemote') }}</strong>
            <span>{{ t('files.bindRemoteHint') }}</span>
          </span>
          <span class="files-action-badge">{{ t('files.reserved') }}</span>
        </button>
      </div>

      <p v-if="notice" class="files-notice" data-testid="files-notice">{{ notice }}</p>

      <!-- 已绑定的工作区 -->
      <section v-if="hasWorkspaces" class="files-section" data-testid="files-workspaces">
        <h2 class="files-section-title">
          {{ t('files.workspaces') }}
          <span v-if="scanning" class="files-section-count">{{ t('files.scanning') }}</span>
        </h2>
        <ul class="files-workspace-list">
          <li v-for="workspace in workspaces" :key="workspace.id">
            <div
              class="files-workspace"
              :class="{ 'is-active': activeWorkspace?.id === workspace.id }"
            >
              <button
                class="files-workspace-main"
                type="button"
                data-testid="files-workspace-item"
                @click="activeWorkspaceId = workspace.id"
              >
                <span class="files-row-mark" aria-hidden="true">
                  <svg viewBox="0 0 24 24" focusable="false">
                    <path d="M3 7h6l2 2h10v10H3z" />
                  </svg>
                </span>
                <span class="files-row-text">
                  <strong>{{ workspace.displayName }}</strong>
                  <small>
                    {{ (filesByWorkspace[workspace.id] ?? []).length }}
                    {{ t('files.fileCount') }}
                    <template v-if="!workspace.canWrite">· {{ t('files.readOnly') }}</template>
                  </small>
                </span>
              </button>
              <button
                class="files-workspace-remove"
                type="button"
                :aria-label="t('files.unbind')"
                data-testid="files-workspace-remove"
                @click="unbindWorkspace(workspace)"
              >
                <svg viewBox="0 0 24 24" focusable="false">
                  <path d="M7 7l10 10M17 7L7 17" />
                </svg>
              </button>
            </div>
          </li>
        </ul>

        <ul v-if="activeFiles.length" class="files-list">
          <li v-for="entry in activeFiles" :key="entry.id">
            <button
              class="files-row"
              type="button"
              data-testid="files-document-item"
              @click="openEntry(entry)"
            >
              <span class="files-row-mark" aria-hidden="true">
                <svg viewBox="0 0 24 24" focusable="false">
                  <path d="M6 3h8l4 4v14H6z" />
                  <path d="M14 3v4h4" />
                </svg>
              </span>
              <span class="files-row-text">
                <strong>{{ entry.name }}</strong>
                <small>
                  <template v-if="parentPath(entry)">{{ parentPath(entry) }} · </template>
                  {{ formatModified(entry.modified) }}
                </small>
              </span>
            </button>
          </li>
        </ul>
        <p v-else class="files-hint">{{ t('files.workspaceEmpty') }}</p>
      </section>

      <!-- 空态 -->
      <section v-else class="files-empty">
        <span class="files-empty-mark" aria-hidden="true">
          <svg viewBox="0 0 24 24" focusable="false">
            <path d="M3 7h6l2 2h10v10H3z" />
          </svg>
        </span>
        <h2>{{ t('files.emptyTitle') }}</h2>
        <p>{{ t('files.emptyBody') }}</p>
        <button
          class="files-open-button"
          type="button"
          data-testid="files-open-file-button"
          @click="emit('openFile')"
        >
          {{ t('home.open') }}
        </button>
      </section>
    </template>
  </section>
</template>

<style scoped>
.files-tab {
  display: flex;
  flex-direction: column;
  gap: 18px;
  /* Clear the floating capsule and its 12px drop from the safe area. */
  padding: 16px 20px calc(env(safe-area-inset-bottom, 0px) + 92px);
}

.files-tab-header {
  display: grid;
  gap: 4px;
}

.files-tab-title {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.01em;
}

.files-tab-subtitle {
  margin: 0;
  color: var(--text-muted);
  font-size: 13px;
  line-height: 1.5;
}

/* Search ------------------------------------------------------------------ */

.files-search {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  height: 44px;
  padding: 0 14px;
  border: var(--hairline) solid var(--border);
  border-radius: 14px;
  background: var(--surface-raised);
  box-shadow: var(--shadow-sm);
}

.files-search:focus-within {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--focus-ring);
}

.files-search-icon,
.files-search-clear {
  display: inline-grid;
  place-items: center;
  flex: none;
  width: 20px;
  height: 20px;
  color: var(--text-faint);
}

.files-search-icon svg,
.files-search-clear svg,
.files-row-mark svg,
.files-workspace-remove svg,
.files-action-mark svg,
.files-empty-mark svg {
  width: 100%;
  height: 100%;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.files-search-icon svg {
  stroke-width: 1.9;
}

.files-search-input {
  flex: 1;
  min-width: 0;
  border: 0;
  background: transparent;
  color: var(--text);
  font: inherit;
  font-size: 14px;
  outline: none;
  appearance: none;
}

.files-search-input::-webkit-search-cancel-button {
  display: none;
}

.files-search-clear {
  padding: 0;
  border: 0;
  background: transparent;
  touch-action: manipulation;
  cursor: pointer;
}

.files-search-clear svg {
  stroke-width: 2;
}

/* Sections & lists -------------------------------------------------------- */

.files-section {
  display: grid;
  gap: 10px;
}

.files-section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.files-section-count {
  padding: 1px 7px;
  border-radius: 999px;
  background: var(--accent-tint-11);
  color: var(--accent-strong);
  font-size: 10px;
}

.files-list,
.files-workspace-list {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.files-row,
.files-workspace-main {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 12px 14px;
  border: var(--hairline) solid var(--border);
  border-radius: 16px;
  background: var(--surface-raised);
  color: var(--text);
  font: inherit;
  text-align: left;
  touch-action: manipulation;
  cursor: pointer;
}

.files-row:active,
.files-workspace-main:active {
  background: var(--surface-muted);
}

.files-row:focus-visible,
.files-workspace-main:focus-visible,
.files-search-clear:focus-visible {
  outline: 2px solid var(--focus-ring);
  outline-offset: 2px;
}

.files-row-mark {
  display: inline-grid;
  place-items: center;
  width: 20px;
  height: 20px;
  color: var(--accent);
}

.files-row-mark svg {
  stroke-width: 1.7;
}

.files-row-text {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.files-row-text strong {
  overflow: hidden;
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.files-row-text small {
  overflow: hidden;
  color: var(--text-faint);
  font-size: 11px;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.files-row-tag {
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--accent-tint-11);
  color: var(--accent-strong);
  font-size: 10px;
  font-weight: 700;
  white-space: nowrap;
}

.files-hint {
  margin: 0;
  padding: 12px 2px;
  color: var(--text-faint);
  font-size: 12px;
  line-height: 1.5;
}

.files-notice {
  margin: 0;
  padding: 10px 14px;
  border-radius: 12px;
  background: var(--accent-tint-10);
  color: var(--accent-strong);
  font-size: 12px;
  line-height: 1.5;
}

/* Workspaces -------------------------------------------------------------- */

.files-workspace {
  display: grid;
  grid-template-columns: 1fr auto;
  align-items: center;
  gap: 6px;
  overflow: hidden;
  border: var(--hairline) solid var(--border);
  border-radius: 16px;
  background: var(--surface-raised);
}

.files-workspace.is-active {
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-tint-11);
}

.files-workspace-main {
  border: 0;
  border-radius: 0;
  background: transparent;
}

.files-workspace-remove {
  display: inline-grid;
  place-items: center;
  width: 24px;
  height: 24px;
  margin-right: 12px;
  padding: 0;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: var(--text-faint);
  touch-action: manipulation;
  cursor: pointer;
}

.files-workspace-remove svg {
  width: 14px;
  height: 14px;
  stroke-width: 2;
}

.files-workspace-remove:active {
  background: var(--surface-muted);
}

/* Actions ----------------------------------------------------------------- */

.files-actions {
  display: grid;
  gap: 12px;
}

.files-action {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 14px;
  padding: 16px 18px;
  border: var(--hairline) solid var(--separator);
  border-radius: 20px;
  background: var(--surface);
  color: var(--text);
  font: inherit;
  text-align: left;
  touch-action: manipulation;
  cursor: pointer;
}

.files-action.is-primary {
  border-color: transparent;
  background: var(--accent-soft);
  color: var(--accent-strong);
}

/* Reserved for shared multi-user editing; the entry point exists now so the
   surface does not move once the backend lands. */
.files-action.is-reserved {
  border-style: dashed;
}

.files-action:disabled {
  opacity: 0.72;
  cursor: default;
}

.files-action-mark {
  display: inline-grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border-radius: 12px;
  background: var(--accent-tint-11);
}

.files-action-mark svg {
  width: 20px;
  height: 20px;
  stroke-width: 1.9;
}

.files-action-text {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.files-action-text strong {
  font-size: 15px;
  font-weight: 650;
}

.files-action-text span {
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.4;
}

.files-action-badge {
  padding: 3px 8px;
  border-radius: 999px;
  background: var(--accent-tint-11);
  color: var(--text-faint);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.03em;
  white-space: nowrap;
}

/* Empty state ------------------------------------------------------------- */

.files-empty {
  display: grid;
  justify-items: center;
  gap: 8px;
  padding: 28px 20px;
  text-align: center;
}

.files-empty-mark {
  display: inline-grid;
  place-items: center;
  width: 56px;
  height: 56px;
  border-radius: 18px;
  background: var(--accent-soft);
  color: var(--accent-strong);
}

.files-empty-mark svg {
  width: 28px;
  height: 28px;
  stroke-width: 1.6;
}

.files-empty h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 650;
}

.files-empty p {
  margin: 0;
  max-width: 30ch;
  color: var(--text-muted);
  font-size: 13px;
  line-height: 1.5;
}

.files-open-button {
  margin-top: 6px;
  padding: 10px 22px;
  border: 0;
  border-radius: 999px;
  background: var(--accent-strong);
  color: #fff;
  font: inherit;
  font-size: 13px;
  font-weight: 650;
  touch-action: manipulation;
}

.files-open-button:active {
  opacity: 0.85;
}

.files-open-button:focus-visible {
  outline: 2px solid var(--focus-ring);
  outline-offset: 2px;
}
</style>