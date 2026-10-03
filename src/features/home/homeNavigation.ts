import type { I18nKey } from '../../lib/i18n'

export const HOME_TABS = Object.freeze({
  HOME: 'home',
  DOCUMENTS: 'documents',
  SETTINGS: 'settings',
} as const)
export type HomeTab = (typeof HOME_TABS)[keyof typeof HOME_TABS]
export type HomeTabIcon = 'home' | 'document' | 'settings'
export interface HomeTabItem {
  id: HomeTab
  labelKey: I18nKey
  icon: HomeTabIcon
}
export const HOME_TAB_ITEMS = [
  {
    id: HOME_TABS.HOME,
    labelKey: 'nav.home',
    icon: 'home',
  },
  {
    id: HOME_TABS.DOCUMENTS,
    labelKey: 'nav.documents',
    icon: 'document',
  },
  {
    id: HOME_TABS.SETTINGS,
    labelKey: 'nav.settings',
    icon: 'settings',
  },
] as const satisfies readonly HomeTabItem[]
export const DEFAULT_HOME_TAB: HomeTab = HOME_TABS.HOME