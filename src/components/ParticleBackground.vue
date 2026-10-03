<script setup lang="ts">
/**
 * Perspicuity 粒子背景
 *
 * 效果对齐 ushio-md（jiuxina/ushio-md, MIT）的 ParticleEffectWidget：
 *   🌸 sakura  樱花  —— 粉色花瓣飘落，自旋 + 横向摇摆
 *   🌧️ rain    下雨  —— 斜向雨滴线段
 *   ✨ firefly 萤火虫 —— 黄绿光点，缓慢漂浮 + 闪烁 + 边界反弹 + 外发光
 *   ❄️ snow    雪花  —— 白色圆点 + 十字，缓慢摇摆飘落
 *
 * 实现说明（Flutter CustomPainter → Canvas2D 的等价迁移）：
 *   - 坐标系用「归一化 0..1 + 乘尺寸」的方式，与上游一致，
 *     这样参数（size/speed）语义与 ushio-md 完全相同，便于用户迁移直觉。
 *   - 用 rAF 驱动；App 后台 / 编辑器聚焦时暂停，省电。
 *   - MaskFilter.blur 的外发光用 ctx.shadowBlur + shadowColor 等价实现。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { PARTICLE_BASE_COUNTS, type ParticleType } from './particleTypes'

export type { ParticleType }

const props = withDefaults(
  defineProps<{
    enabled?: boolean
    /** 效果类型。 */
    type?: ParticleType
    /** 速率 0.05-1.0（对齐上游，UI 显示 ×2）。 */
    speed?: number
    /** 数量倍数 0.25-2.0。 */
    count?: number
    /** 大小倍数 0.5-2.0。 */
    size?: number
    /** 透明度 0.1-1.0。 */
    opacity?: number
    /** 风向 -1.0 到 1.0。 */
    wind?: number
    /** 是否全局显示（false = 仅首页）。 */
    global?: boolean
    /** 当前是否在首页。 */
    atHome?: boolean
  }>(),
  {
    enabled: false,
    type: 'sakura',
    speed: 0.5,
    count: 1.0,
    size: 1.0,
    opacity: 1.0,
    wind: 0.0,
    global: true,
    atHome: true,
  },
)

const canvasRef = ref<HTMLCanvasElement | null>(null)

const active = computed(() => props.enabled && (props.global || props.atHome))

interface Particle {
  x: number
  y: number
  size: number
  speed: number
  angle: number
  wobble: number
  opacity: number
  phase: number
  color: string
}

/** 上游的基础粒子数（sakura 30 / rain 100 / firefly 25 / snow 60）。 */
const BASE_COUNTS = PARTICLE_BASE_COUNTS

function rgba(r: number, g: number, b: number, a: number) {
  return `rgba(${r},${g},${b},${a})`
}

/** 线性插值两个 RGB 颜色，等价上游 Color.lerp。 */
function lerpColor(
  c1: [number, number, number],
  c2: [number, number, number],
  t: number,
): [number, number, number] {
  return [
    Math.round(c1[0] + (c2[0] - c1[0]) * t),
    Math.round(c1[1] + (c2[1] - c1[1]) * t),
    Math.round(c1[2] + (c2[2] - c1[2]) * t),
  ]
}

const SAKURA_A: [number, number, number] = [0xff, 0xb7, 0xc5]
const SAKURA_B: [number, number, number] = [0xff, 0x69, 0xb4]
const FIREFLY_A: [number, number, number] = [0x9a, 0xcd, 0x32]
const FIREFLY_B: [number, number, number] = [0xad, 0xff, 0x2f]

let raf = 0
let particles: Particle[] = []
let width = 0
let height = 0
let dpr = 1
let lastFrame = 0
let paused = false
/** 缓存的 2D 上下文，避免每帧重复 getContext 的开销。 */
let cachedCtx: CanvasRenderingContext2D | null = null

function createParticle(randomY: boolean): Particle {
  const type = props.type
  switch (type) {
    case 'sakura': {
      const t = Math.random()
      const [r, g, b] = lerpColor(SAKURA_A, SAKURA_B, t)
      return {
        x: Math.random(),
        y: randomY ? Math.random() : -0.1,
        size: (8 + Math.random() * 8) * props.size,
        speed: 0.3 + Math.random() * 0.3,
        angle: Math.random() * 2 * Math.PI,
        wobble: Math.random() * 2 * Math.PI,
        opacity: (0.6 + Math.random() * 0.4) * props.opacity,
        phase: Math.random() * 2 * Math.PI,
        color: rgba(r, g, b, 1),
      }
    }
    case 'rain':
      return {
        x: Math.random(),
        y: randomY ? Math.random() : -0.1,
        size: (2 + Math.random() * 3) * props.size,
        speed: 1.5 + Math.random() * 1.0,
        angle: 0.15,
        wobble: 0,
        opacity: (0.3 + Math.random() * 0.4) * props.opacity,
        phase: 0,
        color: rgba(0x87, 0xce, 0xeb, 0.6),
      }
    case 'firefly': {
      const t = Math.random()
      const [r, g, b] = lerpColor(FIREFLY_A, FIREFLY_B, t)
      return {
        x: Math.random(),
        y: Math.random(),
        size: (3 + Math.random() * 4) * props.size,
        speed: 0.1 + Math.random() * 0.15,
        angle: Math.random() * 2 * Math.PI,
        wobble: 0,
        opacity: (0.4 + Math.random() * 0.6) * props.opacity,
        phase: Math.random() * 2 * Math.PI,
        color: rgba(r, g, b, 1),
      }
    }
    case 'snow':
    default:
      return {
        x: Math.random(),
        y: randomY ? Math.random() : -0.1,
        size: (3 + Math.random() * 5) * props.size,
        speed: 0.2 + Math.random() * 0.3,
        angle: 0,
        wobble: Math.random() * 2 * Math.PI,
        opacity: (0.5 + Math.random() * 0.5) * props.opacity,
        phase: Math.random() * 2 * Math.PI,
        color: '#ffffff',
      }
  }
}

function rebuild(): boolean {
  const canvas = canvasRef.value
  if (!canvas) return false
  const rect = canvas.getBoundingClientRect()
  // canvas 处于 display:none（v-show=false）时 rect 为 0×0。
  // 此时绝不能拿去当画布尺寸——否则宽高退化成 1×1，
  // 归一化坐标 ×1 退化到 0~1px，而 p.size 仍是 8~16px 的像素半径，
  // 结果就是所有巨型粉色椭圆挤在角落 → 「满屏粉色」。
  if (rect.width < 2 || rect.height < 2) return false

  dpr = Math.min(window.devicePixelRatio || 1, 2)
  width = Math.max(1, Math.floor(rect.width))
  height = Math.max(1, Math.floor(rect.height))
  canvas.width = Math.floor(width * dpr)
  canvas.height = Math.floor(height * dpr)
  const ctx = canvas.getContext('2d')
  if (ctx) {
    cachedCtx = ctx
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.clearRect(0, 0, width, height)
  }

  const base = BASE_COUNTS[props.type]
  const n = Math.max(4, Math.round(base * props.count))
  particles = Array.from({ length: n }, () => createParticle(true))
  return true
}

/**
 * 等 DOM 真正完成布局后再重建。
 * v-show 从 display:none 切到 display:block 的样式刷新发生在 watch 回调之后，
 * 直接同步 rebuild() 必然拿到 0×0 —— 这就是「一开粒子就满屏粉色」的根因。
 */
async function rebuildAfterLayout() {
  await nextTick()
  if (!rebuild()) {
    // 极端情况（布局尚未完成）再等一帧
    requestAnimationFrame(() => rebuild())
  }
}

// --- 各类型绘制（对齐上游 _drawXxx） ---

function drawSakura(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  p: Particle,
  size: number,
) {
  ctx.save()
  ctx.translate(x, y)
  ctx.rotate(p.angle)
  ctx.globalAlpha = p.opacity
  ctx.fillStyle = p.color
  ctx.beginPath()
  // 椭圆花瓣：宽 size、高 size*0.6（对齐上游 width: p.size, height: p.size*0.6）
  ctx.ellipse(0, 0, size / 2, (size * 0.6) / 2, 0, 0, Math.PI * 2)
  ctx.fill()
  ctx.restore()
}

function drawRain(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  p: Particle,
  size: number,
) {
  const length = size * 8
  const dx = Math.sin(p.angle) * length
  const dy = Math.cos(p.angle) * length
  ctx.globalAlpha = p.opacity
  ctx.strokeStyle = p.color
  ctx.lineWidth = Math.max(0.5, size * 0.5)
  ctx.lineCap = 'round'
  ctx.beginPath()
  ctx.moveTo(x, y)
  ctx.lineTo(x + dx, y + dy)
  ctx.stroke()
}

/**
 * 把一个 CSS 颜色改成「同色、指定 alpha」的 rgba()。
 * 支持 #rgb / #rrggbb / rgb() / rgba()，解析失败时回退为透明，
 * 避免在渐变末端留下黑色插值造成的灰环。
 */
function withAlpha(color: string, alpha: number): string {
  const rgbaMatch = color.match(
    /^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)(?:\s*,\s*([\d.]+))?\s*\)$/i,
  )
  if (rgbaMatch) {
    const [, r, g, b] = rgbaMatch
    return `rgba(${r}, ${g}, ${b}, ${alpha})`
  }

  const hex = color.replace('#', '')
  if (hex.length === 3) {
    const r = parseInt(hex[0] + hex[0], 16)
    const g = parseInt(hex[1] + hex[1], 16)
    const b = parseInt(hex[2] + hex[2], 16)
    return `rgba(${r}, ${g}, ${b}, ${alpha})`
  }
  if (hex.length === 6) {
    const r = parseInt(hex.slice(0, 2), 16)
    const g = parseInt(hex.slice(2, 4), 16)
    const b = parseInt(hex.slice(4, 6), 16)
    return `rgba(${r}, ${g}, ${b}, ${alpha})`
  }

  return `rgba(0, 0, 0, ${alpha})`
}

function drawFirefly(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  p: Particle,
  size: number,
) {
  // 外发光：用 radialGradient 替代 ctx.shadowBlur。
  // shadowBlur 在移动端是逐像素高斯模糊，几十个粒子同屏会直接掉帧；
  // 径向渐变是纯 GPU 友好的填充，观感几乎一致。
  //
  // 渐变的末端必须用「同色透明」而不是 'rgba(0,0,0,0)'：向黑色透明插值
  // 时中间会经过一段灰色，在浅色背景上表现为一圈灰环。这里复用 p.color
  // 的颜色通道、只把 alpha 收到 0，过渡就干净了。
  const glowR = size * 3
  const grad = ctx.createRadialGradient(x, y, 0, x, y, glowR)
  grad.addColorStop(0, p.color)
  grad.addColorStop(1, withAlpha(p.color, 0))
  ctx.globalAlpha = p.opacity * 0.5
  ctx.fillStyle = grad
  ctx.beginPath()
  ctx.arc(x, y, glowR, 0, Math.PI * 2)
  ctx.fill()

  // 核心光点
  ctx.globalAlpha = p.opacity
  ctx.fillStyle = p.color
  ctx.beginPath()
  ctx.arc(x, y, size, 0, Math.PI * 2)
  ctx.fill()
}

function drawSnow(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  p: Particle,
  size: number,
) {
  ctx.globalAlpha = p.opacity
  ctx.fillStyle = p.color
  ctx.beginPath()
  ctx.arc(x, y, size, 0, Math.PI * 2)
  ctx.fill()

  ctx.globalAlpha = p.opacity * 0.5
  ctx.strokeStyle = p.color
  ctx.lineWidth = 1
  ctx.beginPath()
  ctx.moveTo(x - size, y)
  ctx.lineTo(x + size, y)
  ctx.moveTo(x, y - size)
  ctx.lineTo(x, y + size)
  ctx.stroke()
}

function draw() {
  const ctx = cachedCtx
  if (!ctx) return
  if (width < 2 || height < 2) return
  ctx.clearRect(0, 0, width, height)
  ctx.globalAlpha = 1
  ctx.shadowBlur = 0

  // 尺寸硬保护：上游 p.size 是「参考 400 宽下的像素值」，
  // 在真实设备（宽约 360~430dp）上按比例映射，避免异常环境下被放大糊屏。
  const scale = Math.min(2, Math.max(0.5, width / 400))

  for (const p of particles) {
    const x = p.x * width
    const y = p.y * height
    switch (props.type) {
      case 'sakura':
        drawSakura(ctx, x, y, p, p.size * scale)
        break
      case 'rain':
        drawRain(ctx, x, y, p, p.size * scale)
        break
      case 'firefly':
        drawFirefly(ctx, x, y, p, p.size * scale)
        break
      case 'snow':
        drawSnow(ctx, x, y, p, p.size * scale)
        break
    }
  }
  ctx.globalAlpha = 1
  ctx.shadowBlur = 0
}

// --- 各类型物理（对齐上游 _updateXxxParticle） ---

function updateAll(dt: number) {
  const windEffect = props.wind * 0.003
  for (let i = 0; i < particles.length; i++) {
    const p = particles[i]
    switch (props.type) {
      case 'sakura':
        p.y += p.speed * dt
        p.wobble += dt * 2
        p.x += Math.sin(p.wobble) * 0.002 + windEffect
        p.angle += dt * 0.5
        break
      case 'rain':
        p.y += p.speed * dt
        p.x += p.angle * dt * 0.3 + windEffect
        break
      case 'firefly':
        p.phase += dt * 3
        p.x += Math.sin(p.phase) * 0.002 + windEffect * 0.3
        p.y += Math.cos(p.phase * 0.7) * 0.001
        p.opacity = (0.3 + Math.sin(p.phase * 2) * 0.35 + 0.35) * props.opacity
        // 边界反弹
        if (p.x < 0) p.x = 0
        if (p.x > 1) p.x = 1
        if (p.y < 0) p.y = 0
        if (p.y > 1) p.y = 1
        continue
      case 'snow':
        p.y += p.speed * dt
        p.wobble += dt * 1.5
        p.x += Math.sin(p.wobble) * 0.001 + windEffect
        break
    }

    // 出界重生（萤火虫不重生，见上）
    if (p.y > 1.1 || p.x < -0.1 || p.x > 1.1) {
      particles[i] = createParticle(false)
    }
  }
}

/**
 * 时间步长（对齐上游 `final dt = 0.016 * widget.speed`）。
 *
 * 上游是 AnimationController 每帧回调 + 固定 0.016 步长（约 60fps）。
 * 之前这里错误地写成 `elapsed(秒) * 16.67`，等于把每帧步长放大了 ~25 倍，
 * 所以「调到最慢也飞快」。现在改为固定基准步长；同时用真实帧间隔做一次
 * 温和的倍率补偿（限制在 0.5~1.5），让低帧率设备不至于慢成幻灯片。
 */
function computeDt(frameSeconds: number): number {
  const base = 0.016 * props.speed
  const frameFactor = Math.min(1.5, Math.max(0.5, frameSeconds / 0.0167))
  return base * frameFactor
}

function step(ts: number) {
  if (!active.value) {
    raf = 0
    return
  }
  raf = requestAnimationFrame(step)

  // 兜底自愈：万一 paused 因某个没收到 focusout 的路径卡在 true，
  // 只要页面可见且当前焦点不在可编辑元素上，就立刻恢复。
  // 这样「切完页面粒子停住、点一下才动」的问题从根上不会再出现。
  if (paused && document.visibilityState !== 'hidden' && !isEditableTarget(document.activeElement)) {
    paused = false
    lastFrame = 0
  }
  if (paused) return

  // 画布尺寸为 0（尚未完成布局 / 刚切回）→ 补一次重建，避免用退化尺寸绘制
  if (width < 2 || height < 2) rebuild()

  if (!lastFrame) lastFrame = ts
  const elapsed = (ts - lastFrame) / 1000
  if (elapsed < 0.016) return // 目标 ~60fps
  lastFrame = ts
  if (width < 2 || height < 2) return // 尺寸仍无效则不画
  updateAll(computeDt(Math.min(elapsed, 0.05)))
  draw()
}

function start() {
  cancelAnimationFrame(raf)
  raf = 0
  lastFrame = 0
  paused = false
  if (!active.value) return
  raf = requestAnimationFrame(step)
}

function stop() {
  cancelAnimationFrame(raf)
  raf = 0
  lastFrame = 0
}

function onVisibility() {
  // 回到前台时重置时间基准，避免累积一个巨大的 dt 把粒子瞬移出界
  paused = document.visibilityState === 'hidden'
  lastFrame = 0
}
/**
 * 只有「真正把光标放进可编辑区域」才值得暂停粒子（省电）。
 *
 * 之前的版本在任何 focusin（包括点击底部 tab 的 <button>）都直接
 * paused = true，而恢复只依赖 focusout——切 tab / 点按钮后焦点常常
 * 停在同一个元素上，focusout 不会触发，paused 就永远卡在 true，
 * 表现就是「切完页面粒子停住，点一下别处才恢复」。
 *
 * 这里把判断收窄到可编辑元素；并且一旦焦点落在非可编辑处，
 * 立即恢复，不再依赖 focusout。
 */
function isEditableTarget(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null
  if (!el || !el.tagName) return false
  const tag = el.tagName
  return (
    el.isContentEditable
    || tag === 'INPUT'
    || tag === 'TEXTAREA'
    || tag === 'SELECT'
  )
}

function onFocusIn(event: FocusEvent) {
  // 仅当焦点进入可编辑区域时才暂停；点 tab/按钮等非编辑元素不暂停。
  paused = isEditableTarget(event.target)
}
function onFocusOut(event: FocusEvent) {
  // 离开焦点时，只要不是正落到另一个可编辑元素上，就恢复运行。
  paused = isEditableTarget(event.relatedTarget)
  lastFrame = 0
}
function onResize() {
  rebuild()
}

onMounted(() => {
  if (typeof window === 'undefined') return
  void rebuildAfterLayout()
  if (active.value) start()
  window.addEventListener('resize', onResize)
  document.addEventListener('visibilitychange', onVisibility)
  document.addEventListener('focusin', onFocusIn)
  document.addEventListener('focusout', onFocusOut)
})

onBeforeUnmount(() => {
  stop()
  window.removeEventListener('resize', onResize)
  document.removeEventListener('visibilitychange', onVisibility)
  document.removeEventListener('focusin', onFocusIn)
  document.removeEventListener('focusout', onFocusOut)
})

let toggleToken = 0
watch(active, async val => {
  const token = ++toggleToken
  if (val) {
    // 先等样式从 display:none 切换完成，再量尺寸建画布。
    // 用 token 防止「快速来回切页面」时旧回调后到覆盖新状态。
    await rebuildAfterLayout()
    if (token !== toggleToken) return
    start()
  } else {
    stop()
    if (cachedCtx && width >= 2 && height >= 2) {
      cachedCtx.clearRect(0, 0, width, height)
    }
  }
})

// 类型/数量/大小变化 → 重建；透明度/风/速度无需重建
watch(
  () => [props.type, props.count, props.size],
  () => {
    if (active.value) void rebuildAfterLayout()
  },
)
</script>

<template>
  <!--
    用 visibility 而非 v-show(display) 隐藏：
    display:none 会让 getBoundingClientRect() 变成 0×0，导致重建画布时拿到退化尺寸。
    visibility:hidden 保留布局盒，尺寸始终可测，从根源上避免「满屏粉色」。
    active=false 时额外关闭 rAF，仍不耗电。
  -->
  <canvas
    ref="canvasRef"
    class="perspicuity-particles"
    :style="{ visibility: active ? 'visible' : 'hidden' }"
    aria-hidden="true"
  />
</template>

<style scoped>
.perspicuity-particles {
  position: fixed;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  z-index: 1;
  display: block;
}
</style>