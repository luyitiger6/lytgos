# Design — 官网首版

## 一句话

`site/` 是 VitePress 静态站点。结构与文案基线取自同作者的 `oryxos-main/website`，首版只做品牌替换，不重做信息架构。

## 为什么照搬

| 维度 | 说明 |
|---|---|
| 信息架构同构 | 两个项目回答同一组问题：是什么 / 为什么 / 怎么跑 / 到哪了 |
| 视觉已定型 | 橙黑 token 在 `custom.css` 里已成套（278 行），重画无差异化收益 |
| 成本 | 照搬一天可上线；重画至少一周，且首版用户看不出差别 |

后续若要重画，只改 `site/.vitepress/theme/components/Home.vue` 单文件，`config.mts` 与文档页不受影响。

## 目录结构

```
site/
├── .vitepress/
│   ├── config.mts              base / locales / 导航 / 侧边栏 / sitemap
│   └── theme/
│       ├── index.ts            继承默认主题 + 注册 <Home />
│       ├── custom.css          橙黑 token 与导航 / 侧边栏 / 页脚覆盖
│       └── components/
│           ├── Home.vue        首页全部区块（1149 行）
│           └── Layout.vue      包一层默认布局
├── docs/                       英文 19 篇
├── zh/docs/                    中文 19 篇（与英文一一镜像）
├── public/
│   ├── logo.svg · favicon.svg  橙黑 wordmark
│   ├── CNAME                   lytgos.tigerustmq.com
│   └── images/                 24 个架构与流程图
├── index.md                    layout: home + <Home />
└── zh/index.md                 同上
```

## 语言分区

| 路径 | 语言 | 首页 | 文档 |
|---|---|---|---|
| `/` | English | `index.md` | `docs/**` |
| `/zh/` | 中文 | `zh/index.md` | `zh/docs/**` |

沿用上游站点的分区方式（英文在根），不改成 `/en/`。理由：所有站内链接、侧边栏、`transformHead` 的 canonical 计算都以根为英文基准，改分区等于全量重写链接。

## 品牌替换

| 原值 | 新值 |
|---|---|
| `OryxOS` | `lytgos` |
| `/oryxos/` | `/lytgos/` |
| `github.com/oryx-labs/oryxos` | `github.com/luyitiger6/lytgos` |
| `oryx-labs.github.io/oryxos` | `lytgos.tigerustmq.com` |
| `OryxTool` | `LytgosTool` |
| API key 前缀 `oryx_` | `lytgos_` |

`logo.svg` 与 `favicon.svg` 是重画的，不是替换字符串：上游是「oryx 双角」造型，lytgos 用 L 形折线 + 橙色方块。

## 主题

```css
:root {
  --vp-c-bg: #000000;
  --vp-c-bg-soft: #111111;
  --vp-c-bg-mute: #1a1a1a;
  --vp-c-text-1: #f5f5f5;
  --vp-c-text-2: #a3a3a3;
  --vp-c-border: #222222;
  --vp-c-brand-1: #c2550a;
  --vp-c-brand-2: #ea6a00;
  --vp-c-brand-3: #f97316;
}
```

`appearance: 'force-light'` 是刻意的：站点黑底全部来自 `custom.css`，不用 VitePress 暗色模式的默认变量，避免两套黑互相覆盖。`.VPNavBarAppearance` 与 `.VPSwitchAppearance` 已 `display: none`。

`custom.css` 末尾 `display: none` 掉 `.VPHero` 与 `.VPFooter`——首页与页脚完全由 `Home.vue` 提供。

## base 与域名

```ts
const base = process.env.VITEPRESS_BASE ?? '/lytgos/'
const siteUrl = 'https://lytgos.tigerustmq.com/'
```

| 场景 | base | 访问地址 |
|---|---|---|
| 本地 / 项目页 | `/lytgos/` | `localhost:5173/lytgos/`、`luyitiger6.github.io/lytgos/` |
| 自定义域名 | `/` | `lytgos.tigerustmq.com/` |

CI 里设 `VITEPRESS_BASE=/`，代码不改。

## 部署

`.github/workflows/deploy-site.yml`：push 到 `main` 且改动 `site/**` 时触发。

```
pnpm install --frozen-lockfile
pnpm build（VITEPRESS_BASE=/）
upload-pages-artifact → deploy-pages
```

`pnpm-lock.yaml` 必须入库，`--frozen-lockfile` 强依赖它。

## 不做什么

- 不改上游 19×2 篇文档的事实内容，只换品牌词
- 不引入评论、统计、CDN 等第三方依赖
- 不改 `docs/` 与 `zh/docs/` 的文件名与路由路径
