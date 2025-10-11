# 言犀键盘 (LexiSharp Keyboard)

一个基于 Kotlin 的 Android 平台的语音输入法键盘应用，专注于提供高质量的语音转文字输入体验。

## 功能特性

- 🎤 **长按录音**: 通过长按麦克风按钮开始语音识别
- ⚡ **文件极速识别**: 松开麦克风后整体上传音频并一次性返回结果
- 🧠 **AI 文本优化**: 集成 LLM 后处理，智能修正识别结果
- 🔧 **多引擎支持**: 支持火山引擎、OpenAI、SiliconFlow、ElevenLabs、阿里云百炼、Google Gemini 等多种 ASR 服务
- 📱 **简洁界面**: Material3 设计风格，最小化界面干扰
- 🟣 **悬浮球切换**: 支持悬浮球快速切回 言犀键盘
- 📝 **拼音输入**: 支持全拼和小鹤双拼输入模式，利用 LLM 对拼音进行转换
- 🎛️ **自定义按键**: 支持自定义标点符号按钮
- 🔤 **多语言切换**: 中英文模式快速切换
- 📊 **统计功能**: 显示历史语音识别总字数

## 技术架构

### 核心组件

- **输入法服务** (`AsrKeyboardService.kt`): 继承自 InputMethodService，管理键盘交互和文本输入
- **ASR 引擎接口** (`AsrEngine.kt`): 定义统一的 ASR 引擎接口
- **ASR 供应商管理** (`AsrVendor.kt`): 管理多种 ASR 服务供应商
- **文件式 ASR 引擎**:
  - `VolcFileAsrEngine.kt`: 火山引擎文件识别实现
  - `SiliconFlowFileAsrEngine.kt`: SiliconFlow 文件识别实现
  - `OpenAiFileAsrEngine.kt`: OpenAI Whisper 兼容实现
  - `ElevenLabsFileAsrEngine.kt`: ElevenLabs 语音识别实现
  - `DashscopeFileAsrEngine.kt`: 阿里云百炼语音识别实现
  - `GeminiFileAsrEngine.kt`: Google Gemini 语音理解（通过提示词转写）
- **LLM 后处理器** (`LlmPostProcessor.kt`): 基于大语言模型的文本修正
- **设置界面** (`SettingsActivity.kt`): 配置 ASR 服务和 LLM 参数
- **权限管理** (`PermissionActivity.kt`): 处理麦克风权限请求
- **悬浮球服务** (`FloatingImeSwitcherService.kt`): 实现悬浮球切换输入法功能
- **输入法选择器** (`ImePickerActivity.kt`): 快速切换输入法的界面
- **数据存储** (`Prefs.kt`): 运行时配置管理
- **提示词预设** (`PromptPreset.kt`): 管理多种 AI 后处理提示词

### 技术栈

- **开发语言**: Kotlin 2.2.20
- **最低 SDK**: API 31 (Android 11)
- **目标 SDK**: API 34 (Android 14)
- **编译 SDK**: API 36
- **网络通信**: OkHttp3 (HTTP)
- **UI 框架**: Material3 + ViewBinding
- **并发处理**: Kotlin Coroutines
- **版本号**: 2.1.5 (versionCode 23)

### 使用指南

#### 语音输入功能

1. **基本操作**：

   - 长按键盘中央的麦克风按钮开始录音
   - 松开按钮后，音频会自动上传到所选的 ASR 服务进行识别
   - 识别结果会自动插入到当前输入框

2. **AI 编辑功能**：
   - 点击键盘上的编辑按钮（AI 图标）
   - 语音输入编辑指令（如"删除最后一个词"、"将'你好'改为'您好'"等）
   - 说完指令后再按一次编辑按钮，AI 会根据指令修改上次识别的文本或选中内容

#### LLM 拼音输入功能

1. **拼音输入模式**：

   - 在键盘上正常输入拼音（支持全拼和小鹤双拼）
   - 输入完成后，系统会自动调用 LLM 将拼音转换为对应的汉字
   - 可在设置中调整自动 LLM 转换的时间间隔（默认为 0 表示手动触发）

2. **拼音设置**：
   - 支持全拼输入模式：如输入 "nihao"，LLM 转换为 "你好"
   - 支持小鹤双拼输入模式
   - 可在设置中选择默认的 26 键语言模式（中文或英文）

#### 键盘按钮功能

1. **主要按钮布局**：

   - **中央麦克风按钮**：长按进行语音识别
   - **后处理切换按钮**：开启/关闭 AI 后处理功能
   - **提示词选择按钮**：切换不同的 AI 后处理提示词预设
   - **收起键盘按钮**：隐藏键盘界面
   - **退格按钮**：删除字符，支持上滑/左滑删除所有内容，下滑撤销后处理结果
   - **设置按钮**：进入应用设置界面
   - **切换输入法按钮**：快速切换到其他输入法
   - **回车按钮**：换行或提交
   - **空格按钮**: 长按直接输入语音，从空格快速上滑进入语音输入键盘

2. **自定义按键**：

   - 键盘底部有 5 个可自定义的标点符号按钮
   - 可在设置中自定义每个按钮显示的字符或标点
   - 支持添加常用符号如逗号、句号、问号等

3. **模式切换按钮**：
   - **字母/数字切换**：在字母键盘和数字符号键盘之间切换
   - **大小写切换**：切换英文大小写输入
   - **中英文切换**：快速切换中文和英文输入模式

#### 悬浮球功能

小技巧：在设置中开启"启用悬浮球快速切换输入法"，并授予悬浮窗权限；当当前输入法不是本应用时，悬浮球会显示，点击即可唤起系统输入法选择器快速切换回言犀键盘。

## 配置说明

### ASR 供应商选择与配置

设置页支持按供应商切换配置，所选供应商仅显示对应参数：

- 供应商：`火山引擎Volcano Engine`、`硅基流动SiliconFlow`、`OpenAI`、`ElevenLabs`、`阿里云百炼DashScope`、`Google Gemini`

#### 火山引擎 ASR 配置（推荐）

当前版本仅使用非流式（文件）识别极速版，配置更简化，速度更快：

- **X-Api-App-Key**: 应用密钥，即应用 ID（必填）
- **X-Api-Access-Key**: 访问密钥，即 Access Token（必填）

申请方式：火山引擎(豆包语音)[https://console.volcengine.com/speech/app?opt=create]里面创建应用获得 20 小时免费额度。

#### SiliconFlow ASR 配置

- **API Key（Bearer）**: SiliconFlow 控制台生成的密钥（必填）
- **Model Name**: 如 `FunAudioLLM/SenseVoiceSmall`（默认值）
- 端点固定为：`https://api.siliconflow.cn/v1/audio/transcriptions`

#### OpenAI ASR 配置

- **API Key**: OpenAI API 密钥（以 `sk-` 开头，必填）
- **Endpoint**: 完整 API 地址，如 `https://api.openai.com/v1/audio/transcriptions`
- **Model**: 模型名称，支持 `gpt-4o-mini-transcribe`、`gpt-4o-transcribe` 或 `whisper-1`

注意：OpenAI 接口单次上传上限为 25MB，长段语音建议分次识别。

#### ElevenLabs ASR 配置

- **API Key**: ElevenLabs 控制台生成的 API 密钥（必填）
- **Model ID**: 语音识别模型 ID（默认值）
- 端点固定为：`https://api.elevenlabs.io/v1/speech-to-text`

#### 阿里云百炼（DashScope）ASR 配置

- **API Key**: 阿里云百炼控制台生成的 API Key（必填）
- **Model**: 模型名称，目前仅支持 `qwen3-asr-flash`（默认值）

特殊说明：DashScope 采用临时上传 + 生成接口模式，需要通过 OSS 中转音频文件，延迟可能稍高。

### LLM 后处理配置

可配置大语言模型对识别结果进行智能修正：

- **API 密钥**: LLM 服务的 API 密钥
- **服务端点**: LLM API 地址
- **模型名称**: 使用的 LLM 模型
- **温度参数**: 控制生成文本的随机性 (0-2.0)
- **提示词预设**: 支持多种预设提示词，可自定义添加
- **自动后处理**: 可设置自动后处理开关

### 其他功能配置

- **拼音输入**: 支持全拼和小鹤双拼输入模式
- **自动转换**: 设置拼音自动转换为汉字的时间间隔
- **自定义按键**: 可配置 5 个自定义标点符号按键
- **悬浮球**: 可调节悬浮球透明度，快速切换输入法
- **振动反馈**: 可分别设置麦克风和键盘按键的振动反馈
- **语言设置**: 支持跟随系统、简体中文、英文三种语言模式

## 识别方式

- 非流式：本地录音为 PCM 16kHz/16-bit/mono，结束后封装为 WAV 一次性通过 HTTP 上传至各 ASR 服务的接口并返回结果。
- AI 编辑：支持通过语音指令编辑上次识别的文本或选中内容

## 项目结构

```
app/
├── src/
│   ├── main/
│   │   ├── java/com/brycewg/asrkb/
│   │   │   ├── asr/                    # ASR 引擎实现和接口
│   │   │   │   ├── AsrEngine.kt        # ASR 引擎基础接口
│   │   │   │   ├── AsrVendor.kt        # ASR 供应商枚举
│   │   │   │   ├── VolcFileAsrEngine.kt     # 火山引擎文件 ASR 实现
│   │   │   │   ├── DashscopeFileAsrEngine.kt # 阿里云通义千问 ASR 实现
│   │   │   │   ├── ElevenLabsFileAsrEngine.kt # ElevenLabs ASR 实现
│   │   │   │   ├── GeminiFileAsrEngine.kt     # Google Gemini ASR 实现
│   │   │   │   ├── OpenAiFileAsrEngine.kt     # OpenAI Whisper ASR 实现
│   │   │   │   ├── SiliconFlowFileAsrEngine.kt # SiliconFlow ASR 实现
│   │   │   │   └── LlmPostProcessor.kt         # LLM 后处理器
│   │   │   ├── ime/                    # 输入法服务
│   │   │   │   └── AsrKeyboardService.kt       # 主键盘服务 (InputMethodService)
│   │   │   ├── ui/                     # 用户界面组件
│   │   │   │   ├── SettingsActivity.kt        # 设置界面
│   │   │   │   ├── PermissionActivity.kt      # 权限请求界面
│   │   │   │   ├── ImePickerActivity.kt       # 输入法选择器
│   │   │   │   └── FloatingImeSwitcherService.kt # 悬浮输入法切换服务
│   │   │   ├── store/                  # 数据存储和配置
│   │   │   │   ├── Prefs.kt            # 运行时配置管理
│   │   │   │   └── PromptPreset.kt     # 提示词预设
│   │   │   └── App.kt                  # 应用程序入口
│   │   ├── res/                        # 资源文件
│   │   │   ├── layout/                 # 布局文件
│   │   │   │   ├── activity_settings.xml    # 设置界面布局
│   │   │   │   ├── keyboard_view.xml         # 键盘视图布局
│   │   │   │   └── keyboard_qwerty_view.xml  # QWERTY 键盘布局
│   │   │   ├── values/                 # 默认资源值
│   │   │   │   ├── strings.xml             # 字符串资源
│   │   │   │   ├── colors.xml              # 颜色资源
│   │   │   │   ├── themes.xml              # 主题资源
│   │   │   │   └── dimens.xml              # 尺寸资源
│   │   │   ├── values-*/               # 国际化资源
│   │   │   │   ├── values-zh-rCN/          # 中文简体资源
│   │   │   │   └── values-en/              # 英文资源
│   │   │   ├── drawable/               # 图标和背景资源
│   │   │   ├── drawable-nodpi/         # 无密度图标资源
│   │   │   ├── color/                  # 颜色状态列表资源
│   │   │   ├── animator/               # 动画资源
│   │   │   ├── xml/                    # XML 配置文件
│   │   │   └── mipmap-anydpi/          # 应用图标资源
│   │   └── AndroidManifest.xml         # 应用清单文件
├── build.gradle.kts                    # 应用级构建配置
└── build.gradle.kts                    # 项目级构建配置
```

## 许可证

本项目采用 MIT 许可证，详见 LICENSE 文件。

## 贡献指南

欢迎提交 Issue 和 Pull Request 来改进项目。在提交代码前，请确保：

1. 代码通过所有测试
2. 遵循项目代码规范
3. 添加必要的注释和文档
4. 更新相关的 README 文档
