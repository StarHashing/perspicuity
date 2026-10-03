<script setup lang="ts">
/**
 * 通用输入 / 确认弹窗。
 *
 * 为什么不用 window.prompt / window.confirm：Android WebView 的原生
 * prompt 是一个不可控的方块弹窗（跟随系统主题，跟 App 的暖色纸感完全脱节），
 * 而且无法做校验反馈与自动聚焦。这里自己实现一套，视觉与 App 一致。
 *
 * 使用方式（命令式，基于 Promise）：
 *   const dialogRef = ref<PromptDialogApi | null>(null)
 *   const name = await dialogRef.value!.askText({ title, placeholder })
 *   const yes  = await dialogRef.value!.askConfirm({ title, confirmLabel })
 */
import { nextTick, ref } from 'vue'

export interface PromptDialogApi {
  askText(options: AskTextOptions): Promise<string | null>
  askConfirm(options: AskConfirmOptions): Promise<boolean>
}

export interface AskTextOptions {
  title: string
  /** 预填内容。 */
  initial?: string
  placeholder?: string
  confirmLabel?: string
  cancelLabel?: string
}

export interface AskConfirmOptions {
  title: string
  /** 可选的补充说明。 */
  body?: string
  confirmLabel?: string
  cancelLabel?: string
  /** 危险操作（删除等）用红色确认按钮。 */
  danger?: boolean
}

type DialogMode = 'text' | 'confirm'

const visible = ref(false)
const mode = ref<DialogMode>('text')
const title = ref('')
const body = ref('')
const placeholder = ref('')
const confirmLabel = ref('')
const cancelLabel = ref('')
const danger = ref(false)
const value = ref('')
const inputEl = ref<HTMLInputElement | null>(null)

let resolver: ((result: string | null) => void) | null = null
let confirmResolver: ((result: boolean) => void) | null = null

function reset() {
  visible.value = false
  title.value = ''
  body.value = ''
  placeholder.value = ''
  confirmLabel.value = ''
  cancelLabel.value = ''
  danger.value = false
  value.value = ''
  resolver = null
  confirmResolver = null
}

function askText(options: AskTextOptions): Promise<string | null> {
  title.value = options.title
  body.value = ''
  placeholder.value = options.placeholder ?? ''
  confirmLabel.value = options.confirmLabel ?? ''
  cancelLabel.value = options.cancelLabel ?? ''
  danger.value = false
  value.value = options.initial ?? ''
  mode.value = 'text'
  visible.value = true
  return new Promise<string | null>(resolve => {
    resolver = resolve
    void nextTick(() => {
      inputEl.value?.focus()
      inputEl.value?.select()
    })
  })
}

function askConfirm(options: AskConfirmOptions): Promise<boolean> {
  title.value = options.title
  body.value = options.body ?? ''
  confirmLabel.value = options.confirmLabel ?? ''
  cancelLabel.value = options.cancelLabel ?? ''
  danger.value = options.danger === true
  mode.value = 'confirm'
  visible.value = true
  return new Promise<boolean>(resolve => {
    confirmResolver = resolve
  })
}

/** 确认：文本模式返回输入值，确认模式返回 true。 */
function submit() {
  if (mode.value === 'text') {
    const done = resolver
    const result = value.value.trim() ? value.value.trim() : null
    reset()
    done?.(result)
    return
  }
  const done = confirmResolver
  reset()
  done?.(true)
}

/** 取消 / 点遮罩：文本模式返回 null，确认模式返回 false。 */
function cancel() {
  if (mode.value === 'text') {
    const done = resolver
    reset()
    done?.(null)
    return
  }
  const done = confirmResolver
  reset()
  done?.(false)
}

/** 文本输入按回车直接提交。 */
function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter') {
    event.preventDefault()
    submit()
  } else if (event.key === 'Escape') {
    event.preventDefault()
    cancel()
  }
}

defineExpose<PromptDialogApi>({ askText, askConfirm })
</script>

<template>
  <Teleport to="body">
    <Transition name="pdialog">
      <div v-if="visible" class="pdialog-backdrop" @click.self="cancel">
        <div class="pdialog" role="dialog" aria-modal="true">
          <h3 class="pdialog-title">{{ title }}</h3>
          <p v-if="body" class="pdialog-body">{{ body }}</p>
          <input
            v-if="mode === 'text'"
            ref="inputEl"
            v-model="value"
            class="pdialog-input"
            type="text"
            :placeholder="placeholder"
            @keydown="onKeydown"
          />
          <div class="pdialog-actions">
            <button class="pdialog-btn is-ghost" type="button" @click="cancel">
              {{ cancelLabel || '取消' }}
            </button>
            <button
              class="pdialog-btn is-primary"
              :class="{ 'is-danger': danger }"
              type="button"
              @click="submit"
            >
              {{ confirmLabel || '确定' }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.pdialog-backdrop {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: var(--scrim);
  animation: pdialog-fade-in 0.16s ease;
}

.pdialog {
  width: min(360px, 100%);
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 20px 20px 16px;
  border-radius: 18px;
  background: var(--surface-raised);
  border: var(--hairline) solid var(--border);
  box-shadow: var(--shadow-float);
}

.pdialog-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--text);
}

.pdialog-body {
  margin: -6px 0 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--text-muted);
}

.pdialog-input {
  width: 100%;
  box-sizing: border-box;
  padding: 11px 14px;
  border-radius: 12px;
  font-size: 15px;
  color: var(--text);
  background: var(--surface-sunken);
  border: var(--hairline) solid var(--border);
  outline: none;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.pdialog-input::placeholder {
  color: var(--text-faint);
}

.pdialog-input:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-tint-10);
}

.pdialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 2px;
}

.pdialog-btn {
  padding: 9px 18px;
  border-radius: 11px;
  font-size: 14px;
  font-weight: 500;
  border: var(--hairline) solid transparent;
  cursor: pointer;
  transition: background 0.15s ease, opacity 0.15s ease;
}

.pdialog-btn.is-ghost {
  color: var(--text-muted);
  background: transparent;
  border-color: var(--border);
}

.pdialog-btn.is-ghost:hover {
  background: var(--surface-muted);
}

.pdialog-btn.is-primary {
  color: var(--on-accent);
  background: var(--accent);
}

.pdialog-btn.is-primary:hover {
  opacity: 0.9;
}

.pdialog-btn.is-primary.is-danger {
  background: var(--danger);
}

@keyframes pdialog-fade-in {
  from { opacity: 0; }
  to { opacity: 1; }
}

.pdialog-enter-active,
.pdialog-leave-active {
  transition: opacity 0.16s ease;
}

.pdialog-enter-from,
.pdialog-leave-to {
  opacity: 0;
}
</style>
