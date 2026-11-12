## Why

仓库目前只有 README，没有对外门户。README 适合已经决定要读的人，回答不了「这东西值不值得信」这个问题：招聘方、潜在使用者、未来的贡献者需要一个页面，一次看完产品定位、能力边界、架构取舍与当前进度，并能一键跳到源码。

两者承担不同角色，不能互相替代：

- **README** 是仓库内入口，面向已经打开仓库的人
- **官网** 是搜索引擎与社交媒体入口，面向还不知道 lytgos 是什么的人

内容必须一致，但表达密度不同——官网要能在三十秒内讲清楚「这是什么、为什么需要它、现在做到哪」。

## What Changes

- 新增 `site/` 目录：VitePress 静态站点，构建产物为纯静态文件
- 支持中英双语，`/` 为英文、`/zh/` 为中文（沿用上游站点的语言分区），导航栏提供切换入口
- 视觉方向：纯黑背景（导航与页脚同色，不留白条）+ 橙色主色，首页为自定义 `Home.vue`，不复用 VitePress 默认 Home 模板
- 结构与文案基线取自 `oryxos-main/website`（同一作者的另一项目），只做品牌替换（`OryxOS`→`lytgos`、仓库→`luyitiger6/lytgos`、域名→`lytgos.tigerustmq.com`），不重写信息架构
- 部署：GitHub Pages，仓库 `luyitiger6/lytgos`，`base: '/lytgos/'`，自定义域名 `lytgos.tigerustmq.com`
- 同步约定：README、官网首页、官网文档页三处内容任一变更时，必须在同一次提交内同步另外两处，不允许只改一处留下漂移

## Impact

新增：

- `site/` 完整 VitePress 工程：`package.json`、`.vitepress/config.mts`、`.vitepress/theme/`（`custom.css` + `components/Home.vue`）、`index.md`、`zh/index.md`、`docs/**` 与 `zh/docs/**`（各 19 篇）
- `site/public/`：`logo.svg`、`favicon.svg`、`CNAME`、`images/`（架构图与流程图）
- `.github/workflows/deploy-site.yml`：推送到 `main` 时自动构建并发布到 Pages

修改：

- `AGENTS.md` §7 目录地图补 `site/`
- `README.md` 增加官网入口

不影响：任何 Java 模块、`harness/` 闸门判定逻辑、依赖方向。

## Decisions

### 为什么用 VitePress，不用原方案的纯静态 HTML

原 proposal 写的是「零构建的纯静态 HTML + CSS」。改为 VitePress 的理由：

| 维度 | 纯静态 HTML | VitePress |
|---|---|---|
| 双语 | 手写两套 HTML 并行维护 | 内置 `locales`，目录镜像即可 |
| 文档正文 | 每个 md 手写成 HTML | markdown 直接渲染，自带侧边栏与本地搜索 |
| 内容更新 | 改一处要同步两个文件 | 只改 markdown |
| 构建依赖 | 无 | 需要 Node，构建产物仍是纯静态 |

官网除首页还有文档部分，纯静态 HTML 在这块维护成本过高。VitePress 的产物仍是纯静态，部署方式与原来完全相同。

代价是引入 `node_modules` 与构建步骤，因此 `site/` 不参与 Maven 构建，`harness/` 也不检查它。

### base 与自定义域名

`base: '/lytgos/'` 适配 GitHub Pages 项目页路径（`luyitiger6.github.io/lytgos/`）。绑定自定义域名 `lytgos.tigerustmq.com` 后资源路径会变成 `https://lytgos.tigerustmq.com/lytgos/...`，与根路径不匹配。

处理方式：`base` 从环境变量读取，默认 `/lytgos/`；CI 里绑定域名后设 `VITEPRESS_BASE=/` 即可，不改代码。

### 首页为什么是照搬而非重画

上游 `oryxos-main/website` 与 lytgos 是同一作者、同一套信息架构的项目：同样是「受控执行的 Agent 运行时」，同样需要回答「是什么 / 为什么 / 怎么跑 / 当前到哪」。

首版直接移植其 `Home.vue`（1149 行，七个区块）+ `custom.css`（278 行橙黑变量），只做三件替换：品牌名、仓库地址、域名。理由是把设计迭代留给真正有差异化的那一轮，而不是在「先能上线」的阶段重做一遍布局。

后续若要重画，改 `site/.vitepress/theme/components/Home.vue` 单文件即可，不影响 `config.mts` 与文档页。

## Open Questions

无。视觉方向、语言策略、域名、部署路径均已确定。
