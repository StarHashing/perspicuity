/**
 * Perspicuity 粒子背景设置
 *
 * 参数集合与取值范围对齐 ushio-md（jiuxina/ushio-md, MIT）：
 *   particleType / particleSpeed / particleCount / particleSize
 *   particleOpacity / particleWind / particleGlobal
 *
 * 默认关闭 —— 保证不改上游默认观感。
 */
import type { SettingsValue } from './settingsState'
import {
  PARTICLE_TYPES,
  PARTICLE_TYPE_LABELS,
  type ParticleType,
} from '../../components/particleTypes'

export type { ParticleType }
export { PARTICLE_TYPES, PARTICLE_TYPE_LABELS }

export interface ParticleSettings {
  /** 总开关。 */
  enabled: boolean
  /** 效果类型：樱花 / 雨 / 萤火虫 / 雪花。 */
  type: ParticleType
  /** 速率 0.05-1.0（UI 显示 ×2，与上游一致）。 */
  speed: number
  /** 数量倍数 0.25-2.0。 */
  count: number
  /** 大小倍数 0.5-2.0。 */
  size: number
  /** 透明度 0.1-1.0。 */
  opacity: number
  /** 风向 -1.0 到 1.0。 */
  wind: number
  /** 全局显示（true=所有页面，false=仅首页）。 */
  global: boolean
}

export const DEFAULT_PARTICLE_SETTINGS: ParticleSettings = {
  enabled: false,
  type: 'sakura',
  speed: 0.5,
  count: 1.0,
  size: 1.0,
  opacity: 1.0,
  wind: 0.0,
  global: true,
}

/**
 * 设置页 row id ↔ 存储 key 的归属声明。
 *
 * settingsContent.test.ts 的治理规则要求每个 runtime row 都被某个
 * feature 模块「认领」，并保证 descriptor 默认值与模块默认值一致。
 * 这里用同一份 DEFAULT 派生，改默认值只需改一处。
 */
export const PARTICLE_SETTING_KEYS = [
  'particleEnabled',
  'particleType',
  'particleSpeed',
  'particleCount',
  'particleSize',
  'particleOpacity',
  'particleWind',
  'particleGlobal',
] as const

/** 设置页 row id → 默认值（供治理测试与桥接读取共用）。 */
export const DEFAULT_PARTICLE_ROW_VALUES: Readonly<Record<string, SettingsValue>> = {
  particleEnabled: DEFAULT_PARTICLE_SETTINGS.enabled,
  particleType: DEFAULT_PARTICLE_SETTINGS.type,
  particleSpeed: DEFAULT_PARTICLE_SETTINGS.speed,
  particleCount: DEFAULT_PARTICLE_SETTINGS.count,
  particleSize: DEFAULT_PARTICLE_SETTINGS.size,
  particleOpacity: DEFAULT_PARTICLE_SETTINGS.opacity,
  particleWind: DEFAULT_PARTICLE_SETTINGS.wind,
  particleGlobal: DEFAULT_PARTICLE_SETTINGS.global,
}

function clampNum(value: unknown, fallback: number, min: number, max: number, decimals = 2) {
  const n = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(n)) return fallback
  const clamped = Math.min(Math.max(n, min), max)
  const f = 10 ** decimals
  return Math.round(clamped * f) / f
}

export function normalizeParticleSettings(value: unknown): ParticleSettings {
  const raw = (value ?? {}) as Partial<ParticleSettings>
  const type = PARTICLE_TYPES.includes(raw.type as ParticleType)
    ? (raw.type as ParticleType)
    : DEFAULT_PARTICLE_SETTINGS.type
  return {
    enabled: raw.enabled === true,
    type,
    speed: clampNum(raw.speed, DEFAULT_PARTICLE_SETTINGS.speed, 0.05, 1.0),
    count: clampNum(raw.count, DEFAULT_PARTICLE_SETTINGS.count, 0.25, 2.0),
    size: clampNum(raw.size, DEFAULT_PARTICLE_SETTINGS.size, 0.5, 2.0),
    opacity: clampNum(raw.opacity, DEFAULT_PARTICLE_SETTINGS.opacity, 0.1, 1.0),
    wind: clampNum(raw.wind, DEFAULT_PARTICLE_SETTINGS.wind, -1.0, 1.0),
    global: raw.global !== false,
  }
}

export function getParticleSettings(
  getValue: <T extends SettingsValue>(key: string, defaultValue: T) => T,
): ParticleSettings {
  // 设置页写的是分散 key（particleEnabled / particleType / ...），
  // 这里把它们汇总成一个对象；旧版聚合 key `particles` 仍作为兜底读取。
  const legacy = normalizeParticleSettings(
    getValue('particles', DEFAULT_PARTICLE_SETTINGS as unknown as SettingsValue),
  )
  const hasUiKeys =
    getValue('particleEnabled', '__none__') !== '__none__' ||
    getValue('particleType', '__none__') !== '__none__'

  if (!hasUiKeys) return legacy

  return normalizeParticleSettings({
    enabled: getValue('particleEnabled', legacy.enabled),
    type: getValue('particleType', legacy.type),
    speed: getValue('particleSpeed', legacy.speed),
    count: getValue('particleCount', legacy.count),
    size: getValue('particleSize', legacy.size),
    opacity: getValue('particleOpacity', legacy.opacity),
    wind: getValue('particleWind', legacy.wind),
    global: getValue('particleGlobal', legacy.global),
  } as unknown as Partial<ParticleSettings>)
}