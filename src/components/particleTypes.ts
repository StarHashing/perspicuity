/**
 * 粒子类型定义（独立文件，供组件与设置模块共享）。
 * 放在 .ts 里而不是 .vue，是为了能被普通 TS 模块 import。
 */
export type ParticleType = 'sakura' | 'rain' | 'firefly' | 'snow'

export const PARTICLE_TYPES: readonly ParticleType[] = ['sakura', 'rain', 'firefly', 'snow']

export const PARTICLE_TYPE_LABELS: Record<ParticleType, { label: string; icon: string }> = {
  sakura: { label: '樱花', icon: '🌸' },
  rain: { label: '下雨', icon: '🌧️' },
  firefly: { label: '萤火虫', icon: '✨' },
  snow: { label: '雪花', icon: '❄️' },
}

/** 上游的基础粒子数。 */
export const PARTICLE_BASE_COUNTS: Record<ParticleType, number> = {
  sakura: 30,
  rain: 100,
  firefly: 25,
  snow: 60,
}