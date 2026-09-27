# Agit Backend

极简账号后端，仅保存用户名与密码哈希，用于 Agit App 登录注册。
不需要任何密钥或环境变量配置。

本目录属于 Agit 仓库。部署到服务器时，将整个 `backend` 目录上传到服务器任意目录，
再按下方步骤启动即可。

## 部署步骤

1. 在服务器上安装 Python 3.10+。
2. 进入上传后的 `backend` 目录，安装依赖：
   ```bash
   pip install -r requirements.txt
   ```
3. 启动服务（监听 3399 端口，waitress）：
   ```bash
   python app.py
   ```
4. 在 IIS / Nginx 中配置反向代理，把 `https://your-domain.com/backend/*`
   映射到 `http://127.0.0.1:3399/backend/*`。

## API

- `POST /backend/register` `{username, password}` → `{token, user}`
- `POST /backend/login` `{username, password}` → `{token, user}`
- `GET  /backend/me` `Authorization: Bearer <token>` → `{user}`
- `POST /backend/change-password` `Authorization: Bearer <token>` `{old_password, new_password}`
- `GET  /backend/health`

## 说明

- 登录后服务端生成随机会话 token 存入本地 SQLite，有效期 30 天。
- 没有任何密钥 / 环境变量需要配置，开箱即用。
- Android 端后端地址通过根目录 `gradle.properties` 的 `AGIT_API_BASE_URL` 配置，模拟器默认指向宿主机本地后端。
