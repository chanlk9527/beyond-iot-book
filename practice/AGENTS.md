# 平台工作区

此目录包含可运行的 IoT 平台；书籍位于 `../book/`，开发任务默认不读取。

- `README.md` 提供功能与文档索引，`STARTUP.md` 提供日常启动方式；按当前任务选择入口，不必全部读取。
- `backend/`：Java 后端。涉及模块边界时读 `docs/architecture.md`，构建与测试命令见 `backend/README.md`，再定位具体模块。
- `console/`：管理前端与设备联调界面，命令见 `console/README.md` 与 `console/package.json`。
- `infra/`：本地基础设施；`scripts/` 和根部启动脚本负责运行管理。
- `docs/`：平台契约与设计，按功能选择文档；`docs/superpowers/plans/` 为历史实施计划，默认不批量加载。
- 优先在受影响模块内使用 `rg` 搜索与执行对应检查。功能事实以代码、测试和当前模块文档为准。
- 遵守根目录 AGENTS.md 的公司透明加密与标准读取约束。
