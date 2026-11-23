# Aurora Chat 后端服务

Go 语言编写的后端服务，提供即时通讯 API 和 WebSocket 推送。

## 环境要求

- Go 1.21 或更高版本
- 下载地址：https://go.dev/dl/

## 本地开发启动

```bash
cd backend
go mod tidy
go run .
```

默认端口：
- HTTP API: `:5004`（可通过 `SERVER_PORT` 环境变量修改）
- TCP 推送: `:5005`（可通过 `TCP_PORT` 环境变量修改）

## 云服务器部署（Linux 宝塔面板）

### 1. 在 Windows 上交叉编译 Linux 版本

```powershell
cd backend
$env:GOOS="linux"; $env:GOARCH="amd64"; go build -o aurora-server .
```

### 2. 通过宝塔面板上传

1. 打开宝塔面板 → 文件 → 进入服务器目录（如 `/www/wwwroot/www.example.com/backend/`）
2. 上传 `aurora-server` 文件和 `data/` 目录（如有）

### 3. 在宝塔终端执行部署

```bash
cd /www/wwwroot/www.example.com/backend

# 给执行权限
chmod +x aurora-server

# 停止旧进程
pkill -f aurora-server

# 启动新服务（首次需先准备 ai_config.env 等运行时配置）
nohup ./aurora-server > aurora-server.log 2>&1 &
```

### 4. 查看运行状态

```bash
# 查看进程
ps aux | grep aurora-server

# 查看日志
tail -f aurora-server.log

# 测试健康检查
curl http://localhost:5004/api/health
```

## 端口配置对照表

| 组件 | 默认端口 | 环境变量 | Android 端配置位置 |
|------|---------|---------|-------------------|
| HTTP API | 5004 | `SERVER_PORT` | `AuroraApi.kt` → `serverUrl` |
| TCP 推送 | 5005 | `TCP_PORT` | `TcpService.kt` → `TCP_PORT` |

**注意**：修改端口后，必须同时更新环境变量和 Android 端的对应配置。

## 故障排除

### "连接被拒绝" / "Connection refused"
1. 检查服务器进程是否在运行：`ps aux | grep aurora-server`
2. 检查端口是否在监听：`ss -tlnp | grep 5004`
3. 检查腾讯云安全组是否关联到实例
4. 检查服务器内部防火墙（宝塔面板）

### "连接超时" / "Connection timed out"
1. 安全组规则可能未生效，检查入站规则是否正确
2. 检查服务器是否可 ping 通：`ping <你的服务器IP>`
3. 检查端口是否可以从外部访问：`telnet <你的服务器IP> 5004`

### 登录一直转圈
1. 先用 `curl` 测试 API 是否可达
2. 检查 Android 端的 `serverUrl` 配置是否正确
3. 检查 Android 端 `AndroidManifest.xml` 中是否有 `android:usesCleartextTraffic="true"`
4. 查看 Android Logcat：`adb logcat -s AuroraApi`
