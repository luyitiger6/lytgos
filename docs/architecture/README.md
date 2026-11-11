# 架构图

本目录存放架构与依赖关系的可视化。数据源是 `.dot` 文件，产物是 `.svg`，两者都入库。

**改图先改 `.dot`，再重新生成 `.svg`，不要手改 SVG。**

## 已有图

| 文件 | 回答什么问题 |
|---|---|
| `module-dependencies.dot` / `.svg` | 14 个模块的依赖方向：谁依赖谁，`lytgos-core` 有没有出边 |

## 重新生成

```bash
dot -Tsvg docs/architecture/module-dependencies.dot -o docs/architecture/module-dependencies.svg
```

依赖 Graphviz（`dot -V` 能正常输出即可）。改动 `.dot` 后必须重新生成 `.svg` 并一并提交，否则图和源码会脱节。

## 何时必须更新

- 新增、删除或重命名 `lytgos-*` 模块
- 调整模块间的依赖方向或分层归属
- 改变 `lytgos-boot` 的聚合范围

以上任一发生，`.dot` 与 `.svg` 必须同一次提交，并在对应 change 的 proposal 中说明图的变更。
