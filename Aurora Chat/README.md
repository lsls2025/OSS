# Aurora Chat

Android 即时通讯应用（Kotlin + Jetpack Compose）+ Go 后端服务。

私聊消息使用端到端加密（ECDH + AES-256-GCM），服务器仅存储密文。支持私聊、群聊、好友、红包、转账、社区、AI 对话等功能。

## 项目结构

```
Aurora Chat/
├── app/                    # Android 客户端（Kotlin + Compose）
│   ├── src/main/           # 源码、资源、AndroidManifest
│   └── build.gradle.kts    # app 模块构建配置
├── backend/                # Go 后端服务
│   ├── *.go                # 后端源码
│   ├── go.mod / go.sum     # Go 依赖
│   └── README.md           # 后端部署说明
├── gradle/                 # Gradle wrapper
├── build.gradle.kts        # 根构建配置
├── settings.gradle.kts     # Gradle 设置
└── gradlew / gradlew.bat   # Gradle 包装器
```

## 环境要求

- **Android 端**：JDK 21、Android SDK（compileSdk 35）
- **后端**：Go 1.21+

## 编译 Android 客户端

1. 安装 JDK 21 和 Android SDK
2. 配置 `local.properties`（从 `local.properties.example` 复制，或手动创建）：
   ```
   sdk.dir=<你的 Android SDK 路径>
   ```
3. 在项目根目录执行：
   ```bash
   ./gradlew assembleDebug   # Linux/macOS
   gradlew.bat assembleDebug # Windows
   ```
4. APK 输出在 `app/build/outputs/apk/debug/`

## 编译并运行后端

详见 [`backend/README.md`](backend/README.md)。

```bash
cd backend
go mod tidy
go run .
```

默认端口：HTTP API `:5004`，TCP 推送 `:5005`（可用 `SERVER_PORT` / `TCP_PORT` 环境变量修改）。

## 端到端加密说明

私聊消息采用端到端加密：
- 每个用户生成 EC secp256r1 密钥对，私钥加密存储在本地，公钥上传服务器
- 发消息用临时密钥做 ECDH + AES-GCM 加密，服务器只看到密文
- 每次消息使用独立临时密钥，具备前向安全

群聊、社区、漂流瓶等非私聊内容使用服务端静态加密（AES-256-GCM）落库。

## 许可证

暂未指定，请参考项目内授权说明。
