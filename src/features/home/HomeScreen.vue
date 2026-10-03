<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import LiquidGlassTabBar from './components/LiquidGlassTabBar.vue'
import HomeOverview from './HomeTab.vue'
import FilesTab from './FilesTab.vue'
import SettingsScreen from '../settings/SettingsScreen.vue'
import type { HomeDocumentItem } from './homeDocuments'
import type { WorkspaceOpenResult } from '../../lib/workspace'
import { HOME_TABS, type HomeTab } from './homeNavigation'
import { SETTINGS_PAGES, type SettingsPage } from '../settings/settingsNavigation'
import type { AdvancedMaintenanceActionHandler } from '../settings/advancedSettings'

interface Props {
  activeTab: HomeTab
  settingsPage: SettingsPage
  continueDocument: HomeDocumentItem | null
  pinnedDocuments: HomeDocumentItem[]
  earlierDocuments: HomeDocumentItem[]
  /** 最近打开过的文档，供「文件」页搜索；与首页共用同一份数据。 */
  recentDocuments: HomeDocumentItem[]
  hiddenDocumentCount: number
  notice: string | null
  selectionActive: boolean
  selectionCount: number
  selectedIds: ReadonlySet<string>
  allSelectedPinned: boolean
  deleteSheetOpen: boolean
  renameSheetOpen: boolean
  runMaintenanceAction: AdvancedMaintenanceActionHandler
}

const props = defineProps<Props>()

const emit = defineEmits<{
  setTab: [tab: HomeTab]
  openDocument: [id: string]
  openFile: []
  /** 打开绑定目录里的文件（工作区文档），转交 App 走 SAF 文档打开流程。 */
  openWorkspaceFile: [payload: WorkspaceOpenResult]
  newDocument: []
  showAllDocuments: []
  setSettingsPage: [page: SettingsPage]
  selectDocument: [id: string]
  toggleDocument: [id: string]
  exitSelection: []
  pinSelected: []
  deleteSelected: []
  shareSelected: []
  renameSelected: [id: string, name: string]
  'update:deleteSheetOpen': [open: boolean]
  'update:renameSheetOpen': [open: boolean]
}>()

const homeMain = ref<HTMLElement | null>(null)

const isSettingsDetail = computed(
  () => props.activeTab === HOME_TABS.SETTINGS && props.settingsPage !== SETTINGS_PAGES.INDEX,
)

const showBottomNav = computed(() => !isSettingsDetail.value)

watch(
  () => [props.activeTab, props.settingsPage] as const,
  () => {
    homeMain.value?.scrollTo({ top: 0, left: 0 })
  },
  { flush: 'post' },
)
</script>

<template>
  <main class="app-shell is-home" :class="{ 'is-detail': isSettingsDetail }">
    <div ref="homeMain" class="home-main">
      <HomeOverview
        v-if="activeTab === HOME_TABS.HOME"
        :continue-document="continueDocument"
        :pinned-documents="pinnedDocuments"
        :earlier-documents="earlierDocuments"
        :hidden-document-count="hiddenDocumentCount"
        :notice="notice"
        @new-document="emit('newDocument')"
        @open-document="id => emit('openDocument', id)"
        @open-file="emit('openFile')"
        @open-documents-tab="emit('setTab', HOME_TABS.DOCUMENTS)"
      />
      <FilesTab
        v-else-if="activeTab === HOME_TABS.DOCUMENTS"
        :recent-documents="recentDocuments"
        @open-file="emit('openFile')"
        @open-document="id => emit('openDocument', id)"
        @open-workspace-file="payload => emit('openWorkspaceFile', payload)"
      />
      <SettingsScreen
        v-else
        :active-page="settingsPage"
        :run-maintenance-action="runMaintenanceAction"
        @set-page="page => emit('setSettingsPage', page)"
      />
    </div>
    <LiquidGlassTabBar
      v-if="showBottomNav"
      :active-tab="activeTab"
      @set-tab="(tab: HomeTab) => emit('setTab', tab)"
    />
  </main>
</template>

<style scoped>
.home-main {
  min-height: 0;
  overflow: auto;
  background: var(--app-bg);
  -webkit-overflow-scrolling: touch;
}

/* 悬浮胶囊要有落点，页面底部需要一层很淡的渐隐，否则内容滚到最底时
   会直接顶到胶囊边缘，投影落在空处、层次感消失。 */
.home-main::after {
  content: '';
  position: sticky;
  bottom: 0;
  display: block;
  height: 24px;
  margin-top: -24px;
  background: linear-gradient(to bottom, transparent, var(--app-bg));
  pointer-events: none;
}
</style>
