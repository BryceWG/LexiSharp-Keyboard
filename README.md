<div align="center">

<img src="images/icon_new.svg" width="128" height="128" alt="Logo">

# 「说点啥」(BiBi Keyboard)

**基于 AI 的智能语音输入法 | 让语音输入更自然、更高效**

### 🌐 [官方网站](https://bibi.brycewg.com) • 📖 [使用文档](https://bibidocs.brycewg.com)

简体中文 | [English](README_EN.md)

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://www.android.com/)
[![Language](https://img.shields.io/badge/Language-Kotlin-blue.svg)](https://kotlinlang.org/)
[![Telegram](https://img.shields.io/badge/Telegram-Join%20Chat-blue?logo=telegram)](https://t.me/+UGFobXqi2bYzMDFl)
[![zread](https://img.shields.io/badge/Ask_Zread-_.svg?style=flat&color=00b0aa&labelColor=000000&logo=data%3Aimage%2Fsvg%2Bxml%3Bbase64%2CPHN2ZyB3aWR0aD0iMTYiIGhlaWdodD0iMTYiIHZpZXdCb3g9IjAgMCAxNiAxNiIgZmlsbD0ibm9uZSIgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAvc3ZnIj4KPHBhdGggZD0iTTQuOTYxNTYgMS42MDAxSDIuMjQxNTZDMS44ODgxIDEuNjAwMSAxLjYwMTU2IDEuODg2NjQgMS42MDE1NiAyLjI0MDFWNC45NjAxQzEuNjAxNTYgNS4zMTM1NiAxLjg4ODEgNS42MDAxIDIuMjQxNTYgNS42MDAxSDQuOTYxNTZDNS4zMTUwMiA1LjYwMDEgNS42MDE1NiA1LjMxMzU2IDUuNjAxNTYgNC45NjAxVjIuMjQwMUM1LjYwMTU2IDEuODg2NjQgNS4zMTUwMiAxLjYwMDEgNC45NjE1NiAxLjYwMDFaIiBmaWxsPSIjZmZmIi8%2BCjxwYXRoIGQ9Ik00Ljk2MTU2IDEwLjM5OTlIMi4yNDE1NkMxLjg4ODEgMTAuMzk5OSAxLjYwMTU2IDEwLjY4NjQgMS42MDE1NiAxMS4wMzk5VjEzLjc1OTlDMS42MDE1NiAxNC4xMTM0IDEuODg4MSAxNC4zOTk5IDIuMjQxNTYgMTQuMzk5OUg0Ljk2MTU2QzUuMzE1MDIgMTQuMzk5OSA1LjYwMTU2IDE0LjExMzQgNS42MDE1NiAxMy43NTk5VjExLjAzOTlDNS42MDE1NiAxMC42ODY0IDUuMzE1MDIgMTAuMzk5OSA0Ljk2MTU2IDEwLjM5OTlaIiBmaWxsPSIjZmZmIi8%2BCjxwYXRoIGQ9Ik0xMy43NTg0IDEuNjAwMUgxMS4wMzg0QzEwLjY4NSAxLjYwMDEgMTAuMzk4NCAxLjg4NjY0IDEwLjM5ODQgMi4yNDAxVjQuOTYwMUMxMC4zOTg0IDUuMzEzNTYgMTAuNjg1IDUuNjAwMSAxMS4wMzg0IDUuNjAwMUgxMy43NTg0QzE0LjExMTkgNS42MDAxIDE0LjM5ODQgNS4zMTM1NiAxNC4zOTg0IDQuOTYwMVYyLjI0MDFDMTQuMzk4NCAxLjg4NjY0IDE0LjExMTkgMS42MDAxIDEzLjc1ODQgMS42MDAxWiIgZmlsbD0iI2ZmZiIvPgo8cGF0aCBkPSJNNCAxMkwxMiA0TDQgMTJaIiBmaWxsPSIjZmZmIi8%2BCjxwYXRoIGQ9Ik00IDEyTDEyIDQiIHN0cm9rZT0iI2ZmZiIgc3Ryb2tlLXdpZHRoPSIxLjUiIHN0cm9rZS1saW5lY2FwPSJyb3VuZCIvPgo8L3N2Zz4K&logoColor=ffffff)](https://zread.ai/BryceWG/BiBi-Keyboard)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/BryceWG/BiBi-Keyboard)
![GitHub all releases](https://img.shields.io/github/downloads/BryceWG/BiBi-Keyboard/total)

[功能特性](#-功能特性) • [快速开始](#-快速开始)

</div>

## 🌟 Pro 版已上架

> 💎 **「说点啥」Pro 版**现已正式上架 Play 商店，买断价仅 5.49$！

Pro 版提供更多高级功能和更优质的使用体验。
欢迎在「说点啥」3.9.0 版本后的关于-了解 Pro ，或者[Pro 功能](https://bibidocs.brycewg.com/pro/features.html)文档中了解更多内容。我们非常欢迎你的体验反馈，帮助我们打磨出更好的产品！

如果你对「说点啥」感兴趣，也加入我们的 [Telegram 群组](https://t.me/+UGFobXqi2bYzMDFl)了解更多信息

## ✨ 功能特性

<table>
<tr>
<td width="50%">

### 🎤 语音识别

- **长按录音** - 简单直观的录音操作
- **智能判停** - 静音自动停止录音,无需手动操作
- **极速识别** - 松开即上传，快速返回结果
- **多引擎支持** - 11 个主流 ASR 服务（7 个云端 + 4 个本地）
- **本地 ASR 模型** - 支持离线语音识别，无需网络，保护隐私
- **AI 文本后处理** - LLM 后处理修正识别结果

</td>
<td width="50%">

### 🟣 悬浮球输入 ⭐

- **跨输入法使用** - 任何输入法都能语音输入
- **无缝集成** - 保持原有输入习惯
- **自动插入** - 识别结果自动填入
- **兼容性模式** - 支持 Telegram、抖音等特殊应用
- **视觉反馈** - 录音/处理状态一目了然

</td>
</tr>
<tr>
<td width="50%">

### 📝 智能输入

- **AI 编辑面板** - 专用编辑界面，语音指令编辑文本
- **丰富的编辑工具** - 光标移动、选择、复制粘贴等完整编辑功能
- **智能目标选择** - 自动识别编辑目标（选中文本/上次识别/全文）
- **自定义按键** - 个性化标点符号
- **小企鹅/同文输入法联动** - 支持通过修改版小企鹅/同文输入法直接调用「说点啥」的语音识别能力
- **外部语音输入接口** - 支持第三方应用通过 SpeechRecognizer 接口调用「说点啥」进行语音输入 [开源接口测试工具](https://github.com/BryceWG/SpeechRecognizer-Tester)

</td>
<td width="50%">

### 🎨 用户体验

- **Material3 设计** - 现代化界面风格，Monet 色彩适配
- **多语言支持** - 支持简体中文、繁体中文、英文、日语
- **键盘高度调节** - 三档高度自由选择
- **测试输入** - 设置页内直接测试输入法
- **统计功能** - 识别字数统计
- **振动反馈** - 按下麦克风时振动反馈
- **自动更新检查** - 每日打开软件自动检查新版本

</td>
</tr>
</table>

## 📱 UI 展示

<table>
<tr>
<td width="50%" align="center">
<img src="images/keyboard_view.png" alt="键盘视图" style="max-height: 200px; width: auto;"/>
<img src="images/edit_keyboard_view.png" alt="编辑键盘视图" style="max-height: 200px; width: auto;"/>
<img src="images/numpad_keyboard_view.jpg" alt="小键盘视图" style="max-height: 200px; width: auto;"/>
<br/>
<b>🎹 键盘视图</b>
<br/>
<sub>简洁的键盘界面，支持语音输入、键盘切换和自定义按键</sub>
</td>
<td width="50%" align="center">
<img src="images/settings.jpg" alt="设置界面" style="max-height: 500px; width: auto;"/>
<br/>
<b>⚙️ 设置界面</b>
<br/>
<sub>丰富的配置选项，支持多种 ASR 引擎和 LLM 后处理</sub>
</td>
</tr>
<tr>
<td width="50%" align="center">
<img src="images/floating_ball.jpg" alt="悬浮球功能" style="max-height: 500px; width: auto;"/>
<br/>
<b>🟣 悬浮球功能</b>
<br/>
<sub>跨输入法语音识别，随时随地语音输入</sub>
</td>
<td width="50%" align="center">
<img src="images/post_processing.jpg" alt="AI 后处理" style="max-height: 500px; width: auto;"/>
<br/>
<b>🤖 AI 后处理</b>
<br/>
<sub>LLM 智能优化识别结果，提升文本质量</sub>
</td>
</tr>
</table>

## 🚀 快速开始

[供应商配置文档](https://brycewg.notion.site/bibi-keyboard-providers-guide)

### 📋 系统要求

- Android 8.0 (API 26) 或更高版本
- 麦克风权限（语音识别）
- 悬浮窗权限（可选，用于悬浮球功能）
- 无障碍权限（可选，用于自动插入文本）

### 📥 安装步骤

1. **下载安装**
   - 从 [Releases](../../releases) 页面下载最新版本 APK
   - 安装到 Android 设备

2. **启用输入法**

   ```
   设置 → 系统 → 语言和输入法 → 虚拟键盘 → 管理键盘 → 启用"「说点啥」"
   ```

3. **配置 ASR 服务**
   - 打开说点啥设置
   - 选择 ASR 供应商（推荐：火山引擎）
   - 填入 API 密钥

4. **开始使用**
   - 在任意输入框切换到说点啥
   - 长按麦克风按钮开始语音输入

> 💡 **提示**: 首次使用建议先配置火山引擎，可获得 20 小时免费额度！

### 🎨 技术栈

```
Kotlin 2.2.20
Android SDK 36 (Compile SDK 36, Target SDK 35, Min SDK 29)
Material Design 3
Coroutines (异步处理)
OkHttp 5.2.1 (网络请求)
SharedPreferences (数据存储)
sherpa-onnx (本地 ASR 模型)
```

## 📄 许可证

本项目采用 **Apache 2.0 许可证**，详见 [LICENSE](LICENSE) 文件。

```
Apache 2.0 License - 自由使用、修改、分发，需保留版权声明
```

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=BryceWG/BiBi-Keyboard&type=date&legend=top-left)](https://www.star-history.com/#BryceWG/BiBi-Keyboard&type=date&legend=top-left)

## 👥 贡献者

感谢所有为本项目做出贡献的开发者！

<table>
<tr>
<td align="center">
<a href="https://github.com/BryceWG">
<img src="https://github.com/BryceWG.png" width="60px;" alt="BryceWG"/>
<br />
<sub><b>BryceWG</b></sub>
</a>
<br />
<sub>项目创建者</sub>
</td>
<td align="center">
  <a href="https://github.com/flyhunterl">
    <img src="https://github.com/flyhunterl.png" width="60px;" alt="flyhunterl" />
    <br />
    <sub><b>flyhunterl</b></sub>
  </a>
  <br />
  <sub>功能建议/实现</sub>
</td>
<td align="center">
  <a href="https://github.com/kc0ed">
    <img src="https://github.com/kc0ed.png" width="60px;" alt="kc0ed" />
    <br />
    <sub><b>kc0ed</b></sub>
  </a>
  <br />
  <sub>功能建议/实现</sub>
</td>
</tr>
</table>

## ☕ 赞赏支持

如果这个项目对你有帮助，请给个 Star ⭐️ 也欢迎请我喝杯咖啡或者购买 Pro 版 ☕️

<div align="center">
<img src="images/wechat.jpg" alt="微信赞赏码" width="300"/>
<br/>
<sub>微信扫码赞赏</sub>
</div>

## 🙏 致谢

感谢以下开源项目为本项目提供的技术支持：

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) - 提供了本地 ASR 模型的技术方案支持，使离线语音识别成为可能
- [TEN-VAD](https://github.com/TEN-framework/ten-vad) - 提供了现有的 VAD 模型支持
- [SyncClipboard](https://github.com/Jeric-X/SyncClipboard) - 提供了剪贴板同步的后端服务(非软件本地运行,需要服务器)
- [Phosphor](https://github.com/phosphor-icons/homepage) - 提供了软件内几乎所有 Icons
- [miuix](https://github.com/compose-miuix-ui/miuix) - 提供了 Miuix 风格的 Compose UI 组件、图标与偏好设置组件
- [WaveLineView](https://github.com/Jay-Goo/WaveLineView) - 提供了录音波形动画的实现方案，使音频可视化效果更加流畅美观
- 感谢《补全计划》图标包作者南㲺为本项目设计了全新的应用图标

<div align="center">

**Made with ❤️ by BryceWG**

</div>
