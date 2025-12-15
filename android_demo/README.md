# AutoGLM Android Demo

> 🤖 最小化 Android 应用 Demo，连接 AIstudio 部署的 server.py，实现语音控制手机

## 📋 功能概述

这是一个最小化的 Android 应用 Demo，实现以下核心功能：

1. **语音输入** - 用户按住按钮说话，使用 Android 系统语音识别
2. **服务器连接** - 连接到 AIstudio 部署的 server.py 服务
3. **AI 指令获取** - 将语音内容发送到服务器，获取操作指令
4. **自动化执行** - 通过无障碍服务在手机上执行操作

## 🏗️ 项目结构

```
android_demo/
├── app/
│   ├── src/main/
│   │   ├── java/com/autoglm/phonedemo/
│   │   │   ├── MainActivity.java          # 主界面 Activity
│   │   │   ├── ApiClient.java             # 网络请求客户端
│   │   │   ├── ChatMessage.java           # 对话消息数据类
│   │   │   ├── ChatAdapter.java           # 对话列表适配器
│   │   │   └── AutomationAccessibilityService.java  # 无障碍自动化服务
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   │   ├── activity_main.xml      # 主界面布局
│   │   │   │   └── item_chat_message.xml  # 对话消息项布局
│   │   │   ├── values/
│   │   │   │   ├── strings.xml            # 字符串资源
│   │   │   │   ├── colors.xml             # 颜色资源
│   │   │   │   └── themes.xml             # 主题配置
│   │   │   ├── drawable/                  # 图形资源
│   │   │   └── xml/
│   │   │       └── accessibility_service_config.xml  # 无障碍服务配置
│   │   └── AndroidManifest.xml            # 应用清单
│   └── build.gradle                        # 模块构建配置
├── build.gradle                            # 项目构建配置
├── settings.gradle                         # 项目设置
├── gradle.properties                       # Gradle 属性
└── README.md                               # 本文档
```

## 🚀 快速开始

### 前提条件

1. **Android Studio** - 建议使用 Android Studio Hedgehog (2023.1.1) 或更高版本
2. **Android 设备** - Android 7.0 (API 24) 及以上版本
3. **服务器** - AIstudio 上已部署的 server.py 服务

### 构建步骤

#### 1. 使用 Android Studio 打开项目

```bash
# 在 Android Studio 中选择 File -> Open
# 导航到 android_demo 目录并打开
```

#### 2. 同步 Gradle

Android Studio 会自动检测 `build.gradle` 文件并提示同步。点击 "Sync Now" 完成依赖下载。

#### 3. 配置服务器地址

在应用运行后，在界面中输入您的服务器地址，格式如：
```
http://your-aistudio-server:8000
```

#### 4. 构建并安装

- 点击 `Run` 按钮或使用快捷键 `Shift + F10`
- 选择目标设备（真机或模拟器）
- 等待构建完成并自动安装

### 命令行构建（可选）

如果您更喜欢命令行：

```bash
cd android_demo

# 下载 Gradle Wrapper（如果没有）
gradle wrapper --gradle-version 8.0

# 构建 Debug APK
./gradlew assembleDebug

# APK 输出位置
# app/build/outputs/apk/debug/app-debug.apk

# 安装到设备
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 📱 使用说明

### 1. 首次启动配置

1. **授予权限**
   - 应用启动后会请求录音权限，请允许
   - 系统会提示开启无障碍服务

2. **开启无障碍服务**
   - 进入 `设置 -> 无障碍 -> AutoGLM自动化服务`
   - 开启服务并确认

3. **连接服务器**
   - 在输入框中输入 AIstudio 部署的服务器地址
   - 点击"连接服务器"按钮
   - 等待连接成功提示

### 2. 语音操控流程

1. 确保服务器已连接（状态指示灯为绿色）
2. **长按**语音按钮开始说话
3. 说出您想让手机执行的操作，例如：
   - "打开微信"
   - "打开淘宝搜索无线耳机"
   - "打开小红书搜索美食攻略"
4. 松开按钮，等待语音识别
5. 识别结果发送到服务器
6. 服务器返回操作指令
7. 应用自动执行操作

### 3. 支持的操作

| 操作类型 | 说明 | 指令示例 |
|---------|------|---------|
| Launch | 启动应用 | "打开微信" |
| Tap | 点击坐标 | 由 AI 自动生成 |
| Swipe | 滑动屏幕 | 由 AI 自动生成 |
| Type | 输入文字 | 由 AI 自动生成 |
| Back | 返回上一页 | 由 AI 自动生成 |
| Home | 返回桌面 | 由 AI 自动生成 |
| Long Press | 长按 | 由 AI 自动生成 |
| Double Tap | 双击 | 由 AI 自动生成 |

## 🔧 配置说明

### 服务器 API 接口

本 Demo 与仓库中的 `server.py` 配合使用，需要以下接口：

#### 健康检查接口

```
GET /health

响应：
{
    "status": "ok",
    "model_loaded": true
}
```

#### 聊天接口

```
POST /chat
Content-Type: application/json

请求体：
{
    "prompt": "用户的语音指令",
    "system": "你是一个手机自动化助手。请输出具体的操作指令。"
}

响应：
{
    "action": "do(action=\"Launch\", app=\"微信\")"
}
```

### 超时配置

在 `ApiClient.java` 中可以调整网络超时时间：

```java
httpClient = new OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)   // 连接超时
    .readTimeout(60, TimeUnit.SECONDS)      // 读取超时（AI 推理需要时间）
    .writeTimeout(30, TimeUnit.SECONDS)     // 写入超时
    .build();
```

### 应用包名配置

在 `AutomationAccessibilityService.java` 中的 `getPackageNameForApp()` 方法配置应用名称与包名的映射：

```java
switch (appName) {
    case "微信": return "com.tencent.mm";
    case "淘宝": return "com.taobao.taobao";
    // ... 添加更多应用
}
```

## ⚠️ 注意事项

### 安全提示

1. **无障碍服务权限**
   - 无障碍服务拥有很高的系统权限
   - 请确保只在可信环境下使用
   - 不要在公共场所使用敏感操作

2. **网络安全**
   - Demo 默认允许 HTTP 明文传输
   - 生产环境建议使用 HTTPS
   - 在 `AndroidManifest.xml` 中配置网络安全策略

### 已知限制

1. **文字输入**
   - 当前实现通过剪贴板复制文字
   - 完整的文字输入建议配合 ADB Keyboard 使用
   - 参考 Open-AutoGLM 主项目的 ADB 输入实现

2. **语音识别**
   - 依赖系统自带的语音识别服务
   - 部分设备可能需要联网进行语音识别
   - 识别准确率取决于系统语音引擎

3. **应用启动**
   - 需要预先配置应用包名映射
   - 未配置的应用无法通过名称启动

## 🔍 调试说明

### 查看日志

```bash
# 过滤 AutoGLM 相关日志
adb logcat | grep AutoGLM

# 或使用 Android Studio 的 Logcat 工具
# 过滤 Tag: AutoGLM_MainActivity, AutoGLM_ApiClient, AutoGLM_Accessibility
```

### 常见问题

**Q: 语音识别不工作？**
- 检查是否授予了录音权限
- 检查设备是否支持语音识别
- 部分设备需要联网才能使用语音识别

**Q: 无法连接服务器？**
- 检查服务器地址是否正确
- 检查服务器是否启动
- 检查网络连接
- 检查是否允许 HTTP 明文传输

**Q: 操作执行失败？**
- 检查无障碍服务是否已开启
- 检查应用包名是否已配置
- 查看 Logcat 日志获取详细错误信息

## 📚 相关文档

- [Open-AutoGLM 主项目](../README.md)
- [server.py 服务端代码](../server.py)
- [Android 无障碍服务官方文档](https://developer.android.com/guide/topics/ui/accessibility/service)

## 📄 许可证

本 Demo 遵循与 Open-AutoGLM 主项目相同的许可证。

---

> 💡 **提示**: 这是一个最小化 Demo，主要用于学习和演示。如需在生产环境使用，请根据实际需求进行扩展和安全加固。
