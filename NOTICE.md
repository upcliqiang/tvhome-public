# 来源与第三方组件

本仓库不是从零写的，它是在别人的开源作品上改出来的。这里把所有"别人的东西"列清楚。

## 上游项目

| 项目 | 许可 | 用到了什么 |
|---|---|---|
| [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv) | MIT | **本仓库的主体来源**。`launcher/` 的工程结构、桌面框架、磁贴网格、设置页骨架、无障碍守护等都来自这里 |
| [pengchujin/aerial-tv](https://github.com/pengchujin/aerial-tv) | MIT | 仅作**实现参考**：Home 键守护、设置页的交互方式。没有复制其代码（上游是 Kotlin，本项目是 Java） |

上游的 `LICENSE` 原文保留在 `LICENSE`（Copyright (c) 2026 Gathin），本仓库的修改以同样的 MIT 条款发布。

## 内置的第三方资源

| 资源 | 许可 | 位置 |
|---|---|---|
| Outfit 字体 | SIL Open Font License 1.1 | `launcher/FONT-LICENSE-OFL.txt` |
| Microsoft Fluent Emoji 3D（万圣节南瓜/蝙蝠/幽灵） | MIT | `launcher/ICONS-LICENSE-FLUENT-EMOJI.txt` |
| ZXing core 3.5.3（生成上传页二维码） | Apache-2.0 | Gradle 依赖 `com.google.zxing:core` |

## 关于版本分叉

本仓库的起点是上游 2026 年 11 月初的快照（应用内 `versionName` 3.3）。之后上游继续演进（例如把屏保场景重构为 `ClockScene`、补了大量单元测试），本仓库**没有跟进**这些重构，所以两边已经明显分叉：

- 本仓库有、上游没有：`ScreensaverScene`、`ScreensaverStyle`、`Weather`、`TileArtCache`、`WallpaperCache`、`EdgeScrollView`、`MemInfo`、`UploadServer` 等
- 上游有、本仓库没有：`ClockScene`、`ClockRefresh`、`app/src/test/` 下的测试、`addons/`、`docs/`、`tools/` 等目录

如果你要的是"最新上游 + 我的修复"，更好的做法是把本仓库 `UploadServer.java`、`MainActivity.warmFocusLayer()` 这两处改动挑到上游最新代码上，而不是直接合并整个仓库。
