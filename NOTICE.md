# NOTICE — 来源与版权声明

**Perspicuity（澄怀）** 是一款基于以下开源项目二次开发的移动端 Markdown 编辑器。

本项目为 **hard fork（硬分叉）**：在上游项目基础上改名、更换包名、独立演进，
不向上游提交 PR，与上游项目无隶属关系。

---

## 上游项目

### 1. MarkText for Android

- 项目：`Renakoni/marktext-android`
- 许可证：**MIT License**
- 版权：**Copyright (c) 2026 Renakoni**
- 用途：本项目的外壳架构、Android 原生文档 I/O（SAF）、
  设置/主题系统、移动端工具栏等均源自此项目。

### 2. Muya

- 项目：`marktext/muya`（通过 `third_party/muya` 以 `@muyajs/core` 引入）
- 许可证：**MIT License**
- 版权：**Copyright (c) 2017-present Luo Ran and MarkText Contributors**
- 用途：本项目 Markdown 编辑内核（块模型、状态机、OT 引擎）。

### 3. icu4j-charset-detector

- 项目：`third_party/icu4j-charset-detector`
- 许可证：**MIT License**
- 用途：文件编码检测。

### 4. ushio-md

- 项目：`jiuxina/ushio-md`
- 许可证：**MIT License**
- 用途：本项目**粒子背景效果**（`src/components/ParticleBackground.vue`、
  `src/features/settings/particleSettings.ts`、`src/components/particleTypes.ts`）
  移植自该项目的 `lib/widgets/particle_effect_widget.dart` 与
  `lib/widgets/global_particle_overlay.dart`，包含四种效果（樱花 / 雨 / 萤火虫 / 雪花）
  的绘制方式、物理参数、取值范围与管理方式。
  实现语言由 Flutter/Dart 改写为 TypeScript + Canvas 2D，参数语义保持一致。

---

## 本项目

- 名称：Perspicuity / 澄怀
- 包名：`com.starhashing.perspicuity`
- 作者：StarHashing（哈希散落星光）
- 许可证：MIT License

## MIT 义务履行说明

MIT 许可证允许使用、复制、修改、合并、发布、分发、再许可及销售，
**唯一义务是在软件的所有副本或实质性部分中保留上述版权声明和许可声明**。
本 NOTICE 文件及各子项目目录下的 LICENSE 文件共同履行该义务。
