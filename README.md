# PhoneAssistant

一个基于 Android 的离线 AI 手机助手，支持本地大语言模型推理与语音识别，通过无障碍服务实现设备自动化操作。

## 功能特性

- **离线 LLM 推理**：基于 [MNN](https://github.com/alibaba/MNN) 的本地大模型推理，无需联网即可使用
- **语音识别**：集成 [Vosk](https://alphacephei.com/vosk/) 离线语音转文字
- **在线模式**：可选接入 Qwen API，支持在线/离线双模式切换
- **设备自动化**：通过 Android 无障碍服务执行系统级操作（打开应用、点击、输入、滚动等）
- **多模型管理**：内置模型目录，支持从 ModelScope 下载和管理多个模型
- **多模态支持**：原生 C++ 层支持图片、音频、视频等多模态输入
- **流式对话**：支持多轮对话与流式输出
- **Material Design**：使用 Jetpack Compose 构建现代化 UI

## 架构概览

项目采用清晰的分层架构：

- **指令模式**：用户输入 → LLM 规划器 → 结构化 JSON → 无障碍服务执行
- **对话模式**：用户输入 → LLM 推理 → 流式文本输出

核心组件：
- `domain/` — 业务逻辑层（AssistantEngine、ChatEngine、CommandPlanner）
- `data/` — 数据层（模型目录、模型仓库、设置管理）
- `offline/` — 离线能力（MNN LLM 桥接、Vosk 语音）
- `online/` — 在线能力（Qwen API）
- `service/` — Android 服务（无障碍服务、动作执行器）
- `ui/` — Compose UI 层
- `app/src/main/cpp/` — C++ 原生层（多模态处理、视频解码、音频输出）

## 构建要求

- Android Studio Hedgehog 或更高版本
- Android NDK（CMake 构建）
- 目标架构：**arm64-v8a**
- 最低 SDK：参见 `app/build.gradle.kts`

## 快速开始

1. 克隆仓库：
   ```bash
   git clone https://github.com/konglixi/PhoneAssistant.git
   ```

2. 用 Android Studio 打开项目

3. 同步 Gradle 并构建项目

4. 安装到 arm64 Android 设备

5. 在系统设置中开启 **Phone Assistant** 的无障碍服务权限

6. 在应用内的「模型」页面下载所需模型

7. 输入自然语言指令即可使用

## 项目结构

```
PhoneAssistant/
├── app/
│   ├── src/main/
│   │   ├── java/dev/phoneassistant/
│   │   │   ├── MainActivity.kt
│   │   │   ├── data/          # 数据层
│   │   │   ├── domain/        # 业务逻辑
│   │   │   ├── offline/       # 离线 LLM & 语音
│   │   │   ├── online/        # 在线 API
│   │   │   ├── service/       # 无障碍服务
│   │   │   ├── ui/            # Compose UI
│   │   │   └── utils/         # 工具类
│   │   ├── cpp/               # C++ 原生代码
│   │   │   ├── processor.cpp  # 多模态处理器
│   │   │   └── video/         # 视频解码 & 处理
│   │   └── jniLibs/           # 预编译 .so 库
│   └── build.gradle.kts
├── LICENSE
└── README.md
```

## 依赖致谢

| 项目 | 许可证 | 用途 |
|------|--------|------|
| [MNN](https://github.com/alibaba/MNN) | Apache 2.0 | 本地大模型推理引擎 |
| [Vosk](https://alphacephei.com/vosk/) | Apache 2.0 | 离线语音识别 |
| [nlohmann/json](https://github.com/nlohmann/json) | MIT | C++ JSON 解析 |
| [Jetpack Compose](https://developer.android.com/jetpack/compose) | Apache 2.0 | UI 框架 |
| [OkHttp](https://square.github.io/okhttp/) | Apache 2.0 | HTTP 客户端 |
| [Coil](https://coil-kt.github.io/coil/) | Apache 2.0 | 图片加载 |

## License

```
Copyright 2026 konglixi

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
