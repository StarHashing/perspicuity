import { createApp } from 'vue'
import './style.css'
import App from './App.vue'
import { appLogger, installGlobalLogging } from './lib/logger'

/**
 * 构建标记：每次出包时手动更新为对应的版本号/日期。
 * 作用：用户在「设置 → 导出日志」导出的 ZIP 里，web/debug-logs.jsonl 的
 * 第一条（或前几条）日志会带上这个标记，开发者据此一眼确认「用户手机上跑的
 * 到底是哪一个构建」，彻底排除「装了旧包」这类假象。
 */
const BUILD_STAMP = 'v0.1.0-open-source-polish-2026-10-03';

installGlobalLogging()
appLogger.info('app build marker', { buildStamp: BUILD_STAMP })
appLogger.info('vue mount start')
createApp(App).mount('#app')
appLogger.info('vue mount complete')
