# Ydrop

Ydrop 是一个以 Android 为主的 AI 闪念采集与整理工具，支持语音、文字、截图和链接输入，并通过 relay、豆包语音转写和 NAS 同步把碎片信息沉淀为可执行记录。

## 当前已具备的能力

- Jetpack Compose 单 Activity Android 应用
- 基于 Room 的本地记录 inbox
- 支持快速文字记录与保存/同步
- 支持前台语音录音并生成语音 note
- 内置 WebDAV 设置、测试连接和自动同步开关
- 内置 relay 中转服务设置，用于生成公网临时音频链接
- 内置豆包 / Volcengine 语音转写设置
- Markdown 导出格式，方便 AI 后处理
- WebDAV 实时同步到 NAS
- 结构化 note 模型，包含类型、重要程度、颜色、同步状态、relay 信息和转写状态

## 当前应用形态

- `快速记录` 首页用于文字和语音输入
- 独立设置页用于编辑 WebDAV / relay / 豆包配置
- `最近记录` 列表展示本地 inbox 历史
- 彩色 note 卡片展示类型 / 重要程度 / 同步状态
- 语音 note 卡片展示 relay 与转写状态

## 仍在推进中的部分

- relay -> 豆包 -> transcript -> NAS 的长期稳定性验证
- 更完整的后台 worker / 重试 / 离线补偿
- 更安全的凭据存储
- 转写成功后的 relay 临时文件自动清理

## 建议下一步

1. 继续验证完整的 relay -> 豆包 -> transcript -> NAS 闭环稳定性。
2. 把敏感配置迁移到加密存储或 Android Keystore。
3. 继续扩展 WorkManager，把转写失败重试也做稳。
4. 继续完善 overlay / quick tile / widget 等快速入口。
5. 增加转写成功后的 relay 临时文件自动清理。
