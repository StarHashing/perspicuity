<script setup lang="ts">
import { computed, ref } from 'vue'
import AppBottomNavigationIcon from './AppBottomNavigationIcon.vue'
import { HOME_TAB_ITEMS, type HomeTab } from '../homeNavigation'
import { useI18n } from '../../../lib/i18n'

const props = defineProps<{
  activeTab: HomeTab
}>()

const emit = defineEmits<{
  setTab: [tab: HomeTab]
}>()

const { t } = useI18n()
const tabs = HOME_TAB_ITEMS

const activeIndex = computed(() =>
  Math.max(0, tabs.findIndex(tab => tab.id === props.activeTab)),
)

// Slot geometry mirrors ushio-md's LayoutBuilder math: the selection pill is
// centred on a slot and clamped between 48 and 64 logical pixels.
const pillStyle = computed(() => {
  const count = tabs.length
  const slotPercent = 100 / count
  const centre = (activeIndex.value + 0.5) * slotPercent
  return {
    width: `clamp(48px, ${(slotPercent * 0.72).toFixed(2)}%, 64px)`,
    transform: `translateX(calc(${centre}cqw - 50%))`,
  }
})

const dragOffset = ref(0)
const dragging = ref(false)
let dragStartX = 0

function onPointerDown(event: PointerEvent) {
  if (event.button !== undefined && event.button !== 0) {
    return
  }
  dragging.value = true
  dragStartX = event.clientX
  dragOffset.value = 0
  const target = event.currentTarget as HTMLElement
  target.setPointerCapture?.(event.pointerId)
}

function onPointerMove(event: PointerEvent) {
  if (!dragging.value) {
    return
  }
  dragOffset.value = event.clientX - dragStartX
}

function onPointerUp(event: PointerEvent) {
  if (!dragging.value) {
    return
  }
  dragging.value = false
  const target = event.currentTarget as HTMLElement
  target.releasePointerCapture?.(event.pointerId)

  const track = (event.currentTarget as HTMLElement).closest('.capsule-track')
  const width = track?.getBoundingClientRect().width ?? 0
  if (!width) {
    dragOffset.value = 0
    return
  }

  // Snap the dragged pill to whichever slot its centre landed on.
  const slotWidth = width / tabs.length
  const currentCentre = (activeIndex.value + 0.5) * slotWidth + dragOffset.value
  const nextIndex = Math.min(
    tabs.length - 1,
    Math.max(0, Math.floor(currentCentre / slotWidth)),
  )
  dragOffset.value = 0
  if (nextIndex !== activeIndex.value) {
    emit('setTab', tabs[nextIndex].id)
  }
}

function cancelDrag() {
  dragging.value = false
  dragOffset.value = 0
}

function selectTab(tab: HomeTab) {
  emit('setTab', tab)
}
</script>

<template>
  <nav
    class="capsule-nav"
    :aria-label="t('nav.primary')"
    data-testid="app-bottom-navigation"
  >
    <div
      class="capsule-track"
      :class="{ 'is-dragging': dragging }"
      @pointerdown="onPointerDown"
      @pointermove="onPointerMove"
      @pointerup="onPointerUp"
      @pointercancel="cancelDrag"
    >
      <span
        class="capsule-pill"
        :style="{
          ...pillStyle,
          '--drag': `${dragOffset}px`,
        }"
        aria-hidden="true"
      />
      <button
        v-for="tab in tabs"
        :key="tab.id"
        class="capsule-button"
        :class="{ 'is-active': activeTab === tab.id }"
        type="button"
        :aria-label="t(tab.labelKey)"
        :aria-current="activeTab === tab.id ? 'page' : undefined"
        :data-testid="`bottom-nav-${tab.id}`"
        @click="selectTab(tab.id)"
      >
        <span class="capsule-icon" aria-hidden="true">
          <AppBottomNavigationIcon :name="tab.icon" />
        </span>
        <span class="capsule-label">{{ t(tab.labelKey) }}</span>
      </button>
    </div>
  </nav>
</template>

<style scoped>
/* Floating liquid-glass capsule.

   配色方向（重要）：ushio-md 那套「白色半透明」是给深色背景设计的，直接搬到
   澄怀的米黄纸（--app-bg #f7f2e6）上会几乎隐形 —— 白叠米黄，还是米黄。
   这里反过来做：让胶囊比页面背景**更亮、更实**，像米黄纸上浮起一张更浅的
   纸，再用一圈清晰的暖色描边和一道落地投影把它从背景里切出来。

   变量全部在组件内定义，不依赖外部主题是否提供 --capsule-*（上一版就是因为
   引用了未定义变量，background 退化成 transparent，只剩模糊在撑场面）。
   所有值都从主题令牌派生，因此 60+ 套主题下都能自动跟色。 */
.capsule-nav {
  /* 配色令牌全部复用主题体系已提供的叠加色（--accent-soft / --accent-tint-10
     / --float-border-color），因此 60+ 套主题自动跟色；也不用现代颜色函数，
     本项目契约禁止它们（老 WebView 不支持），一律走预置令牌。 */
  --capsule-surface: var(--surface-raised);
  --capsule-rim: var(--float-border-color);
  --capsule-shadow: var(--shadow-float);
  --capsule-pill-bg: var(--accent-soft);
  --capsule-pill-rim: var(--accent-tint-11);

  position: fixed;
  z-index: 30;
  right: 16px;
  bottom: calc(env(safe-area-inset-bottom, 0px) + 12px);
  left: 16px;
  container-type: inline-size;
  pointer-events: none;
}

.capsule-track {
  position: relative;
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  align-items: center;
  height: 60px;
  overflow: hidden;
  /* 实底色 + 描边 + 投影三重分离：即使 backdrop-filter 不被 WebView 支持，
     胶囊依然是看得见的一层，而不是消失。 */
  border: var(--hairline) solid var(--capsule-rim);
  border-radius: 30px;
  background: var(--capsule-surface);
  box-shadow:
    0 12px 26px -10px var(--capsule-shadow),
    0 2px 6px -2px var(--capsule-shadow);
  -webkit-backdrop-filter: blur(16px) saturate(1.4);
  backdrop-filter: blur(16px) saturate(1.4);
  pointer-events: auto;
  touch-action: pan-y;
}

.capsule-pill {
  /* --drag 由模板内联 style 在拖拽时注入（默认 0px），这里声明默认值既是
     运行时兜底，也让 themeTokens 的「未定义令牌」契约能识别到它。 */
  --drag: 0px;
  position: absolute;
  top: 50%;
  left: 0;
  height: 44px;
  border: var(--hairline) solid var(--capsule-pill-rim);
  border-radius: 999px;
  background: var(--capsule-pill-bg);
  box-shadow: 0 4px 12px -4px var(--capsule-shadow);
  transform-origin: left center;
  translate: var(--drag, 0px) -50%;
  transition: transform 260ms var(--ease-spring);
  pointer-events: none;
  will-change: transform;
}

.capsule-track.is-dragging .capsule-pill {
  scale: 0.88;
  transition: scale 120ms var(--ease-out);
}

.capsule-button {
  position: relative;
  z-index: 1;
  display: grid;
  align-content: center;
  justify-items: center;
  gap: 3px;
  min-width: 0;
  height: 60px;
  padding: 0 4px;
  border: 0;
  background: transparent;
  color: var(--text-faint);
  font: inherit;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: -0.004em;
  touch-action: manipulation;
  transition: color 200ms var(--ease-out);
}

.capsule-button.is-active {
  color: var(--accent-strong);
}

.capsule-button:active {
  color: var(--text-muted);
  transition-duration: 0ms;
}

.capsule-button:focus-visible {
  outline: 2px solid var(--focus-ring);
  outline-offset: -4px;
  border-radius: 20px;
}

.capsule-icon {
  display: inline-grid;
  place-items: center;
  height: 24px;
  transition: transform 200ms var(--ease-out);
}

.capsule-button.is-active .capsule-icon {
  transform: scale(1.08);
}

.capsule-label {
  max-width: 100%;
  overflow: hidden;
  line-height: 1;
  white-space: nowrap;
  text-overflow: ellipsis;
}

@media (min-width: 720px) {
  .capsule-nav {
    right: max(24px, calc((100vw - 720px) / 2));
    left: max(24px, calc((100vw - 720px) / 2));
  }
}

@media (prefers-reduced-motion: reduce) {
  .capsule-pill,
  .capsule-icon,
  .capsule-button {
    transition: none;
  }
}
</style>
