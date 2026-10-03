<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useI18n } from '../../../lib/i18n'
import {
  deleteImportedFont,
  getFontImportUserMessage,
  isFontImportAvailable,
  listImportedFonts,
  pickFontDirectory,
  pickFontFiles,
  renameImportedFont,
  type FontImportResult,
  type ImportedFontFile,
} from '../../../lib/fontImport'
import {
  applyImportedFontFaces,
  getImportedFonts,
  setImportedFonts,
  toFontFamilyName,
} from '../importedFontRegistry'

defineProps<{
  testId: string
}>()
const emit = defineEmits<{
  'fonts-changed': []
}>()

const { t } = useI18n()
const fonts = ref<ImportedFontFile[]>([...getImportedFonts()])
const busy = ref(false)
const errorMessage = ref<string | null>(null)
const statusMessage = ref<string | null>(null)
const available = isFontImportAvailable()

/** 正在编辑显示名的字体（fileName）；null 表示没有处于编辑态的项。 */
const editingFileName = ref<string | null>(null)
const editingName = ref('')
/** 预览面板展开的字体（fileName）；null 表示没有展开的项。 */
const previewingFileName = ref<string | null>(null)
/**
 * 字体列表是否展开。
 *
 * 默认**折叠**：用户批量导入上百个字体后，列表会占掉好几屏，
 * 往下翻设置变得很烦。折叠后只留一行摘要 + 展开按钮，需要时再点开。
 */
const listExpanded = ref(false)
// v-for 里的模板 ref 会被收集成数组；同一时刻只有一项处于编辑态，
// 所以取首个元素即可。
const renameInput = ref<HTMLInputElement[] | null>(null)

const totalBytes = computed(() => fonts.value.reduce((sum, font) => sum + (font.bytes || 0), 0))
const summary = computed(() =>
  t('settings.typography.fontLibrarySummary', {
    count: fonts.value.length,
    size: formatBytes(totalBytes.value),
  }),
)

function formatBytes(bytes: number) {
  if (!Number.isFinite(bytes) || bytes <= 0) {
    return '0 KB'
  }
  const units = ['B', 'KB', 'MB', 'GB']
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  const digits = value >= 100 || unit === 0 ? 0 : 1
  return `${value.toFixed(digits)} ${units[unit]}`
}

/** @font-face 的 src 走设备本地文件，用加前缀的族名把真实文件绑上去。 */
function fontStack(font: ImportedFontFile) {
  return `'${toFontFamilyName(font)}', sans-serif`
}

async function refresh() {
  const list = await listImportedFonts()
  setImportedFonts(list)
  applyImportedFontFaces(list)
  fonts.value = [...list]
  // registry 是模块级单例，里面换了一批字体外面看不出来，
  // 靠这个事件让设置页重算字体候选。
  emit('fonts-changed')
}

function describeResult(result: FontImportResult) {
  if (result.imported.length === 0 && result.skipped.length === 0 && result.rejected.length === 0) {
    return t('settings.typography.importFontsEmpty')
  }
  return t('settings.typography.importFontsDone', {
    imported: result.imported.length,
    skipped: result.skipped.length,
    rejected: result.rejected.length,
  })
}

async function runImport(
  importer: () => Promise<FontImportResult | { canceled: true }>,
) {
  if (busy.value) {
    return
  }
  busy.value = true
  errorMessage.value = null
  statusMessage.value = null
  // 导入大目录可能要几秒（原生侧已挪到后台线程，不会 ANR），
  // 这里先给一句进度文案，避免界面看起来像卡死。
  statusMessage.value = t('settings.typography.importingFonts')
  try {
    const result = await importer()
    if ('canceled' in result) {
      statusMessage.value = null
      return
    }
    await refresh()
    statusMessage.value = describeResult(result)
    // 导入成功后自动展开列表：用户刚导完就在找字体，
    // 如果还保持折叠，会以为「导入没成功」。
    if (result.imported.length > 0) {
      listExpanded.value = true
    }
  } catch (error) {
    statusMessage.value = null
    errorMessage.value = getFontImportUserMessage(error)
  } finally {
    busy.value = false
  }
}

function importFiles() {
  void runImport(pickFontFiles)
}

function importDirectory() {
  void runImport(pickFontDirectory)
}

async function removeFont(font: ImportedFontFile) {
  if (busy.value) {
    return
  }
  busy.value = true
  errorMessage.value = null
  statusMessage.value = null
  try {
    await deleteImportedFont(font.fileName)
    // 删掉的字体如果正被编辑/预览，把状态一并收掉，否则会留下指向不存在项的面板。
    if (editingFileName.value === font.fileName) {
      cancelRename()
    }
    if (previewingFileName.value === font.fileName) {
      previewingFileName.value = null
    }
    await refresh()
    statusMessage.value = t('settings.typography.removeFontDone', { name: font.displayName })
  } catch (error) {
    errorMessage.value = getFontImportUserMessage(error)
  } finally {
    busy.value = false
  }
}

function startRename(font: ImportedFontFile) {
  if (busy.value) {
    return
  }
  editingFileName.value = font.fileName
  editingName.value = font.displayName
  errorMessage.value = null
  statusMessage.value = null
  // 让输入框拿到焦点并全选，用户可以直接输入新名字覆盖旧名。
  void nextTick(() => {
    const input = Array.isArray(renameInput.value) ? renameInput.value[0] : renameInput.value
    input?.focus()
    input?.select()
  })
}

function cancelRename() {
  editingFileName.value = null
  editingName.value = ''
}

async function commitRename(font: ImportedFontFile) {
  if (busy.value) {
    return
  }
  const nextName = editingName.value.trim()
  // 没改 / 改空了：等价于取消，不打桥。
  if (!nextName || nextName === font.displayName) {
    cancelRename()
    return
  }
  busy.value = true
  errorMessage.value = null
  statusMessage.value = null
  try {
    await renameImportedFont(font.fileName, nextName)
    cancelRename()
    await refresh()
    statusMessage.value = t('settings.typography.renameFontDone', { name: nextName })
  } catch (error) {
    errorMessage.value = getFontImportUserMessage(error)
  } finally {
    busy.value = false
  }
}

function togglePreview(font: ImportedFontFile) {
  previewingFileName.value = previewingFileName.value === font.fileName ? null : font.fileName
}

function toggleList() {
  listExpanded.value = !listExpanded.value
  // 收起列表时把编辑/预览态一并收掉：否则重新展开时面板还开着，
  // 但用户已经忘了当时在看哪个字体，体验很怪。
  if (!listExpanded.value) {
    cancelRename()
    previewingFileName.value = null
  }
}

onMounted(() => {
  if (available) {
    void refresh().catch(() => {
      errorMessage.value = t('settings.value.unavailable')
    })
  }
})
</script>

<template>
  <div class="font-library" :data-testid="testId">
    <!-- 折叠头：点整行切换列表展开/收起。默认收起，摘要始终可见。 -->
    <button
      class="font-library-head"
      type="button"
      :aria-expanded="listExpanded"
      :disabled="fonts.length === 0"
      :data-testid="`${testId}-toggle`"
      @click="toggleList"
    >
      <span class="font-library-head-text">{{ summary }}</span>
      <span v-if="fonts.length > 0" class="font-library-head-arrow" :class="{ 'is-open': listExpanded }">
        {{ listExpanded ? '▾' : '▸' }}
      </span>
    </button>

    <p v-if="!available" class="font-library-note">
      {{ t('settings.value.androidOnly') }}
    </p>

    <div v-else class="font-library-actions">
      <button
        class="font-library-button"
        type="button"
        :disabled="busy"
        :data-testid="`${testId}-import-files`"
        @click="importFiles"
      >
        {{ t('settings.typography.importFontFiles') }}
      </button>
      <button
        class="font-library-button"
        type="button"
        :disabled="busy"
        :data-testid="`${testId}-import-folder`"
        @click="importDirectory"
      >
        {{ t('settings.typography.importFontFolder') }}
      </button>
    </div>

    <p v-if="statusMessage" class="font-library-status" role="status">{{ statusMessage }}</p>
    <p v-if="errorMessage" class="font-library-error" role="alert">{{ errorMessage }}</p>

    <ul v-if="fonts.length > 0 && listExpanded" class="font-list" :data-testid="`${testId}-list`">
      <li v-for="font in fonts" :key="font.fileName" class="font-item">
        <div class="font-item-main">
          <!-- 编辑态：显示名换成输入框，回车保存，Esc 取消 -->
          <form
            v-if="editingFileName === font.fileName"
            class="font-item-rename"
            @submit.prevent="commitRename(font)"
          >
            <input
              ref="renameInput"
              v-model="editingName"
              class="font-item-rename-input"
              type="text"
              :maxlength="120"
              :disabled="busy"
              :aria-label="t('settings.typography.renameFont')"
              :data-testid="`${testId}-rename-input-${font.fileName}`"
              @keydown.esc.prevent="cancelRename"
            />
            <button
              class="font-item-action font-item-save"
              type="submit"
              :disabled="busy"
              :data-testid="`${testId}-rename-save-${font.fileName}`"
            >
              {{ t('settings.typography.renameFontSave') }}
            </button>
            <button
              class="font-item-action"
              type="button"
              :disabled="busy"
              @click="cancelRename"
            >
              {{ t('settings.typography.renameFontCancel') }}
            </button>
          </form>

          <!-- 常态：点名字即可开始重命名 -->
          <button
            v-else
            class="font-item-name"
            type="button"
            :style="{ fontFamily: fontStack(font) }"
            :title="t('settings.typography.renameFontHint')"
            :disabled="busy"
            :data-testid="`${testId}-rename-${font.fileName}`"
            @click="startRename(font)"
          >
            {{ font.displayName }}
          </button>

          <span class="font-item-meta">{{ formatBytes(font.bytes) }} · {{ font.familyName }}</span>
        </div>

        <div class="font-item-actions">
          <button
            class="font-item-action"
            type="button"
            :aria-expanded="previewingFileName === font.fileName"
            :data-testid="`${testId}-preview-${font.fileName}`"
            @click="togglePreview(font)"
          >
            {{ previewingFileName === font.fileName
              ? t('settings.typography.previewFontHide')
              : t('settings.typography.previewFont') }}
          </button>
          <button
            class="font-item-action font-item-remove"
            type="button"
            :disabled="busy"
            :aria-label="t('settings.typography.removeFont')"
            :data-testid="`${testId}-remove-${font.fileName}`"
            @click="removeFont(font)"
          >
            {{ t('settings.typography.removeFont') }}
          </button>
        </div>

        <!-- 预览面板：用该字体真实渲染一段中英数混排示例 -->
        <div
          v-if="previewingFileName === font.fileName"
          class="font-preview"
          :style="{ fontFamily: fontStack(font) }"
          :data-testid="`${testId}-preview-panel-${font.fileName}`"
        >
          <p class="font-preview-line font-preview-title">{{ t('settings.typography.previewFontSampleTitle') }}</p>
          <p class="font-preview-line">{{ t('settings.typography.previewFontSampleBody') }}</p>
          <p class="font-preview-line font-preview-mono">ABCDEFGHIJKLM abcdefghijklm 0123456789</p>
          <p class="font-preview-line">{{ t('settings.typography.previewFontSampleCjk') }}</p>
        </div>
      </li>
    </ul>
    <p v-else-if="available" class="font-library-empty">
      {{ t('settings.typography.fontLibraryEmpty') }}
    </p>
  </div>
</template>

<style scoped>
.font-library {
  display: grid;
  gap: 12px;
  padding: 14px 20px 18px;
}

.font-library-summary {
  margin: 0;
  color: var(--text-muted);
  font-size: 13px;
  line-height: 1.4;
}

/* 折叠头：整行可点，左侧摘要，右侧箭头。 */
.font-library-head {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  margin: 0;
  padding: 8px 10px;
  border: 1px solid var(--separator);
  border-radius: var(--radius-sm);
  background: var(--surface-muted);
  color: var(--text-muted);
  font: inherit;
  font-size: 13px;
  line-height: 1.4;
  text-align: left;
  touch-action: manipulation;
}

.font-library-head:active {
  background: var(--press);
}

.font-library-head:disabled {
  opacity: 0.7;
}

.font-library-head-text {
  flex: 1 1 auto;
  min-width: 0;
  overflow-wrap: anywhere;
}

.font-library-head-arrow {
  flex: 0 0 auto;
  font-size: 12px;
  color: var(--accent-strong);
}

.font-library-note,
.font-library-empty {
  margin: 0;
  color: var(--text-muted);
  font-size: 13px;
  line-height: 1.5;
}

.font-library-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.font-library-button {
  flex: 1 1 auto;
  min-height: 40px;
  padding: 8px 14px;
  border: 1px solid var(--separator);
  border-radius: var(--radius-sm);
  background: var(--surface-muted);
  color: var(--accent-strong);
  font: inherit;
  font-size: 14px;
  font-weight: 600;
  touch-action: manipulation;
}

.font-library-button:active {
  background: var(--press);
}

.font-library-button:disabled {
  opacity: 0.5;
}

.font-library-status {
  margin: 0;
  color: var(--text-muted);
  font-size: 13px;
}

.font-library-error {
  margin: 0;
  color: var(--danger, #b3261e);
  font-size: 13px;
}

.font-list {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.font-item {
  display: grid;
  gap: 8px;
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  background: var(--surface-sunken);
}

.font-item-main {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.font-item-name {
  justify-self: start;
  max-width: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  font-size: 15px;
  color: var(--text);
  text-align: left;
  overflow-wrap: anywhere;
}

.font-item-name:active {
  opacity: 0.6;
}

.font-item-name:disabled {
  opacity: 0.6;
}

.font-item-rename {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.font-item-rename-input {
  flex: 1 1 140px;
  min-width: 0;
  min-height: 34px;
  padding: 4px 8px;
  border: 1px solid var(--accent-strong);
  border-radius: var(--radius-sm);
  background: var(--surface);
  color: var(--text);
  font: inherit;
  font-size: 15px;
}

.font-item-meta {
  color: var(--text-muted);
  font-size: 12px;
  overflow-wrap: anywhere;
}

.font-item-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  justify-content: flex-end;
}

.font-item-action {
  flex: 0 0 auto;
  min-height: 34px;
  padding: 6px 10px;
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--accent-strong);
  font: inherit;
  font-size: 13px;
  font-weight: 600;
  touch-action: manipulation;
}

.font-item-action:active {
  background: var(--press);
}

.font-item-action:disabled {
  opacity: 0.5;
}

.font-item-save {
  color: var(--accent-strong);
}

.font-item-remove {
  color: var(--danger, #b3261e);
}

.font-preview {
  display: grid;
  gap: 6px;
  padding: 10px 12px;
  border: 1px solid var(--separator);
  border-radius: var(--radius-sm);
  background: var(--surface);
}

.font-preview-line {
  margin: 0;
  color: var(--text);
  font-size: 16px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.font-preview-title {
  font-size: 20px;
  font-weight: 600;
}

.font-preview-mono {
  letter-spacing: 0.02em;
}
</style>