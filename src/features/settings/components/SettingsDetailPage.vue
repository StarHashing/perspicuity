<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import SettingsChoiceRow from './SettingsChoiceRow.vue'
import FontLibrarySettings from './FontLibrarySettings.vue'
import SettingsRow from './SettingsRow.vue'
import SettingsSection from './SettingsSection.vue'
import SettingsSelectRow from './SettingsSelectRow.vue'
import SettingsSliderRow from './SettingsSliderRow.vue'
import SettingsTextRow from './SettingsTextRow.vue'
import SettingsToggleRow from './SettingsToggleRow.vue'
import ToolbarQuickSettings from './ToolbarQuickSettings.vue'
import SelectionToolbarSettings from './SelectionToolbarSettings.vue'
import {
  SETTINGS_DETAIL_SECTIONS,
  type SettingsActionRow,
  type SettingsChoiceRow as SettingsChoiceDescriptor,
  type SettingsSliderRow as SettingsSliderDescriptor,
  type SettingsStatusRow,
  type SettingsTextRow as SettingsTextDescriptor,
  type SettingsToggleRow as SettingsToggleDescriptor,
} from '../settingsContent'
import {
  APP_LANGUAGE_OPTIONS,
  AUTO_APP_LOCALE,
  useI18n,
  type I18nKey,
} from '../../../lib/i18n'
import { SETTINGS_PAGES, type SettingsPage } from '../settingsNavigation'
import { useSettingsState } from '../settingsState'
import { getFontChoices } from '../typographySettings'
import {
  isAdvancedMaintenanceActionId,
  type AdvancedMaintenanceActionHandler,
  type AdvancedMaintenanceActionId,
} from '../advancedSettings'
import { getAdvancedDiagnostics } from '../advancedDiagnostics'
import {
  isAllFilesAccessGranted,
  isAllFilesAccessAvailable,
  requestAllFilesAccess,
} from '../../../lib/allFilesAccess'
import {
  formatImportedImageStorageBytes,
  getImportedAndroidImageStorageStats,
  type ImportedAndroidImageStorageStats,
} from '../../../lib/androidImages'
import { useModalFocus } from '../../../lib/modalFocus'

const props = defineProps<{
  page: SettingsPage
  runMaintenanceAction: AdvancedMaintenanceActionHandler
}>()

const { locale, localePreference, setLocalePreference, t } = useI18n()
const { getValue, setValue } = useSettingsState()
const sections = computed(() => SETTINGS_DETAIL_SECTIONS[props.page] ?? [])
/**
 * 分块字体行的可选候选：内置字体 + 已导入字体。
 *
 * 依赖 registryVersion：导入/删除字体后 registry 内容变了，但这个组件
 * 并不知道（它不是那批数据的持有者）。用一个自增计数器做触发信号，
 * 由 FontLibrarySettings 在刷新后调用 bumpFontRegistryRevision()。
 */
const fontRegistryRevision = ref(0)
const fontOptions = computed(() => {
  // 触碰 revision 建立依赖，否则导入字体后候选列表不会更新。
  void fontRegistryRevision.value
  return getFontChoices().map(choice => ({ id: choice.id, label: choice.label }))
})
function bumpFontRegistryRevision() {
  fontRegistryRevision.value += 1
}
/** 只有字体族字段才挂下拉：靠 id 结尾判定，避免误伤其他自由文本行。 */
function isFontStackRow(rowId: string) {
  return rowId.endsWith('latinFont') || rowId.endsWith('cjkFont')
}
type MaintenanceActionId = AdvancedMaintenanceActionId

const maintenanceAction = ref<MaintenanceActionId | null>(null)
const maintenanceActionBusy = ref(false)
const maintenanceActionError = ref<string | null>(null)
const maintenanceActionResult = ref<string | null>(null)
const advancedDiagnostics = ref<Record<string, string>>({})
const importedImageStorageStats = ref<ImportedAndroidImageStorageStats | null>(null)
const importedImageStorageLoading = ref(false)
const importedImageStorageError = ref(false)
/**
 * 「所有文件访问权限」的实时状态镜像。
 *
 * `null` 表示尚未探测（首屏查询完成前），UI 显示「检查中」；
 * 真实状态以系统为准——用户去系统页面开完返回后，这里需重新查询。
 */
const allFilesAccessGrantedState = ref<boolean | null>(null)
const allFilesAccessSupported = ref(true)

async function refreshAllFilesAccessState() {
  if (!isAllFilesAccessAvailable()) {
    allFilesAccessGrantedState.value = false
    allFilesAccessSupported.value = false
    return
  }
  const state = await isAllFilesAccessGranted()
  allFilesAccessGrantedState.value = state.granted
  allFilesAccessSupported.value = state.supported !== false
}

onMounted(() => {
  void refreshAllFilesAccessState()
})

/** 从系统设置页返回时，重新校验权限（页面切回是异步的，延迟一拍再查）。 */
function handleWindowFocusForAllFiles() {
  if (props.page === SETTINGS_PAGES.ADVANCED) {
    void refreshAllFilesAccessState()
  }
}
if (typeof window !== 'undefined') {
  window.addEventListener('focus', handleWindowFocusForAllFiles)
}
const maintenanceModalRoot = ref<HTMLElement | null>(null)
const maintenanceCancelButton = ref<HTMLButtonElement | null>(null)
const maintenanceActionCopies: Record<
  MaintenanceActionId,
  {
    titleKey: I18nKey
    bodyKey: I18nKey
    actionKey: I18nKey
    confirmKey: I18nKey
    confirmTestId: string
    danger?: boolean
  }
> = {
  exportLogs: {
    titleKey: 'settings.maintenance.exportLogsTitle',
    bodyKey: 'settings.maintenance.exportLogsBody',
    actionKey: 'settings.maintenance.exportLogsAction',
    confirmKey: 'settings.maintenance.exportLogsConfirm',
    confirmTestId: 'settings-maintenance-export-confirm',
  },
  clearLogs: {
    titleKey: 'settings.maintenance.clearLogsTitle',
    bodyKey: 'settings.maintenance.clearLogsBody',
    actionKey: 'settings.maintenance.clearLogsAction',
    confirmKey: 'settings.maintenance.clearLogsConfirm',
    confirmTestId: 'settings-maintenance-clear-logs-confirm',
    danger: true,
  },
  cleanImportedImages: {
    titleKey: 'settings.maintenance.cleanImagesTitle',
    bodyKey: 'settings.maintenance.cleanImagesBody',
    actionKey: 'settings.maintenance.cleanImagesAction',
    confirmKey: 'settings.maintenance.cleanImagesConfirm',
    confirmTestId: 'settings-maintenance-clean-images-confirm',
    danger: true,
  },
  clearDrafts: {
    titleKey: 'settings.maintenance.clearDraftsTitle',
    bodyKey: 'settings.maintenance.clearDraftsBody',
    actionKey: 'settings.maintenance.clearDraftsAction',
    confirmKey: 'settings.maintenance.clearDraftsConfirm',
    confirmTestId: 'settings-maintenance-clear-confirm',
    danger: true,
  },
  resetSettings: {
    titleKey: 'settings.maintenance.resetSettingsTitle',
    bodyKey: 'settings.maintenance.resetSettingsBody',
    actionKey: 'settings.maintenance.resetSettingsAction',
    confirmKey: 'settings.maintenance.resetSettingsConfirm',
    confirmTestId: 'settings-maintenance-reset-confirm',
    danger: true,
  },
}
const activeMaintenanceActionCopy = computed(() =>
  maintenanceAction.value ? maintenanceActionCopies[maintenanceAction.value] : null,
)
const languageOptions = computed(() =>
  [
    {
      id: AUTO_APP_LOCALE,
      label: t('settings.language.automatic'),
    },
    ...APP_LANGUAGE_OPTIONS.map(option => ({
      id: option.id,
      label: t(option.labelKey),
    })),
  ],
)

function setLanguage(value: string) {
  if (value === AUTO_APP_LOCALE) {
    setLocalePreference(AUTO_APP_LOCALE)
    return
  }
  const nextLocale = APP_LANGUAGE_OPTIONS.find(option => option.id === value)?.id
  if (nextLocale) {
    setLocalePreference(nextLocale)
  }
}

function getToggleValue(row: SettingsToggleDescriptor) {
  return getValue(row.id, row.defaultValue)
}

function getChoiceValue(row: SettingsChoiceDescriptor) {
  const value = getValue(row.id, row.defaultValue)
  return row.options.some(option => option.id === value) ? value : row.defaultValue
}

function getSliderValue(row: SettingsSliderDescriptor) {
  return getValue(row.id, row.defaultValue)
}

function getTextValue(row: SettingsTextDescriptor) {
  return getValue(row.id, row.defaultValue)
}

function optionLabels(row: SettingsChoiceDescriptor) {
  return row.options.map(option => ({
    id: option.id,
    // Product names (theme labels) render verbatim; everything else is copy.
    label: option.label ?? (option.labelKey ? t(option.labelKey) : option.id),
    swatches: option.swatches,
    heading: option.headingKey ? t(option.headingKey) : undefined,
    testId: `${row.testId}-option-${option.id.replace(/[^a-z0-9]+/gi, '-')}`,
  }))
}

function getActionValue(row: SettingsActionRow) {
  return row.valueKey ? t(row.valueKey) : undefined
}

function getStatusValue(row: SettingsStatusRow) {
  if (props.page === SETTINGS_PAGES.ADVANCED && row.id === 'allFilesAccessState') {
    if (!allFilesAccessSupported.value) {
      return t('settings.value.unsupported')
    }
    if (allFilesAccessGrantedState.value === null) {
      return t('settings.value.checking')
    }
    return allFilesAccessGrantedState.value
      ? t('settings.value.granted')
      : t('settings.value.notGranted')
  }

  if (props.page === SETTINGS_PAGES.ADVANCED && row.id === 'importedImageStorage') {
    if (importedImageStorageLoading.value) {
      return t('settings.value.checking')
    }
    if (importedImageStorageError.value) {
      return t('settings.value.unavailable')
    }
    if (!importedImageStorageStats.value) {
      return t('settings.value.androidOnly')
    }
    return t('settings.value.importedImageStorageUsage', {
      count: importedImageStorageStats.value.fileCount,
      size: formatImportedImageStorageBytes(importedImageStorageStats.value.bytes, locale.value),
    })
  }

  if (props.page === SETTINGS_PAGES.ADVANCED) {
    const value = advancedDiagnostics.value[row.id]
    if (value) {
      return value
    }
  }
  return t(row.valueKey)
}

function shouldShowCustomToolbar() {
  return getValue<string>('toolbarQuickBarMode', 'default') === 'custom'
}

function shouldShowSettingsRow(rowId: string) {
  if (props.page === SETTINGS_PAGES.APPEARANCE && rowId === 'customTheme') {
    return getValue<string>('themeMode', 'system') === 'custom'
  }
  return true
}

function setStoredValue(rowId: string, value: boolean | number | string) {
  setValue(rowId, value)
  // 「所有文件访问」不是普通开关：打开时并不能由代码授权，必须把用户
  // 引到系统设置页手动开。这里记下「用户意愿」并拉系统页；关掉开关则只
  // 记录意愿（真正回收权限仍要用户去系统里操作）。
  if (rowId === 'allFilesAccess' && value === true) {
    void openAllFilesAccessSettings()
  }
}

/** 打开系统「所有文件访问」设置页，返回后重新校验状态。 */
async function openAllFilesAccessSettings() {
  const result = await requestAllFilesAccess()
  if (result.granted) {
    allFilesAccessGrantedState.value = true
    return
  }
  // 用户返回后 window focus 会再查一次；这里也补一次，覆盖不掉 focus 的情况。
  if (result.opened) {
    setTimeout(() => {
      void refreshAllFilesAccessState()
    }, 400)
  }
}

function getMaintenanceActionId(rowId: string): MaintenanceActionId | null {
  return isAdvancedMaintenanceActionId(rowId) ? rowId : null
}

function getMaintenanceActionCopy(rowId: string) {
  const maintenanceActionId = props.page === SETTINGS_PAGES.ADVANCED
    ? getMaintenanceActionId(rowId)
    : null
  return maintenanceActionId ? maintenanceActionCopies[maintenanceActionId] : null
}

function recordAction(row: SettingsActionRow) {
  const maintenanceActionId = props.page === SETTINGS_PAGES.ADVANCED
    ? getMaintenanceActionId(row.id)
    : null
  if (maintenanceActionId) {
    maintenanceAction.value = maintenanceActionId
    maintenanceActionError.value = null
    maintenanceActionResult.value = null
    return
  }
  setValue(`action:${row.id}`, Date.now())
}

function closeMaintenanceSheet() {
  if (maintenanceActionBusy.value) {
    return
  }
  maintenanceAction.value = null
  maintenanceActionError.value = null
  maintenanceActionResult.value = null
}

const { focusInitial, onModalKeydown } = useModalFocus({
  root: maintenanceModalRoot,
  initialFocus: () => maintenanceCancelButton.value,
  onEscape: closeMaintenanceSheet,
})

async function confirmMaintenanceAction() {
  const action = maintenanceAction.value
  if (!action || maintenanceActionBusy.value) {
    return
  }

  maintenanceModalRoot.value?.focus({ preventScroll: true })
  maintenanceActionBusy.value = true
  maintenanceActionError.value = null
  maintenanceActionResult.value = null
  try {
    const result = await props.runMaintenanceAction(action)
    await refreshImportedImageStorage()
    if (result?.message) {
      maintenanceActionResult.value = result.message
      return
    }
    maintenanceAction.value = null
  } catch (error) {
    maintenanceActionError.value = error instanceof Error
      ? error.message
      : t('settings.maintenance.genericError')
  } finally {
    maintenanceActionBusy.value = false
    await nextTick()
    focusInitial()
  }
}

async function refreshImportedImageStorage() {
  importedImageStorageLoading.value = true
  try {
    importedImageStorageStats.value = await getImportedAndroidImageStorageStats()
    importedImageStorageError.value = false
  } catch {
    importedImageStorageStats.value = null
    importedImageStorageError.value = true
  } finally {
    importedImageStorageLoading.value = false
  }
}

async function refreshAdvancedInformation() {
  const [diagnostics] = await Promise.all([
    getAdvancedDiagnostics(),
    refreshImportedImageStorage(),
  ])
  advancedDiagnostics.value = {
    deviceInfo: diagnostics.deviceInfo,
    webviewInfo: diagnostics.webViewInfo,
  }
}

watch(
  () => props.page,
  page => {
    if (page !== SETTINGS_PAGES.ADVANCED) {
      return
    }

    void refreshAdvancedInformation()
  },
  { immediate: true },
)
</script>

<template>
  <SettingsSection
    v-if="page === SETTINGS_PAGES.APPEARANCE"
    :title="t('settings.section.language')"
  >
    <SettingsSelectRow
      :label="t('settings.language.app')"
      :model-value="localePreference"
      :options="languageOptions"
      test-id="settings-language-app"
      @update:model-value="setLanguage"
    />
  </SettingsSection>

  <SettingsSection v-for="section in sections" :key="section.titleKey" :title="t(section.titleKey)">
    <template v-for="row in section.rows" :key="row.testId">
      <SettingsToggleRow
        v-if="shouldShowSettingsRow(row.id) && row.kind === 'toggle'"
        :label="t(row.labelKey)"
        :model-value="getToggleValue(row)"
        :test-id="row.testId"
        @update:model-value="value => setStoredValue(row.id, value)"
      />
      <SettingsSelectRow
        v-else-if="shouldShowSettingsRow(row.id) && row.kind === 'choice' && row.display === 'select'"
        :label="t(row.labelKey)"
        :model-value="getChoiceValue(row)"
        :options="optionLabels(row)"
        :test-id="row.testId"
        @update:model-value="value => setStoredValue(row.id, value)"
      />
      <SettingsChoiceRow
        v-else-if="shouldShowSettingsRow(row.id) && row.kind === 'choice'"
        :label="t(row.labelKey)"
        :model-value="getChoiceValue(row)"
        :options="optionLabels(row)"
        :test-id="row.testId"
        @update:model-value="value => setStoredValue(row.id, value)"
      />
      <SettingsSliderRow
        v-else-if="shouldShowSettingsRow(row.id) && row.kind === 'slider'"
        :label="t(row.labelKey)"
        :model-value="getSliderValue(row)"
        :min="row.min"
        :max="row.max"
        :step="row.step"
        :unit="row.unitKey ? t(row.unitKey) : undefined"
        :test-id="row.testId"
        @update:model-value="value => setStoredValue(row.id, value)"
      />
      <SettingsTextRow
        v-else-if="shouldShowSettingsRow(row.id) && row.kind === 'text'"
        :label="t(row.labelKey)"
        :model-value="getTextValue(row)"
        :placeholder="row.placeholderKey ? t(row.placeholderKey) : undefined"
        :multiline="row.multiline"
        :font-options="isFontStackRow(row.id) ? fontOptions : undefined"
        :test-id="row.testId"
        @update:model-value="value => setStoredValue(row.id, value)"
      />
      <SettingsRow
        v-else-if="shouldShowSettingsRow(row.id) && row.kind === 'action'"
        :label="t(row.labelKey)"
        :value="getActionValue(row)"
        :action-label="getMaintenanceActionCopy(row.id) ? t(getMaintenanceActionCopy(row.id)!.actionKey) : undefined"
        :action-variant="getMaintenanceActionCopy(row.id)?.danger ? 'danger' : 'default'"
        :test-id="row.testId"
        button
        @activate="recordAction(row)"
      />
      <ToolbarQuickSettings
        v-else-if="shouldShowSettingsRow(row.id) && row.kind === 'customToolbar' && shouldShowCustomToolbar()"
        :test-id="row.testId"
      />
      <SelectionToolbarSettings
        v-else-if="shouldShowSettingsRow(row.id) && row.kind === 'customSelectionToolbar'"
        :test-id="row.testId"
      />
      <FontLibrarySettings
        v-else-if="row.kind === 'customFonts'"
        :test-id="row.testId"
        @fonts-changed="bumpFontRegistryRevision"
      />
      <SettingsRow
        v-else-if="shouldShowSettingsRow(row.id) && row.kind === 'status'"
        :label="t(row.labelKey)"
        :value="getStatusValue(row)"
        :test-id="row.testId"
      />
    </template>
  </SettingsSection>

  <Transition name="editor-sheet">
    <section
      v-if="activeMaintenanceActionCopy"
      ref="maintenanceModalRoot"
      class="draft-save-sheet"
      role="dialog"
      aria-modal="true"
      aria-labelledby="settings-maintenance-title"
      tabindex="-1"
      data-testid="settings-maintenance-sheet"
      @click.self="closeMaintenanceSheet"
      @keydown="onModalKeydown"
    >
      <div class="draft-save-panel">
        <h2 id="settings-maintenance-title">{{ t(activeMaintenanceActionCopy.titleKey) }}</h2>
        <p>{{ t(activeMaintenanceActionCopy.bodyKey) }}</p>
        <p v-if="maintenanceActionResult" role="status">
          {{ maintenanceActionResult }}
        </p>
        <p v-if="maintenanceActionError" role="alert">
          {{ maintenanceActionError }}
        </p>
        <div class="draft-save-actions">
          <button
            v-if="!maintenanceActionResult"
            type="button"
            :class="{ 'danger-action': activeMaintenanceActionCopy.danger, 'primary-action': !activeMaintenanceActionCopy.danger }"
            :disabled="maintenanceActionBusy"
            :aria-busy="maintenanceActionBusy ? 'true' : undefined"
            :data-testid="activeMaintenanceActionCopy.confirmTestId"
            @click="confirmMaintenanceAction"
          >
            {{ t(activeMaintenanceActionCopy.confirmKey) }}
          </button>
          <button
            ref="maintenanceCancelButton"
            type="button"
            :disabled="maintenanceActionBusy"
            data-testid="settings-maintenance-cancel"
            @click="closeMaintenanceSheet"
          >
            {{ maintenanceActionResult ? t('settings.maintenance.done') : t('editor.link.cancel') }}
          </button>
        </div>
      </div>
    </section>
  </Transition>
</template>
