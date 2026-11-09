# 书籍工作区

此目录只包含书籍与阅读网站，平台实现位于 `../practice/`，默认不读取。

- 按任务定位：`SUMMARY.md` 是阅读顺序来源，`manuscript/` 是正文，`public/` 是站点资源，`.vitepress/` 是网站配置与主题。
- 修改正文前按需读取 `WRITING.md`、`VOICE.md`、`TERMINOLOGY.md`；涉及图示再读 `ILLUSTRATIONS.md`，涉及定位再读 `BOOK-DESIGN.md`。
- `FIVE-CHAPTER-REVIEW.md` 是审稿记录，`temp/` 是历史草稿；仅在相关任务中读取。
- 在此目录运行 `npm run docs:dev` 或 `npm run docs:build`。部署入口为仓库根目录 `vercel.json`。
- 不要为了写书扫描整个平台；确需核对实现时只读取具体模块。
- 遵守根目录 AGENTS.md 的公司透明加密与标准读取约束。
