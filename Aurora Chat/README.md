# Aurora Chat

Android 即时通讯应用（Kotlin + Jetpack Compose）+ Go 后端服务。

消息链路采用客户端-服务端传输加密（sess:v1:）+ 服务端静态加密（enc:v1:，AES-256-GCM）保障安全，采用服务端可信模型。支持私聊、群聊、好友、红包、转账、社区、AI 对话等功能。

## 项目结构

```
Aurora Chat/
├── app/                       # 组装根：MainActivity、导航、Koin 装配、业务界面
├── core/
│   ├── common/                # 日志系统（ErrorReporter / ModelLogger）
│   ├── domain/                # 纯契约：ChatEventSink、注入点
│   ├── network/               # 网络层（待迁入 HttpClient / AuroraApi）
│   ├── data/                  # 仓储与本地存储（待迁入）
│   └── ui/                    # 主题与通用 Compose 构件
├── feature/
│   ├── chat/ ai/ profile/     # 业务域模块
│   └── community/ server/ misc/
├── backend/
│   ├── main.go                # 组装根
│   ├── routes_*.go            # 按业务域的路由注册
│   ├── *_partN.go             # 巨型文件按声明边界拆分后的同包分片
│   ├── internal/platform/     # 统一响应与依赖契约
│   ├── siteserver/            # 独立静态服务（1111）
│   └── go.mod / go.sum
├── gradle/ · site/
├── build.gradle.kts · settings.gradle.kts · gradlew / gradlew.bat
```

## 模块边界

依赖方向单向，`:app` 是唯一组装根：

```
:app → :feature:* → :core:domain + :core:ui
:core:data → :core:domain + :core:network
:core:network → :core:domain + :core:common
:core:domain → :core:common
```

`:core:domain` 只放接口与注入点，不引用 Android 或 Compose 类型。推送与数据层通过
`ChatEvents.sink` 回调，不再反向依赖 `ui.viewmodel`。后端 `internal/platform` 承载横切能力，
`package main` 保留兼容门面，各域注册函数由 `main.go` 按序调用。

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

## 消息加密说明

消息链路采用服务端可信模型：
- 客户端发送消息时用会话密钥做传输加密（sess:v1:），服务端可解密
- 服务端落库前使用 AES-256-GCM 静态加密（enc:v1:），数据库仅存密文
- 服务端读取/推送时解密，再以接收方会话密钥重新加密下发

## 许可证

暂未指定，请参考项目内授权说明。
