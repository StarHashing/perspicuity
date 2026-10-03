<script setup lang="ts">
import { computed } from 'vue'
import type { HomeDocumentItem } from './homeDocuments'
import { useI18n } from '../../lib/i18n'
// 首页品牌标记直接用应用图标（启动器前景图裁到内容边界），而不是另画的 SVG——
// 这样用户看到的左上角就是桌面上那个图标本身。
import appIcon from '../../assets/app-icon.png'

interface Props {
  continueDocument: HomeDocumentItem | null
  pinnedDocuments: HomeDocumentItem[]
  earlierDocuments: HomeDocumentItem[]
  hiddenDocumentCount: number
  notice: string | null
}

const props = defineProps<Props>()

const emit = defineEmits<{
  openDocument: [id: string]
  openFile: []
  newDocument: []
  showAllDocuments: []
  openDocumentsTab: []
}>()

const { t } = useI18n()

// The home feed shows a short recency slice; the full list lives in the
// documents tab, which the "see all" affordance jumps to.
const RECENT_LIMIT = 5

const recentDocuments = computed(() => {
  const items = [...props.pinnedDocuments, ...props.earlierDocuments]
  return items.slice(0, RECENT_LIMIT)
})

const hasAnyDocument = computed(
  () =>
    Boolean(props.continueDocument)
    || props.pinnedDocuments.length > 0
    || props.earlierDocuments.length > 0,
)

const isPinned = (id: string) => props.pinnedDocuments.some(item => item.id === id)
</script>

<template>
  <section class="home-tab" data-testid="home-tab">
    <header class="home-tab-header">
      <div class="home-tab-brand">
        <h1 class="home-tab-title">{{ t('app.name') }}</h1>
        <span class="home-tab-title-en">{{ t('app.nameEn') }}</span>
      </div>
      <span class="home-tab-mark" aria-hidden="true">
        <img :src="appIcon" alt="" />
      </span>
    </header>

    <p v-if="notice" class="home-tab-notice" role="status">{{ notice }}</p>

    <div class="home-quick-actions">
      <button
        class="quick-action is-primary"
        type="button"
        data-testid="new-document-button"
        @click="emit('newDocument')"
      >
        <span class="quick-action-mark" aria-hidden="true">
          <svg viewBox="0 0 24 24" focusable="false">
            <path d="M12 5v14M5 12h14" />
          </svg>
        </span>
        <span class="quick-action-text">
          <strong>{{ t('home.newDocument') }}</strong>
          <span>{{ t('home.quick.newHint') }}</span>
        </span>
      </button>
      <button
        class="quick-action"
        type="button"
        data-testid="open-file-button"
        @click="emit('openFile')"
      >
        <span class="quick-action-mark" aria-hidden="true">
          <svg viewBox="0 0 24 24" focusable="false">
            <path d="M4 6h12M4 12h12M4 18h8" />
            <path d="M16 14l4 4-4 4" />
          </svg>
        </span>
        <span class="quick-action-text">
          <strong>{{ t('home.open') }}</strong>
          <span>{{ t('home.quick.openHint') }}</span>
        </span>
      </button>
    </div>

    <section v-if="continueDocument" class="home-section">
      <h2 class="home-section-label">{{ t('home.continueWriting') }}</h2>
      <button
        class="continue-card"
        type="button"
        :data-doc-id="continueDocument.id"
        @click="emit('openDocument', continueDocument.id)"
      >
        <span class="continue-card-title">{{ continueDocument.title }}</span>
        <span class="continue-card-meta">{{ continueDocument.details }}</span>
      </button>
    </section>

    <section v-if="recentDocuments.length" class="home-section">
      <div class="home-section-head">
        <h2 class="home-section-label">{{ t('home.recent') }}</h2>
        <button
          class="home-section-more"
          type="button"
          data-testid="home-see-all-documents"
          @click="emit('openDocumentsTab')"
        >
          {{ t('home.seeAll') }}
        </button>
      </div>
      <div class="home-recent-list">
        <button
          v-for="document in recentDocuments"
          :key="document.id"
          class="home-recent-row"
          type="button"
          :data-doc-id="document.id"
          @click="emit('openDocument', document.id)"
        >
          <span class="home-recent-glyph" aria-hidden="true">
            <svg viewBox="0 0 24 24" focusable="false">
              <path d="M7 3h7l4 4v14H7z" />
              <path d="M14 3v5h5" />
              <path d="M9.5 12h5" />
              <path d="M9.5 16h5" />
            </svg>
          </span>
          <span class="home-recent-text">
            <strong>{{ document.title }}</strong>
            <span>{{ document.details }}</span>
          </span>
          <span v-if="isPinned(document.id)" class="home-recent-pin" aria-hidden="true">
            <svg viewBox="0 0 24 24" focusable="false">
              <path d="M9 4h6l-.7 6 3.2 3v1.5H6.5V13l3.2-3z" />
              <path d="M12 14.5V20" />
            </svg>
          </span>
        </button>
      </div>
      <p v-if="hiddenDocumentCount > 0" class="home-recent-more">
        {{ t('home.moreDocuments', { count: hiddenDocumentCount }) }}
      </p>
    </section>

    <section v-if="!hasAnyDocument" class="home-empty">
      <span class="home-empty-mark" aria-hidden="true">
        <svg viewBox="0 0 24 24" focusable="false">
          <path d="M7 3h7l4 4v14H7z" />
          <path d="M14 3v5h5" />
          <path d="M9.5 13h5" />
        </svg>
      </span>
      <h2>{{ t('home.emptyTitle') }}</h2>
      <p>{{ t('home.emptyBody') }}</p>
    </section>
  </section>
</template>

<style scoped>
.home-tab {
  display: flex;
  flex-direction: column;
  gap: 24px;
  /* Clear the floating capsule and its 12px drop from the safe area. */
  padding: 16px 20px calc(env(safe-area-inset-bottom, 0px) + 92px);
}

.home-tab-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

/* 品牌文字块：中文名（较小）+ 下方英文名。 */
.home-tab-brand {
  display: grid;
  gap: 2px;
  min-width: 0;
}

/* 品牌标记：直接用应用图标本身（启动器前景图裁到内容边界后存成
   src/assets/app-icon.png）。不用 SVG 近似、不染色、不套方底——
   用户看到的左上角就是桌面上那个图标。 */
.home-tab-mark {
  display: inline-flex;
  align-items: center;
  flex: none;
}

.home-tab-mark img {
  display: block;
  width: auto;
  height: 40px;
  max-width: 40vw;
  object-fit: contain;
  /* 图片左右镜像（水平翻转）后放到右侧。 */
  transform: scaleX(-1);
}

.home-tab-title {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  letter-spacing: -0.01em;
}

.home-tab-title-en {
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.06em;
}

.home-tab-notice {
  margin: 0;
  color: var(--text-muted);
  font-size: 13px;
}

.home-quick-actions {
  display: grid;
  gap: 12px;
}

.quick-action {
  display: grid;
  grid-template-columns: auto 1fr;
  align-items: center;
  gap: 14px;
  padding: 18px 18px;
  border: var(--hairline) solid var(--separator);
  border-radius: 20px;
  background: var(--surface);
  color: var(--text);
  font: inherit;
  text-align: left;
  touch-action: manipulation;
}

.quick-action.is-primary {
  border-color: transparent;
  background: var(--accent-soft);
  color: var(--accent-strong);
}

.quick-action:active {
  opacity: 0.82;
}

.quick-action:focus-visible {
  outline: 2px solid var(--focus-ring);
  outline-offset: 2px;
}

.quick-action-mark {
  display: inline-grid;
  place-items: center;
  width: 38px;
  height: 38px;
  /* 用主题自带的柔色叠加令牌，不依赖任何外部 --capsule-* 变量，
     也不使用现代颜色函数（项目契约禁止，老 WebView 不支持）。 */
  border-radius: 12px;
  background: var(--accent-soft);
}

.quick-action-mark svg {
  width: 20px;
  height: 20px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 2;
}

.quick-action-text {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.quick-action-text strong {
  font-size: 15px;
  font-weight: 650;
}

.quick-action-text span {
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 500;
}

.home-section {
  display: grid;
  gap: 10px;
}

.home-section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.home-section-label {
  margin: 0;
  color: var(--text-faint);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.home-section-more {
  padding: 4px 2px;
  border: 0;
  background: transparent;
  color: var(--accent-strong);
  font: inherit;
  font-size: 12px;
  font-weight: 650;
  touch-action: manipulation;
}

.home-section-more:focus-visible {
  outline: 2px solid var(--focus-ring);
  outline-offset: 2px;
  border-radius: 6px;
}

.continue-card {
  display: grid;
  gap: 6px;
  padding: 22px 20px;
  border: var(--hairline) solid var(--separator);
  border-radius: 22px;
  background: var(--surface);
  color: var(--text);
  font: inherit;
  text-align: left;
  touch-action: manipulation;
}

.continue-card:active {
  opacity: 0.85;
}

.continue-card:focus-visible {
  outline: 2px solid var(--focus-ring);
  outline-offset: 2px;
}

.continue-card-title {
  overflow: hidden;
  font-size: 17px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.continue-card-meta {
  color: var(--text-muted);
  font-size: 12px;
}

.home-recent-list {
  display: grid;
  overflow: hidden;
  border: var(--hairline) solid var(--separator);
  border-radius: 18px;
  background: var(--surface);
}

.home-recent-row {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 12px;
  min-height: 56px;
  padding: 10px 16px;
  border: 0;
  background: transparent;
  color: var(--text);
  font: inherit;
  text-align: left;
  touch-action: manipulation;
}

.home-recent-row + .home-recent-row {
  border-top: var(--hairline) solid var(--separator);
}

.home-recent-row:active {
  background: var(--accent-soft);
}

.home-recent-row:focus-visible {
  outline: 2px solid var(--focus-ring);
  outline-offset: -2px;
}

.home-recent-glyph {
  display: inline-grid;
  place-items: center;
  color: var(--text-faint);
}

.home-recent-glyph svg,
.home-recent-pin svg {
  width: 19px;
  height: 19px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.7;
}

.home-recent-text {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.home-recent-text strong {
  overflow: hidden;
  font-size: 14px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.home-recent-text span {
  overflow: hidden;
  color: var(--text-muted);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.home-recent-pin {
  display: inline-grid;
  place-items: center;
  color: var(--accent-strong);
}

.home-recent-more {
  margin: 0;
  color: var(--text-faint);
  font-size: 12px;
  text-align: center;
}

.home-empty {
  display: grid;
  justify-items: center;
  gap: 8px;
  padding: 40px 20px;
  text-align: center;
}

.home-empty-mark {
  display: inline-grid;
  place-items: center;
  width: 56px;
  height: 56px;
  border-radius: 18px;
  background: var(--accent-soft);
  color: var(--accent-strong);
}

.home-empty-mark svg {
  width: 28px;
  height: 28px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.6;
}

.home-empty h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 650;
}

.home-empty p {
  margin: 0;
  max-width: 26ch;
  color: var(--text-muted);
  font-size: 13px;
  line-height: 1.5;
}
</style>