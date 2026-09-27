---
AIGC:
    Label: "1"
    ContentProducer: 001191440300708461136T1XGW3
    ProduceID: 32a912e3af5da84212b41419c1c9aee8_6de2cf5cb67f11f18d43525400393706
    ReservedCode1: llcWG8gHNT3EriYx00xZN1qobHJz9xozECldyJDP1LrbQsm2GF3o+zmhCD0oSPjmth9+ihqcljPZX5ElcK+76wLHc5l7w4pEmG+EpBHYSedTw5qCp9vYSU48RckDcj4C+0mwCWAVlQzlf00bkvQaRCFdjc5CMe/mYM51r1bfHW0x/BPjbdte6uS0jjM=
    ContentPropagator: 001191440300708461136T1XGW3
    PropagateID: 32a912e3af5da84212b41419c1c9aee8_6de2cf5cb67f11f18d43525400393706
    ReservedCode2: llcWG8gHNT3EriYx00xZN1qobHJz9xozECldyJDP1LrbQsm2GF3o+zmhCD0oSPjmth9+ihqcljPZX5ElcK+76wLHc5l7w4pEmG+EpBHYSedTw5qCp9vYSU48RckDcj4C+0mwCWAVlQzlf00bkvQaRCFdjc5CMe/mYM51r1bfHW0x/BPjbdte6uS0jjM=
---

# 白日迷宫

Three.js 3D 迷宫网页游戏，核心文件：`index.html`（前端，含全部 Three.js 场景与交互）、`server.js`（Node.js WebSocket 服务端，权威逻辑），运行端口 **1008**（pm2 进程名 `maze-plain`）。

---

## 最终目标（全局）

将本作逐步构建为一款**类似《和平精英》的战术竞技（吃鸡）游戏**。当前进度处在第一阶段：实现吃鸡式开局——**跳伞**。

## 当前进度（开发中）

### ✅ 已完成：跳伞开局机制（第一阶段）

1. **开始游戏即全员高空出生**：开发者面板点击“开始游戏”（或服务端 `setgame on`）后，所有在线玩家被传送到非常高空的随机散布点（高度 `PARACHUTE_Y = 150`），并进入跳伞阶段（`parachuting = true`）。
2. **空中自主飘动/下落**：
   - 服务端权威推进下落（`PARACHUTE_DESCENT = 14` 单位/秒，100ms 一跳），客户端本地动画同步，前后端参数一致。
   - 跳伞中仍可用 WASD 水平移动控制飘动方向、用鼠标自由旋转视角，用于选择降落地点。
   - 跳伞中无视墙体碰撞（但受地图边界限制），落地后恢复地面玩法与墙体碰撞。
3. **落地判定**：高度接近地面（`skyY <= 0`）即落地，服务端广播 `parachuteLand` + 贴地坐标；客户端幂等收尾，跳伞结束进入地面阶段。
4. **前后端一致 + 服务端权威（防绕过）**：
   - 服务端 `move` 消息在跳伞中一律以权威高度 `skyY` 广播，忽略客户端上报 y（防伪造高度/瞬间落地）。
   - 新增 `chuteGo`（客户端申请高空传送，服务端校验跳伞状态）、`chuteLandLocal`（客户端本地落地，服务端幂等确认落地）。
   - 跳伞中禁止攻击（前端 `mousedown`/`tryAttack` 拦截 + 服务端 `attack` 拒绝双方），禁止跳跃。
   - 新加入 / 断线重连且游戏已开始：自动进入高空跳伞开局（`startParachute`）。
   - 结束游戏 / 返回迷宫 / 进入对战：一律复位为落地状态并回出生点，不残留跳伞状态。
5. **开发文档**：本文件（README.md）。

### ⬜ 待完成（后续阶段）

- 缩圈 / 安全区机制
- 地面物资（武器/护甲/药品）拾取
- 枪械射击与弹道
- 载具、空投等吃鸡玩法元素
- 淘汰排名与结算

## 运行方式

```bash
# 项目目录（线上为 /www/wwwroot/3d.aurorachat.asia）
pm2 delete maze-plain >/dev/null 2>&1   # 已存在则先删旧进程
pm2 start server.js --name maze-plain
pm2 save
# 验证端口
ss -tlnp | grep 1008
```

浏览器访问 `http://<服务器IP或域名>:1008`。

## 开发者面板

连按 3 下 Shift 或 按 `=` 键，都会呼出同一个开发者密码框；输入密码 `XINIAN`（大小写均可，如 xinian / XinIan 都行）即进入开发者面板。面板提供：开始游戏 / 结束游戏、穿墙、无敌、一击必杀、无限连击、攻击距离、友伤、吸附、跳转对战、状态清理等开发调试功能。

> 注：此前旧的开发者密码 `ls` 已废弃，现统一为 `XINIAN`。

> 注意：本工程是不带手电筒（探照灯）的版本。
*（内容由AI生成，仅供参考）*
