# Agit

面向 Android 的自托管代码仓库平台。内置自研内容寻址版本控制引擎（零外部 Git 依赖），配套 Jetpack Compose 客户端与极简 Flask 后端，在手机上即可完成建仓、提交、分支、Diff 与远程仓库连接。

## 功能特性

- 自研 VCS 引擎：SHA-256 内容寻址对象库（`.agit/objects/`）、分支、提交、Diff 引擎，不依赖系统 Git
- 代码仓库管理：新建仓库、文件浏览与编辑、提交记录、分支查看
- 远程仓库：账号注册登录、公开仓库浏览、按密码连接他人仓库、连接授权与拉黑管理
- 极简后端：仅保存用户名与密码哈希，SQLite 存储，随机会话 token 有效期 30 天，无需任何密钥配置

## 项目结构

```
app/       Android 客户端（Kotlin + Jetpack Compose）
backend/   Flask 后端（账号与远程仓库）
```

## 技术栈

| 模块 | 技术 |
|---|---|
| Android | Kotlin 2.0.21、Jetpack Compose 1.7.8（Material3）、Navigation Compose、Retrofit 2.11、OkHttp 4.12 |
| 构建 | AGP 8.7.3、Gradle、minSdk 26 / targetSdk 35 |
| 后端 | Python 3.10+、Flask、waitress、SQLite |

## 快速开始

### 启动后端

```bash
cd backend
pip install -r requirements.txt
python app.py
```

服务监听 `127.0.0.1:3399`，接口前缀 `/backend/`。

### 构建 Android 客户端

后端地址通过根目录 `gradle.properties` 的 `AGIT_API_BASE_URL` 配置：

- Android 模拟器默认使用 `http://10.0.2.2:3399/`（指向宿主机本地后端），无需修改即可运行
- 真机调试请改为局域网 IP，如 `http://192.168.1.100:3399/`
- 部署服务器后改为你的域名，如 `https://your-domain.com/`

```bash
./gradlew assembleDebug
```

### 部署后端到服务器

1. 上传 `backend` 目录到服务器，安装依赖并启动：
   ```bash
   pip install -r requirements.txt
   python app.py
   ```
2. 在 Nginx / IIS 配置反向代理，将 `https://your-domain.com/backend/*` 映射到 `http://127.0.0.1:3399/backend/*`
3. 将 Android 端 `AGIT_API_BASE_URL` 改为你的域名

## API 一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/backend/register` | 注册，返回 `{token, user}` |
| POST | `/backend/login` | 登录，返回 `{token, user}` |
| GET | `/backend/me` | 当前用户信息（Bearer token） |
| POST | `/backend/change-password` | 修改密码（Bearer token） |
| GET | `/backend/health` | 健康检查 |

## License

MIT
