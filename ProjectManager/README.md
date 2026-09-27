---
AIGC:
    Label: "1"
    ContentProducer: 001191440300708461136T1XGW3
    ProduceID: 32a912e3af5da84212b41419c1c9aee8_c949b102ba2a11f1b172525400248c00
    ReservedCode1: v4Qx9C7xgGLVl/+30TQ9W9P7ySbL9ubxsEf8TPlrGYh0NLX7d08fSMDtCq/TqHZFUZl1PsKCqGfc6qqHqZXKyj9bbJVwtA1Bm+VjRfQLlTabOJCJfEdULrLsuKV8yDVksx1TUHL4qzy5R3BC8T6y3/krtqE8WJemoDrZs9/AMReqhI+OXU2HKAhexDk=
    ContentPropagator: 001191440300708461136T1XGW3
    PropagateID: 32a912e3af5da84212b41419c1c9aee8_c949b102ba2a11f1b172525400248c00
    ReservedCode2: v4Qx9C7xgGLVl/+30TQ9W9P7ySbL9ubxsEf8TPlrGYh0NLX7d08fSMDtCq/TqHZFUZl1PsKCqGfc6qqHqZXKyj9bbJVwtA1Bm+VjRfQLlTabOJCJfEdULrLsuKV8yDVksx1TUHL4qzy5R3BC8T6y3/krtqE8WJemoDrZs9/AMReqhI+OXU2HKAhexDk=
---

# Project Manager

A self-hosted project & site management toolkit for Android, bundled with a lightweight Python server.

Project Manager turns your Android device into a management console for your servers and websites: browse and edit site files, open a web terminal, take backups, manage reverse proxies, and preview HTML/media content — all protected by card-key activation.

## Features

- **Site file manager** — browse, upload, download, rename, and edit files on remote servers
- **Web terminal** — run commands on your server from your phone, with dangerous-command guarding
- **Backup & restore** — one-tap site backups with a balance-based quota system
- **Reverse proxy manager** — review and manage Nginx reverse proxy rules
- **Card-key activation** — A/B/C permission levels validated against a backend (A = platform admin, B/C = per-site users)
- **HTML / media preview** — render remote web pages and preview images, audio, and video
- **Material 3 UI** — modern Compose interface with light/dark/auto theme

## Architecture

```
ProjectManager
├── app/                  # Android client (Kotlin + Jetpack Compose)
│   └── src/main/kotlin/com/pm/manager/
│       ├── terminal/     # API client, WebSocket session, site config
│       ├── ui/           # Compose screens (files, terminal, backup, proxy, settings)
│       └── model/        # Data models
└── server/
    └── site_server.py    # Python backend (stdlib only) for site management
```

The Android app talks to the Python backend over HTTP/WebSocket. The backend runs on your server, manages the site root directory, validates card keys, and executes terminal/file/proxy operations.

## Requirements

- JDK 17+
- Android SDK (compileSdk 35, minSdk 26)
- Gradle 8.x (wrapper included)

## Build

```bash
./gradlew assembleDebug
```

## Configuration

Backend endpoints are injected at build time via `gradle.properties`:

```properties
PM_API_BASE_URL=http://10.0.2.2:3001/   # HTTP API base URL
PM_WS_HOST=10.0.2.2                     # WebSocket host
PM_WS_PORT=3003                         # WebSocket port
```

Defaults point at the Android emulator's host loopback (`10.0.2.2`); override them for your deployment.

## Server deployment

```bash
python3 site_server.py --root /www/wwwroot/example.com --port 3001
```

| Option | Default | Description |
| --- | --- | --- |
| `--root` | `/www/wwwroot/example.com` | Site root directory |
| `--port` | `3001` | HTTP listen port |
| `--token` | `change-me` | Optional shared auth token |

Sensitive settings are read from environment variables when present:

- `AURORA_API` — card-key validation backend URL (default `http://127.0.0.1:5004`)
- `ADMIN_PASSWORD` — admin panel secondary password (default empty)

## Security notes

- Terminal commands are filtered against a blocklist of destructive operations (`rm -rf /`, `mkfs`, `dd`, etc.)
- Script uploads are restricted by permission level; only A/B-level cards may create script files
- Backups are stored outside the site root and keyed per domain

## License

MIT
内容由豆包ai生成










































































































































































































































































































































































































































牛逼怎么来到这了