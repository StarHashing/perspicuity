<script setup lang="ts">
import { APP_INFO } from '../../../lib/appInfo'
import { useI18n } from '../../../lib/i18n'
// 直接用应用图标本身（桌面/启动器上那个图标），而不是另画的轮廓标记。
// 这样「关于」页展示的就是用户装到手机上看到的那个图标。
import appIcon from '../../../assets/app-icon.png'

const { t } = useI18n()
</script>

<template>
  <section class="about-page" :aria-label="t('settings.about')" data-testid="settings-about-page">
    <div class="about-hero" data-testid="settings-about-app">
      <img class="about-mark" :src="appIcon" alt="" />
      <p class="about-name">{{ APP_INFO.name }}</p>
    </div>

    <p class="about-blurb">{{ t('about.basedOn') }}</p>

    <div class="about-list">
      <div class="about-row" data-testid="settings-about-version">
        <span class="about-row-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" focusable="false">
            <path d="M4 12h6" />
            <path d="M14 12h6" />
            <circle cx="12" cy="12" r="2.8" />
          </svg>
        </span>
        <span class="about-row-main">
          <span class="about-row-label">{{ t('about.version') }}</span>
          <span class="about-row-value">{{ APP_INFO.version }}</span>
        </span>
      </div>

      <a
        class="about-row is-link"
        :href="APP_INFO.repositoryUrl"
        target="_blank"
        rel="noreferrer"
        data-testid="settings-about-github"
      >
        <span class="about-row-icon" aria-hidden="true">
          <!-- Octicons mark-github (MIT); GitHub permits the mark as a
               link to a GitHub repository. Filled glyph, so it opts out
               of the shared stroke styling below. -->
          <svg viewBox="0 0 16 16" focusable="false" class="about-github-mark">
            <path
              d="M8 0c4.42 0 8 3.58 8 8a8.013 8.013 0 0 1-5.45 7.59c-.4.08-.55-.17-.55-.38 0-.27.01-1.13.01-2.2 0-.75-.25-1.23-.54-1.48 1.78-.2 3.65-.88 3.65-3.95 0-.88-.31-1.59-.82-2.15.08-.2.36-1.02-.08-2.12 0 0-.67-.22-2.2.82-.64-.18-1.32-.27-2-.27-.68 0-1.36.09-2 .27-1.53-1.03-2.2-.82-2.2-.82-.44 1.1-.16 1.92-.08 2.12-.51.56-.82 1.28-.82 2.15 0 3.06 1.86 3.75 3.64 3.95-.23.2-.44.55-.51 1.07-.46.21-1.61.55-2.33-.66-.15-.24-.6-.83-1.23-.82-.67.01-.27.38.01.53.34.19.73.9.82 1.13.16.45.68 1.31 2.69.94 0 .67.01 1.3.01 1.49 0 .21-.15.45-.55.38A7.995 7.995 0 0 1 0 8c0-4.42 3.58-8 8-8Z"
            />
          </svg>
        </span>
        <span class="about-row-main">
          <span class="about-row-label">{{ t('about.github') }}</span>
        </span>
        <span class="about-row-trailing" aria-hidden="true">
          <svg viewBox="0 0 24 24" focusable="false">
            <path d="M15 3h6v6" />
            <path d="M10 14L21 3" />
            <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6" />
          </svg>
        </span>
      </a>
    </div>
  </section>
</template>

<style scoped>
.about-page {
  display: grid;
  gap: 0;
}

.about-hero {
  display: grid;
  justify-items: center;
  gap: 14px;
  padding: 52px 20px 32px;
  background: var(--app-bg);
  color: var(--text-muted);
}

/* 真·应用图标：直接展示桌面上的那个图标本身，不染色、不套方底。 */
.about-mark {
  display: block;
  width: auto;
  height: 108px;
  max-width: 40vw;
  object-fit: contain;
}

.about-name {
  margin: 0;
  color: var(--text);
  font-size: 18px;
  line-height: 1.3;
  font-weight: 700;
  letter-spacing: 0;
}

/* 开源说明：写明本项目基于哪个开源项目二次开发。 */
.about-blurb {
  margin: 0;
  padding: 0 24px 28px;
  border-bottom: var(--hairline) solid var(--separator);
  background: var(--app-bg);
  color: var(--text-muted);
  font-size: 13px;
  line-height: 1.6;
  text-align: center;
  text-wrap: pretty;
}

.about-list {
  border-bottom: var(--hairline) solid var(--separator);
  background: var(--surface);
}

.about-row {
  position: relative;
  display: grid;
  grid-template-columns: 68px minmax(0, 1fr) auto;
  align-items: center;
  gap: 0;
  width: 100%;
  min-height: 72px;
  padding: 10px 20px 10px 0;
  border: 0;
  background: transparent;
  color: var(--text);
  font: inherit;
  text-align: left;
  text-decoration: none;
  transition: background-color var(--dur-standard) var(--ease-out);
}

.about-row + .about-row::before {
  content: '';
  position: absolute;
  top: 0;
  right: 0;
  left: 68px;
  border-top: var(--hairline) solid var(--separator);
  pointer-events: none;
}

.about-row.is-link {
  cursor: pointer;
}

.about-row.is-link:active {
  background: var(--press);
  transition-duration: 0ms;
}

.about-row.is-link:focus-visible {
  outline: 2px solid var(--focus-ring);
  outline-offset: -2px;
}

.about-row-icon,
.about-row-trailing {
  display: grid;
  place-items: center;
  color: var(--text-muted);
}

.about-row-icon svg {
  width: 26px;
  height: 26px;
  fill: none;
  stroke: currentColor;
  stroke-width: 2.2;
  stroke-linecap: round;
  stroke-linejoin: round;
}

/* The GitHub mark is a filled glyph; solid shapes carry more visual
   weight than 2.2 strokes, so it sits slightly smaller. */
.about-row-icon svg.about-github-mark {
  width: 24px;
  height: 24px;
  fill: currentColor;
  stroke: none;
}

.about-row-trailing svg {
  width: 24px;
  height: 24px;
  fill: none;
  stroke: currentColor;
  stroke-width: 2.1;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.about-row-main {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.about-row-label {
  min-width: 0;
  color: var(--text);
  font-size: 16px;
  line-height: 1.25;
  font-weight: 600;
  letter-spacing: -0.008em;
  overflow-wrap: anywhere;
}

.about-row-value {
  min-width: 0;
  color: var(--text-muted);
  font-size: 14px;
  line-height: 1.3;
  font-weight: 400;
  letter-spacing: 0;
  overflow-wrap: anywhere;
}
</style>
