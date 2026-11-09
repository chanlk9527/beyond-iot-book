# Beyond-IoT

本仓库包含两个独立工作区：

| 目录 | 内容 | 入口 |
| --- | --- | --- |
| `book/` | 《IoT 平台设计》书稿、写作规范和 VitePress 阅读网站 | [书籍说明](book/README.md) |
| `practice/` | 星云 IoT 平台后端、管理前端、基础设施和开发文档 | [平台说明](practice/README.md) |

## 日常开发

开发平台时，建议直接将 `practice/` 作为编辑器或 AI 工具的工作目录；写书时打开 `book/`。两边的依赖、构建和文档分别维护。根目录的 `AGENTS.md` 只负责范围选择与通用文件读取约束，各目录内另有简短工作指引。

- 平台启动：见 [practice/STARTUP.md](practice/STARTUP.md)，现有启动脚本和路径保持可用。
- 书籍预览：进入 `book/`，执行 `npm ci`、`npm run docs:dev`。
- 书籍构建：进入 `book/`，执行 `npm run docs:build`。
- Vercel：Root Directory 保持仓库根目录，根目录 `vercel.json` 将安装和构建指向 `book/`，详见 [部署说明](book/DEPLOYMENT.md)。

书稿正文、术语、审稿记录与草稿均归 `book/`；平台设计、实现计划、接口文档均归 `practice/docs/`。`.github/` 保存仓库级 CI 配置。
