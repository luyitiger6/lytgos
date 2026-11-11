# Tasks

## 1. 工程骨架

- [x] 1.1 移植 `oryxos-main/website` 全量文件到 `site/`（`.vitepress/`、`docs/`、`zh/docs/`、`public/`、`index.md`、`zh/index.md`、`package.json`）
- [x] 1.2 `site/package.json`：`name` 改 `lytgos-site`，scripts 补 `dev` / `build` / `preview`
- [x] 1.3 `site/.gitignore`：`node_modules` / `.vitepress/dist` / `.vitepress/cache`
- [x] 1.4 删除上游 `package-lock.json`，改 `pnpm install` 生成 `pnpm-lock.yaml`（15.5s，126 包）

## 2. 品牌替换

- [x] 2.1 `OryxOS` → `lytgos`、`/oryxos/` → `/lytgos/`（47 个文件）
- [x] 2.2 `github.com/oryx-labs/oryxos` → `github.com/luyitiger6/lytgos`、`oryx-labs.github.io/oryxos` → `lytgos.tigerustmq.com`
- [x] 2.3 `OryxTool` → `LytgosTool`、API key 前缀 `oryx_` → `lytgos_`（含中英两套文档）
- [x] 2.4 文件名替换：`oryxos-overview-*.svg` → `lytgos-overview-*.svg`
- [x] 2.5 `config.mts` 移除上游 Demo 链接（`demo.robustmq.com:1524`），改写 `description` 与 `keywords`

## 3. 主题与首页

- [x] 3.1 `theme/custom.css`：黑底 + 橙色 token（`--vp-c-bg: #000000`、`--vp-c-brand-3: #f97316`），导航 / 侧边栏 / 页脚全 `#000`
- [x] 3.2 `theme/index.ts`：继承默认主题、引入 `custom.css`、全局注册 `<Home />`、`Layout` 覆盖
- [x] 3.3 `components/Home.vue`（1149 行）：Hero / Why / 能力 / 架构 / 状态 / 路线图 / 文档 / 页脚
- [x] 3.4 重画 `public/logo.svg`（L 形折线 + 橙色方块，`lyt` + `gos` 双色 wordmark）
- [x] 3.5 `index.md` 与 `zh/index.md`：`layout: home` + `<Home />`
- [x] 3.6 `custom.css` 隐藏 VitePress 默认 `.VPHero` 与 `.VPFooter`，首页与页脚完全自有

## 4. 文档页

- [x] 4.1 英文 19 篇：`docs/what.md` / `why` / `architecture` / `react-loop` / `provider` / `memory` / `tool` / `api` / `cli` / `profile` / `auth` / `roadmap` / `quick-start` / `contributing` / `maintainer` / `github-workflow`
- [x] 4.2 中文 19 篇：`zh/docs/**` 与英文一一镜像
- [x] 4.3 侧边栏五组（介绍 / 快速入门 / 架构设计 / 参考 / 社区）中英各一套，路径前缀 `/docs/` 与 `/zh/docs/`

## 5. 资产

- [x] 5.1 `public/favicon.svg` 保留几何标记，不依赖字体
- [x] 5.2 `public/images/` 24 个 SVG/JPG：架构图、ReAct 循环、记忆结构、沙箱流程、调度器、Agent 生命周期、流水线、治理图
- [x] 5.3 `public/CNAME`：`lytgos.tigerustmq.com`

## 6. 配置与部署

- [x] 6.1 `config.mts`：`base = process.env.VITEPRESS_BASE ?? '/lytgos/'`，`siteUrl = 'https://lytgos.tigerustmq.com/'`，`sitemap.hostname` 指向该域名
- [x] 6.2 `appearance: 'force-light'` 保留：站点只从 `custom.css` 取黑底，避免 VitePress 暗色变量二次覆盖
- [x] 6.3 `.github/workflows/deploy-site.yml`：`pnpm install --frozen-lockfile` → `pnpm build`（`VITEPRESS_BASE=/`）→ `upload-pages-artifact` → `deploy-pages`

## 7. 收尾

- [x] 7.1 `AGENTS.md` §7 目录地图按实际结构更新（`docs/` + `zh/docs/`、`Home.vue`）
- [x] 7.2 `README.md` badges 增加官网入口
- [x] 7.3 `pnpm build` 通过：`BUILD_EXIT=0`，2.76s，35 个 HTML，无 dead link
- [x] 7.4 产物校验：`dist/index.html` 内资源路径含 `/lytgos/assets/` 前缀，`dist/images/lytgos-overview-*.svg` 已改名
- [ ] 7.5 视觉核对：本地 `pnpm dev` 打开首页确认黑底无白条、中英不乱码（待人工确认）
