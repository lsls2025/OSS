// ============ 3D 迷宫 独立后端（与米乎星球彻底拆分） ============
// 端口：9090（宝塔 Node 项目端口；对外经反向代理暴露）
// 职责：① 提供迷宫前端静态文件 ② 提供 /ws3d 多人 WebSocket
const http = require('http');
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const WebSocketServer = require('ws').WebSocketServer;

// gzip 压缩缓存：key = 文件路径，内容逐字节比对，一旦变化即重压，避免部署后用到陈旧压缩版
const gzipCache = new Map();

const PORT = process.env.PORT || 9090;
const PUBLIC_DIR = __dirname; // 直接服务前端文件所在目录（index.html / assets）

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
};

// ---------- 静态文件服务 ----------
const server = http.createServer((req, res) => {
  let urlPath;
  try {
    urlPath = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
  } catch {
    res.writeHead(400).end('Bad Request');
    return;
  }
  if (urlPath.endsWith('/')) urlPath += 'index.html';
  const filePath = path.normalize(path.join(PUBLIC_DIR, urlPath));
  // 带分隔符比较：否则 c:\3D_0_backup 这类“同前缀兄弟目录”会被误判为目录内，可被 ../ 绕过
  if (filePath !== PUBLIC_DIR && !filePath.startsWith(PUBLIC_DIR + path.sep)) {
    res.writeHead(403).end('Forbidden');
    return;
  }
  fs.readFile(filePath, (err, data) => {
    if (err) {
      res.writeHead(404).end('Not Found');
      return;
    }
    const ext = path.extname(filePath).toLowerCase();
    // 版本化资源（three 引擎与角色图都带 ?v=xxx）允许长缓存：URL 一变就是新文件，
    // 二次进入与断线重连后的重新加载几乎瞬时完成（老旧设备/慢网收益明显）。
    // index.html 与未带版本号的资源仍保持 no-store，避免“旧页面/旧逻辑”被缓存。
    const versioned = ext !== '.html' && /[?&]v=[^&]+/.test(req.url || '');
    const headers = {
      'Content-Type': MIME[ext] || 'application/octet-stream',
      'Access-Control-Allow-Origin': '*',
      'Cache-Control': versioned
        ? 'public, max-age=31536000, immutable'
        : 'no-store, no-cache, must-revalidate, max-age=0',
    };
    if (versioned) headers['Vary'] = 'Accept-Encoding';
    else { headers['Pragma'] = 'no-cache'; headers['Expires'] = '0'; }
    // gzip 压缩：仅文本类(js/css/html/json/svg)且客户端支持时才启用，图片等已压缩格式不处理
    const compressible = ['.js', '.css', '.html', '.json', '.svg'].includes(ext);
    const acceptsGzip = /gzip/i.test(req.headers['accept-encoding'] || '');
    if (compressible && acceptsGzip) {
      let c = gzipCache.get(filePath);
      if (!c || !c.data.equals(data)) {
        c = { data, gz: zlib.gzipSync(data) };
        gzipCache.set(filePath, c);
      }
      headers['Content-Encoding'] = 'gzip';
      headers['Vary'] = 'Accept-Encoding';
      headers['X-Full-Length'] = data.length; // 原始(解压后)大小，供前端进度条做准确分母
      res.writeHead(200, headers);
      res.end(c.gz);
      return;
    }
    headers['X-Full-Length'] = data.length;
    res.writeHead(200, headers);
    res.end(data);
  });
});

function makeId() {
  return Math.random().toString(36).slice(2, 10);
}

// ---------- 多人联机（/ws3d） ----------

// ---------- 手电筒电量（服务端权威）----------
// 电量 0~100。常亮每秒掉 100/60（约 1.667%），正好 60 秒掉到 0；
// 断电每秒回充 100/20 = 5%，正好 20 秒从 0 充到 100（仅手电筒关闭时回充）；
// 电量降到 FORCE_OFF_AT 时强制熄灭，并提示玩家“手电筒没电了”。
const FLASH_BATTERY_MAX = 100;
const FLASH_DRAIN_PER_SEC = 100 / 60;
const FLASH_CHARGE_PER_SEC = 100 / 20;
const FLASH_FORCE_OFF_AT = 1;

const PLAYERS_3D = new Map(); // id -> { id, name, role, x, z, angle, ws }
// 房间开启计时：第一个玩家进入时记为起点（房间清空后归零，下次第一个进入重新计时）。
// 通过 welcome / players 广播给全员，前端据此显示“本局已开启多久”。
let roomStart = null;
// 对局阶段（开发者控制）：'prep' 预备房 | 'playing' 对局中。
// 按下“开始游戏”切换到 playing（把所有人传送到迷宫新出生点、计时开始），“结束游戏”切回 prep。
let gamePhase = 'prep';
let gameStartTs = null; // 本局开始时刻（仅 playing 时有值），用于顶栏计时器
let ghostToken = null; // 当前对局中被分配为“鬼”的玩家 id（仅在 playing 且已分配后有值）
let rollTimer = null, allocTimer = null; // 开始游戏后：先 5s 提示“正在随机分配鬼”，再 5s 下发真实身份
// ---- 刀人·救助·胜负（服务端权威判定）----
// 一次性全场关灯标记：锁定那一瞬间除鬼外所有人手电强制关闭（可手动重开），全对局仅一次（startgame 重置）
let lightsOutUsed = false;
// 连续被打断计数：成功击杀清零，≥3 → 成员方全员胜利（startgame 重置，只统计本局内）
let consecutiveFails = 0;
// 当前正在进行的刀人记录（同一时刻只允许一把刀）：{ by, target, until }；被救/死亡/掉线/本局结束都会清空
let activeKnife = null;
let knifeTimer = null; // 刀人 5 秒后的结算定时器（killed / gone）
const REVEAL_ALL_MS = 5000; // 「全员爆点」持续时间（毫秒）：期间狼人可见所有人位置
const KNIFE_MS = 5000;   // 刀人定身时长（毫秒）
const KNIFE_RANGE = 4.5; // 服务端识别距离（米）：与前端判定体积“识别距离适中约 4.5 米”一致
const KNIFE_HALF = 1.2;  // 判定体积半宽（米）：比立牌略大约 2.4×2.4×1.4，距离校验按“水平距离 ≤ 识别距离+半宽”
const KNIFE_Y_TOL = 1.5; // 垂直容差（米）：立牌中心 0.95 + 判定体积半高 0.7 的宽松上限
// 鬼「变身前摇」时长（毫秒）：服务端权威 —— 前摇结束时由服务端统一翻图并广播，
// 保证所有客户端在同一时刻看到变身（客户端只负责播前摇特效，不自行换图）。
const GHOST_TRANSFORM_MS = 2000;      // 成员形态 → 鬼形态（亮出真身）：前摇 2 秒
const GHOST_TRANSFORM_BACK_MS = 1000; // 鬼形态 → 变回成员（收起）：前摇只要 1 秒
// 角色立牌的“中心离地高度” = 立牌显示尺寸的一半（与前端 index.html 的 BODY_CENTER_Y 保持一致，改尺寸时同步）。
// 前端上报的 y 就是立牌中心高度：站在地面 = BODY_CENTER_Y，明显高于它才算腾空。
const BODY_CENTER_Y = 0.95;
const AIRBORNE_Y = BODY_CENTER_Y + 0.25; // 腾空判定阈值（连跳悬停时远高于此值）
const PONG_GRACE_MS = 2000;   // 心跳 ping 发出后未收到 pong 的宽限期（仅用于存活判定，不影响断开逻辑）
const TOKENS_3D = new Map(); // token -> 断线玩家的档案，用于断线重连恢复身份
const wss3d = new WebSocketServer({ server, path: '/ws3d' });

// 开发者鉴权：密码从环境变量注入（DEV_PASSWORD 开发者面板 / DEV_ROLE_PASSWORD 角色卡），未配置时开发者功能自动禁用
const DEV_PASSWORD = (process.env.DEV_PASSWORD || '').toLowerCase();
const DEV_ROLE_PASSWORD = (process.env.DEV_ROLE_PASSWORD || '').toLowerCase();
function isDev(p) { return !!(p && p.dev); }
let flashBan = { on: false, by: null }; // 禁用手电：开发者开启后，除开发者本人(by)外所有人不可开手电筒

// 前端构建号（与 index.html 的 BUILD、three 静态 import 的 ?v= 三处联动）：
// 用于识别“浏览器在跑旧缓存页面”，并提示前端强制刷新一次（只提示不踢线）。
const ASSET_VER = 58;

// 被“状态清理”踢出的 token 黑名单：用于拒绝被踢者 10 秒内的自动重连，
// 否则其他玩家的前端 onclose 会 scheduleReconnect() 3 秒自动重连回来，导致“踢不掉人”。
const KICKED_TOKENS = new Map(); // token -> 过期时间戳(ms)

const KICK_ONE_MS = 10 * 60 * 1000; // 「单踢」的封禁时长：10 分钟（按 token，不按 IP）
// 踢掉某一个人：拉黑其 token 到 until、通知其客户端（客户端据此写本地缓存）、断开连接并清场
function kickPlayerOut(q, until) {
  if (!q) return;
  if (q.token) KICKED_TOKENS.set(q.token, until);
  // 他若正在刀人/被刀：立即中止（不判死亡、不误加打断）
  if (activeKnife && (activeKnife.by === q.id || activeKnife.target === q.id)) abortKnife('gone');
  // 先发消息（带上解封时刻）再断开：客户端据此写“10 分钟内禁止进入”的本地缓存
  if (q.ws.readyState === 1) { try { q.ws.send(JSON.stringify({ type: 'kickout', until })); } catch (e) {} }
  const wasLockAll = !!q.lockAll;
  PLAYERS_3D.delete(q.id);
  if (PLAYERS_3D.size === 0) roomStart = null; // 房间空了：计时归零
  if (q.token) TOKENS_3D.delete(q.token); // 不留断线档案，避免他换个连接马上回来
  sendToRoom(q.room, { type: 'leave', id: q.id });
  if (wasLockAll && !Array.from(PLAYERS_3D.values()).some(o => o.lockAll)) {
    broadcast3d({ type: 'lockall', on: false });
  }
  broadcastPlayers3d();
  try { q.ws.close(1000, 'kicked'); } catch (e) {}
  console.log('[3d kickone]', q.name, 'banned until', new Date(until).toLocaleTimeString());
}
function kickBlocked(token) {
  if (!token) return false;
  const exp = KICKED_TOKENS.get(token);
  if (!exp) return false;
  if (Date.now() < exp) return true;
  KICKED_TOKENS.delete(token);
  return false;
}

function snap3d(p) {
  return { id: p.id, name: p.name, ghost: !!p.ghost, ghostImg: !!p.ghostImg, x: p.x, z: p.z, angle: p.angle, pitch: p.pitch || 0, flash: p.flash !== false, enhanced: p.enhancedFlash === true, battery: p.battery == null ? FLASH_BATTERY_MAX : Math.round(p.battery * 10) / 10, knifeBy: p.knifeBy || null, dead: !!p.dead, rescued: !!p.rescueUsed };
}
// 按真实流逝时间推进手电筒电量（服务端权威、按真实比例分配）：
// 开灯按消耗速率扣、关灯按回充速率加，电量值恒等于“上一次基准值 + 速率×真实经过秒数”。
// 这样无论开/关多少次、何时切换，电量都按“真实经过时间”精确分配，不存在客户端估算漂移。
function batteryOf(p, now) {
  // 增强手电（开发者）：电量恒满、永不强制熄灭，且始终视为开灯（远程端据此渲染增强光）
  if (p.enhancedFlash) { p.battery = FLASH_BATTERY_MAX; p.flash = true; p.batteryAt = now; return false; }
  if (p.battery == null) { p.battery = FLASH_BATTERY_MAX; p.batteryAt = now; }
  const dt = (now - p.batteryAt) / 1000;
  if (dt <= 0) return false;
  let forcedOff = false;
  if (p.flash !== false) { // 与 snap3d 一致：true/undefined 均视为“开灯消耗”，仅显式 false 才断电回充
    p.battery -= FLASH_DRAIN_PER_SEC * dt;
    // 电量降到阈值即强制熄灭（不再继续往下掉），随后进入断电回充
    if (p.battery <= FLASH_FORCE_OFF_AT) {
      p.battery = FLASH_FORCE_OFF_AT;
      p.flash = false;
      forcedOff = true;
    }
  } else if (p.battery < FLASH_BATTERY_MAX) {
    p.battery = Math.min(FLASH_BATTERY_MAX, p.battery + FLASH_CHARGE_PER_SEC * dt);
  }
  p.batteryAt = now;
  return forcedOff;
}

// 向所有（含指定的某 id）发送可由连接定制内容的消息
function broadcast3dPer(fn) {
  for (const p of PLAYERS_3D.values()) {
    if (p.ws.readyState === 1) {
      try { p.ws.send(JSON.stringify(fn(p))); } catch (e) { /* ignore */ }
    }
  }
}
// 迷宫出生点：入口附近（0/1 号格是清过墙的连通区）随机，并在多个候选点里挑“离其他在线玩家最远”的一个。
// 旧实现只在 0.2~0.8 的极小方格内随机，多人同时复活/刷新时会直接重叠 → 表现为“刷新后没在出生点，而是出现在别人身上”。
function mazeSpawnPos(selfId) {
  const MIN_G = 0.35, MAX_G = 1.05; // 世界坐标约 0.77~1.8。格线 1.5（世界 2.5）处的墙是随机迷宫未清空的，
  // 出生范围必须避开其“墙+玩家半径”的阻挡区（约格 1.29 起），否则有几率刷进墙里卡死。
  let bestPos = null, bestGap = -1;
  for (let i = 0; i < 12; i++) {
    const cand = { x: MIN_G + Math.random() * (MAX_G - MIN_G), z: MIN_G + Math.random() * (MAX_G - MIN_G) };
    let gap = Infinity;
    for (const o of PLAYERS_3D.values()) {
      if (selfId && o.id === selfId) continue;
      if (!isLivePlayer(o)) continue; // 只避让真实在线的玩家
      const d = Math.sqrt((o.x - cand.x) ** 2 + (o.z - cand.z) ** 2);
      if (d < gap) gap = d;
    }
    if (gap > bestGap) { bestGap = gap; bestPos = cand; }
    if (bestGap > 1.6) break; // 已经足够开阔，提前返回
  }
  return bestPos;
}
function broadcast3d(msg, exceptId) {
  const s = JSON.stringify(msg);
  for (const p of PLAYERS_3D.values()) {
    if (p.ws.readyState === 1 && p.id !== exceptId) {
      try { p.ws.send(s); } catch (e) { /* ignore */ }
    }
  }
}
// 判定玩家连接是否“存活”：socket 打开 且 最近一次心跳收到 pong。
// 广播列表与在线人数必须统一用该口径：半死连接（TCP 未触发 close、但已收不到 pong）既不计入在线，
// 也不能继续出现在 players 列表里，否则客户端会一直渲染其立牌 → “在线 1 人却看到鬼影”且迟迟不消失
function isLivePlayer(o) {
  // 存活口径的唯一来源是 socket：ws.isAlive 由 pong 回置、由心跳循环清零后重新 ping。
  // 不能读玩家对象上的字段（正常加入的玩家从未初始化过它），否则所有在线玩家都会被判为“不存活”，
  // 名单/在线数/分配名单/开战出生点全部为空。
  if (!o || !o.ws || o.ws.readyState !== 1) return false;
  if (o.ws.isAlive) return true;
  // 心跳发出 ping 前会先把 isAlive 置 false，pong 回来才恢复；这段极短窗口内若正好广播名单，
  // 会把正常玩家误判为“已掉线”，导致他人视角立牌消失、在线数抖动、本端坐标对齐被跳过。
  // 故把“ping 刚发出、应答可能还在路上”的宽限期也视为存活；真正半死的连接宽限期过后即被剔除并断开。
  return !!o.ws.pingAt && (Date.now() - o.ws.pingAt < PONG_GRACE_MS);
}
function livePlayers() {
  return Array.from(PLAYERS_3D.values()).filter(isLivePlayer);
}
// ---- 房间隔离：预备房 prep / 对局房 match 互不可见、互不同步 ----
function forEachInRoom(room, fn) {
  for (const o of PLAYERS_3D.values()) {
    if (o.room === room && isLivePlayer(o)) fn(o);
  }
}
// 仅向同一房间内的玩家下发消息（exceptId 可排除自己）
function sendToRoom(room, msg, exceptId) {
  const s = JSON.stringify(msg);
  for (const o of PLAYERS_3D.values()) {
    if (o.room !== room || o.id === exceptId) continue;
    if (o.ws.readyState === 1) { try { o.ws.send(s); } catch (e) {} }
  }
}
function roomCount(room) {
  let n = 0;
  for (const o of PLAYERS_3D.values()) if (o.room === room && isLivePlayer(o)) n++;
  return n;
}
// 给某玩家构造其专属的 welcome 负载：仅含同房间玩家，对局房才带 gameStart
function welcomePayload(p) {
  // 过渡房 limbo 只暴露自己：两名同时处于 5 秒过渡的玩家互不看见（他们此刻都已“离场”）
  const same = (p.room === 'limbo') ? [p] : livePlayers().filter(q => q.room === p.room);
  return { type: 'welcome', id: p.id, room: p.room, players: same.map(snap3d), online: same.length, roomStart, phase: gamePhase, gameStart: (p.room === 'match') ? gameStartTs : null,
    skill: { ms: skillMsOf(p), full: SKILL_FULL_MS, ready: skillReady(p) } };
}
function broadcastPlayers3d() {
  // 逐接收者下发“仅同房间”的玩家名单，保证预备房与对局房互不串场
  for (const o of PLAYERS_3D.values()) {
    if (!isLivePlayer(o)) continue;
    // 过渡房 limbo 只发自己：同时处于 5 秒过渡的两个人互不看见（此刻都不在任何房间里）
    const same = (o.room === 'limbo') ? [o] : livePlayers().filter(q => q.room === o.room);
    const payload = { type: 'players', players: same.map(snap3d), online: same.length, roomStart, phase: gamePhase, gameStart: (o.room === 'match') ? gameStartTs : null };
    if (o.ws.readyState === 1) { try { o.ws.send(JSON.stringify(payload)); } catch (e) {} }
  }
}
// 在线人数：只统计“连接仍存活”的玩家，断连/心跳失效的玩家不计入，避免顶部一直显示已掉线的人
function connectedCount() {
  let n = 0;
  for (const o of PLAYERS_3D.values()) {
    if (isLivePlayer(o)) n++;
  }
  return n;
}
// ⚠ MAZE_SIZE / OUTER_CELLS 必须与 index.html 的 SIZE / OUTER_CELLS 完全一致（smoke.js 会校验）。
// 这两个值只用于“坐标钳位”：一旦小于地图实际范围，玩家走到一半就会被钳回，
// 表现为半路撞上一堵看不见的墙（地图从 24 扩到 50 时，原来的 -8..20 就会这样）。
const MAZE_SIZE = 30;     // 迷宫内部尺寸（格）
const OUTER_CELLS = 12;   // 迷宫外的广场宽度（格）
// 可移动范围（格坐标）：与前端 MOVE_MIN/MOVE_MAX 同源，再各外放 2 格余量，
// 保证任何合法位置都不会被误钳，同时仍然拦住异常/作弊数值。
const MOVE_CLAMP_MIN = -(OUTER_CELLS + 2);
const MOVE_CLAMP_MAX = (MAZE_SIZE - 1) + OUTER_CELLS + 2;
function clamp3dMove(v) {
  if (!isFinite(v)) return 0;
  return Math.max(MOVE_CLAMP_MIN, Math.min(MOVE_CLAMP_MAX, v));
}

// ---- 鬼的「刀人」技能：在“成员形态（伪装）”下累计维持 SKILL_FULL_MS 即可激活 ----
// 累计是分次累加的（切回鬼形态只是暂停，不清零）；用时间戳惰性计算，不需要每帧 tick。
// 服务端是唯一权威：客户端只按这里下发的进度画条，不会自己提前“就绪”。
const SKILL_FULL_MS = 10000;
function skillMsOf(p) {
  if (!p || !p.ghost) return 0;
  let ms = p.skillMs || 0;
  // ⚠ 只有“鬼形态”才涨条；成员形态下绝对不涨（切回成员立刻停止累计）
  if (p.skillSince && p.ghostImg && !p.dead) ms += Date.now() - p.skillSince;
  return Math.min(SKILL_FULL_MS, ms);
}
function skillReady(p) { return skillMsOf(p) >= SKILL_FULL_MS; }
// 充能进度只私发给鬼本人（其他人不该看到他的技能状态）
function pushSkill(p) {
  if (!p || !p.ghost || !p.ws || p.ws.readyState !== 1) return;
  try { p.ws.send(JSON.stringify({ type: 'skill', ms: skillMsOf(p), full: SKILL_FULL_MS, ready: skillReady(p) })); } catch (e) { /* ignore */ }
}
// 切换鬼的形态（true = 鬼形态）：进入鬼形态开始计时；切回成员形态把这段时长并入基础值并立刻停表
function applyGhostForm(p, ghostImgOn) {
  if (!p) return;
  const on = !!ghostImgOn;
  // 离开“鬼形态”＝立刻停止累计：先把这段时长并入基础值，再改状态（顺序不能反）
  if (!on && p.skillSince) { p.skillMs = skillMsOf(p); p.skillSince = 0; }
  if (p.skillTimer) { clearTimeout(p.skillTimer); p.skillTimer = null; }
  p.ghostImg = on;
  if (on && p.ghost && !p.skillSince) {
    p.skillSince = Date.now();
    // 充能满的那一刻主动推一次：客户端的“已就绪”由服务端确认，不会自己提前亮
    const left = SKILL_FULL_MS - skillMsOf(p);
    if (left > 0) p.skillTimer = setTimeout(() => { p.skillTimer = null; pushSkill(p); }, left + 30);
  }
  pushSkill(p);
}
// 刀人结束后鬼充能清零：清零后若仍保持鬼形态则立即重新开始累计（重新攒满 10 秒才能再刀）
function resetGhostSkill(p) {
  if (!p) return;
  p.skillMs = 0;
  p.skillSince = 0;
  if (p.skillTimer) { clearTimeout(p.skillTimer); p.skillTimer = null; }
  if (p.ghost && p.ghostImg) {
    p.skillSince = Date.now();
    const left = SKILL_FULL_MS - skillMsOf(p);
    if (left > 0) p.skillTimer = setTimeout(() => { p.skillTimer = null; pushSkill(p); }, left + 30);
  }
  pushSkill(p);
}
// 刀人双方水平距离（服务端自己算一遍，不信客户端）：鬼↔目标 与 救助者↔被刀者/鬼 共用
function dist3d(a, b) {
  return Math.sqrt((a.x - b.x) ** 2 + (a.z - b.z) ** 2);
}
function knifeReachOk(a, b) {
  if (!a || !b) return false;
  if (dist3d(a, b) > KNIFE_RANGE + KNIFE_HALF) return false;
  if (Math.abs((a.y || 0) - (b.y || 0)) > KNIFE_Y_TOL) return false;
  return true;
}
// 中止当前刀人（不判死亡、不误加打断计数）：reason 'rescued'|'gone'，'killed' 走 knife 结算流程
function abortKnife(reason) {
  if (!activeKnife) return;
  const k = activeKnife;
  activeKnife = null;
  if (knifeTimer) { clearTimeout(knifeTimer); knifeTimer = null; }
  const by = PLAYERS_3D.get(k.by);
  const tg = PLAYERS_3D.get(k.target);
  if (by) by.knifing = false;
  if (tg) { tg.knifeBy = null; tg.knifeUntil = 0; }
  if (gamePhase === 'playing') sendToRoom('match', { type: 'knifeEnd', by: k.by, target: k.target, reason });
  console.log('[3d knife] aborted', reason, 'by', k.by, 'target', k.target);
}
// 统一“回预备房”（死亡 / 逃脱 / 胜利共用）：改房 + 清刀人进行态 + 通知对局房移除立牌。
// 借现成的房间隔离，他从其他人视野消失；玩家本人由对应私信/广播（youDied / escape / membersWin）获知结果。
function moveToPrep(p) {
  if (!p) return;
  const wasMatch = p.room === 'match';
  p.room = 'prep';
  p.knifeBy = null; p.knifeUntil = 0;
  p.knifing = false;
  p.transformUntil = 0;
  p.skillMs = 0; p.skillSince = 0;
  if (p.skillTimer) { clearTimeout(p.skillTimer); p.skillTimer = null; }
  p.ghost = false; p.ghostImg = false;
  p.escaped = false;
  // 回预备房：位置重置到出生点（死亡 / 逃脱 / 胜利共用），不停留在本局结束位置
  const sp = mazeSpawnPos(p.id);
  if (sp) { p.x = sp.x; p.z = sp.z; }
  p.angle = 0;
  if (wasMatch) sendToRoom('match', { type: 'leave', id: p.id });
}
// ---- 过渡「虚空房」limbo ----
// 死亡 / 逃脱 / 胜负结算后的那 5 秒：玩家既不在预备房、也不在对局房（房间隔离 → 谁都看不见他、
// 鬼也刀不到他），客户端此时正显示“5 秒后回到预备房”的全屏遮罩。
// 不这么做的话：玩家在遮罩后仍能走动甚至被鬼再刀一次，状态会彻底错乱（“逃出去了又被抓”）。
const LIMBO_MS = 5000; // 与客户端过渡界面倒计时一致
const LIMBO_TIMERS = new Map(); // id -> 定时器
function moveToLimbo(p) {
  if (!p) return;
  const wasMatch = p.room === 'match';
  // 立即脱离所有房间：对局房里的人看不到他、预备房里的人也看不到他，且刀人/救助都因房间不符被拒
  p.room = 'limbo';
  p.knifeBy = null; p.knifeUntil = 0;
  p.knifing = false;
  p.transformUntil = 0;
  p.skillMs = 0; p.skillSince = 0;
  if (p.skillTimer) { clearTimeout(p.skillTimer); p.skillTimer = null; }
  if (wasMatch) sendToRoom('match', { type: 'leave', id: p.id }); // 对局房立刻移除其立牌
  const pid = p.id;
  const old = LIMBO_TIMERS.get(pid);
  if (old) clearTimeout(old);
  LIMBO_TIMERS.set(pid, setTimeout(() => {
    LIMBO_TIMERS.delete(pid);
    const q = PLAYERS_3D.get(pid);
    if (!q || q.room !== 'limbo') return; // 期间已被结束游戏/踢出等处理过 → 不再自作主张改房间
    moveToPrep(q); // 5 秒到：正式回预备房（位置重置到出生点、清标记）
    q.dead = false; q.rescueUsed = false;
    broadcastPlayers3d();
  }, LIMBO_MS));
}
// 鬼方获胜：对局房里已无成员（全部被击杀或全部逃出）→ 本局结束、全员回预备房。
// 与“连续 3 次打断 = 成员胜利”对称，是另一条（也是最后一条）全局胜负。
function endMatchAsGhostWin(reason) {
  if (gamePhase !== 'playing') return;
  gamePhase = 'prep';
  gameStartTs = null;
  clearTimeout(rollTimer); clearTimeout(allocTimer);
  abortKnife('gone'); // 兜底：此时 activeKnife 应为空
  broadcast3d({ type: 'ghostWin', reason }); // 全量下发：预备房的中途加入者也知道本局已结束
  let moved = 0;
  for (const o of PLAYERS_3D.values()) {
    // 只剩鬼（成员已全部阵亡/逃脱）；他也走 5 秒“鬼获胜”过渡，然后回预备房
    if (o.room === 'match') { moveToLimbo(o); moved++; }
  }
  ghostToken = null;
  broadcastPlayers3d();
  console.log('[3d game] ghost win:', reason, 'players to limbo:', moved);
}
// 对局房里还剩几个“成员”（不含鬼、不含已离场/已阵亡/已逃脱的人）
function aliveMembersInMatch() {
  let n = 0;
  for (const o of PLAYERS_3D.values()) {
    if (o.room === 'match' && isLivePlayer(o) && !o.ghost) n++;
  }
  return n;
}
// 每次有人离场（被击杀 / 逃出）后调用：成员清零 → 鬼获胜，本局结束
function checkMatchEnd() {
  if (gamePhase !== 'playing') return;
  if (aliveMembersInMatch() > 0) return;
  endMatchAsGhostWin('allDown');
}
// 成员方全员胜利：本局结束、对局房全员回预备房（仅发本局参与者，预备房中途加入者不打扰）
function endMatchAsMembersWin(reason) {
  if (gamePhase !== 'playing') return;
  gamePhase = 'prep';
  gameStartTs = null;
  clearTimeout(rollTimer); clearTimeout(allocTimer);
  abortKnife('gone'); // 此时 activeKnife 应为空（rescue 已中止）；兜底
  sendToRoom('match', { type: 'membersWin', reason });
  let moved = 0;
  for (const o of PLAYERS_3D.values()) {
    if (o.room === 'match') {
      moveToPrep(o);
      o.dead = false; o.rescueUsed = false;
      moved++;
    }
  }
  ghostToken = null;
  broadcastPlayers3d();
  console.log('[3d game] members win:', reason, 'players back to prep:', moved);
}

wss3d.on('connection', (ws) => {
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });
  // 注意：本连接复用断线档案时必须把 id 同步为该档案的 id（见 join 复用分支），
  // 否则本连接后续所有 PLAYERS_3D.get(id) 都会落空（指令全部失效 + 记录永不清理）
  let id = makeId();
  let joined = false;

  ws.on('message', (raw) => {
    let m;
    try { m = JSON.parse(raw); } catch { return; }
    try {
    switch (m.type) {
      case 'join': {
        if (joined) return;
        const name = String(m.name || '').trim().slice(0, 12) || '游客';
        const role = Math.max(1, Math.min(6, Number(m.role) || 1));
        const token = String(m.token || '').trim();
        // 被“状态清理”踢出的 token：拒绝其在踢出后 10 秒内的自动重连，防止被踢者绕一圈又回来
        if (kickBlocked(token)) {
          try { ws.send(JSON.stringify({ type: 'kickout' })); } catch (e) {}
          try { ws.close(1000, 'kicked'); } catch (e) {}
          return;
        }
        // 版本握手：前端上报的构建号与服务端不一致 → 浏览器很可能在跑“旧缓存页面”，
        // 提示前端强制刷新一次（只提示、不踢线，避免旧页面陷入重连死循环）。
        if (m.ver != null && Number(m.ver) !== ASSET_VER) {
          try { ws.send(JSON.stringify({ type: 'verMismatch', ver: ASSET_VER })); } catch (e) {}
        }
        // 断线重连恢复身份（不产生分身）：
        // 仅当同一 token 的旧连接“已死/在断线档案”时才复用同一个 id；
        // 若该 token 还挂着一个活跃连接（如同一浏览器的另一个标签页），就当作新玩家，避免旧身体残留/身份被挤占。
        let p = null;
        if (token) {
          for (const o of PLAYERS_3D.values()) {
            // 存活口径与 isLivePlayer 完全一致：只有“确已失效”的连接才允许被复用，
            // 避免把仍有活跃连接的玩家（如同一浏览器的另一个标签页）当成断线档案而抢占其身份
            if (o.token === token && !isLivePlayer(o)) { p = o; break; }
          }
          if (!p && TOKENS_3D.has(token)) {
            // 仅当该 token 当前没有任何“在线记录”时才启用断线档案：
            // 否则（例如刚刷新过、新档案还在线，同时旧档案被 close 存进了档案表）会复活一份
            // 早已被取代的旧尸体，在别人视角形成同名幽灵分身，并让击杀数/坐标错位。
            let tokenHasLive = false;
            for (const o of PLAYERS_3D.values()) {
              if (o.token === token && isLivePlayer(o)) { tokenHasLive = true; break; }
            }
            if (!tokenHasLive) p = TOKENS_3D.get(token);
          }
        }
        if (p) {
          if (TOKENS_3D.has(token)) TOKENS_3D.delete(token);
          // 若旧 socket 尚在（僵尸/死连接），复用后终止它；其 close 因 p.ws 已替换而被忽略
          if (p.ws && p.ws !== ws) { try { p.ws.terminate(); } catch (e) {} }
          p.ws = ws;
          // 关键：本连接的 id 必须与档案 id 对齐，后续消息分发与 close 清理都按该 id 查表
          id = p.id;
          p.disconnectedAt = 0;
          // 断线时正处在“5 秒过渡房”（刚被击杀/刚逃出）：重连一律回预备房，
          // 否则档案里的 limbo 会被带回来，玩家永远待在谁也看不见的虚空里。
          const ltOld = LIMBO_TIMERS.get(p.id);
          if (ltOld) { clearTimeout(ltOld); LIMBO_TIMERS.delete(p.id); }
          if (p.room === 'limbo') p.room = 'prep';
          // 昵称与角色以**本次 join 上报**为准：玩家“返回主界面 → 换个角色再进来”时走的就是这条
          // 断线档案复用分支，档案里还留着上一局的 name/role。不刷新就会出现
          // “自己明明选了鬼、别人视角里却是普通人”（名字取自 name、立牌贴图取自 role，
          // 两者一旦不同步，还会互相矛盾/时好时坏）。
          p.name = name;
          p.role = role;
          // 重连视为新一局：手电筒电量复位满电、手电筒默认开启（与开局一致），断电回充基准时间对齐此刻；
          // 关键：必须复位 flash=true，否则沿用上次离线前的“关闭/没电”状态，会导致开局“一直亮着却不耗电”。
          p.battery = FLASH_BATTERY_MAX; p.batteryAt = Date.now(); p.flash = true;
          // 复位逃离标记：否则此前离开过迷宫的玩家重连后会带着 escaped=true 回迷宫，
          // 导致其“成功离开了迷宫”广播被永久吞掉
          p.escaped = false;
          // 重连视为新一局：复位刀人·救助相关状态（掉线若正被刀/正在刀人，close 已中止 activeKnife）
          p.knifeBy = null; p.knifeUntil = 0; p.knifing = false;
          p.dead = false; p.rescueUsed = false;
          p.transformUntil = 0;
          // 复位游戏开发态（增强手电），防止断线档案把上一局状态带进新对局；
          // 客户端 onopen 会按本地开关重新上报，只有真正开启的才会恢复
          p.enhancedFlash = false;
          // 全员锁定同样视为“上一局状态”：重连即复位，并让其余玩家解除锁定
          //（客户端若仍需锁定，会在 onopen 中按 lockAllSelf 重新上报恢复）
          if (p.lockAll) {
            p.lockAll = false;
            broadcast3d({ type: 'lockall', on: false });
          }
          // 页面刷新（F5/强制刷新）属于“重新开局”：坐标复位到出生点。
          // 否则会带着刷新前的坐标回到场上（常正好叠在别人身上）→ 表现为“刷新后没有出现在出生点”。
          // 只认 fresh 标记：同一页面内的自动重连（网络抖动）保持原位，不打断对局。
          if (m.fresh === true) {
            const sp = mazeSpawnPos(p.id);
            if (sp) { p.x = sp.x; p.z = sp.z; }
            p.angle = 0;
          }
          PLAYERS_3D.set(p.id, p);
          if (roomStart == null) roomStart = Date.now(); // 房间首个玩家进入：启动计时
          joined = true;
          ws.send(JSON.stringify(welcomePayload(p)));
          // 重连后把手电筒状态（已复位为开启）同步回本端，避免本端仍停在“关闭”而服务端在耗电/客户端灯不亮
          if (p.ws.readyState === 1) { try { p.ws.send(JSON.stringify({ type: 'flash', id: p.id, on: true })); } catch (e) {} }
          // 禁用手电生效中：重连的非开发者立即被限制（开发者本人豁免）
          if (flashBan.on) {
            if (p.id !== flashBan.by) { p.flash = false; if (p.ws.readyState === 1) { try { p.ws.send(JSON.stringify({ type: 'flashban', on: true })); } catch (e) {} } }
            else if (p.ws.readyState === 1) { try { p.ws.send(JSON.stringify({ type: 'flashban', on: false })); } catch (e) {} }
          }
          // 全员锁定生效中：重连的非锁定者立即被锁定（锁定者本人豁免并保持自己的锁定状态）
          if (Array.from(PLAYERS_3D.values()).some(o => o.lockAll) && !p.lockAll) {
            if (p.ws.readyState === 1) { try { p.ws.send(JSON.stringify({ type: 'lockall', on: true })); } catch (e) {} }
          }
          sendToRoom(p.room, { type: 'join', player: snap3d(p) }, p.id);
          broadcastPlayers3d();
          console.log('[3d rejoin]', p.name, 'total:', PLAYERS_3D.size);
          break;
        }
        // 出生点：入口无障碍区内挑“离其他人最远”的点，避免多人同时进入时叠在一起
        const npSpawn = mazeSpawnPos();
        const np = { id, name, role, room: 'prep', ghost: false, ghostImg: false, x: npSpawn.x, z: npSpawn.z, angle: 0, pitch: 0, escaped: false, enhancedFlash: false, token: token || null, disconnectedAt: 0, flash: true, battery: FLASH_BATTERY_MAX, batteryAt: Date.now(), dead: false, knifeBy: null, knifeUntil: 0, rescueUsed: false, ws };
        // 注意：token 只在 close 时写入 TOKENS_3D，此处不注册，
        // 否则同一 token 的第二个标签页会误当“重连”挤占第一个标签页/在他人视角制造分身。
        PLAYERS_3D.set(id, np);
        if (roomStart == null) roomStart = Date.now(); // 房间首个玩家进入：启动计时
        joined = true;
        if (flashBan.on) {
          if (id !== flashBan.by) { np.flash = false; if (ws.readyState === 1) { try { ws.send(JSON.stringify({ type: 'flashban', on: true })); } catch (e) {} } }
          else if (ws.readyState === 1) { try { ws.send(JSON.stringify({ type: 'flashban', on: false })); } catch (e) {} }
        }
        // 全员锁定生效中：新加入的非锁定者立即被锁定
        if (Array.from(PLAYERS_3D.values()).some(o => o.lockAll) && !np.lockAll) {
          if (ws.readyState === 1) { try { ws.send(JSON.stringify({ type: 'lockall', on: true })); } catch (e) {} }
        }
        // 重发一次 welcome（此刻已把自己算进人数，players 全长即在线总数，保证各端在线人数一致）
        ws.send(JSON.stringify(welcomePayload(np)));
        sendToRoom(np.room, { type: 'join', player: snap3d(np) }, id);
        broadcastPlayers3d();
        console.log('[3d join]', name, 'total:', PLAYERS_3D.size);
        break;
      }
      case 'move': {
        const p = PLAYERS_3D.get(id);
        if (!p || !joined) return;
        // 记录最近一次上报的高度与时刻：供“空中悬停兜底”判定（见下方 2 秒周期任务）。
        // 正常悬停（连跳）客户端 ≤1 秒必有一次 keepalive 上报，所以长期静默 + 人在空中 ⇒ 该端已冻结/卡死。
        p.y = Number(m.y) || BODY_CENTER_Y;
        p.lastMoveAt = Date.now();
        // 位置冻结的四种情况：① 开发者“全员锁定”中的被锁者（非发起者）
        //                    ② 鬼的“变身前摇”那 2 秒 —— 前摇期间无法移动
        //                    ③ 被刀定身（被刀者 3 秒）
        //                    ④ 正在刀人（鬼 3 秒）
        // 都只冻结水平位置，视角（朝向/俯仰）照常同步（被刀者还可开关手电）。
        const lockAllOn = Array.from(PLAYERS_3D.values()).some(o => o.lockAll);
        const inCast = p.transformUntil && Date.now() < p.transformUntil;
        const inKnife = p.knifeBy && Date.now() < p.knifeUntil;
        const inKnifing = !!p.knifing;
        if (inCast || inKnife || inKnifing || (lockAllOn && !p.lockAll)) {
          p.angle = Number(m.angle) || p.angle;
          p.pitch = Number(m.p) || 0;
          sendToRoom(p.room, { type: 'move', id, x: p.x, z: p.z, angle: p.angle, pitch: p.pitch, y: Number(m.y) || BODY_CENTER_Y }, id);
          break;
        }
        p.x = clamp3dMove(Number(m.x)); p.z = clamp3dMove(Number(m.z));
        p.angle = Number(m.angle) || p.angle;
        p.pitch = Number(m.p) || 0;
        sendToRoom(p.room, { type: 'move', id, x: p.x, z: p.z, angle: p.angle, pitch: p.pitch, y: Number(m.y) || BODY_CENTER_Y }, id);
        break;
      }
      case 'flash': {
        const p = PLAYERS_3D.get(id);
        if (!p || !joined) return;
        // 禁用手电：被限制者（非开发者本人）尝试开灯 → 拒绝并保持关闭，私信提示“手电筒已禁用”
        if (m.on && flashBan.on && p.id !== flashBan.by) {
          if (p.ws.readyState === 1) { try { p.ws.send(JSON.stringify({ type: 'flashDenied' })); } catch (e) {} }
          sendToRoom(p.room, { type: 'flash', id, on: false });
          break;
        }
        const now = Date.now();
        batteryOf(p, now); // 先把“当前状态”下的电量推进到此刻
        if (m.on) {
          // 想开灯但电量已不足（降到强制熄灭阈值及以下）→ 拒绝开启并回弹，提示没电
          if (p.battery <= FLASH_FORCE_OFF_AT) {
            if (p.ws.readyState === 1) { try { p.ws.send(JSON.stringify({ type: 'flashDead' })); } catch (e) {} }
            sendToRoom(p.room, { type: 'flash', id, on: false });
            break;
          }
          p.flash = true;
        } else {
          p.flash = false;
        }
        // 广播给包括自己在内的同房间玩家，其他端据此开关”那盏远程灯“，
        // 自己这端也据此保持与服务端一致（离线清理/重连后用 snap3d 的 flash 也能对齐）。
        sendToRoom(p.room, { type: 'flash', id, on: p.flash, enhanced: p.enhancedFlash === true });
        break;
      }
      case 'setenhancedflash': {
        const p = PLAYERS_3D.get(id);
        if (!p || !joined) return;
        // 增强手电（开发者）：开启后服务端电量恒满、永不强制熄灭，并广播 enhanced 供他人渲染增强光
        p.enhancedFlash = !!m.on;
        if (p.enhancedFlash) { p.battery = FLASH_BATTERY_MAX; p.flash = true; }
        sendToRoom(p.room, { type: 'flash', id, on: p.flash, enhanced: p.enhancedFlash === true });
        break;
      }
      case 'ghostfx': {
        // 鬼按 P：开始“变身前摇”。服务端是唯一权威 ——
        //   ① 立刻广播 ghostfx（同房间所有人，含发起者本人，从这一刻一起播 2 秒红光）；
        //   ② 2 秒整时由服务端自己翻图并广播 roleimg。
        // 这样“变身那一瞬间”是服务端统一下发的：所有客户端同一时刻换图，
        // 没有人要多等一个网络往返（之前是客户端自己翻图再通知别人 → 别人总慢半拍）。
        const p = PLAYERS_3D.get(id);
        if (!p || !joined || !p.ghost) return; // 只有鬼能变身；成员发来直接忽略
        const now = Date.now();
        if (p.transformUntil && now < p.transformUntil) return; // 前摇期间不可重复触发
        if (p.knifing) return; // 正在刀人（3 秒定身）期间不可变身
        // 前摇时长按方向区分：伪装成成员 → 变鬼要 2 秒；鬼 → 变回成员只要 1 秒（收起来更快）
        const ms = p.ghostImg ? GHOST_TRANSFORM_BACK_MS : GHOST_TRANSFORM_MS;
        p.transformUntil = now + ms;
        sendToRoom(p.room, { type: 'ghostfx', id: p.id, ms });
        const pid = p.id;
        setTimeout(() => {
          const q = PLAYERS_3D.get(pid);
          if (!q || !q.ghost) return; // 期间身份被清（例如点了结束游戏）→ 不再翻图
          // 形态切换要顺带结算「刀人」技能充能：只有“鬼形态”才累计
          applyGhostForm(q, !q.ghostImg);
          sendToRoom(q.room, { type: 'roleimg', id: q.id, on: q.ghostImg });
          console.log('[3d game] ghost transform', pid, '->', q.ghostImg, 'skill', skillMsOf(q));
        }, ms);
        break;
      }
      case 'setroleimg': {
        // 兼容旧客户端：换图现在完全由服务端在 ghostfx 的前摇结束时决定并广播，
        // 客户端自行切换的请求一律忽略（保留这个 case 只为协议校验与旧页面不报错）。
        break;
      }
      case 'knife': {
        // 鬼刀人（服务端权威判定）：守卫 → 锁定双方 3 秒 → 无人救则被刀者死亡
        const p = PLAYERS_3D.get(id);
        if (!p || !joined) return;
        // 守卫① 鬼身份 + 对局房
        if (!p.ghost || ghostToken !== p.id || p.room !== 'match') return;
        // 守卫② 充能就绪（鬼形态累计 10 秒，服务端确认；「鬼冷却」豁免时无需等待）
        if (!p.ghostNoCd && !skillReady(p)) return;
        // 守卫③ 必须处于鬼形态（前摇未结束/成员形态一律拒绝）
        if (!p.ghostImg) return;
        if (p.transformUntil && Date.now() < p.transformUntil) return; // 变身切换前摇中
        if (p.knifing || activeKnife) return; // 同一时刻只允许一把刀
        const q = PLAYERS_3D.get(String(m.target));
        if (!q || q.id === p.id || q.room !== 'match' || !isLivePlayer(q)) return;
        // 守卫④ 目标是成员、存活、未被刀
        if (q.ghost || q.dead || q.escaped || q.knifeBy) return;
        // 守卫⑤ 距离/体积校验（服务端自己算一遍，不信客户端）
        if (!knifeReachOk(p, q)) return;
        // —— 判定通过：锁定双方 ——
        const now = Date.now();
        activeKnife = { by: p.id, target: q.id, until: now + KNIFE_MS };
        p.knifing = true;
        q.knifeBy = p.id; q.knifeUntil = now + KNIFE_MS;
        // 一次性全场关灯：锁定那一瞬间除鬼外所有人手电强制关闭（可手动重开），全对局仅一次
        if (!lightsOutUsed) {
          lightsOutUsed = true;
          for (const o of PLAYERS_3D.values()) {
            if (o.room === 'match' && isLivePlayer(o) && o.id !== p.id) {
              o.flash = false;
              sendToRoom('match', { type: 'flash', id: o.id, on: false });
            }
          }
          sendToRoom('match', { type: 'lightsoff' });
        }
        sendToRoom('match', { type: 'knifed', by: p.id, target: q.id, ms: KNIFE_MS });
        // 被刀者私信：红屏 + 倒计时
        if (q.ws.readyState === 1) { try { q.ws.send(JSON.stringify({ type: 'youAreKnifed', ms: KNIFE_MS, by: p.id })); } catch (e) {} }
        clearTimeout(knifeTimer);
        knifeTimer = setTimeout(() => {
          knifeTimer = null;
          if (!activeKnife) return; // 已被救助/中止
          const k = activeKnife;
          const by = PLAYERS_3D.get(k.by);
          const tg = PLAYERS_3D.get(k.target);
          activeKnife = null;
          // 异常兜底：本局已结束 / 鬼或被刀者已不在对局房 / 已死亡 → 不判死亡、不误加打断
          const byOk = by && by.room === 'match' && isLivePlayer(by);
          const tgOk = tg && tg.room === 'match' && isLivePlayer(tg) && !tg.dead && tg.knifeBy === k.by;
          if (!byOk || !tgOk) {
            if (by) by.knifing = false;
            if (tg) { tg.knifeBy = null; tg.knifeUntil = 0; }
            if (gamePhase === 'playing') sendToRoom('match', { type: 'knifeEnd', by: k.by, target: k.target, reason: 'gone' });
            return;
          }
          // —— 5 秒无人救：被刀者死亡 ——
          by.knifing = false;
          tg.knifeBy = null; tg.knifeUntil = 0;
          tg.dead = true;
          consecutiveFails = 0; // 成功击杀：连续失败计数清零
          if (tg.ws.readyState === 1) { try { tg.ws.send(JSON.stringify({ type: 'youDied' })); } catch (e) {} }
          sendToRoom('match', { type: 'knifeEnd', by: k.by, target: k.target, reason: 'killed' });
          // 死亡 → 先离场（谁也看不见、也刀不到他），5 秒后回预备房等待本局结束
          moveToLimbo(tg);
          resetGhostSkill(by); // 刀人结束：鬼充能清零，需重新累计 10 秒
          console.log('[3d knife] killed', tg.name, 'by', by.name);
          checkMatchEnd(); // 最后一名成员也被击杀 → 鬼获胜，本局结束
        }, KNIFE_MS);
        console.log('[3d knife]', p.name, 'knifed', q.name);
        break;
      }
      case 'rescue': {
        // 成员救助被刀者（服务端权威判定）：靠近被刀者或鬼 → 打断刀人
        const p = PLAYERS_3D.get(id);
        if (!p || !joined) return;
        // 救助者守卫：成员 / 存活 / 对局房 / 非被刀者 / 非鬼 / 每局限一次
        if (p.ghost || p.dead || p.escaped || p.room !== 'match' || p.knifeBy) return;
        if (p.rescueUsed) return;
        const k = activeKnife;
        if (!k) return;
        const by = PLAYERS_3D.get(k.by);
        const tg = PLAYERS_3D.get(k.target);
        if (!by || !tg) return;
        if (tg.dead || !tg.knifeBy || tg.knifeBy !== k.by) return;
        if (p.id === by.id || p.id === tg.id) return;
        // 距离校验：靠近被刀者或鬼（服务端自己算）
        if (!knifeReachOk(p, tg) && !knifeReachOk(p, by)) return;
        // —— 判定通过：打断刀人 ——
        p.rescueUsed = true; // 每人每局限一次
        activeKnife = null;
        if (knifeTimer) { clearTimeout(knifeTimer); knifeTimer = null; }
        by.knifing = false;
        tg.knifeBy = null; tg.knifeUntil = 0;
        sendToRoom('match', { type: 'knifeEnd', by: by.id, target: tg.id, reason: 'rescued' });
        consecutiveFails++; // 被救 = 打断一次
        resetGhostSkill(by); // 刀人结束：鬼充能清零
        console.log('[3d rescue]', p.name, 'saved', tg.name, 'fails:', consecutiveFails);
        if (consecutiveFails >= 3) endMatchAsMembersWin('3rescues'); // 连续 3 次打断且期间无击杀 → 成员胜利
        break;
      }
      case 'setflashban': {
        // 禁用手电（开发者专用）：开启后强制除开发者本人外的所有人关灯且不可再开；关闭则解除限制
        const p = PLAYERS_3D.get(id);
        if (!p || !joined || !isDev(p)) return;
        flashBan.on = !!m.on;
        flashBan.by = flashBan.on ? p.id : null;
        for (const o of PLAYERS_3D.values()) {
          if (!isLivePlayer(o)) continue;
          if (o.id === flashBan.by) {
            // 开发者本人豁免：解除禁用状态
            if (o.ws.readyState === 1) { try { o.ws.send(JSON.stringify({ type: 'flashban', on: false })); } catch (e) {} }
          } else if (flashBan.on) {
            o.flash = false; // 立即强制关灯
            if (o.ws.readyState === 1) { try { o.ws.send(JSON.stringify({ type: 'flashban', on: true })); } catch (e) {} }
            broadcast3d({ type: 'flash', id: o.id, on: false });
          } else {
            if (o.ws.readyState === 1) { try { o.ws.send(JSON.stringify({ type: 'flashban', on: false })); } catch (e) {} }
          }
        }
        break;
      }
      case 'setlockall': {
        // 全员锁定（开发者专用）：开启后除开发者本人外的所有人无法移动/跳跃（视角仍可自由翻转）
        const p = PLAYERS_3D.get(id);
        if (!p || !joined || !isDev(p)) return;
        p.lockAll = !!m.on;
        // 广播给其余玩家（发起者本地由面板自行显示提示），让他们进入/解除锁定状态
        broadcast3d({ type: 'lockall', on: p.lockAll }, id);
        console.log('[3d lockall]', p.name, p.lockAll ? 'on' : 'off');
        break;
      }
      case 'setghostcd': {
        // 鬼冷却豁免（开发者专用）：只对本端玩家生效。开启后若该玩家是鬼，刀人不再要求 10 秒充能；
        // 若该玩家不是鬼，此标记无任何效果（刀人守卫仍要求鬼身份）。
        const p = PLAYERS_3D.get(id);
        if (!p || !joined || !isDev(p)) return;
        p.ghostNoCd = !!m.on;
        console.log('[3d ghostnoCd]', p.name, p.ghostNoCd ? 'on' : 'off');
        break;
      }
      case 'devlogin': {
        // 开发者鉴权：密码与服务端约定一致后，赋予“开发者指令”权限
        const p = PLAYERS_3D.get(id);
        if (!p || !joined) return;
        const pwd = String(m.pwd || '').toLowerCase();
        const devOk = !!pwd && ((DEV_PASSWORD && pwd === DEV_PASSWORD) || (DEV_ROLE_PASSWORD && pwd === DEV_ROLE_PASSWORD));
        if (devOk) p.dev = true;
        try { ws.send(JSON.stringify({ type: devOk ? 'devloginOk' : 'devloginFail' })); } catch (e) {}
        break;
      }
      case 'revealall': {
        // 全员爆点（开发者专用）：5 秒内让狼人（鬼）看到所有人的位置（前端表现为“人穿墙发光”）
        const p = PLAYERS_3D.get(id);
        if (!p || !joined || !isDev(p)) return;
        broadcast3d({ type: 'revealAll', ms: REVEAL_ALL_MS, by: p.id });
        // 被报点的人要收到提示：对局房里除鬼以外的每个人（鬼是看的人，不提示）
        for (const o of PLAYERS_3D.values()) {
          if (o.room === 'match' && isLivePlayer(o) && !o.ghost) {
            if (o.ws.readyState === 1) { try { o.ws.send(JSON.stringify({ type: 'exposed', ms: REVEAL_ALL_MS })); } catch (e) {} }
          }
        }
        console.log('[3d revealall] by', p.name);
        break;
      }
      case 'revealone': {
        // 单爆·报点（开发者专用）：只把某一个人的位置报给狼人（其他人不亮）
        const p = PLAYERS_3D.get(id);
        if (!p || !joined || !isDev(p)) return;
        const q = PLAYERS_3D.get(String(m.target));
        if (!q || !isLivePlayer(q)) return;
        broadcast3d({ type: 'revealOne', target: q.id, ms: REVEAL_ALL_MS, by: p.id });
        // 被点名的那个人收到“位置已暴露”提示（鬼本人不需要提示）
        if (!q.ghost && q.ws.readyState === 1) {
          try { q.ws.send(JSON.stringify({ type: 'exposed', ms: REVEAL_ALL_MS })); } catch (e) {}
        }
        console.log('[3d revealone]', q.name, 'by', p.name);
        break;
      }
      case 'playerlist': {
        // 单踢/单爆用的在线名单（开发者专用）：跨房间下发，局内局外的人都看得到
        const p = PLAYERS_3D.get(id);
        if (!p || !joined || !isDev(p)) return;
        const list = livePlayers().map(o => ({ id: o.id, name: o.name, room: o.room }));
        if (p.ws.readyState === 1) { try { p.ws.send(JSON.stringify({ type: 'playerlist', players: list })); } catch (e) {} }
        break;
      }
      case 'kickone': {
        // 单踢（开发者专用）：把某一个人踢出服务器并拉黑 10 分钟（按 token，不按 IP ——
        // 同局域网一起玩时封 IP 会误伤到自己人）。客户端本地缓存同样记 10 分钟，刷新也进不来。
        const p = PLAYERS_3D.get(id);
        if (!p || !joined || !isDev(p)) return;
        const q = PLAYERS_3D.get(String(m.target));
        if (!q || q.id === p.id) return; // 不存在 / 不能踢自己
        kickPlayerOut(q, Date.now() + KICK_ONE_MS);
        break;
      }
      case 'pullall': {
        // 吸附：把所有人传送到发起者当前坐标（开发者模式专用）
        const p = PLAYERS_3D.get(id);
        if (!p || !joined || !isDev(p)) return;
        const tx = clamp3dMove(Number(m.x));
        const tz = clamp3dMove(Number(m.z));
        for (const o of PLAYERS_3D.values()) {
          if (o.id === id || !isLivePlayer(o)) continue; // 只吸附真实在线的人，避免把幽灵记录传送到场内
          o.x = tx; o.z = tz; o.angle = p.angle;
          broadcast3d({ type: 'pull', id: o.id, x: tx, z: tz, angle: o.angle });
        }
        console.log('[3d pull]', p.name, 'pulled everyone to', tx, tz);
        break;
      }
      case 'kickall': {
        // 踢掉所有人（开发者模式专用，相当于恢复出厂设置）：
        // 清空所有玩家数据/断线档案/对战状态，通知所有在线连接回主界面，然后断开。
        if (!joined) return;
        const kicker = PLAYERS_3D.get(id);
        if (!kicker || !isDev(kicker)) return;
        // 清场前中止一切刀人（不判死亡、不误加打断）
        abortKnife('gone');
        // 通知每个在线连接“被重置”，让前端回到选角色主界面并清空本地数据
        // 同时把这些连接的 token 记为“已被踢”，10 秒内禁止其自动重连回来，做到真正踢干净
        const now2 = Date.now();
        const kickedIds = [];
        for (const o of PLAYERS_3D.values()) {
          kickedIds.push(o.id);
          if (o.token) KICKED_TOKENS.set(o.token, now2 + 10000);
          if (o.ws.readyState === 1) { try { o.ws.send(JSON.stringify({ type: 'kickout' })); } catch (e) {} }
        }
        // 清空全部玩家与断线档案，再统一断开
        const all = Array.from(PLAYERS_3D.values());
        PLAYERS_3D.clear();
        TOKENS_3D.clear();
        for (const o of all) {
          if (o.ws.readyState === 1) { try { o.ws.close(1000, 'kicked'); } catch (e) {} }
        }
        console.log('[3d reset] kick all, total kicked:', all.length);
        break;
      }
      // ---------- 对局控制（开发者专用）----------
      case 'startgame': {
        // 开始游戏：把当前预备房的全体在线玩家“打包”进对局房（重置出生点、随机分配鬼），
        // 随后分两阶段下发：先 5s 倒计时提示，再 5s 后公布身份（真实分配，服务端权威）。
        const sp = PLAYERS_3D.get(id);
        if (!sp || !joined || !isDev(sp)) return;
        if (gamePhase === 'playing') break; // 已在局内，忽略重复触发
        // 只有预备房里的人进本局：正在“5 秒过渡房”里的（刚被击杀/刚逃出）不能被硬拉回对局
        const live = livePlayers().filter(o => o.room === 'prep');
        if (live.length === 0) break; // 无人可开局
        if (activeKnife) abortKnife('gone'); // 兜底：上一局残留的刀人强制中止
        gamePhase = 'playing';
        gameStartTs = Date.now();
        // 每局只允许一次全场关灯；连续打断计数也按局重置（“连续 3 次”仅本局内有效）
        lightsOutUsed = false;
        consecutiveFails = 0;
        // 随机抽一名“鬼”，其余为“成员”；全员进入对局房、重置出生点
        const gi = Math.floor(Math.random() * live.length);
        ghostToken = live[gi].id;
        live.forEach((o, idx) => {
          o.room = 'match';
          o.ghost = (idx === gi);
          o.ghostImg = false;
          // 技能充能也归零（初始是“成员形态”，不涨条；变身为鬼形态后才开始累计）
          o.skillMs = 0; o.skillSince = 0;
          if (o.skillTimer) { clearTimeout(o.skillTimer); o.skillTimer = null; }
          // 刀人·救助状态也归零（新对局：全员存活、未被刀、救助次数未用）
          o.dead = false; o.knifeBy = null; o.knifeUntil = 0; o.knifing = false; o.rescueUsed = false;
          o.transformUntil = 0;
          const np = mazeSpawnPos(o.id);
          o.x = np.x; o.z = np.z; o.angle = 0;
          o.battery = FLASH_BATTERY_MAX; o.batteryAt = Date.now(); o.flash = true; o.escaped = false;
        });
        // 仅向对局房广播开局（预备房的中途加入者看不到、不进入本局）
        sendToRoom('match', { type: 'gamestart', gameStart: gameStartTs, phase: gamePhase, players: live.map(snap3d), online: live.length });
        // 5s 后提示“正在随机分配鬼”
        clearTimeout(rollTimer);
        rollTimer = setTimeout(() => { sendToRoom('match', { type: 'rollghost' }); }, 5000);
        // 再 5s（共 10s）后下发真实身份
        clearTimeout(allocTimer);
        allocTimer = setTimeout(() => {
          sendToRoom('match', { type: 'rolealloc', ghost: ghostToken });
          // 身份公布 = 对局真正开始：鬼的「刀人」技能从这一刻起在“成员形态”下开始累计
          const g0 = PLAYERS_3D.get(ghostToken);
          // 身份公布：充能归零。此刻是“成员形态”所以不累计 —— 鬼必须自己变身成鬼形态才涨条
          if (g0) { g0.skillMs = 0; g0.skillSince = 0; pushSkill(g0); }
        }, 10000);
        console.log('[3d game] start, players:', live.length, 'ghost:', ghostToken);
        break;
      }
      case 'endgame': {
        // 结束游戏：对局房全员回到预备房（连接不中断），等待下一局
        const ep = PLAYERS_3D.get(id);
        if (!ep || !joined || !isDev(ep)) return;
        if (gamePhase !== 'playing') break;
        gamePhase = 'prep';
        gameStartTs = null;
        clearTimeout(rollTimer); clearTimeout(allocTimer);
        abortKnife('gone'); // 本局结束：立即中止刀人（不判死亡、不误加打断）
        for (const o of PLAYERS_3D.values()) {
          // 'limbo' 也要一起收：那是“已阵亡/已逃脱、正在等 5 秒过渡”的人，
          // 开发者手动结束时他们必须立刻回预备房，不能让过渡定时器稍后再把他们扔进去
          if (o.room === 'match' || o.room === 'limbo') {
            const lt = LIMBO_TIMERS.get(o.id);
            if (lt) { clearTimeout(lt); LIMBO_TIMERS.delete(o.id); }
            o.room = 'prep'; o.ghost = false; o.ghostImg = false;
            o.skillMs = 0; o.skillSince = 0; // 技能充能清空
            if (o.skillTimer) { clearTimeout(o.skillTimer); o.skillTimer = null; }
            o.dead = false; o.escaped = false; o.knifeBy = null; o.knifeUntil = 0; o.knifing = false; o.rescueUsed = false;
            o.transformUntil = 0;
          }
        }
        ghostToken = null;
        broadcast3d({ type: 'gameend', phase: gamePhase }); // 全量（含预备房中途加入者）
        broadcastPlayers3d(); // 让全员同步最新 phase 与同房间名单
        console.log('[3d game] end');
        break;
      }
      case 'chat': {
        const p = PLAYERS_3D.get(id);
        if (!p || !joined) return;
        const text = String(m.text || '').slice(0, 200);
        if (!text.trim()) return;
        sendToRoom(p.room, { type: 'chat', name: p.name, text }, id);
        break;
      }
      case 'escape': {
        const p = PLAYERS_3D.get(id);
        if (!p || !joined || p.escaped) return;
        // 只有对局房中的存活成员能逃脱；鬼 / 已死 / 被刀定身者不可
        if (p.room !== 'match' || p.ghost || p.dead || p.knifeBy) return;
        // 逃出的那一刻就立刻离场：既不在预备房也不在对局房（5 秒后才回预备房）。
        // 否则这 5 秒里他还在场上跑动、还可能被鬼刀中 → “逃出去了又被抓”的状态错乱。
        p.escaped = true;
        sendToRoom(p.room, { type: 'escape', name: p.name });
        console.log('[3d escape]', p.name);
        moveToLimbo(p); // 个人胜利：自己离场，其他人继续不受影响
        checkMatchEnd(); // 最后一名成员也逃出去了 → 鬼获胜，本局结束
        broadcastPlayers3d();
        break;
      }
      case 'jump': {
        // 转发跳跃给其他人，让远端身体也能看到起跳
        const p = PLAYERS_3D.get(id);
        if (!p || !joined) return;
        // 全员锁定生效中：被锁者（非发起者）跳跃被禁止
        if (Array.from(PLAYERS_3D.values()).some(o => o.lockAll) && !p.lockAll) break;
        // 刀人定身：被刀者 / 正在刀人的鬼都不能跳跃
        if (p.knifeBy && Date.now() < p.knifeUntil) break;
        if (p.knifing) break;
        sendToRoom(p.room, { type: 'jump', id }, id);
        break;
      }
    }
    } catch (e) {
      // 单条消息出错绝不允许把整个进程打崩（一次未捕获异常会让全体玩家掉线）
      console.error('[3d msg] handler error:', (m && m.type) || '?', e);
    }
  });

  ws.on('close', () => {
    // 优先按本连接的 id 查档案；万一未命中（如历史遗留的 id 未对齐），按 socket 归属兜底查找，
    // 避免玩家记录永久残留在 PLAYERS_3D（对局状态永不自动复位）。
    let p = PLAYERS_3D.get(id) || null;
    if (!p) p = Array.from(PLAYERS_3D.values()).find(o => o.ws === ws) || null;
    // 若该连接已被断线重连替换（p.ws 现在是新连接），忽略旧连接的关闭，避免误删/残留旧身体
    if (!p || p.ws !== ws) return;
    p.disconnectedAt = Date.now();
    // 被刀者或鬼掉线：立即中止刀人（不判死亡、不误加打断）
    if (activeKnife && (activeKnife.by === p.id || activeKnife.target === p.id)) abortKnife('gone');
    // 保存断线档案，供该玩家用同一 token 重连恢复身份（60 秒内）
    if (p.token) TOKENS_3D.set(p.token, p);
    const wasLockAll = !!p.lockAll;
    PLAYERS_3D.delete(p.id);
    if (PLAYERS_3D.size === 0) roomStart = null; // 房间空了：计时归零，下次首个进入重新计时
    sendToRoom(p.room, { type: 'leave', id: p.id });
    // 锁定者离场且场上再无其他锁定者 → 全员解除锁定（其余玩家的锁定提示随之消失）
    if (wasLockAll && !Array.from(PLAYERS_3D.values()).some(o => o.lockAll)) {
      broadcast3d({ type: 'lockall', on: false });
    }
    broadcastPlayers3d();
    console.log('[3d leave]', p.name, 'remain:', PLAYERS_3D.size);
  });
  ws.on('error', () => {});
});

// 手电筒电量：每 250ms 按“真实时间”推进并下发权威电量给对应玩家；
// 触发强制熄灭（电量降到阈值）时广播关闭该玩家的远程灯，并私信本端“手电筒没电了”。
setInterval(() => {
  const now = Date.now();
  for (const p of PLAYERS_3D.values()) {
    const forced = batteryOf(p, now);
    if (p.ws.readyState === 1) {
      try { p.ws.send(JSON.stringify({ type: 'battery', battery: Math.round(p.battery * 10) / 10 })); } catch (e) {}
      if (forced) { try { p.ws.send(JSON.stringify({ type: 'flashDead' })); } catch (e) {} }
    }
    if (forced) sendToRoom(p.room, { type: 'flash', id: p.id, on: false });
  }
}, 250);

// 玩家心跳：定期清理断线连接（缩短周期，坏连接尽快移除，避免“还在线/进不去”）；并清理超时未重连的档案
setInterval(() => {
  const now = Date.now();
  for (const p of PLAYERS_3D.values()) {
    // 断线角色兜底清理：socket 已关闭/正在关闭（close 事件可能因代理断连、进程异常等原因收不到），
    // 权威地按“离场”处理，避免残留记录长期挂在名单里。
    if (!p.ws || p.ws.readyState !== 1) {
      // 被刀者或鬼掉线：立即中止刀人（不判死亡、不误加打断）
      if (activeKnife && (activeKnife.by === p.id || activeKnife.target === p.id)) abortKnife('gone');
      const wasLockAll = !!p.lockAll;
      PLAYERS_3D.delete(p.id);
      if (PLAYERS_3D.size === 0) roomStart = null; // 房间空了：计时归零，下次首个进入重新计时
      p.disconnectedAt = now;
      if (p.token) TOKENS_3D.set(p.token, p);
      sendToRoom(p.room, { type: 'leave', id: p.id });
      // 锁定者离场且场上再无其他锁定者 → 全员解除锁定
      if (wasLockAll && !Array.from(PLAYERS_3D.values()).some(o => o.lockAll)) {
        broadcast3d({ type: 'lockall', on: false });
      }
      broadcastPlayers3d();
      console.log('[3d janitor] drop dead socket:', p.name, 'remain:', PLAYERS_3D.size);
      continue;
    }
    if (!p.ws.isAlive) { p.ws.terminate(); continue; }
    p.ws.isAlive = false;
    p.ws.pingAt = now; // 记录 ping 发出时刻，供 isLivePlayer 的宽限期判定
    p.ws.ping();
  }
  for (const [t, p] of TOKENS_3D) {
    if (now - p.disconnectedAt > 60000) TOKENS_3D.delete(t);
  }
  // 顺带清理已过期的“被踢”黑名单，避免长期运行时该 Map 单调增长（禁重连窗口仍为 10 秒）
  for (const [t, exp] of KICKED_TOKENS) {
    if (now >= exp) KICKED_TOKENS.delete(t);
  }
}, 10000);

// 周期全量对账：每 3 秒广播一次完整玩家名单，让所有客户端定期同步。
// 治愈“A 看到的、B 却看不到/位置错”的不对称问题（靠 join/welcome 增量快照易漏发，
// 周期性全量名单让前端 syncPlayers 自动补齐缺失立牌并校准位置）。
setInterval(() => {
  if (PLAYERS_3D.size === 0) return;
  // 悬空兜底：服务端权威地把“已静默却停在空中”的人放回地面。
  // 客户端在切标签页/失焦时会立刻落地并上报（见 index.html landImmediately），这是主防线；
  // 这里再做一层服务端保险：正常连跳悬停 ≤1 秒必有一次 keepalive 上报，
  // 因此“高度明显离地 + 超过 3 秒没有任何 move”只可能是该端冻结/卡死（后台标签被挂起、旧缓存页面等），
  // 此时由服务端强制落地并广播，保证任何情况下他人视角都不会看到永久悬浮的人。
  const nowMs = Date.now();
  for (const p of PLAYERS_3D.values()) {
    if (p.y > AIRBORNE_Y && nowMs - (p.lastMoveAt || 0) > 3000) {
      p.y = BODY_CENTER_Y;
      sendToRoom(p.room, { type: 'move', id: p.id, x: p.x, z: p.z, angle: p.angle, pitch: p.pitch, y: BODY_CENTER_Y });
    }
  }
  broadcastPlayers3d();
}, 2000);

// 强制监听 IPv4 0.0.0.0，确保宝塔用 127.0.0.1 反向代理也能连通（避免 tcp6-only 导致的 502）
server.listen(PORT, '0.0.0.0', () => {
  console.log('—— 3D 迷宫（独立后端） ——');
  console.log(`服务器已启动:  http://localhost:${PORT}`);
});