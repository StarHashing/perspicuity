<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from '../../../lib/i18n'
const props = defineProps<{
  label: string
  modelValue: string
  placeholder?: string
  multiline?: boolean
  testId?: string
  /**
   * 可选的下拉候选（字体族）。
   * 给了就渲染成一个「输入框 + 下拉」，既能手输族名，也能从已导入字体里点选。
   * 这是刻意的：手输是上游就有的能力，不能因为加了选择器就丢掉。
   */
  fontOptions?: readonly { id: string; label: string }[]
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

function emitUpdate(value: string) {
  emit('update:modelValue', value)
}

const isPickerOpen = ref(false)
const { t } = useI18n()

const hasFontOptions = computed(() => (props.fontOptions?.length ?? 0) > 0)

function chooseFontOption(id: string) {
  emitUpdate(id)
  isPickerOpen.value = false
}
</script>

<template>
  <label class="settings-text-row" :data-testid="testId">
    <span class="settings-text-label">{{ label }}</span>
    <textarea
      v-if="multiline"
      class="settings-text-input is-multiline"
      rows="4"
      :placeholder="placeholder"
      :value="modelValue"
      @input="emitUpdate(($event.target as HTMLTextAreaElement).value)"
    />
    <span v-else class="settings-text-with-picker">
      <input
        class="settings-text-input"
        type="text"
        :placeholder="placeholder"
        :value="modelValue"
        @input="emitUpdate(($event.target as HTMLInputElement).value)"
      >
      <button
        v-if="hasFontOptions"
        class="settings-text-picker-toggle"
        type="button"
        :aria-expanded="isPickerOpen"
        @click="isPickerOpen = !isPickerOpen"
      >
        {{ t('settings.typography.fontPickerToggle') }}
      </button>
    </span>
    <ul v-if="isPickerOpen && hasFontOptions" class="settings-text-picker">
      <li v-for="option in fontOptions" :key="option.id">
        <button
          class="settings-text-picker-option"
          :class="{ 'is-selected': option.id === modelValue }"
          type="button"
          @click="chooseFontOption(option.id)"
        >
          {{ option.label }}
        </button>
      </li>
    </ul>
  </label>
</template>

<style scoped>
.settings-text-row {
  position: relative;
  display: grid;
  gap: 10px;
  width: 100%;
  min-width: 0;
  min-height: 76px;
  padding: 12px 20px 14px;
  border: 0;
  color: var(--text);
}

.settings-text-label {
  min-width: 0;
  font-size: 15px;
  font-weight: 500;
  line-height: 1.35;
  letter-spacing: -0.006em;
  overflow-wrap: anywhere;
}

.settings-text-input {
  width: 100%;
  min-height: 42px;
  padding: 8px 12px;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  background: var(--surface-muted);
  color: var(--text);
  font: inherit;
  font-size: 14px;
  line-height: 1.35;
  transition:
    border-color var(--dur-standard) var(--ease-out),
    background-color var(--dur-standard) var(--ease-out);
}

.settings-text-input.is-multiline {
  resize: vertical;
}

.settings-text-with-picker {
  display: flex;
  align-items: stretch;
  gap: 8px;
}

.settings-text-with-picker .settings-text-input {
  flex: 1 1 auto;
}

.settings-text-picker-toggle {
  flex: 0 0 auto;
  min-height: 42px;
  padding: 8px 14px;
  border: 1px solid var(--separator);
  border-radius: var(--radius-sm);
  background: var(--surface-muted);
  color: var(--accent-strong);
  font: inherit;
  font-size: 14px;
  font-weight: 600;
}

.settings-text-picker-toggle:active {
  background: var(--press);
}

.settings-text-picker {
  display: grid;
  gap: 2px;
  max-height: 260px;
  margin: 0;
  padding: 6px;
  overflow-y: auto;
  border: 1px solid var(--separator);
  border-radius: var(--radius-sm);
  background: var(--surface-raised, var(--surface));
  list-style: none;
}

.settings-text-picker-option {
  width: 100%;
  min-height: 40px;
  padding: 8px 10px;
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text);
  font: inherit;
  font-size: 14px;
  text-align: left;
}

.settings-text-picker-option.is-selected {
  color: var(--accent-strong);
  font-weight: 600;
}

.settings-text-picker-option:active {
  background: var(--press);
}

.settings-text-input:focus-visible {
  border-color: var(--accent);
  outline: 2px solid var(--focus-ring);
  outline-offset: 1px;
}
</style>
