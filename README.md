# 《IoT 平台设计》

副标题：从设备接入、数据语义到平台运行与演进

作者：Chanlk  
联系方式：[chanlk9527@gmail.com](mailto:chanlk9527@gmail.com)

这是一本关于 IoT 平台设计的系列书稿。当前第一章“IoT 世界地图”已完成，后续内容将围绕“星云 IoT 平台”逐步展开。

> 如何把不同厂商的设备、应用和用户组织成可持续演进的 IoT 平台？

## 书稿入口

- [全书引言](manuscript/00-introduction.md)
- [全书目录](SUMMARY.md)
- [书籍定位与第一章设计](BOOK-DESIGN.md)
- [第一部分：IoT 世界地图与平台基础](manuscript/part-01-iot-platform/_index.md)
- [第 1 章：IoT 世界地图](manuscript/part-01-iot-platform/01-iot-world.md)
- [作者人格与行文声音](VOICE.md)
- [写作约定](WRITING.md)
- [全书图示与表格规范](ILLUSTRATIONS.md)

## 目录结构

`SUMMARY.md` 是阅读顺序的唯一来源，`WRITING.md` 约束写作方式；`manuscript/` 保存正文。

正文按 IoT 平台的主题和章节组织。页面标题与 `SUMMARY.md` 决定阅读顺序，后续主题将在第一部分下逐步加入。

## 在线阅读网站

网站使用 VitePress，部署到 Vercel。安装 Node.js 24 后，在项目根目录执行 `npm ci` 和 `npm run docs:dev` 即可本地阅读；`npm run docs:build` 生成正式网站。

章节与分部导航来自 `SUMMARY.md`，正文继续维护在 `manuscript/`。第一章引言与八节正文均已完成，中文搜索、深浅色主题与图示放大等站点能力仍可直接使用。

第一部分现位于 `manuscript/part-01-iot-platform/`，第一章正文位于 `01-iot-world.md`。第一章按八个主题拆页，均已加入目录，以“从 IoT 世界地图走向平台：共同问题与基本概念”收尾。

完整步骤见 [在线阅读与 Vercel 部署](DEPLOYMENT.md)。

## 当前章节安排

现行设计从第一部分“IoT 世界地图与平台基础”开始。第一章负责建立 IoT 的概念、角色、协议、产品形态和平台边界，后续章节再进入星云平台的具体设计。

分册暂不落定，设计说明记录当前定位与章节安排。第一章引言、1.1—1.8 正文已全部完成；从下一章开始，转向平台内部的对象、关系与基础能力设计。
