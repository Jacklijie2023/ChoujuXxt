# 丑橘学习助手 (ChoujuXxt)

<p align="center">
  <img src="cover.jpg" width="200" alt="丑橘学习助手封面"/>
</p>

**丑橘学习助手** 是一款 Android 悬浮窗 AI 客户端，专注于学习场景下的智能辅助。支持悬浮球常驻桌面，一键截图识题、AI 解答、自动填写，让学习更高效。

> 本项目基于 [HanakoAI](https://github.com/zyf2007/HanakoAI) 二次开发，遵循 [AGPL-3.0](LICENSE) 协议。

---

## ✨ 主要功能

- **悬浮球常驻桌面** — 随时发起识题，无需切换应用
- **普通模式** — 框选题目区域，查看解题思路与答案，一键复制
- **自动模式** — 整屏发送 AI，选择题答案直接显示在悬浮球，填空题自动写入剪贴板
- **多图模式** — 长按悬浮球进入多图模式，批量截图后一起发送 AI
- **多种 AI 模型支持** — OpenAI Compatible / OpenAI Responses / Anthropic / Google Gemini
- **灵活处理链路** — `OCR_THEN_LLM`（先 OCR 再文本模型）/ `MULTIMODAL_DIRECT`（多模态直接理解）
- **自定义助手提示词** — 内置多种预设，也可自由配置
- **历史记录** — 查看所有处理过的题目与结果
- **无障碍服务** — 支持从屏幕 UI 节点直接提取文本

## 📱 下载

[前往 Releases 下载最新版本](../../releases)

## 🔧 模式说明

### 普通模式

1. 启动悬浮球
2. 点击悬浮球
3. 框选题目区域
4. 查看解题思路、答案，以及一键复制片段

适合需要看解题过程、核对 OCR 结果的场景。

### 自动模式

1. 在主页长按"启动"进入自动模式
2. 点击悬浮球
3. AI 自动判断题型：
   - **选择题** → 答案字母显示到悬浮球
   - **填空题/文本题** → 最终答案写入剪贴板

每题只需一步操作，追求极致效率。

## ⚙️ 配置说明

应用内可配置：

- 模型提供方 / API Base URL / API Key
- OCR 模型 / 文本模型 / 多模态模型
- 助手提示词
- 截屏方式（系统屏幕录制 / Shizuku + adb）

## 🛠️ 本地构建

**环境要求：**

- Android Studio 最新稳定版
- Android SDK 36
- JDK 11+
- Android 12+ 设备或模拟器（`minSdk = 26`）

**构建调试包：**

```bash
./gradlew assembleFullDebug
```

**构建签名发布包：**

在项目根目录提供 `keystore.properties`：

```properties
storeFile=release.keystore
storePassword=your_password
keyAlias=your_alias
keyPassword=your_password
```

然后执行：

```bash
./gradlew assembleFullRelease
```

## 📁 项目结构

```
app/src/main/java/fun/kirari/hanako/
├── accessibility/    # 无障碍服务
├── automation/       # 自动化动作
├── capture/          # 屏幕截取
├── data/             # 数据模型与存储
├── network/          # AI 模型 API 通信
├── overlay/          # 悬浮窗核心逻辑
├── ui/               # 界面 (Jetpack Compose)
└── util/             # 工具类
```

## 🙏 鸣谢

- **[HanakoAI](https://github.com/zyf2007/HanakoAI)** — 本项目的核心架构与功能来源于此，由 [@zyf2007](https://github.com/zyf2007) 开发，采用 [AGPL-3.0](https://github.com/zyf2007/HanakoAI/blob/main/LICENSE) 协议授权。感谢原作者的优秀工作！
- **[FloatStudyAssistant](https://github.com/qwe5283/FloatStudyAssistant)** — 悬浮窗搜题方案的灵感来源

## 📄 许可证

本项目基于 [HanakoAI](https://github.com/zyf2007/HanakoAI) 二次开发，遵循 [GNU Affero General Public License v3.0](LICENSE) 协议。

根据 AGPL-3.0 要求：
- 你可以自由使用、修改和分发本软件
- 修改后的版本必须同样以 AGPL-3.0 协议发布
- 如果通过网络提供服务，必须提供源代码访问
