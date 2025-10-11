# LexiSharp Keyboard

A Kotlin-based Android speech-to-text keyboard application focused on providing high-quality voice input experience.

## Features

- 🎤 **Press-and-Hold Recording**: Start speech recognition by long-pressing the microphone button
- ⚡ **Fast File Recognition**: Upload entire audio at once after releasing the microphone and get results immediately
- 🧠 **AI Text Enhancement**: Integrated LLM post-processing for intelligent correction of recognition results
- 🔧 **Multi-Engine Support**: Supports Volcano Engine, OpenAI, SiliconFlow, ElevenLabs, DashScope, Google Gemini and other ASR services
- 📱 **Clean Interface**: Material3 design style with minimal interface interference
- 🟣 **Floating Ball Switcher**: Supports floating ball for quick switching back to LexiSharp Keyboard
- 📝 **Pinyin Input**: Supports full pinyin and Xiaohe double pinyin input modes, using LLM for pinyin conversion
- 🎛️ **Customizable Keys**: Supports customizable punctuation buttons
- 🔤 **Multi-language Switching**: Quick switching between Chinese and English modes
- 📊 **Statistics**: Displays total character count of historical speech recognition

## Technical Architecture

### Core Components

- **Input Method Service** (`AsrKeyboardService.kt`): Inherits from InputMethodService, manages keyboard interaction and text input
- **ASR Engine Interface** (`AsrEngine.kt`): Defines unified ASR engine interface
- **ASR Vendor Management** (`AsrVendor.kt`): Manages multiple ASR service providers
- **File-based ASR Engines**:
  - `VolcFileAsrEngine.kt`: Volcano Engine file recognition implementation
  - `SiliconFlowFileAsrEngine.kt`: SiliconFlow file recognition implementation
  - `OpenAiFileAsrEngine.kt`: OpenAI Whisper compatible implementation
  - `ElevenLabsFileAsrEngine.kt`: ElevenLabs speech recognition implementation
  - `DashscopeFileAsrEngine.kt`: Alibaba Cloud DashScope speech recognition implementation
  - `GeminiFileAsrEngine.kt`: Google Gemini speech understanding (transcribed via prompts)
- **LLM Post-processor** (`LlmPostProcessor.kt`): Text correction based on large language models
- **Settings Interface** (`SettingsActivity.kt`): Configure ASR services and LLM parameters
- **Permission Management** (`PermissionActivity.kt`): Handles microphone permission requests
- **Floating Ball Service** (`FloatingImeSwitcherService.kt`): Implements floating ball input method switching
- **IME Picker** (`ImePickerActivity.kt`): Interface for quick input method switching
- **Data Storage** (`Prefs.kt`): Runtime configuration management
- **Prompt Presets** (`PromptPreset.kt`): Manages multiple AI post-processing prompts

### Tech Stack

- **Development Language**: Kotlin 2.2.20
- **Minimum SDK**: API 31 (Android 11)
- **Target SDK**: API 34 (Android 14)
- **Compile SDK**: API 36
- **Network Communication**: OkHttp3 (HTTP)
- **UI Framework**: Material3 + ViewBinding
- **Concurrency**: Kotlin Coroutines
- **Version**: 2.1.5 (versionCode 23)

## Usage Guide

### Speech Input Feature

1. **Basic Operations**:

   - Long-press the microphone button in the center of the keyboard to start recording
   - After releasing the button, audio will be automatically uploaded to the selected ASR service for recognition
   - Recognition results will be automatically inserted into the current input field

2. **AI Editing Feature**:
   - Click the edit button (AI icon) on the keyboard
   - Voice input editing commands (e.g., "delete the last word", "change 'hello' to 'Hello'", etc.)
   - After speaking the command, press the edit button again, and AI will modify the last recognized text or selected content according to the command

### LLM Pinyin Input Feature

1. **Pinyin Input Mode**:

   - Input pinyin normally on the keyboard (supports full pinyin and Xiaohe double pinyin)
   - After completion, the system will automatically call LLM to convert pinyin to corresponding Chinese characters
   - You can adjust the automatic LLM conversion time interval in settings (default is 0 for manual trigger)

2. **Pinyin Settings**:
   - Supports full pinyin input mode: e.g., input "nihao", LLM converts to "你好"
   - Supports Xiaohe double pinyin input mode
   - You can select the default 26-key language mode (Chinese or English) in settings

### Keyboard Button Functions

1. **Main Button Layout**:

   - **Center Microphone Button**: Long-press for speech recognition
   - **Post-processing Toggle Button**: Turn on/off AI post-processing feature
   - **Prompt Selection Button**: Switch between different AI post-processing prompt presets
   - **Hide Keyboard Button**: Hide keyboard interface
   - **Backspace Button**: Delete characters, supports swipe up/left to delete all content, swipe down to undo post-processing results
   - **Settings Button**: Enter application settings interface
   - **Switch IME Button**: Quick switch to other input methods
   - **Enter Button**: New line or submit
   - **Space Button**: Long-press for direct voice input, swipe up from space to enter voice input keyboard

2. **Customizable Keys**:

   - There are 5 customizable punctuation buttons at the bottom of the keyboard
   - You can customize the character or punctuation displayed on each button in settings
   - Supports adding common symbols like commas, periods, question marks, etc.

3. **Mode Switch Buttons**:
   - **Letter/Number Switch**: Switch between letter keyboard and number/symbol keyboard
   - **Case Switch**: Switch between English uppercase and lowercase input
   - **Chinese/English Switch**: Quick switch between Chinese and English input modes

### Floating Ball Feature

Pro tip: Enable "Enable floating ball for quick IME switching" in settings and grant overlay permission; when the current input method is not this app, the floating ball will be displayed. Click it to bring up the system input method selector to quickly switch back to LexiSharp Keyboard.

## Configuration Guide

### ASR Provider Selection and Configuration

The settings page supports switching configurations by provider, showing only corresponding parameters for the selected provider:

- Providers: `Volcano Engine`, `SiliconFlow`, `OpenAI`, `ElevenLabs`, `DashScope`, `Google Gemini`

#### Volcano Engine ASR Configuration (Recommended)

The current version only uses non-streaming (file) recognition express version, with simplified configuration and faster speed:

- **X-Api-App-Key**: Application key, i.e., application ID (required)
- **X-Api-Access-Key**: Access key, i.e., Access Token (required)

Application method: Create an application in Volcano Engine (Doubao Speech) [https://console.volcengine.com/speech/app?opt=create] to get 20 hours of free quota.

#### SiliconFlow ASR Configuration

- **API Key (Bearer)**: Key generated by SiliconFlow console (required)
- **Model Name**: Such as `FunAudioLLM/SenseVoiceSmall` (default value)
- Endpoint fixed at: `https://api.siliconflow.cn/v1/audio/transcriptions`

#### OpenAI ASR Configuration

- **API Key**: OpenAI API key (starts with `sk-`, required)
- **Endpoint**: Complete API address, such as `https://api.openai.com/v1/audio/transcriptions`
- **Model**: Model name, supports `gpt-4o-mini-transcribe`, `gpt-4o-transcribe` or `whisper-1`

Note: OpenAI interface has a single upload limit of 25MB. For long speech, it's recommended to recognize in segments.

#### ElevenLabs ASR Configuration

- **API Key**: API key generated by ElevenLabs console (required)
- **Model ID**: Speech recognition model ID (default value)
- Endpoint fixed at: `https://api.elevenlabs.io/v1/speech-to-text`

#### Alibaba Cloud DashScope ASR Configuration

- **API Key**: API Key generated by Alibaba Cloud DashScope console (required)
- **Model**: Model name, currently only supports `qwen3-asr-flash` (default value)

Special note: DashScope uses temporary upload + generation interface mode, needs to transfer audio files through OSS, latency may be slightly higher.

### LLM Post-processing Configuration

You can configure large language models for intelligent correction of recognition results:

- **API Key**: API key of LLM service
- **Service Endpoint**: LLM API address
- **Model Name**: LLM model used
- **Temperature Parameter**: Control randomness of generated text (0-2.0)
- **Prompt Presets**: Supports multiple preset prompts, can add custom ones
- **Auto Post-processing**: Can set automatic post-processing switch

### Other Feature Configuration

- **Pinyin Input**: Supports full pinyin and Xiaohe double pinyin input modes
- **Auto Conversion**: Set time interval for automatic conversion of pinyin to Chinese characters
- **Customizable Keys**: Can configure 5 customizable punctuation keys
- **Floating Ball**: Can adjust floating ball transparency for quick IME switching
- **Vibration Feedback**: Can set vibration feedback for microphone and keyboard keys separately
- **Language Settings**: Supports three language modes: follow system, Simplified Chinese, English

## Recognition Method

- Non-streaming: Local recording as PCM 16kHz/16-bit/mono, after completion packaged as WAV and uploaded once via HTTP to each ASR service interface and returns results.
- AI Editing: Supports editing last recognized text or selected content through voice commands

## Project Structure

```
app/
├── src/
│   ├── main/
│   │   ├── java/com/brycewg/asrkb/
│   │   │   ├── asr/                    # ASR engine implementations and interfaces
│   │   │   │   ├── AsrEngine.kt        # ASR engine base interface
│   │   │   │   ├── AsrVendor.kt        # ASR vendor enumeration
│   │   │   │   ├── VolcFileAsrEngine.kt     # Volcano Engine file ASR implementation
│   │   │   │   ├── DashscopeFileAsrEngine.kt # Alibaba Cloud Qwen ASR implementation
│   │   │   │   ├── ElevenLabsFileAsrEngine.kt # ElevenLabs ASR implementation
│   │   │   │   ├── GeminiFileAsrEngine.kt     # Google Gemini ASR implementation
│   │   │   │   ├── OpenAiFileAsrEngine.kt     # OpenAI Whisper ASR implementation
│   │   │   │   ├── SiliconFlowFileAsrEngine.kt # SiliconFlow ASR implementation
│   │   │   │   └── LlmPostProcessor.kt         # LLM post-processor
│   │   │   ├── ime/                    # Input method service
│   │   │   │   └── AsrKeyboardService.kt       # Main keyboard service (InputMethodService)
│   │   │   ├── ui/                     # User interface components
│   │   │   │   ├── SettingsActivity.kt        # Settings interface
│   │   │   │   ├── PermissionActivity.kt      # Permission request interface
│   │   │   │   ├── ImePickerActivity.kt       # Input method picker
│   │   │   │   └── FloatingImeSwitcherService.kt # Floating IME switching service
│   │   │   ├── store/                  # Data storage and configuration
│   │   │   │   ├── Prefs.kt            # Runtime configuration management
│   │   │   │   └── PromptPreset.kt     # Prompt presets
│   │   │   └── App.kt                  # Application entry point
│   │   ├── res/                        # Resource files
│   │   │   ├── layout/                 # Layout files
│   │   │   │   ├── activity_settings.xml    # Settings interface layout
│   │   │   │   ├── keyboard_view.xml         # Keyboard view layout
│   │   │   │   └── keyboard_qwerty_view.xml  # QWERTY keyboard layout
│   │   │   ├── values/                 # Default resource values
│   │   │   │   ├── strings.xml             # String resources
│   │   │   │   ├── colors.xml              # Color resources
│   │   │   │   ├── themes.xml              # Theme resources
│   │   │   │   └── dimens.xml              # Dimension resources
│   │   │   ├── values-*/               # Internationalization resources
│   │   │   │   ├── values-zh-rCN/          # Simplified Chinese resources
│   │   │   │   └── values-en/              # English resources
│   │   │   ├── drawable/               # Icons and background resources
│   │   │   ├── drawable-nodpi/         # No-density icon resources
│   │   │   ├── color/                  # Color state list resources
│   │   │   ├── animator/               # Animation resources
│   │   │   ├── xml/                    # XML configuration files
│   │   │   └── mipmap-anydpi/          # App icon resources
│   │   └── AndroidManifest.xml         # Application manifest file
├── build.gradle.kts                    # App-level build configuration
└── build.gradle.kts                    # Project-level build configuration
```

## License

This project is licensed under the MIT License. See the LICENSE file for details.

## Contributing Guide

Issues and Pull Requests are welcome to improve the project. Before submitting code, please ensure:

1. Code passes all tests
2. Follow project coding standards
3. Add necessary comments and documentation
4. Update relevant README documentation

---

_README in Chinese: [README.md](README.md)_
