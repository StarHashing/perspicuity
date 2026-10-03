<h1 align="center">Perspicuity · 澄怀</h1>

<p align="center">
  <em>澄怀观道，Markdown 自成一境。</em>
</p>

<p align="center">
  <sub>A lean, open-source Markdown editor for Android.<br>一款为 Android 打造的轻量开源 Markdown 编辑器。</sub>
</p>

<p align="center">
  <sub>by <a href="https://github.com/StarHashing/perspicuity">StarHashing</a></sub>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/License-MIT-4c566a?style=flat-square" alt="License: MIT">
  &nbsp;
  <img src="https://img.shields.io/badge/Android-7.0%2B-4c8492?style=flat-square&logo=android&logoColor=white" alt="Android 7.0+">
</p>

<p align="center">
  <a href="#简介">简介</a> ·
  <a href="#功能一览">功能一览</a> ·
  <a href="#从源码构建">从源码构建</a> ·
  <a href="#许可与致谢">许可与致谢</a>
  &nbsp;|&nbsp;
  <a href="#overview">Overview</a> ·
  <a href="#features-at-a-glance">Features</a> ·
  <a href="#build-from-source">Build from source</a> ·
  <a href="#license--attribution">License</a>
</p>

> [!NOTE]
> **Perspicuity（澄怀）是一个独立维护的开源衍生项目**，基于开源项目
> [MarkText for Android](https://github.com/Renakoni/marktext-android)（MIT 许可）
> 构建，重做了界面，并加入若干自研功能（全局粒子背景、悬浮便签窗等）。
> 本项目与上游项目无从属关系，也未获其背书；完整的来源与版权声明见
> [许可与致谢](#许可与致谢) 与 [NOTICE.md](NOTICE.md)。
>
> **Perspicuity（澄怀） is an independently maintained, open-source derivative
> project.** It is built on the open-source project
> [MarkText for Android](https://github.com/Renakoni/marktext-android) (MIT
> license), with a redesigned interface and additional self-built features.
> It is not affiliated with, nor endorsed by, the upstream project; see
> [License & attribution](#license--attribution) and [NOTICE.md](NOTICE.md)
> for the full provenance and copyright notices.

---

## 简介

Perspicuity（澄怀）把 MarkText 的实时预览 Markdown 编辑体验带到了手机上。
编辑器内核是 Muya —— MarkText 的开源引擎 —— 并针对移动端做了适配：大文档下更快、
按手机宽度重排、支持触摸选区和格式工具栏。你写下什么，就以与桌面编辑器同等还原度
渲染出来，整套界面为单手持机而设计。在此之上，本项目重做了界面，并加入了全局粒子
背景、悬浮便签窗等自研功能。

## Overview

Perspicuity（澄怀） brings MarkText's live-preview Markdown editing to the phone.
The editor core is Muya — MarkText's open-source engine — adapted for mobile:
faster on large documents, reflowed to phone width, with touch selection and a
format toolbar. What you write renders at the same fidelity as the desktop
editor, inside an interface designed for one-handed use. On top of that, this
project redesigns the interface and adds self-built features such as a global
particle background and a floating sticky-note window.

---

## 亮点

### 一款从不丢失一个字的轻量 Markdown 编辑器

- 真正的实时预览（所见即所得）编辑。
- 完整的 CommonMark 与 GitHub Flavored Markdown：数学公式（KaTeX）、表格、
  脚注、front matter、图表，以及语法高亮代码。
- 文档大纲与编辑器内搜索，长文档依然流畅。
- 导出 **PDF**，数学公式、代码高亮、字体内嵌一应俱全。
- **绝不丢失你的工作。** 自动保存、恢复草稿、原子写入，每一次改动都留得住。
- **默认隐私优先。** 无需账号、不联网、无遥测；一切数据都留在设备上。
- **轻量。** Vue + Capacitor 外壳让应用体积很小 —— 安装约 8 MB —— 却功能齐全。

### 让它成为你的

- **自建工具栏。** 从命令池里组合底部快捷栏，拖动即可排序；连选区工具栏也能放入
  你自己的命令。
- **主题与外观。** 浅色、深色，以及 30+ 款自定义主题；字号与布局可调。
- **Markdown 按你的口味。** 从列表标记到 front matter，细致调整 Markdown 的
  书写与渲染方式。
- **文件级控制。** 可对每个文档单独设置编码、换行符与末尾换行处理。

### 为手机而生，人人可用

- **你的文件原地不动。** 通过系统文件选择器直接编辑任意存储服务里的 `.md`，
  并借助分享面板与其他应用互传文档。
- **相对路径图片。** 文档里用 `![](./img/a.png)`、`![](docs/a.png)` 这类相对路径
  引用的图片，会按文档所在目录正确解析并显示。
- **拇指友好。** 舒适的单手可达范围，以及克制、以编辑为先的布局。
- **无障碍且克制。** 安静的石墨灰设计，满足 WCAG 2.2 AA，焦点顺序清晰、
  动效柔和精简。
- **十种语言**，随系统自动选择：英语、德语、西班牙语、法语、日语、韩语、
  葡萄牙语、土耳其语，以及简体与繁体中文。

## Highlights

### A lightweight Markdown editor that never loses a word

- True live-preview (WYSIWYG) editing.
- Full CommonMark and GitHub Flavored Markdown: math (KaTeX), tables, footnotes,
  front matter, diagrams, and syntax-highlighted code.
- A document outline and in-editor search that stay smooth even in long files.
- Export to **PDF** with math, code highlighting, and fonts all baked in.
- **Never loses your work.** Autosave, recovery drafts, and atomic writes keep
  every change.
- **Private by default.** No account, no cloud, no telemetry; everything stays on
  the device.
- **Lightweight.** A Vue + Capacitor shell keeps the app small — around 8 MB
  installed — yet fully featured.

### Make it yours

- **Build your own toolbars.** Compose the bottom quick bar from a pool of commands
  and drag to reorder it. Even the selection toolbar can hold your own commands.
- **Themes and appearance.** Light, dark, and 30+ custom themes; adjustable
  type and layout.
- **Markdown to your taste.** Fine-tune how your Markdown is written and rendered,
  from list markers to front matter.
- **File-level control.** Per-document encoding, line endings, and trailing
  newline handling.

### Built for the phone, polished for everyone

- **Your files stay put.** Edit `.md` straight from any storage provider through
  the system picker, and pass documents to and from other apps with the share
  sheet.
- **Relative-path images.** Images referenced with relative paths such as
  `![](./img/a.png)` or `![](docs/a.png)` resolve and render against the folder
  the document lives in.
- **Made for the thumb.** Comfortable one-handed reach and a calm, editor-first
  layout.
- **Accessible and restrained.** A quiet graphite design that meets WCAG 2.2 AA,
  with a clear focus order and calm, minimal motion.
- **Ten languages,** chosen automatically from your system: English, German,
  Spanish, French, Japanese, Korean, Portuguese, Turkish, and Simplified and
  Traditional Chinese.

---

## 功能一览

| 分类 | 能力 |
| --- | --- |
| 编辑 | 实时预览（所见即所得）、源码/预览一体、触摸选区、格式工具栏 |
| 语法 | CommonMark、GitHub Flavored Markdown、KaTeX 数学公式、表格、脚注、front matter、Mermaid/流程图、语法高亮代码块 |
| 图片 | 本地图片导入、相对路径图片（按文档所在目录解析）、系统存储里的图片 |
| 文件 | 系统文件选择器直接编辑任意存储提供方的 `.md`、分享面板收发文档、按 URI 读取 |
| 导出 | PDF（内嵌数学、代码高亮与字体） |
| 导航 | 文档大纲、编辑器内搜索 |
| 数据安全 | 自动保存、恢复草稿、原子写入 |
| 外观 | 浅色 / 深色 / 30+ 自定义主题、可调字号与布局、全局粒子背景、悬浮便签窗 |
| 语言 | 英语、德语、西班牙语、法语、日语、韩语、葡萄牙语、土耳其语、简体中文、繁体中文 |
| 隐私 | 无需账号、不联网、无遥测，数据全部留在设备上 |

## Features at a glance

| Area | Capability |
| --- | --- |
| Editing | Live-preview (WYSIWYG), source/preview in one, touch selection, format toolbar |
| Syntax | CommonMark, GitHub Flavored Markdown, KaTeX math, tables, footnotes, front matter, Mermaid/flowcharts, syntax-highlighted code |
| Images | Local image import, relative-path images (resolved against the document's folder), images from device storage |
| Files | Edit `.md` from any storage provider via the system picker, share documents in and out, read by URI |
| Export | PDF with embedded math, code highlighting, and fonts |
| Navigation | Document outline, in-editor search |
| Data safety | Autosave, recovery drafts, atomic writes |
| Appearance | Light / dark / 30+ custom themes, adjustable type and layout, global particle background, floating sticky-note window |
| Languages | English, German, Spanish, French, Japanese, Korean, Portuguese, Turkish, Simplified and Traditional Chinese |
| Privacy | No account, no network, no telemetry; everything stays on the device |

---

## 项目状态

> [!NOTE]
> Perspicuity 是一个**独立、自行维护的 fork**，主要用于个人使用，而非在
> Google Play 上分发。`main` 分支是唯一的事实来源；构建均在本地由它产出。

没有保证的发布节奏；已发布的 APK 是面向作者自己设备的 debug/自签名构建 ——
想要最干净的结果，请自行 clone 并构建（见下文）。如果发现 bug 或想要某功能，
非常欢迎提 issue 和 PR。

## Project status

> [!NOTE]
> Perspicuity is an **independent, self-maintained fork**, kept for personal use
> rather than distribution on Google Play. The `main` branch is the source of
> truth; builds are produced locally from it.

There is no guaranteed release cadence, and published APKs are debug/self-signed
builds meant for the author's own devices — clone and build it yourself for the
cleanest result (see below). If you find a bug or want a feature, issues and PRs
are genuinely welcome.

---

## 从源码构建

你需要 [Node.js](https://nodejs.org/)（配合 [pnpm](https://pnpm.io/)）以及
[Android Studio](https://developer.android.com/studio)（Android SDK 和一个 JDK；
应用支持 API 24 及以上，并针对 API 36 构建）。

```sh
pnpm install          # 安装依赖
pnpm dev              # 在浏览器里预览 Web 外壳
pnpm android:sync     # 构建 Web 应用并同步进 Android 工程
pnpm android:open     # 在 Android Studio 中打开，然后运行到设备或模拟器
```

其他脚本（`test`、`test:e2e`、`lint`、`typecheck`、`build`）见 `package.json`。
要从零做一次 Android 构建，还需要 JDK 和 Android SDK，然后：

```sh
pnpm android:sync                                   # build web app + cap sync android
cd android && ./gradlew assembleDebug               # → app/build/outputs/apk/debug/
```

> [!TIP]
> Markdown 编辑器内核是 `third_party/muya` 下一份**被修改过的** `@muyajs/core`
> （Muya）内置副本。如果你改动了它，构建前请把改动同步进
> `node_modules/@muyajs/core/src/**` —— 有一项契约测试会检测漂移。

## Build from source

You'll need [Node.js](https://nodejs.org/) with [pnpm](https://pnpm.io/) and
[Android Studio](https://developer.android.com/studio) (the Android SDK and a
JDK; the app runs on API 24 and newer and is built against API 36).

```sh
pnpm install          # install dependencies
pnpm dev              # preview the web shell in a browser
pnpm android:sync     # build the web app and sync it into the Android project
pnpm android:open     # open it in Android Studio, then run on a device or emulator
```

Other scripts (`test`, `test:e2e`, `lint`, `typecheck`, `build`) are in
`package.json`. For a from-scratch Android build you'll also need a JDK and the
Android SDK, then:

```sh
pnpm android:sync                                   # build web app + cap sync android
cd android && ./gradlew assembleDebug               # → app/build/outputs/apk/debug/
```

> [!TIP]
> The Markdown editor core is a vendored, **modified** copy of `@muyajs/core` (Muya)
> under `third_party/muya`. If you change it, sync your edits into
> `node_modules/@muyajs/core/src/**` before building — a contract test catches drift.

---

## 参与贡献

欢迎提 issue 与 pull request。每个改动尽量聚焦，并在合适的地方补充测试。

## Contributing

Issues and pull requests are welcome. Keep each change focused, and add tests
where they make sense.

---

## 许可与致谢

**Perspicuity（澄怀）** 以 [MIT 许可证](LICENSE) 发布。

它是一项独立的衍生作品，基于开源项目
[MarkText for Android](https://github.com/Renakoni/marktext-android)（MIT）
构建，并通过它站在 MarkText 的开源编辑器内核之上。本项目与 MarkText 项目
无从属关系，也未获其背书。完整的致谢名单见 [NOTICE.md](NOTICE.md)。

- **MarkText for Android** —— 本项目 fork 并参考的上游 Android 外壳/架构
  （Android 文档读写、设置/主题系统、移动端工具栏等）。Copyright © Renakoni，
  MIT 许可。
- **MarkText** —— 本项目遵循的桌面编辑器与设计。Copyright © Luo Ran
  以及 MarkText 贡献者，MIT 许可。
- **Muya**（`@muyajs/core`）—— 编辑器内核，内置并修改于 `third_party/muya`，
  保留其原始 MIT 许可证（[`third_party/muya/LICENSE`](third_party/muya/LICENSE)）。
- **ushio-md** —— 粒子背景效果移植自该项目。Copyright © jiuxina，MIT 许可。

## License & attribution

**Perspicuity（澄怀）** is released under the [MIT License](LICENSE).

It is an independent derivative work, built on the open-source project
[MarkText for Android](https://github.com/Renakoni/marktext-android) (MIT)
and, through it, on MarkText's open-source editor core. It is not affiliated
with or endorsed by the MarkText project. See [NOTICE.md](NOTICE.md) for the
full attribution list.

- **MarkText for Android** — the upstream Android shell/architecture this
  project forks and builds upon (Android document I/O, settings/theme system,
  mobile toolbars, etc.). Copyright © Renakoni, MIT licensed.
- **MarkText** — the desktop editor and design this project follows. Copyright ©
  Luo Ran and the MarkText contributors, MIT licensed.
- **Muya** (`@muyajs/core`) — the editor core, vendored and modified under
  `third_party/muya` with its original MIT license kept
  ([`third_party/muya/LICENSE`](third_party/muya/LICENSE)).
- **ushio-md** — the particle background effect was ported from this project.
  Copyright © jiuxina, MIT licensed.

---

## 致谢 / Acknowledgements

Perspicuity（澄怀）站在大量开源工作之上：[MarkText for Android](https://github.com/Renakoni/marktext-android)
（它 fork 的项目）、[MarkText](https://github.com/marktext/marktext) 编辑器及其
[贡献者](https://github.com/marktext/marktext/graphs/contributors)、
[Muya](https://github.com/marktext/muya) 编辑引擎、
[ushio-md](https://github.com/jiuxina/ushio-md)（粒子效果），以及
[Vue](https://vuejs.org/)、[Vite](https://vite.dev/)、
[Capacitor](https://capacitorjs.com/)。感谢每一位构建它们的人。

还要特别感谢：

- **DeepSeek（梁文谷家吃白饭的大肥鱼）** —— 感谢 DeepSeek 大模型在开发过程中
  提供的支持。（"梁文谷"是社区对梁文峰及 DeepSeek"峰谷"计费机制的戏称。）
- **[Operit](https://github.com/AAswordman/Operit)** —— 一款 Android 上的开源
  AI Agent，本项目把它作为开发工具使用。感谢 [AAswordman](https://github.com/AAswordman)
  及 Operit 的贡献者。

Perspicuity（澄怀）stands on a lot of open-source work: the
[MarkText for Android](https://github.com/Renakoni/marktext-android) project it
forks, the [MarkText](https://github.com/marktext/marktext) editor and its
[contributors](https://github.com/marktext/marktext/graphs/contributors), the
[Muya](https://github.com/marktext/muya) editing engine,
[ushio-md](https://github.com/jiuxina/ushio-md) for the particle effect, and
[Vue](https://vuejs.org/), [Vite](https://vite.dev/), and
[Capacitor](https://capacitorjs.com/). Thank you to everyone who built them.

Special thanks as well to:

- **DeepSeek** — for the large language model support used while building this
  project.
- **[Operit](https://github.com/AAswordman/Operit)** — an open-source AI Agent
  for Android, used as a development tool for this project. Thanks to
  [AAswordman](https://github.com/AAswordman) and the Operit contributors.

---

## 联系 / Contact

- 邮箱 / Email：**starhash@163.com**
- GitHub：[StarHashing](https://github.com/StarHashing)

---

<p align="center"><sub><em>Markdown, quietly yours. · 静默属于你的 Markdown。</em></sub></p>
