# 在线阅读与 Vercel 部署

本站使用 VitePress 1.6.4。正文继续保存在 `manuscript/`，全书阅读顺序与侧边栏来自 `SUMMARY.md`。网站首页在 `index.md`，主题在 `.vitepress/theme/`。

## 本地阅读

使用 Node.js 24（与 Vercel 保持一致），在项目根目录运行：

```sh
npm ci
npm run docs:dev
```

打开终端显示的本地网址。修改 Markdown 后，开发服务器会更新页面；修改 `SUMMARY.md` 后重新启动开发服务器以刷新导航。

正式构建与预览：

```sh
npm run docs:build
npm run docs:preview
```

构建产物在 `.vitepress/dist/`，不提交到 Git。

## 部署到 Vercel

1. 将书稿、网站配置和 `package-lock.json` 一起提交并推送到 GitHub 仓库。
2. 在 Vercel 选择 **Add New → Project**，导入这个仓库。
3. Root Directory 保持仓库根目录；Node.js Version 选择 **24.x**。
4. 仓库中的 `vercel.json` 已设置：Framework Preset 为 **VitePress**，Install Command 为 `npm ci`，Build Command 为 `npm run docs:build`，Output Directory 为 `.vitepress/dist`。
5. 点击 **Deploy**。部署完成后使用 Vercel 提供的网址访问；需要自定义域名时，在项目 Settings → Domains 中添加。

之后推送到 Vercel 设置的生产分支会更新正式网站；其他分支或 Pull Request 可生成预览部署。正式发布前检查首页、中文搜索、第一章图示和章节直达链接，并在目标读者的网络中确认访问效果。

不需要环境变量、数据库或后端服务。首页明确说明这是持续写作的书稿；含有 `## 本章任务` 的章节会自动显示“正在写作”，成稿移除该占位标题后提示也会消失。

## 发布内容范围

公开页面包括首页、`SUMMARY.md`，以及 `manuscript/` 下的正文与分部导读。作者约定、本文件和设计文档不会生成网站页面。Markdown 中引用的图示作为静态资源发布。

正文网址省略 `manuscript/` 前缀，例如第一章为 `/part-01-iot-platform/01-iot-world`。分部导读使用 `/part-01-iot-platform/`，目录使用 `/contents`。已有 Markdown 相对链接由主题配置中的构建规则转换。示例源码由 `prepare.mjs` 在开发或构建前复制到公共资源目录，无须维护第二份副本。

多页章节保留章首页网址，主题页面位于同名目录，例如 `/part-01-iot-platform/01-iot-world/01-boundaries`。`SUMMARY.md` 中章链接下缩进两格的页面会进入章节侧栏和连续阅读顺序。新增或调整锚点时应同步维护对应的映射。

保留 VitePress 稳定版本，并将其 Vite 依赖固定到 6.4.3，以修复旧版开发服务器的已知安全问题；升级时同时验证构建、预览与中文搜索。

## 公司透明加密文件

本电脑上同一文件可能因进程不同而呈现明文或密文。若构建提示不能读取 UTF-8，先确认 Node.js 和 Git 能读取安全工具解密后的文件视图，不能仅修改代码页或把文件判断为损坏。提交后应检查远端仓库的书稿显示为可读 Markdown；Vercel 需要收到可读的源文件。

官方参考：[VitePress 部署指南](https://vuejs.github.io/vitepress/v1/guide/deploy)、[Vercel 项目配置](https://vercel.com/docs/project-configuration)。
