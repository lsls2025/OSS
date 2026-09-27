// ============ 3D 迷宫 独立后端（与米乎星球彻底拆分） ============
// 端口：1008（对外经反向代理暴露）
// 职责：① 提供迷宫前端静态文件 ② 提供 /ws3d 多人 WebSocket
const http = require('http');
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const WebSocketServer = require('ws').WebSocketServer;

// gzip 压缩缓存：key = 文件路径，内容逐字节比对，一旦变化即重压，避免部署后用到陈旧压缩版
const gzipCache = new Map();

const PORT = process.env.PORT || XXXX;
const PUBLIC_DIR = __dirname; // 直接服务前端文件所在目录（index.html / assets）

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.webp': 'image/webp',
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
const CELL_SIZE_WORLD = 1.5;   // 与前端格子尺寸保持一致（用于距离换算）
const MAX_HP = 100;            // 玩家初始/满血量
const MED_USE_TIME = 5;        // 血包使用等待秒数（期间移速 -20%）
const MED_HEAL_HP = 75;        // 血包单次回复血量
const BOSS_HP = 5000;          // Boss 模式：Boss 血量（体型 2 倍由前端渲染）
const BOSS_BULLETS = 999999;   // Boss 模式：强制武器的备弹（近似无限，弹夹打空仍需按 R 换弹）
const REVIVE_TIME = 5;         // Boss 模式：救助倒地队友的倒计时（秒）
const REVIVE_RANGE = 3.5;      // Boss 模式：救助有效距离（格坐标，与客户端判定一致）
// 清理一次救助频道（救助者/被救者双侧状态 + 定时器）
function cleanupRevive(reviver, target) {
  if (reviver.reviveTimer) { clearTimeout(reviver.reviveTimer); reviver.reviveTimer = null; }
  if (reviver.reviving === target.id) reviver.reviving = null;
  if (target.beingRevivedBy === reviver.id) target.beingRevivedBy = null;
}
// 全局清空所有救助状态（开新局/结算/返回迷宫时调用，防止跨局残留）
function cleanupAllRevives() {
  for (const o of PLAYERS_3D.values()) {
    if (o.reviveTimer) { clearTimeout(o.reviveTimer); o.reviveTimer = null; }
    o.reviving = null; o.beingRevivedBy = null;
  }
}
const ATTACK_DAMAGE = 10;      // 每次攻击伤害（空手/旧客户端兜底）
// 武器伤害表：按攻击消息携带的 weapon 决定单发伤害（AK47=24 / AWM=110 / 沙漠之鹰=48~50 随机 / 空手=10）
const WEAPON_DAMAGE = { 'AK47': 24, 'AWM': 110, '沙漠之鹰': 49, 'fist': 10 }; // 沙鹰表值仅均值参考，实际在 weaponDamage 随机
function weaponDamage(weapon) {
  if (weapon === '沙漠之鹰') return 48 + Math.floor(Math.random() * 3); // 手枪削弱：单发 48/49/50 随机
  return WEAPON_DAMAGE[weapon] != null ? WEAPON_DAMAGE[weapon] : ATTACK_DAMAGE;
}
// 攻击距离表（世界单位，1 格 = CELL_SIZE_WORLD）：按武器类型区分，前后端必须完全一致。
// 历史教训：曾把全局 ATTACK_RANGE 一刀切换成 12.0，导致近战变成超远大刀、狙击却只能贴脸 —— 必须按武器区分。
const WEAPON_RANGE = {
  'fist':      5.0,   // 空手/近战：≈3.3 格（旧值 2.6≈1.7 格，实测“必须贴着人才能打”，已加长）
  '沙漠之鹰':  18.0,   // 手枪：12 格
  'AK47':     46.0,   // 步枪：≈30.7 格（上限 30 格之外）
  'AWM':      76.0,   // 狙击枪：≈50.7 格（上限 50 格之外）
};
const DEFAULT_RANGE = 12.0;    // 未知/缺省武器兜底
function weaponRange(weapon) { return WEAPON_RANGE[weapon] != null ? WEAPON_RANGE[weapon] : DEFAULT_RANGE; }
const ATTACK_RANGE = DEFAULT_RANGE; // 保留旧名给未带武器信息的调用兜底
const ATTACK_CD = 450;         // 攻击间隔（毫秒），限制连击
const RESPAWN_CD = 5000;       // 死亡后复活冷却（毫秒）
const ATTACK_RANGE_GRID = ATTACK_RANGE / CELL_SIZE_WORLD;
// 命中判定半径（格坐标）：玩家身体不是“点”，而是有体积的碰撞圆。
// 与前端 PLAYER_RADIUS(=0.25 世界单位) 保持一致，命中距离 = 攻击距离 + 该半径，
// 否则攻击只算到几何中心点，角色会被当成纸片/难以命中。
const HIT_RADIUS_GRID = 0.25 / CELL_SIZE_WORLD;

const PLAYERS_3D = new Map(); // id -> { id, name, role, x, z, angle, hp, lastAttack, respawnAt, team, deathCount, room, ws }
// 房间模型（参考“暗黑迷宫”机制）：每个玩家属于一个房间，只有同房间的人之间才互相可见/同步。
//   'prep'  预备房：默认房间。未开局时所有人都在这里；开局后新进入的人也落这里（看不到对局、无敌人、无毒圈）。
//   'match' 对局房：开发者“开始游戏”时，把预备房里的全体在线玩家打包送进来；对局内容（毒圈/对抗）只在这里生效。
// “开始/结束游戏”切换 gamePhase：'prep' 空闲 | 'playing' 对局中。
let gameOn = false; // 是否已“开始游戏”（= gamePhase==='playing'）：未开始全场不可攻击；由开发者面板“开始游戏/结束游戏”切换
let gamePhase = 'prep'; // 'prep' 预备房空闲 | 'playing' 对局中（服务端权威，随 welcome/players 下发）
// 当前对局模式（由开发者面板两个开始按钮决定）：
//  null    = 未开局/结束游戏
//  'royale' = 系统1·吃鸡：有毒圈，死亡即淘汰回预备房，最后一名存活者胜利并结束本局
//  'respawn'= 系统2·无圈：无毒圈，死亡保留枪、清空弹夹恢复原厂子弹、满血重新跳伞（纯混战，无胜负，开发者手动结束）
let gameMode = null;
let noChute = false; // 开发者面板“无需跳伞”：勾选后无圈系统全员地面随机复活（不跳伞），取消恢复跳伞
// 开发者面板“全局参数”（服务端权威，开启后对所有玩家生效）：穿墙/连跳/透视由各客户端本地机制消费，
// 无限容量在服务端弹药结算处豁免。任何变更通过 devGlobal 广播全场，客户端同步勾选状态与本地行为。
const GLOBAL_DEV = { noclip: false, bhop: false, xray: false, unlimited: false };
function globalFlagsPayload() {
  return { type: 'devGlobal', noChute, noclip: GLOBAL_DEV.noclip, bhop: GLOBAL_DEV.bhop, xray: GLOBAL_DEV.xray, unlimited: GLOBAL_DEV.unlimited };
}
const ROOM_PREP = 'prep', ROOM_MATCH = 'match';
const PONG_GRACE_MS = 2000;   // 心跳 ping 发出后未收到 pong 的宽限期（仅用于存活判定，不影响断开逻辑）
const TOKENS_3D = new Map(); // token -> 断线玩家的档案，用于断线重连恢复身份/击杀数
const wss3d = new WebSocketServer({ server, path: '/ws3d' });

// 开发者鉴权：密码与前端约定一致；只有通过 devlogin 校验的玩家（p.dev=true）才能触发开发者指令
const DEV_PASSWORD = '123'; // 游戏内开发者面板密码（字母大小写均可，统一转小写比对）；“开发者头像”角色卡密码 LSnb666 与之隔离，不会触发开发者授权
function isDev(p) { return !!(p && p.dev); }

// 前端构建号（与 index.html 的 BUILD、three 静态 import 的 ?v= 三处联动）：
// 用于识别“浏览器在跑旧缓存页面”，并提示前端强制刷新一次（只提示不踢线）。
const ASSET_VER = 20;

// ---------- 跳伞开局参数（与前端保持一致） ----------
const PARACHUTE_Y = 150;        // 高空出生高度（世界单位）：开始游戏后全员传送到该高度跳伞
const PARACHUTE_DESCENT = 14;   // 跳伞下落速度（世界单位/秒）：服务端权威推进 + 客户端本地动画

// ---------- 毒圈（吃鸡安全区）参数与状态 ----------
// 节奏：开局 1 分钟后开始缩圈 → 之后每 30 秒收一次 → 直到缩成最小安全区（圈外每秒扣 5 血）。
const ZONE_CFG = {
  waitMs: 60000,        // 开局后先等 1 分钟再开始收缩
  intervalMs: 30000,    // 之后每 30 秒收缩一次
  startRadius: 65,      // 初始安全区半径（世界单位，恰好覆到地图最外缘角落），1 分钟后才开始往内缩
  finalRadius: 8,       // 最终最小安全区半径
  steps: 16,            // 收圈次数：(65-8)/16=3.5625/次；60s + 16×30s ≈ 9 分钟收到底
  centerX: 44.5, centerZ: 44.5, // 安全区中心（世界坐标，地图中心）
  dmgPerSec: 5,         // 圈外每秒伤害
  deathRespawn: 2000,   // 被毒圈淘汰后复活间隔（毫秒）
};
// 下一阶段白圈半径：当前安全区再收一次后的半径（红圈=当前，白圈=下一次落位；二者中心相同本作固定在地图中点）
function zoneStep() { return (ZONE_CFG.startRadius - ZONE_CFG.finalRadius) / ZONE_CFG.steps; }
function nextTargetFrom(r) { return Math.max(ZONE_CFG.finalRadius, r - zoneStep()); }
// 状态：radius=当前（动画中实时变化的）红圈半径；target=白圈目标（本阶段要缩到的位置）
const zone = { active: false, centerX: ZONE_CFG.centerX, centerZ: ZONE_CFG.centerZ, radius: ZONE_CFG.startRadius, target: ZONE_CFG.startRadius, shrinkCount: 0, nextAt: 0, animating: false };
let zoneWaitTimer = null, zoneShrinkTimer = null;
const ZONE_TICK = 250; // 缩圈动画推进粒度（毫秒）
function clearZoneTimers() {
  if (zoneWaitTimer) { clearTimeout(zoneWaitTimer); zoneWaitTimer = null; }
  if (zoneShrinkTimer) { clearInterval(zoneShrinkTimer); zoneShrinkTimer = null; }
  zone.animating = false;
}
// 下一阶段（下一次）缩圈倒计时（秒）推给前端显示；未激活/已缩到底时返回 null
function zoneNextInSec() {
  if (!zone.active || !zone.nextAt) return null;
  return Math.max(0, Math.ceil((zone.nextAt - Date.now()) / 1000));
}
function broadcastZone() {
  // 毒圈只属于对局房：只下发给对局房的玩家（预备房的人不显示毒圈）
  broadcastRoom(ROOM_MATCH, { type: 'zone', active: zone.active, cx: zone.centerX, cz: zone.centerZ, radius: Math.round(zone.radius * 100) / 100, nr: Math.round(zone.target * 100) / 100, nextIn: zoneNextInSec(), shrinking: zone.animating });
}
function sendZoneTo(p) {
  if (!p || p.ws.readyState !== 1) return;
  if (roomOf(p) !== ROOM_MATCH) return; // 预备房不发毒圈状态
  try { p.ws.send(JSON.stringify({ type: 'zone', active: zone.active, cx: zone.centerX, cz: zone.centerZ, radius: Math.round(zone.radius * 100) / 100, nr: Math.round(zone.target * 100) / 100, nextIn: zoneNextInSec(), shrinking: zone.animating })); } catch (e) {}
}
// 平滑缩圈动画：radius 从 animFrom 线性滑向 animTo（耗时 intervalMs），期间判定/伤害跟随实时 radius（真有效）。
function beginShrinkAnim() {
  zone.animating = true;
  const animFrom = zone.radius;
  const animTo = zone.target;
  const animStart = Date.now();
  const animDur = ZONE_CFG.intervalMs;
  zone.nextAt = animStart + animDur; // 本次缩圈“到位”时刻 = 倒计时终点
  clearInterval(zoneShrinkTimer);
  zoneShrinkTimer = setInterval(() => {
    const t = (Date.now() - animStart) / animDur;
    if (t >= 1) {
      zone.radius = animTo;           // 到位
      zone.animating = false;
      clearInterval(zoneShrinkTimer); zoneShrinkTimer = null;
      zone.shrinkCount++;
      if (zone.shrinkCount >= ZONE_CFG.steps) {
        zone.nextAt = 0; zone.target = animTo; // 已缩到底，白圈=红圈
      } else {
        zone.target = nextTargetFrom(animTo);  // 白圈预置下一阶段落位
        zone.nextAt = Date.now() + ZONE_CFG.intervalMs;
        zoneWaitTimer = setTimeout(() => { zoneWaitTimer = null; beginShrinkAnim(); }, ZONE_CFG.intervalMs);
      }
      broadcastZone();
    } else {
      zone.radius = animFrom + (animTo - animFrom) * t; // 中间帧：判定/伤害/显示全跟随
      broadcastZone();
    }
  }, ZONE_TICK);
}
function startZone() {
  clearZoneTimers();
  zone.active = true;
  zone.shrinkCount = 0;
  zone.radius = ZONE_CFG.startRadius;
  zone.target = nextTargetFrom(ZONE_CFG.startRadius); // 白圈预置第一次落位
  zone.nextAt = Date.now() + ZONE_CFG.waitMs; // 先等 1 分钟才开始第一次缩圈
  broadcastZone();
  zoneWaitTimer = setTimeout(() => {
    zoneWaitTimer = null;
    beginShrinkAnim();
  }, ZONE_CFG.waitMs);
}
function stopZone() {
  clearZoneTimers();
  zone.active = false;
  broadcastZone();
}

// ---------- 对战模式参数（与前端保持一致） ----------
const BATTLE_SIZE = 20;            // 对战地图边长（格坐标）
const BATTLE_MOVE_MIN = 1;
const BATTLE_MOVE_MAX = BATTLE_SIZE - 1;
const WIN_SCORE = 20;              // 一队先到 20 分即胜
const TEAM_SIZE = 2;               // 每队两人

// ---------- 对战模式状态 ----------
let battleActive = false;    // 当前处于对战地图
let battleOver = false;      // 本场对战已分出胜负（停止计分/复活）
let battleDevId = null;      // 发起“跳转游戏”的开发者 id（只有他分配队伍/开战）
let battlePlanning = false;  // 处于跳转倒计时/分配阶段（新加入的人也直接进入分配等待）
let battleReturning = false; // 处于“返回迷宫”5秒倒计时（期间客户端不得自愈回迷宫）
let returnToMazeTimer = null; // “返回迷宫”倒计时定时器（防止与新分配状态互相竞态清除）
const battleTeams = new Map(); // id -> team(int)
let bossId = null;             // Boss 模式当前 Boss 玩家 id（无则 null）
let bossBattle = false;        // 当前是否处于 Boss 模式（替代旧“对战/分队计分”玩法）

// 被“状态清理”踢出的 token 黑名单：用于拒绝被踢者 10 秒内的自动重连，
// 否则其他玩家的前端 onclose 会 scheduleReconnect() 3 秒自动重连回来，导致“踢不掉人”。
const KICKED_TOKENS = new Map(); // token -> 过期时间戳(ms)

function kickBlocked(token) {
  if (!token) return false;
  const exp = KICKED_TOKENS.get(token);
  if (!exp) return false;
  if (Date.now() < exp) return true;
  KICKED_TOKENS.delete(token);
  return false;
}

// 分配/对战状态必须在“确有开发者在分配”时才生效，且无主时及时复位，
// 防止上一场残留状态（如开发者中途关页面）导致之后任何人一开游戏就被当成“正在分配”。
function liveDev() {
  if (!battleDevId) return null;
  const d = PLAYERS_3D.get(battleDevId);
  return (d && d.ws && d.ws.readyState === 1) ? d : null;
}
function resetBattleState(notify) {
  if (returnToMazeTimer) { clearTimeout(returnToMazeTimer); returnToMazeTimer = null; }
  battleActive = false;
  battleOver = false;
  battleDevId = null;
  battlePlanning = false;
  battleReturning = false;
  battleTeams.clear();
  teamScores.clear();
  bossId = null; bossBattle = false;
  if (notify) { // 通知仍在线的玩家退出等待/对战界面，回到迷宫
    const msg = JSON.stringify({ type: 'returnMaze' });
    for (const o of PLAYERS_3D.values()) {
      if (o.ws.readyState === 1) { try { o.ws.send(msg); } catch (e) {} }
    }
  }
}
const teamScores = new Map();  // team(int) -> score

// 全员退出对战回到迷宫：清对局状态与队伍积分，所有人回出生点，并广播 returnMaze。
// 排行榜(击杀榜)不清空——kills 保留，因为排名是累计击杀，与本次对局积分无关。
function returnAllToMaze() {
  if (returnToMazeTimer) { clearTimeout(returnToMazeTimer); returnToMazeTimer = null; }
  cleanupAllRevives(); // 回预备房：清掉所有进行中的救助频道
  battleActive = false;
  battleOver = false;
  battleDevId = null;
  battlePlanning = false;
  battleReturning = false;
  battleTeams.clear();
  teamScores.clear();
  bossId = null; bossBattle = false;
  for (const o of PLAYERS_3D.values()) {
    const sp = mazeSpawnPos();
    o.team = null; o.deathCount = 0; o.x = sp.x; o.z = sp.z; o.angle = 0;
    o.hp = MAX_HP; o.respawnAt = 0;
    o.isBoss = false;
    o.room = ROOM_PREP; // 复位房间归属：从对局房回到预备房（隔离边界随之还原）
    o.escaped = false; // 复位逃离标记：从对局返回迷宫后仍可再次触发“离开迷宫”广播
  }
  const payload = JSON.stringify({ type: 'returnMaze' });
  for (const o of PLAYERS_3D.values()) {
    if (o.ws.readyState === 1) { try { o.ws.send(payload); } catch (e) {} }
  }
  broadcastPlayers3d();
  broadcastKills3d();
  console.log('[3d battle] returned to maze');
}

// Boss 模式结算：不返回迷宫，只判定胜负，等待“继续开始”重开一局
function endBossBattle(winner) {
  if (battleOver) return; // 避免重复结算
  battleOver = true;
  cleanupAllRevives(); // 结算：清掉所有进行中的救助频道
  broadcastRoom(ROOM_MATCH, { type: 'bossEnd', winner });
  broadcastPlayers3d();
  console.log('[3d boss] end, winner:', winner);
}

// Boss 模式开战：随机抽一名为 Boss，全员强制 AK+AWM、备弹无限，进入竞技场
// （battleStart 与 bossRestart“继续开始”共用）
function startBossBattle() {
  const order = livePlayers();
  if (order.length === 0) return;
  cleanupAllRevives(); // 新一局：清掉上一局所有救助残留
  for (const o of PLAYERS_3D.values()) o.isBoss = false;
  const boss = order[Math.floor(Math.random() * order.length)];
  bossId = boss.id; boss.isBoss = true;
  const spawns = {};
  for (const o of order) {
    o.team = (o.id === bossId) ? 1 : 0;      // 人类=0（同队互不可伤），Boss=1
    o.room = ROOM_MATCH;                       // 全部拉进对局房：保证隔离 + 本地 gameOn 生效 + 存活计数正确
    o.hp = (o.id === bossId) ? BOSS_HP : MAX_HP;
    o.respawnAt = 0; o.deathCount = 0; o.kills = 0;
    // 开局重置：每人发 10 个血包（按 H 自疗）；清空上一局救助/用血包残留
    o.medkitCount = 10;
    o.usingMedkit = false;
    if (o.medTimer) { clearTimeout(o.medTimer); o.medTimer = null; }
    o.reviving = null; o.beingRevivedBy = null;
    if (o.reviveTimer) { clearTimeout(o.reviveTimer); o.reviveTimer = null; }
    o.escaped = true;                          // 竞技场内不触发“离开迷宫”
    o.parachuting = false; o.skyY = 0; o.floorH = 0;
    const sx = BATTLE_INSIDE_MIN + Math.random() * (BATTLE_INSIDE_MAX - BATTLE_INSIDE_MIN);
    const sz = BATTLE_INSIDE_MIN + Math.random() * (BATTLE_INSIDE_MAX - BATTLE_INSIDE_MIN);
    o.x = sx; o.z = sz; o.angle = 0;
    spawns[o.id] = { x: sx, z: sz };
    o.battleSpawn = { x: sx, z: sz };
    // 武器分配：非 Boss 玩家强制 AK47 + 狙击枪(AWM)、备弹无限（弹夹打空仍需按 R 换弹）；
    // Boss 空手（weapon=null、slots=[null,null]、ammo={}），只能近战，无法获取武器。
    o.reloading = false;
    if (o.reloadTimer) { clearTimeout(o.reloadTimer); o.reloadTimer = null; }
    if (o.id === bossId) {
      o.weapon = null;
      o.slots = [null, null];
      o.ammo = {};
    } else {
      o.weapon = 'AK47';
      o.slots = ['AK47', 'AWM'];
      o.ammo = {
        0: { weapon: 'AK47', mag: WEAPON_STATS['AK47'].mag, reserve: BOSS_BULLETS },
        1: { weapon: 'AWM',  mag: WEAPON_STATS['AWM'].mag,  reserve: BOSS_BULLETS }
      };
      // 注意：ammo 消息不在本循环下发，改到下方 battleStart 广播之后发送，
      // 否则客户端会先收到 ammo、再收到 battleStart，而 handleBattleStart 会清空本地槽位 → 武器被抹掉（只剩一把甚至全空）。
    }
  }
  battleActive = true;
  battleOver = false;
  bossBattle = true;
  battlePlanning = false;
  battleReturning = false;
  gameOn = true; // Boss 模式即“已开局”：放开进攻/攻击（仅对局房内生效）
  // 仅向对局房(ROOM_MATCH)下发开战指令：预备房观众不会误入竞技场，保证模式隔离
  for (const o of PLAYERS_3D.values()) {
    if (roomOf(o) !== ROOM_MATCH) continue;
    try { o.ws.send(JSON.stringify({ type: 'battleStart', bossId, myTeam: o.team, spawns, meds: 10 })); } catch (e) {}
    // 必须在 battleStart 之后下发 ammo：客户端 handleBattleStart 会先把本地武器槽清空，
    // 之后再填充；顺序若颠倒，新一局玩家会丢失武器（表现为“只有一把枪 / 没有无限子弹”）。
    if (o.id !== bossId) { sendAmmoTo(o, 0); sendAmmoTo(o, 1); }
  }
  broadcastRoom(ROOM_MATCH, { type: 'gameState', on: true });
  broadcastPlayers3d();
  broadcastKills3d();
  console.log('[3d boss] started, boss:', boss.name);
}

// 复活延迟阶梯：第1次2秒，第2次5秒，之后恒定10秒
function deathDelayMs(deathCount) {
  if (deathCount <= 1) return 2000;
  if (deathCount === 2) return 5000;
  return 10000;
}

// 对战移动可用的真实范围（格坐标）：围墙内壁约 3.44/16.56，再留出玩家半径与安全余量 → [3.8, 16.2]。
// 若沿用旧 [1,19]：未进入对战视图的玩家上报的迷宫坐标会被 clamp 到 1/19，落到围墙外/墙体内，
// 表现为“别人视角里该玩家站在围墙外、打不到”。对局中的权威坐标必须始终落在围墙内侧。
const BATTLE_INSIDE_MIN = 3.8; // 竞技场墙+玩家半径的阻挡区约格 3.19~3.61；下限必须 ≥3.61 并留余量，
// 否则击退/移动钳位会把玩家中心放进墙阻挡区 → 卡在墙上动不了（跳一下靠“越过墙顶”才能脱困）
const BATTLE_INSIDE_MAX = BATTLE_SIZE - BATTLE_INSIDE_MIN; // 20-3.8=16.2（以中心格 10 对称）
function clampBattle(v) {
  if (!isFinite(v)) return BATTLE_INSIDE_MIN;
  return Math.max(BATTLE_INSIDE_MIN, Math.min(BATTLE_INSIDE_MAX, v));
}

function snap3d(p) {
  return { id: p.id, name: p.name, role: p.role, x: p.x, z: p.z, angle: p.angle, hp: p.hp, kills: p.kills || 0, team: p.team,
           weapon: p.weapon || null, // 当前手持武器（null=空手）：供他人渲染“手上拿着枪”
           isBoss: !!p.isBoss,       // 是否本局 Boss（前端据此把身体渲染为 2 倍体型、血条上限 2000）
           y: p.parachuting ? skyYPayload(p) : Math.round((0.8 + (p.floorH || 0)) * 100) / 100 };
}

// 向所有（含指定的某 id）发送可由连接定制内容的消息
function broadcast3dPer(fn) {
  for (const p of PLAYERS_3D.values()) {
    if (p.ws.readyState === 1) {
      try { p.ws.send(JSON.stringify(fn(p))); } catch (e) { /* ignore */ }
    }
  }
}
// 自动分两队：只有人数为偶数(且≥4)时按 2 人一队；
// 奇数或人数少时每人单独一队，避免出现 2v1 的不公平局面（如 3 人就分 3 队）
function defaultTeamFor(order, i) {
  return (order.length >= 4 && order.length % 2 === 0) ? Math.floor(i / TEAM_SIZE) : i;
}
function autoAssignTeams() {
  const order = livePlayers();
  order.forEach((o, i) => { if (!battleTeams.has(o.id)) battleTeams.set(o.id, defaultTeamFor(order, i)); });
}
// 分配阶段加入/重连的新人：也让他看到 5 秒跳转倒计时，然后落入“开发者正在分配中”等待；并让开发者刷新分配名单
function enterBattlePlanning(p) {
  if (!p || p.ws.readyState !== 1) return;
  // 分配阶段重连回来的“发起者本人”需要额外标记，前端据此恢复“我是分配者”身份：
  // 否则开发者刷新后本地身份丢失，永远打不开分配界面，全员会一直停在“开发者正在分配中”。
  const prepareMsg = { type: 'battlePrepare', count: 5 };
  if (battleDevId === p.id) prepareMsg.dev = true;
  try { p.ws.send(JSON.stringify(prepareMsg)); } catch (e) {}
  if (battleDevId) {
    const devP = PLAYERS_3D.get(battleDevId);
    if (devP && devP.ws.readyState === 1) {
      autoAssignTeams();
      const aArr = livePlayers();
      try { devP.ws.send(JSON.stringify({ type: 'teamAssign', players: aArr.map((o, i) => ({ id: o.id, name: o.name, team: battleTeams.get(o.id) ?? defaultTeamFor(aArr, i) })) })); } catch (e) {}
    }
  }
}
// 对战进行中加入的人：自动补位。
// 优先补进“还没满两人”的现有队伍；若所有队伍都满员，则自动新增一队给这个人。
function assignLateJoinerTeam(p) {
  const counts = new Map(); // team -> 人数
  for (const o of PLAYERS_3D.values()) {
    const t = battleTeams.get(o.id);
    if (t != null) counts.set(t, (counts.get(t) || 0) + 1);
  }
  for (const [t, c] of counts) {
    if (c < TEAM_SIZE) { battleTeams.set(p.id, t); return t; } // 补位
  }
  let nt = 0;
  for (const t of counts.keys()) nt = Math.max(nt, t);
  const team = nt + 1;                       // 全员满员 → 新增一队
  battleTeams.set(p.id, team);
  return team;
}
// 确保每个现存队伍都有比分项，并广播最新比分（让新队也能立即显示）
function broadcastTeamScores3d() {
  for (const t of battleTeams.values()) { if (!teamScores.has(t)) teamScores.set(t, 0); }
  broadcast3d({ type: 'teamScore', teams: Array.from(teamScores.entries()) });
}
// 离线清理：某队已无任何在线成员时，连同其积分项一起清掉。
// 否则离场玩家的队伍会作为“幽灵队伍”残留在积分 HUD / 下一场对局里（离线人物清理不彻底的表现之一）
function pruneTeamIfEmpty(team) {
  if (team == null) return false;
  for (const o of PLAYERS_3D.values()) { if (o.team === team) return false; } // 队里还有人：保留
  let had = false;
  for (const [pid, t] of battleTeams) { if (t === team) { battleTeams.delete(pid); had = true; } }
  teamScores.delete(team);
  return had;
}
// 对战中加入/重连的人：自动补队、按队伍角位出生、下发 battleStart，避免“没弹窗/进不去”，且不让他停留在旧迷宫
function enterLiveBattle(p) {
  if (!p || !battleActive || p.ws.readyState !== 1) return;
  if (!battleTeams.has(p.id)) assignLateJoinerTeam(p);
  p.team = battleTeams.get(p.id);
  if (!teamScores.has(p.team)) teamScores.set(p.team, 0);
  p.hp = MAX_HP; p.respawnAt = 0; p.deathCount = 0; p.kills = 0;
  p.parachuting = false; p.skyY = 0; p.floorH = 0; // 进对战一律落地，不保留跳伞状态
  const c = battleCornerGrid(p.team);
  p.x = clampG(c[0]); p.z = clampG(c[1]);
  try {
    p.ws.send(JSON.stringify({ type: 'battleStart', myTeam: p.team, teams: Array.from(battleTeams.entries()), scores: Array.from(teamScores.entries()), spawns: { [p.id]: { x: p.x, z: p.z } }, teamCount: TEAM_SIZE }));
  } catch (e) {}
  broadcastPlayers3d();
  broadcastKills3d();
  broadcastTeamScores3d();
}
// 对战出生：按队伍分到 4 个角（左上/右上/左下/右下），队伍内两人在角落附近错开
// 四个角尽量往场地内部缩，确保连“两人错开”后最靠外的队员也不压进墙里
const BATTLE_CORNERS = [
  [BATTLE_MOVE_MIN + 5, BATTLE_MOVE_MIN + 5],   // 左上
  [BATTLE_MOVE_MAX - 5, BATTLE_MOVE_MIN + 5],   // 右上
  [BATTLE_MOVE_MIN + 5, BATTLE_MOVE_MAX - 5],   // 左下
  [BATTLE_MOVE_MAX - 5, BATTLE_MOVE_MAX - 5],   // 右下
];
// 竞技场围墙内侧范围（格坐标）：用于对战脉冲识别“仍持迷宫坐标/落在墙外”的漏入玩家并纠正
const BATTLE_CENTER_G = BATTLE_MOVE_MIN + (BATTLE_SIZE - 2) / 2;
const BATTLE_HALF_WORLD = BATTLE_SIZE * CELL_SIZE_WORLD * 0.33;
const ARENA_MIN_G = BATTLE_CENTER_G - BATTLE_HALF_WORLD / CELL_SIZE_WORLD;
const ARENA_MAX_G = BATTLE_CENTER_G + BATTLE_HALF_WORLD / CELL_SIZE_WORLD;
function battleCornerGrid(team) {
  return BATTLE_CORNERS[((team % 4) + 4) % 4];
}
function clampG(v) { return Math.max(BATTLE_MOVE_MIN + 0.5, Math.min(BATTLE_MOVE_MAX - 0.5, v)); }
// 迷宫出生点：入口附近（0/1 号格是清过墙的连通区）随机，并在多个候选点里挑“离其他在线玩家最远”的一个。
// 旧实现只在 0.2~0.8 的极小方格内随机，多人同时复活/刷新时会直接重叠 → 表现为“刷新后没在出生点，而是出现在别人身上”。
function mazeSpawnPos(selfId) {
  const MIN_G = 0.35, MAX_G = 1.2; // 世界坐标约 0.77~2.05。格线 1.5（世界 2.5）处的墙是随机迷宫未清空的，
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
// 结束对战：胜负落定，停止计分，全体回满血不复活
function endBattle(winnerTeam, now) {
  battleOver = true;
  for (const o of PLAYERS_3D.values()) { o.hp = MAX_HP; o.respawnAt = 0; }
  const winner = Array.from(PLAYERS_3D.values()).find(o => o.team === winnerTeam);
  const name = winner ? winner.name : ('队伍' + (winnerTeam + 1));
  broadcast3dPer(p => ({ type: 'battleEnd', winnerTeam, winnerName: name, myTeam: p.team, teams: Array.from(teamScores.entries()) }));
  console.log('[3d battle] end, winner team', winnerTeam, name);
}
function broadcast3d(msg, exceptId) {
  const s = JSON.stringify(msg);
  for (const p of PLAYERS_3D.values()) {
    if (p.ws.readyState === 1 && p.id !== exceptId) {
      try { p.ws.send(s); } catch (e) { /* ignore */ }
    }
  }
}
// ---- 房间隔离（参考“暗黑迷宫”）：预备房与对局房互不可见、互不同步 ----
// 玩家未打过标时默认视为预备房（新连接的默认房间）
function roomOf(p) { return (p && p.room === ROOM_MATCH) ? ROOM_MATCH : ROOM_PREP; }
// 仅向“同房间”的玩家广播（exceptId 可排除自己）。用于位置/攻击/落地等对局内同步，杜绝预备房与对局房串场。
function broadcastRoom(room, msg, exceptId) {
  const s = JSON.stringify(msg);
  for (const p of PLAYERS_3D.values()) {
    if (p.ws.readyState !== 1 || p.id === exceptId) continue;
    if (roomOf(p) !== room) continue;
    try { p.ws.send(s); } catch (e) { /* ignore */ }
  }
}
// 同房间内的在线玩家（预备房/对局房各自成组）
function roomPlayers(room) {
  return livePlayers().filter(o => roomOf(o) === room);
}
// 构造某玩家专属的 welcome 负载：只含“同房间”的玩家名单 + 其所在房间/对局阶段。
// 前端据此决定：进入对局房（活跃对抗）还是留在预备房（等待/观战）。
function welcomeFor(p, online) {
  const room = roomOf(p);
  const same = roomPlayers(room);
  // online 一律用“全局在线数”（调用方传 connectedCount()）：与 broadcastPlayers3d 口径完全一致，
  // 避免分房后不同客户端算出不同的“在线人数”。同名参数保留以兼容既有调用点。
  const total = (online != null ? Number(online) : same.length);
  return { type: 'welcome', id: p.id, players: same.map(snap3d), online: total, room, phase: gamePhase };
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
function broadcastPlayers3d() {
  // 逐接收者下发“仅同房间”的玩家名单：预备房与对局房互不串场。
  // 同时带上 phase / room，让前端知道“当前是预备房还是对局房”“本端在哪个房间”。
  // 【关键】online（顶部在线人数）统一用**全局在线数** connectedCount()，与 welcome 口径一致；
  //   绝不能用 same.length（同房间人数）——否则一旦分房（我在预备房、别人在对局房），
  //   顶部就会各自显示“1 人”，表现为“明明有两个人却只显示 1 个”。
  const onlineTotal = connectedCount();
  for (const p of PLAYERS_3D.values()) {
    if (p.ws.readyState !== 1) continue;
    const room = roomOf(p);
    const same = roomPlayers(room);
    const payload = { type: 'players', battleActive, battlePlanning, battleReturning, players: same.map(snap3d), online: onlineTotal, phase: gamePhase, room };
    try { p.ws.send(JSON.stringify(payload)); } catch (e) { /* ignore */ }
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
function broadcastKills3d() {
  broadcast3d({ type: 'kills', players: Array.from(PLAYERS_3D.values()).map(k => ({ id: k.id, name: k.name, kills: k.kills || 0 })) });
}
// 完成一次击杀：记录击杀数、广播击杀公告；返回本次复活延迟(ms)，由调用方决定是否带上到 attack 消息。
// 按当前对局模式决定死亡后续：
//  - royale（系统1）：死亡即淘汰 → 回预备房清空状态，并判定“最后一名胜者”。
//  - respawn（系统2）：死亡 → 保留枪、清空弹夹恢复原厂子弹、满血重新跳伞（无淘汰）。
//  - 其余（对战/普通迷宫）：沿用原地等待后复活逻辑。
function applyKill3d(attacker, target, now) {
  target.hp = 0;
  // 被击杀：中断正在进行的血包使用（血包计时作废，不回血也不掉血）
  if (target.usingMedkit) {
    target.usingMedkit = false;
    if (target.medTimer) { clearTimeout(target.medTimer); target.medTimer = null; }
  }
  attacker.kills = (attacker.kills || 0) + 1;
  broadcast3d({ type: 'kill', attackerName: attacker.name, targetName: target.name, kills: attacker.kills });
  console.log('[3d kill]', attacker.name, 'killed', target.name);
  if (gameMode === 'royale') { eliminateToPrep(target); checkRoyaleWinner(now); return null; }
  // 无圈模式：先走满 5 秒“复活倒计时”，再由下方心跳循环在 respawnAt 到期时触发跳伞（避免边跳边显示倒计时）
  if (gameMode === 'respawn') { target.respawnAt = now + RESPAWN_CD; return RESPAWN_CD; }
  // Boss 模式：被击杀者不复活、灰身固定，依据死亡对象判定胜负
  if (bossBattle) {
    // 被击杀者若正在被救助，或正在救助别人 → 立即中断该救助频道（尸体不能被救走，救助者也不能边救人边被杀）
    if (target.beingRevivedBy) {
      const rv = PLAYERS_3D.get(target.beingRevivedBy);
      if (rv) { cleanupRevive(rv, target); broadcastRoom(roomOf(target), { type: 'reviveFail', targetId: target.id, reviverId: rv.id, reason: 'interrupted' }); }
    }
    if (target.reviving) {
      const t2 = PLAYERS_3D.get(target.reviving);
      if (t2) { cleanupRevive(target, t2); broadcastRoom(roomOf(target), { type: 'reviveFail', targetId: t2.id, reviverId: target.id, reason: 'interrupted' }); }
    }
    if (target.isBoss) {
      // Boss 被击杀 → 人类获胜
      broadcastRoom(roomOf(target), { type: 'bossDeath', id: target.id, isBoss: true });
      endBossBattle('player');
    } else {
      // 人类被击杀 → 保持死亡（灰身固定、转观战）；人类全灭 → Boss 获胜
      broadcastRoom(roomOf(target), { type: 'bossDeath', id: target.id, isBoss: false });
      const humanAlive = roomPlayers(ROOM_MATCH).filter(o => o.hp > 0 && isLivePlayer(o) && !o.isBoss).length;
      if (humanAlive <= 0) endBossBattle('boss');
    }
    return null; // 不设置复活倒计时
  }
  let delay = RESPAWN_CD;
  if (battleActive) {
    target.deathCount = (target.deathCount || 0) + 1;
    delay = deathDelayMs(target.deathCount);
  }
  target.respawnAt = now + delay;
  return delay;
}

// ---------- 系统1（吃鸡·最后生存）死亡处理 ----------
// 淘汰：回到预备房，清空全部状态（血量/弹药/枪械），解除对局身份。
// 系统1·吃鸡：当前对局房剩余存活人数（用于客户端“剩余 N”实时显示，含预备房观战者）
function royaleAliveCount() {
  return roomPlayers(ROOM_MATCH).filter(o => o.hp > 0 && isLivePlayer(o)).length;
}
function eliminateToPrep(target) {
  target.room = ROOM_PREP;
  target.weapon = null; target.ammo = {}; target.slots = [null, null];
  target.reloading = false;
  if (target.reloadTimer) { clearTimeout(target.reloadTimer); target.reloadTimer = null; }
  target.medkitCount = 0;
  target.usingMedkit = false;
  if (target.medTimer) { clearTimeout(target.medTimer); target.medTimer = null; }
  target.hp = MAX_HP; target.respawnAt = 0;
  target.parachuting = false; target.skyY = 0; target.floorH = 0;
  const sp = mazeSpawnPos(target.id);
  target.x = sp.x; target.z = sp.z; target.angle = 0;
  // 本端：切回预备房 + 复活到出生点（解除死亡遮罩）
  if (target.ws.readyState === 1) {
    try { target.ws.send(JSON.stringify({ type: 'leaveMatch' })); } catch (e) {}
    try { target.ws.send(JSON.stringify({ type: 'respawn', id: target.id, x: target.x, z: target.z, hp: target.hp })); } catch (e) {}
  }
  // 对局房其余人：此人已离场（重新分组名单，预备房/对局房互不可见）
  broadcastRoom(ROOM_MATCH, { type: 'leave', id: target.id });
  broadcastPlayers3d();
  // 剩余存活人数实时广播：吃鸡模式中所有客户端（含预备房观战者）据此刷新“剩余 N”
  broadcast3d({ type: 'royaleAlive', alive: royaleAliveCount() });
  console.log('[3d royale] eliminated -> prep:', target.name);
}
// 系统1：对局房只剩 1 名存活 → 决出胜者，全场（含预备房）通知后立即结束本局回预备房。
function checkRoyaleWinner(now) {
  const alive = roomPlayers(ROOM_MATCH).filter(o => o.hp > 0 && isLivePlayer(o));
  const winner = alive.length === 1 ? alive[0] : null;
  broadcast3d({ type: 'royaleWin', winnerName: winner ? winner.name : '', winnerId: winner ? winner.id : null });
  console.log('[3d royale] winner:', winner ? winner.name : '无（平局/同时阵亡）');
  endRoyale();
}
function endRoyale() {
  gameMode = null;
  stopZone();
  gameOn = false; gamePhase = 'prep';
  for (const o of PLAYERS_3D.values()) {
    o.room = ROOM_PREP;
    o.parachuting = false; o.skyY = 0; o.floorH = 0;
    o.weapon = null; o.ammo = {}; o.slots = [null, null];
    o.reloading = false;
    if (o.reloadTimer) { clearTimeout(o.reloadTimer); o.reloadTimer = null; }
    const sp = (battleActive && o.battleSpawn) ? o.battleSpawn : mazeSpawnPos(o.id);
    o.x = sp.x; o.z = sp.z; o.angle = 0;
    o.hp = MAX_HP; o.respawnAt = 0;
    if (o.ws.readyState === 1) {
      try { o.ws.send(JSON.stringify({ type: 'leaveMatch' })); } catch (e) {}
      try { o.ws.send(JSON.stringify({ type: 'respawn', id: o.id, x: o.x, z: o.z, hp: o.hp })); } catch (e) {}
    }
  }
  broadcast3d({ type: 'gameState', on: false });
  broadcastPlayers3d();
  clearWeapons(); wpSpawnedOnline = 1; broadcast3d({ type: 'weapons', list: [] });
  clearMags(); broadcast3d({ type: 'mags', list: [] });
  console.log('[3d royale] match ended, all back to prep');
}
// ---------- 系统2（无圈·混战）死亡处理 ----------
// 重新跳伞：保留手持枪与背包槽位；弹夹/备弹清空，若持枪则恢复“原厂满弹匣、0 备弹”；满血、高空随机散布。
function respawnChute(target) {
  target.hp = MAX_HP; target.respawnAt = 0;
  target.reloading = false;
  if (target.reloadTimer) { clearTimeout(target.reloadTimer); target.reloadTimer = null; }
  target.ammo = {};
  // 保留的枪按槽位重装满弹夹（弹夹跟枪走）
  if (target.weapon && WEAPON_STATS[target.weapon]) {
    const st = WEAPON_STATS[target.weapon];
    const si = slotForWeapon(target, target.weapon);
    if (si >= 0) {
      target.ammo[si] = { weapon: target.weapon, mag: st.mag, reserve: 0 };
      sendAmmoTo(target, si);
    }
  }
  const sk = randomSkyPos();
  if (noChute) {
    // “无需跳伞”：满血 + 随机地面出生点直接复活（服务端 respawn 广播会让各端立即复位显示）
    target.parachuting = false; target.skyY = 0; target.floorH = 0;
    target.chuteBoost = false;
    target.x = sk.x; target.z = sk.z; target.angle = 0;
    broadcastRoom(ROOM_MATCH, { type: 'respawn', id: target.id, x: target.x, z: target.z, hp: target.hp });
    console.log('[3d respawn-mode] ground-respawn:', target.name, 'keeps weapon', target.weapon);
    return;
  }
  target.parachuting = true; target.skyY = PARACHUTE_Y;
  target.chuteBoost = false;
  target.x = sk.x; target.z = sk.z; target.angle = 0;
  // 【关键】先补发一次 respawn：无圈模式死亡时，其他人的客户端已把此人立牌按“死亡”隐藏（setHp<=0），
  // 若不补发 respawn（setHp>0 重显），重新跳伞后此人立牌对同房其他人一直不可见。
  broadcastRoom(ROOM_MATCH, { type: 'respawn', id: target.id, x: target.x, z: target.z, hp: target.hp });
  broadcastRoom(ROOM_MATCH, { type: 'parachuteStart', players: [{ id: target.id, x: target.x, z: target.z, angle: 0, y: skyYPayload(target) }] });
  console.log('[3d respawn-mode] re-chute:', target.name, 'keeps weapon', target.weapon);
}
// 宽松的移动范围（格坐标），与前端广场范围保持一致。
// 前端迷宫已扩大 5 倍（SIZE 12→60，OUTER_CELLS=6）：移动范围同步扩展到 [-6.5, 65.5]
const MAZE_SIZE = 60;             // 与前端 index.html 的 SIZE 保持一致
const OUTER_CELLS = 6;            // 与前端一致：迷宫外的广场宽度（格）
const MOVE_MIN_3D = -(OUTER_CELLS + 0.5);
const MOVE_MAX_3D = (MAZE_SIZE - 1) + OUTER_CELLS + 0.5;
function clamp3dMove(v) {
  if (!isFinite(v)) return 0;
  return Math.max(MOVE_MIN_3D, Math.min(MOVE_MAX_3D, v));
}

// ---------- 服务端权威武器 ----------
// 设计：武器位置由服务端唯一生成/维护，所有客户端收到的都是同一份列表 → 每个人看到的枪位置完全一致。
// 前端不再做任何本地随机撒放；只负责按服务端下发的 (type,x,z,y) 渲染立牌。
// 坐标口径：统一使用“世界单位”（与前端 three 渲染一致）：world = 格 × CELL_SIZE_WORLD + 0.25。
const WEAPON_TYPES = ['AK47', 'AWM', '沙漠之鹰'];
// 枪械参数（服务端权威）：弹容量 / 开火间隔（秒）/ 换弹时间（秒）/ 移速惩罚（比例，前端同表）
const WEAPON_STATS = {
  'AK47':     { mag: 30, interval: 0.10, reload: 2.8, penalty: 0.20 },
  'AWM':      { mag: 1,  interval: 1.50, reload: 1.5, penalty: 0.20 },
  '沙漠之鹰':  { mag: 7,  interval: 0.33, reload: 2.0, penalty: 0.10 },
};
// 弹夹（地上拾取物，服务端权威生成）：只能装进对应枪械；拾取后加入该枪的备弹（reserve）。
// 名称与前端 assets/weapons/ 的贴图文件名一致（AK47弹匣.webp / 狙击枪弹匣.webp / 沙漠之鹰弹匣.webp）。
const MAG_TYPES = {
  'AK47弹匣':    { for: 'AK47',    ammo: 30 },
  '狙击枪弹匣':   { for: 'AWM',     ammo: 5 },
  '沙漠之鹰弹匣': { for: '沙漠之鹰', ammo: 7 },
};
const MAG_PICK_RANGE = 2.6;     // 弹夹拾取距离（世界单位，水平距离），与武器一致
const WEAPON_PICK_RANGE = 2.6;   // 拾取距离（世界单位，水平距离），与前端 PICK_RANGE 一致
// 注意：拾取距离只按“水平距离”校验。地上的枪立牌是底边贴地竖直立在（楼层）地面上的，
// 其显示高度由贴图宽高比决定，不存在固定的“悬浮高度”常量；把高度差算进距离只会让枪越低越难捡。
// 与前端 index.html 的 HOUSE_PLAN 逐条一致（[cx,cz,w,d,floors,door]）：武器撒放要落在楼内/屋顶，必须同布局。
const HOUSE_PLAN = [
  [12, 16, 5, 5, 3, 1], [22, 16, 5, 5, 5, 1], [17, 9, 4, 4, 1, 3],
  [11, 42, 4, 4, 1, 2], [18, 44, 7, 5, 5, 0], [26, 42, 5, 5, 3, 0],
  [38, 18, 5, 5, 6, 3], [48, 18, 5, 5, 3, 3], [43, 11, 4, 4, 3, 2],
  [38, 40, 5, 5, 6, 2], [48, 40, 5, 5, 3, 2], [43, 48, 4, 4, 1, 3],
  [30, 30, 5, 5, 1, 0],
  [12, 12, 3, 3, 2, 1], [28, 12, 4, 4, 3, 0], [36, 13, 4, 4, 2, 2], [49, 11, 3, 3, 1, 3],
  [12, 24, 3, 3, 2, 1], [12, 34, 3, 3, 1, 2], [12, 45, 3, 3, 2, 1],
  [49, 28, 4, 4, 3, 1], [49, 36, 3, 3, 1, 3],
  [32, 48, 3, 3, 2, 1], [38, 50, 3, 3, 1, 2], [46, 50, 3, 3, 2, 0],
  [30, 44, 3, 3, 2, 3], [31, 18, 3, 3, 1, 1],
];
const HOUSE_FLOOR_H_SRV = 2.0;   // 每层层高（世界单位），与前端 WALL_HEIGHT 一致
// 山体占位中心（与前端 buildMountain 一致）：地面撒枪需避开
const MOUNT_SPOTS_SRV = [[6, 38, 4.5], [6, 22, 4], [53, 38, 4.5], [54, 20, 4], [8, 4, 3.5], [30, 3, 4], [30, 54, 4.5]];
function gwxLine(g) { return g * CELL_SIZE_WORLD + 0.25; }   // 格 → 世界（与前端 toWorld 口径一致）

// ---------- 服务端权威视线（LOS）模型：与前端 allWallGroups 的墙体 AABB 逐条一致 ----------
// 用途：攻击时禁止“隔墙打人”（防御性校验，防止改包/异常客户端绕过前端）。
// 前端墙体来源：房屋墙体（HOUSE_PLAN 每格的四面外墙 + 门洞立柱）、山体底盒、竞技场围墙。
// 迷宫格墙已全部清零（无墙模式），不参与遮挡。
const SRV_CELL = CELL_SIZE_WORLD;            // 1.5
const SRV_HALF = SRV_CELL / 2;               // 0.75
const WALL_THICK_SRV = 0.24;                 // 房屋墙厚（与前端 HOUSE_WALL_T 一致）
const WALL_H_SRV = 2.0;                      // 单层墙高（与前端 WALL_HEIGHT 一致）
const HOUSE_FLOOR_H_WALL = 2.0;              // 楼层层高（与前端 HOUSE_FLOOR_H 一致）
function srvGwxLine(g) { return g * CELL_SIZE_WORLD + 0.25; }
function srvGlineX(g, dir) { return (g + (dir > 0 ? 0.5 : -0.5)) * CELL_SIZE_WORLD + 0.25; }
// 绕 Y 轴旋转后的墙体 AABB（本工程只用 0 / ±90°，与前端 wallBoxOf 公式一致）
function srvWallBox(x, z, w, d, rot, top) {
  const c = Math.abs(Math.cos(rot)), s = Math.abs(Math.sin(rot));
  const hx = (c * w + s * d) / 2, hz = (s * w + c * d) / 2;
  return { minX: x - hx, maxX: x + hx, minZ: z - hz, maxZ: z + hz, top: top == null ? WALL_H_SRV : top };
}
// 启动时一次性构建全部遮挡盒（房屋 + 山体）
const SRV_WALL_BOXES = (() => {
  const boxes = [];
  for (const h of HOUSE_PLAN) {
    const cx = h[0], cz = h[1], w = h[2], d = h[3], floors = h[4], doorDir = h[5];
    const hx0 = cx - (w - 1) / 2, hx1 = cx + (w - 1) / 2;
    const hz0 = cz - (d - 1) / 2, hz1 = cz + (d - 1) / 2;
    const top = floors * HOUSE_FLOOR_H_WALL;   // 整栋楼高：低于楼顶时所有楼层墙都算遮挡
    const push = (x, z, ww, dd) => boxes.push(srvWallBox(x, z, ww, dd, 0, top));
    for (let g = hx0; g <= hx1; g++) {
      if (!(doorDir === 1 && g === cx)) push(srvGwxLine(g), srvGlineX(hz0, -1), SRV_CELL + 0.01, WALL_THICK_SRV); // 北墙
      if (!(doorDir === 0 && g === cx)) push(srvGwxLine(g), srvGlineX(hz1, 1), SRV_CELL + 0.01, WALL_THICK_SRV);  // 南墙
    }
    for (let g = hz0; g <= hz1; g++) {
      if (!(doorDir === 3 && g === cz)) push(srvGlineX(hx0, -1), srvGwxLine(g), WALL_THICK_SRV, SRV_CELL + 0.01); // 西墙
      if (!(doorDir === 2 && g === cz)) push(srvGlineX(hx1, 1), srvGwxLine(g), WALL_THICK_SRV, SRV_CELL + 0.01);  // 东墙
    }
    // 门洞立柱
    if (doorDir === 0 || doorDir === 1) {
      const zz = doorDir === 0 ? srvGlineX(hz1, 1) : srvGlineX(hz0, -1);
      push(srvGwxLine(cx - 0.5), zz, WALL_THICK_SRV, WALL_THICK_SRV);
      push(srvGwxLine(cx + 0.5), zz, WALL_THICK_SRV, WALL_THICK_SRV);
    } else {
      const xx = doorDir === 2 ? srvGlineX(hx1, 1) : srvGlineX(hx0, -1);
      push(xx, srvGwxLine(cz - 0.5), WALL_THICK_SRV, WALL_THICK_SRV);
      push(xx, srvGwxLine(cz + 0.5), WALL_THICK_SRV, WALL_THICK_SRV);
    }
    // 楼板/屋顶薄盒（板厚 0.15，上下各让 0.08，与前端 losSlabs 同口径）：
    // 没有它们时“站楼顶朝下打楼下的玩家”视线判定会穿过屋顶直接命中 —— 离谱穿透。
    // 加上后：楼顶↔楼下的视线被楼板挡住，楼上楼下的垂直方向攻击被正确禁止。
    const slabW = (w + 0.5) * SRV_CELL, slabD = (d + 0.5) * SRV_CELL;
    const slabCX = srvGwxLine(cx), slabCZ = srvGwxLine(cz);
    for (let f = 1; f <= floors; f++) {
      boxes.push({
        minX: slabCX - slabW / 2, maxX: slabCX + slabW / 2,
        minZ: slabCZ - slabD / 2, maxZ: slabCZ + slabD / 2,
        minY: f * HOUSE_FLOOR_H_WALL - 0.08, top: f * HOUSE_FLOOR_H_WALL + 0.08,
      });
    }
  }
  // 山体底盒（与前端一致：方形 r*1.5，top=WALL_HEIGHT）
  for (const mn of MOUNT_SPOTS_SRV) {
    const wx = srvGwxLine(mn[0]), wz = srvGwxLine(mn[1]), r = mn[2];
    boxes.push(srvWallBox(wx, wz, r * 1.5, r * 1.5, 0, WALL_H_SRV));
  }
  return boxes;
})();

// ---------- 地面可达性（防“枪刷在密封房间捡不到”） ----------
// 逻辑：把地图按格栅化，普通墙体/山体底盒（无 minY，即从地面一直立到墙顶）视为不可通行，
// 楼板/屋顶薄盒（带 minY）不挡地面行走。从地图外沿向内 BFS，能到达的格即为“可从外面走进来”的开放格。
// 撒室内枪时只落在可达格，杜绝出现在无门/被相邻楼挡死的密封房内。
const RCH_N = MAZE_SIZE + OUTER_CELLS * 2;   // 与前端移动范围一致（含外沿）
const RCH_REACH = (() => {
  const reach = new Uint8Array(RCH_N * RCH_N);
  function blocked(gx, gz) {
    if (gx < 0 || gz < 0 || gx >= RCH_N || gz >= RCH_N) return false; // 界外视为开放
    const wx = srvGwxLine(gx), wz = srvGwxLine(gz);
    for (const b of SRV_WALL_BOXES) {
      if (b.minY != null) continue; // 楼板/屋顶薄盒：不挡地面通行
      if (wx >= b.minX - 0.2 && wx <= b.maxX + 0.2 && wz >= b.minZ - 0.2 && wz <= b.maxZ + 0.2) return true;
    }
    return false;
  }
  const q = [];
  const push = (x, z) => {
    if (x < 0 || z < 0 || x >= RCH_N || z >= RCH_N) return;
    if (reach[x + z * RCH_N]) return;
    if (blocked(x, z)) return;
    reach[x + z * RCH_N] = 1;
    q.push(x + z * RCH_N);
  };
  for (let i = 0; i < RCH_N; i++) { push(i, 0); push(i, RCH_N - 1); push(0, i); push(RCH_N - 1, i); }
  for (let h = 0; h < q.length; h++) {
    const c = q[h];
    const x = c % RCH_N, z = (c / RCH_N) | 0;
    push(x + 1, z); push(x - 1, z); push(x, z + 1); push(x, z - 1);
  }
  return reach;
})();
// 某格是否“开放可达”（从地图外沿能走进来）；密封房间返回 false
function reachableGroundCell(gx, gz) {
  if (gx < 0 || gz < 0 || gx >= RCH_N || gz >= RCH_N) return false;
  return !!RCH_REACH[gx + gz * RCH_N];
}
// 竞技场围墙（对战模式）不做 LOS 求交：竞技场内部无房屋/山体，双方都在场内时视为通视，
// 场外越界由移动钳位保证，无需逐条判定四周围墙。
// 线段与一组 AABB 求交（slab 法）；ignoreBelowY = 低于该高度的墙段不参与遮挡
function srvSegBlocked(boxes, x0, y0, z0, x1, y1, z1, ignoreBelowY) {
  const dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
  if (dx * dx + dy * dy + dz * dz < 1e-12) return false;
  const invx = 1 / dx, invy = 1 / dy, invz = 1 / dz;
  for (const bx of boxes) {
    const topY = bx.top;
    if (ignoreBelowY != null && ignoreBelowY >= topY) continue;
    // 楼板等水平薄盒带 minY（下沿）；普通墙体从 0 起算
    const yLo = Math.max(bx.minY != null ? bx.minY : 0, ignoreBelowY != null ? ignoreBelowY : 0);
    let tmin = 0, tmax = 1;
    if (Math.abs(dx) < 1e-9) { if (x0 < bx.minX || x0 > bx.maxX) continue; }
    else {
      let t1 = (bx.minX - x0) * invx, t2 = (bx.maxX - x0) * invx;
      if (t1 > t2) { const t = t1; t1 = t2; t2 = t; }
      tmin = Math.max(tmin, t1); tmax = Math.min(tmax, t2);
      if (tmin > tmax) continue;
    }
    if (Math.abs(dy) < 1e-9) { if (y0 < yLo || y0 > topY) continue; }
    else {
      let t1 = (yLo - y0) * invy, t2 = (topY - y0) * invy;
      if (t1 > t2) { const t = t1; t1 = t2; t2 = t; }
      tmin = Math.max(tmin, t1); tmax = Math.min(tmax, t2);
      if (tmin > tmax) continue;
    }
    if (Math.abs(dz) < 1e-9) { if (z0 < bx.minZ || z0 > bx.maxZ) continue; }
    else {
      let t1 = (bx.minZ - z0) * invz, t2 = (bx.maxZ - z0) * invz;
      if (t1 > t2) { const t = t1; t1 = t2; t2 = t; }
      tmin = Math.max(tmin, t1); tmax = Math.min(tmax, t2);
      if (tmin > tmax) continue;
    }
    if (tmin >= 0 && tmin <= 1) return true;
  }
  return false;
}
// 服务端视线判定：攻击者眼睛(1.5) -> 目标身体中心(0.8+1.0) 是否被墙挡住
function srvHasLOS(ax, az, aFloor, tx, tz, tFloor) {
  if (battleActive) {
    // 竞技场：只受四周围墙约束；双方都在场内（由移动钳位保证）时视为通视
    return true;
  }
  const axw = srvGwxLine(ax), azw = srvGwxLine(az);
  const txw = srvGwxLine(tx), tzw = srvGwxLine(tz);
  const eyeY = (aFloor || 0) + 1.5;        // 攻击者眼睛高度
  const tgtY = (tFloor || 0) + 1.0;        // 目标身体中心
  const lo = Math.min(aFloor || 0, tFloor || 0);
  return !srvSegBlocked(SRV_WALL_BOXES, axw, eyeY, azw, txw, tgtY, tzw, lo);
}

// 判定某格是否适合在“绿色地板”上生成（避开建筑占地与山体）——与前端 groundOk 逐条一致
function groundOk(gx, gz) {
  for (const h of HOUSE_PLAN) {
    const hx0 = h[0] - (h[2] - 1) / 2, hx1 = h[0] + (h[2] - 1) / 2;
    const hz0 = h[1] - (h[3] - 1) / 2, hz1 = h[1] + (h[3] - 1) / 2;
    if (gx >= hx0 - 1 && gx <= hx1 + 1 && gz >= hz0 - 1 && gz <= hz1 + 1) return false;
  }
  const wx = gwxLine(gx), wz = gwxLine(gz);
  for (const mn of MOUNT_SPOTS_SRV) {
    const dx = wx - gwxLine(mn[0]), dz = wz - gwxLine(mn[1]);
    if (dx * dx + dz * dz < mn[2] * mn[2]) return false;
  }
  return true;
}
// 按概率随机一种枪（与前端 rollWeapon 完全一致：60% AK47 / 25% AWM / 15% 沙漠之鹰）
function rollWeapon() { const r = Math.random(); return r < 0.60 ? 'AK47' : r < 0.85 ? 'AWM' : '沙漠之鹰'; }

// ---------- 服务端权威“表面高度”（跳伞落地用） ----------
// 与前端 surfaceHeightAt 同口径：房屋各层楼板顶面 + 山体锥面。返回 (wx,wz) 处可站立的最高表面。
// 旧版服务端跳伞循环只认 skyY<=0（地面），玩家飘到房屋上空时会“穿过屋顶”一直落到地面层——
// 别人视角里就是穿过屋顶掉进楼里，且站楼顶的人能隔着屋顶打到他。
const SRV_MOUNT_SURFACES = [ // [格x, 格z, 半径r, 高h] —— 与前端 buildMountain(...) 逐条一致
  [6, 38, 4, 9], [6, 22, 3.5, 8], [53, 38, 4, 10], [54, 20, 3.5, 8],
  [8, 4, 3, 7], [30, 3, 3.5, 8], [30, 54, 4, 10],
];
function srvSurfaceHeightAt(wx, wz) {
  let h = 0;
  for (const hp of HOUSE_PLAN) {
    const cx = hp[0], cz = hp[1], w = hp[2], d = hp[3], floors = hp[4];
    if (!floors) continue;
    const slabW = (w + 0.5) * SRV_CELL, slabD = (d + 0.5) * SRV_CELL;
    const slabCX = srvGwxLine(cx), slabCZ = srvGwxLine(cz);
    if (wx < slabCX - slabW / 2 || wx > slabCX + slabW / 2) continue;
    if (wz < slabCZ - slabD / 2 || wz > slabCZ + slabD / 2) continue;
    const topY = floors * HOUSE_FLOOR_H_WALL; // 顶层楼板顶面即可站立的屋顶
    if (topY > h) h = topY;
  }
  for (const mn of SRV_MOUNT_SURFACES) {
    const mx = srvGwxLine(mn[0]), mz = srvGwxLine(mn[1]), r = mn[2], mh = mn[3];
    const d = Math.hypot(wx - mx, wz - mz);
    if (d < r) {
      const y = mh * (1 - d / r);
      if (y > h) h = y;
    }
  }
  return h;
}

// 权威武器掉落表：id -> { id, type, x, z, y }（x/z/y 均为“世界单位”，与前端 makeWeaponPickup 口径一致）
const WEAPON_DROPS = new Map();
let wpSeq = 0;
function wpId() { return 'w' + (++wpSeq); }
// 快照单把武器（下发用）
function snapWeapon(w) { return { id: w.id, type: w.type, x: Math.round(w.x * 100) / 100, z: Math.round(w.z * 100) / 100, y: Math.round(w.y * 100) / 100 }; }
// 全量武器列表（按 id 排序，保证各端顺序一致）
function weaponList() {
  const arr = Array.from(WEAPON_DROPS.values());
  arr.sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
  return arr.map(snapWeapon);
}
// 广播武器全量（通常用于开局/结束）
function broadcastWeapons() {
  broadcastRoom(ROOM_MATCH, { type: 'weapons', list: weaponList() });
}

// ---------- 权威弹夹掉落系统 ----------
// 弹夹与武器同架构：服务端唯一生成/维护，全量广播 → 人人看到同一份。
// 拾取后加入对应枪械的备弹（reserve），不同弹夹不能装不同的枪。
const MAG_DROPS = new Map();
let mgSeq = 0;
function mgId() { return 'm' + (++mgSeq); }
function snapMag(mg) { return { id: mg.id, type: mg.type, x: Math.round(mg.x * 100) / 100, z: Math.round(mg.z * 100) / 100, y: Math.round(mg.y * 100) / 100 }; }
function magList() {
  const arr = Array.from(MAG_DROPS.values());
  arr.sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
  return arr.map(snapMag);
}
function broadcastMags() {
  broadcastRoom(ROOM_MATCH, { type: 'mags', list: magList() });
}
function sendMagsTo(p) {
  if (!p || p.ws.readyState !== 1) return;
  const list = (roomOf(p) === ROOM_MATCH) ? magList() : [];
  try { p.ws.send(JSON.stringify({ type: 'mags', list })); } catch (e) {}
}
function clearMags() { MAG_DROPS.clear(); }

// ---------- 权威血包掉落系统 ----------
// 血包：地图上刷新、靠近拾取进背包、按 H 使用（5 秒等待回血 75 滴，期间移速 -20%）。
// 与武器/弹夹同架构：服务端唯一生成/维护，全量广播 → 人人看到同一份。
const MED_DROPS = new Map();
let medSeq = 0;
function medId() { return 'b' + (++medSeq); }
function snapMed(md) { return { id: md.id, type: md.type, x: Math.round(md.x * 100) / 100, z: Math.round(md.z * 100) / 100, y: Math.round(md.y * 100) / 100 }; }
function medList() {
  const arr = Array.from(MED_DROPS.values());
  arr.sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
  return arr.map(snapMed);
}
function broadcastMeds() { broadcastRoom(ROOM_MATCH, { type: 'meds', list: medList() }); }
function sendMedsTo(p) {
  if (!p || p.ws.readyState !== 1) return;
  const list = (roomOf(p) === ROOM_MATCH) ? medList() : [];
  try { p.ws.send(JSON.stringify({ type: 'meds', list })); } catch (e) {}
}
function clearMeds() { MED_DROPS.clear(); }
// 权威撒放血包：数量按人数，算法与 spawnMags 同源（楼内地面 + 空地），只在开放格内生成避免刷进密封房
function spawnMeds(online) {
  clearMeds();
  const n = Math.max(1, online || 1);
  const target = Math.min(20, Math.max(8, 5 + n * 2)); // 2 人 9 个，封顶 20
  const buildingCount = new Map();
  let placed = 0;
  for (; placed < target - 2; placed++) {
    let ok = false;
    for (let guard = 0; guard < 80; guard++) {
      const bi = Math.floor(Math.random() * HOUSE_PLAN.length);
      const h = HOUSE_PLAN[bi];
      const c = buildingCount.get(bi) || 0;
      if (c >= 3) continue;
      const hx0 = h[0] - (h[2] - 1) / 2, hx1 = h[0] + (h[2] - 1) / 2;
      const hz0 = h[1] - (h[3] - 1) / 2, hz1 = h[1] + (h[3] - 1) / 2;
      const x0 = gwxLine(hx0) - 0.25, x1 = gwxLine(hx1) + 0.25;
      const z0 = gwxLine(hz0) - 0.25, z1 = gwxLine(hz1) + 0.25;
      if (x1 - x0 < 0.2 || z1 - z0 < 0.2) continue;
      const md = { id: medId(), type: '血包', x: x0 + Math.random() * (x1 - x0), z: z0 + Math.random() * (z1 - z0), y: 0 };
      MED_DROPS.set(md.id, md);
      buildingCount.set(bi, c + 1);
      ok = true; break;
    }
    if (!ok) break;
  }
  for (let k = 0; k < 2; k++) {
    for (let guard = 0; guard < 120; guard++) {
      const gx = Math.floor(MOVE_MIN_3D + Math.random() * (MOVE_MAX_3D - MOVE_MIN_3D));
      const gz = Math.floor(MOVE_MIN_3D + Math.random() * (MOVE_MAX_3D - MOVE_MIN_3D));
      if (!groundOk(gx, gz)) continue;
      const md = { id: medId(), type: '血包', x: gwxLine(gx) + (Math.random() - 0.5), z: gwxLine(gz) + (Math.random() - 0.5), y: 0 };
      MED_DROPS.set(md.id, md);
      placed++; break;
    }
  }
  console.log('[3d meds] spawned', placed, 'for online', n);
}
// 权威撒放弹夹：数量比枪多（用户要求“弹夹多生成一点”），位置算法与 spawnWeapons 同源（楼内地面 + 空地）。
function spawnMags(online) {
  clearMags();
  const n = Math.max(1, online || 1);
  const target = Math.min(48, Math.max(28, 18 + n * 6)); // 2 人 30 个，封顶 48（用户反馈弹夹太少捡不到，大幅加量）
  const MAG_NAMES = Object.keys(MAG_TYPES);
  const buildingCount = new Map();
  let placed = 0;
  // 楼内地面层（每栋楼至多 3 个）
  for (; placed < target - 2; placed++) {
    let ok = false;
    for (let guard = 0; guard < 80; guard++) {
      const bi = Math.floor(Math.random() * HOUSE_PLAN.length);
      const h = HOUSE_PLAN[bi];
      const c = buildingCount.get(bi) || 0;
      if (c >= 5) continue;
      const hx0 = h[0] - (h[2] - 1) / 2, hx1 = h[0] + (h[2] - 1) / 2;
      const hz0 = h[1] - (h[3] - 1) / 2, hz1 = h[1] + (h[3] - 1) / 2;
      const x0 = gwxLine(hx0) - 0.25, x1 = gwxLine(hx1) + 0.25;
      const z0 = gwxLine(hz0) - 0.25, z1 = gwxLine(hz1) + 0.25;
      if (x1 - x0 < 0.2 || z1 - z0 < 0.2) continue;
      const mg = { id: mgId(), type: MAG_NAMES[Math.floor(Math.random() * MAG_NAMES.length)], x: x0 + Math.random() * (x1 - x0), z: z0 + Math.random() * (z1 - z0), y: 0 };
      MAG_DROPS.set(mg.id, mg);
      buildingCount.set(bi, c + 1);
      ok = true; break;
    }
    if (!ok) break;
  }
  // 空地 2 个（走 groundOk 避开建筑与山体）
  for (let k = 0; k < 2; k++) {
    for (let guard = 0; guard < 120; guard++) {
      const gx = Math.floor(MOVE_MIN_3D + Math.random() * (MOVE_MAX_3D - MOVE_MIN_3D));
      const gz = Math.floor(MOVE_MIN_3D + Math.random() * (MOVE_MAX_3D - MOVE_MIN_3D));
      if (!groundOk(gx, gz)) continue;
      const mg = { id: mgId(), type: MAG_NAMES[Math.floor(Math.random() * MAG_NAMES.length)], x: gwxLine(gx) + (Math.random() - 0.5), z: gwxLine(gz) + (Math.random() - 0.5), y: 0 };
      MAG_DROPS.set(mg.id, mg);
      placed++; break;
    }
  }
  console.log('[3d mags] spawned', placed, 'for online', n);
}
// 弹夹按“槽位”独立存储（两把同型号枪各有自己的弹夹/备弹，互不共享）：
// p.ammo[槽位] = { weapon: 枪型, mag, reserve }
function slotForWeapon(p, wt) { return (p && p.slots) ? p.slots.indexOf(wt) : -1; }
// 给玩家发送“某个槽位的当前弹药”快照（仅发本人）
function sendAmmoTo(p, slot) {
  if (!p || p.ws.readyState !== 1) return;
  const am = (slot >= 0) ? (p.ammo && p.ammo[slot]) : null;
  if (!am) return;
  try { p.ws.send(JSON.stringify({ type: 'ammo', slot, weapon: am.weapon, mag: am.mag, reserve: am.reserve })); } catch (e) {}
}
// 单播武器全量给某个玩家（join/welcome 时补齐）。
// 【关键】按房间下发：只有“对局房”的玩家能看到武器列表；预备房玩家收到空列表。
//   否则预备房玩家会看到一道道对局房里的枪立牌，按 F 又因不在对局而失败 → “能看到却捡不了枪”。
function sendWeaponsTo(p) {
  if (!p || p.ws.readyState !== 1) return;
  const list = (roomOf(p) === ROOM_MATCH) ? weaponList() : [];
  try { p.ws.send(JSON.stringify({ type: 'weapons', list })); } catch (e) {}
  sendMagsTo(p); // 弹夹列表与武器同口径下发（预备房为空）
  sendMedsTo(p); // 血包列表同口径下发（预备房为空）
}
// 新增一把武器并广播（丢弃/补撒共用）。x/z/y 为世界单位。
function wpAdd(type, x, z, y, throwFrom) {
  const w = { id: wpId(), type, x, z, y: Math.max(0, y || 0) };
  WEAPON_DROPS.set(w.id, w);
  const snap = snapWeapon(w);
  // 抛掷起点（可选）：丢枪时带上，客户端据此播放“从手里抛出去”的抛物线动画；
  // 开局撒枪/补枪等路径不带 → 客户端退回普通落体。
  if (throwFrom) { snap.fx = throwFrom.fx; snap.fy = throwFrom.fy; snap.fz = throwFrom.fz; }
  broadcastRoom(ROOM_MATCH, { type: 'wpAdd', weapon: snap });
  return w;
}
// 移除一把武器并广播（被拾取）
function wpRemove(idW) {
  if (!WEAPON_DROPS.has(idW)) return false;
  WEAPON_DROPS.delete(idW);
  broadcastRoom(ROOM_MATCH, { type: 'wpRemove', id: idW });
  return true;
}
function clearWeapons() { WEAPON_DROPS.clear(); }
// 权威撒放武器：算法与前端 spreadWeapons 一致（楼内地面层为主、空地稀有、每栋楼至多 2 把、沙漠之鹰保底）。
// 唯一的差别是“在服务端一次性生成”，所有客户端共享同一份列表 → 每个人看到的枪位置完全一致。
//
// 【不再往楼顶撒枪】旧版有 35%~50% 的概率把枪放在楼顶（y = 层数 × 楼层层高，最高 6 层 = 12 世界单位）。
// 楼顶是“必须爬上去才能到”的位置：玩家在楼下看到枪的立牌却怎么走都够不着，
// 拾取只会一直回“距离太远” → 体感就是“楼上的枪根本捡不起来”。现在枪一律撒在
// “走到就能拿”的地面层（楼内的地面层 / 室外空地，y 恒为 0）。
function spawnWeapons(online) {
  clearWeapons();
  const n = Math.max(1, online || 1);
  // 数量：用户反馈“枪的刷新率太少了”，在 6+n 的基础上加大到 8 + n*3（2 人 14 把，封顶 26 把），
  // 配合下方的周期补枪，地上始终有足够的枪可捡。
  const target = Math.min(26, Math.max(14, 8 + n * 3));
  const MAX_INDOOR = Math.max(1, target - 3);
  const ROOF_P = 0.3;            // 楼顶撒枪概率：楼顶平台中心经跳伞可落上去站住，玩家落地后仍可拾取
  const buildingCount = new Map();
  let hasDesert = false;
  const placeOne = (type, wx, wz, y) => { if (type === '沙漠之鹰') hasDesert = true; wpAddSilent(type, wx, wz, y); };
  for (let placed = 0; placed < MAX_INDOOR; placed++) {
    let ok = false;
    for (let guard = 0; guard < 80; guard++) {
      const bi = Math.floor(Math.random() * HOUSE_PLAN.length);
      const h = HOUSE_PLAN[bi];
      const c = buildingCount.get(bi) || 0;
      if (c >= 2) continue;
      const floors = h[4] || 1;
      // 楼顶撒枪（y=层数×层高）：屋顶是露天平台，跳伞/落地都能直接站上去拾取，不受密封影响
      if (Math.random() < ROOF_P) {
        placeOne(rollWeapon(), gwxLine(h[0]), gwxLine(h[1]), floors * HOUSE_FLOOR_H_WALL);
        buildingCount.set(bi, c + 1);
        ok = true; break;
      }
      const hx0 = h[0] - (h[2] - 1) / 2, hx1 = h[0] + (h[2] - 1) / 2;
      const hz0 = h[1] - (h[3] - 1) / 2, hz1 = h[1] + (h[3] - 1) / 2;
      // 只在“从外面能走进来”的开放格（格中心）内撒枪，避免落在密封房捡不到
      const x0 = Math.max(0, Math.round(hx0)), x1 = Math.min(RCH_N - 1, Math.round(hx1));
      const z0 = Math.max(0, Math.round(hz0)), z1 = Math.min(RCH_N - 1, Math.round(hz1));
      if (x1 < x0 || z1 < z0) continue;
      let found = false;
      for (let t = 0; t < 40 && !found; t++) {
        const gx = x0 + Math.floor(Math.random() * (x1 - x0 + 1));
        const gz = z0 + Math.floor(Math.random() * (z1 - z0 + 1));
        if (!reachableGroundCell(gx, gz)) continue;
        placeOne(rollWeapon(), gwxLine(gx), gwxLine(gz), 0); // 开放格地面层
        found = true;
      }
      if (!found) continue;
      buildingCount.set(bi, c + 1);
      ok = true; break;
    }
    if (!ok) break;
  }
  const GROUND_TOTAL = (Math.random() < 0.5 ? 1 : 2) + (n >= 5 ? 1 : 0);
  let gGuard = 0;
  for (let i = 0; i < GROUND_TOTAL; i++) {
    while (gGuard++ < 300) {
      const gx = 6 + Math.floor(Math.random() * (MAZE_SIZE - 12));
      const gz = 6 + Math.floor(Math.random() * (MAZE_SIZE - 12));
      if (!groundOk(gx, gz)) continue;
      placeOne(rollWeapon(), gwxLine(gx), gwxLine(gz), 0);
      break;
    }
  }
  if (!hasDesert) {
    for (let t = 0; t < 200; t++) {
      const bi = Math.floor(Math.random() * HOUSE_PLAN.length);
      const h = HOUSE_PLAN[bi];
      const hx0 = h[0] - (h[2] - 1) / 2, hx1 = h[0] + (h[2] - 1) / 2;
      const hz0 = h[1] - (h[3] - 1) / 2, hz1 = h[1] + (h[3] - 1) / 2;
      const x0 = Math.max(0, Math.round(hx0)), x1 = Math.min(RCH_N - 1, Math.round(hx1));
      const z0 = Math.max(0, Math.round(hz0)), z1 = Math.min(RCH_N - 1, Math.round(hz1));
      if (x1 < x0 || z1 < z0) continue;
      const gx = x0 + Math.floor(Math.random() * (x1 - x0 + 1));
      const gz = z0 + Math.floor(Math.random() * (z1 - z0 + 1));
      if (!reachableGroundCell(gx, gz)) continue;
      // 保底那把沙漠之鹰同样只放地面层（理由见 spawnWeapons 顶部注释：楼顶的枪够不着）
      wpAddSilent('沙漠之鹰', gwxLine(gx), gwxLine(gz), 0);
      break;
    }
  }
  console.log('[3d weapons] spawned', WEAPON_DROPS.size, 'for online', n);
}
// 静默新增（生成阶段用，避免逐把广播；生成完统一 broadcastWeapons）
function wpAddSilent(type, x, z, y) {
  const w = { id: wpId(), type, x, z, y: Math.max(0, y || 0) };
  WEAPON_DROPS.set(w.id, w);
  return w;
}
// 补撒差额（保留给特殊场景调用；当前主流程：开局 spawnWeapons 按人数公式一次性生成固定数量，
// 不做任何自动补枪 —— 用户明确要求“不要自动补，设个固定值按照人数公式来”）
let wpSpawnedOnline = 1;
function refillWeapons(online, force) {
  const n = Math.max(1, online || 1);
  if (!force && n <= wpSpawnedOnline) { wpSpawnedOnline = Math.max(wpSpawnedOnline, n); return 0; }
  // 目标总量与 spawnWeapons 保持同一公式（8 + n*3，封顶 26）：只补差额，不叠床架屋加枪
  const target = Math.min(26, Math.max(14, 8 + n * 3));
  let need = target - WEAPON_DROPS.size;
  wpSpawnedOnline = n;
  if (need <= 0) return 0;
  need = Math.min(need, 8);
  const buildingCount = new Map();
  for (const w of WEAPON_DROPS.values()) {
    for (let bi = 0; bi < HOUSE_PLAN.length; bi++) {
      const h = HOUSE_PLAN[bi];
      const hx0 = h[0] - (h[2] - 1) / 2, hx1 = h[0] + (h[2] - 1) / 2;
      const hz0 = h[1] - (h[3] - 1) / 2, hz1 = h[1] + (h[3] - 1) / 2;
      if (w.x >= gwxLine(hx0) - 0.3 && w.x <= gwxLine(hx1) + 0.3 && w.z >= gwxLine(hz0) - 0.3 && w.z <= gwxLine(hz1) + 0.3) {
        buildingCount.set(bi, (buildingCount.get(bi) || 0) + 1); break;
      }
    }
  }
  const added = [];
  for (let i = 0; i < need; i++) {
    let done = false;
    for (let guard = 0; guard < 80; guard++) {
      const bi = Math.floor(Math.random() * HOUSE_PLAN.length);
      const h = HOUSE_PLAN[bi];
      const c = buildingCount.get(bi) || 0;
      if (c >= 2) continue;
      const hx0 = h[0] - (h[2] - 1) / 2, hx1 = h[0] + (h[2] - 1) / 2;
      const hz0 = h[1] - (h[3] - 1) / 2, hz1 = h[1] + (h[3] - 1) / 2;
      const x0 = gwxLine(hx0) - 0.25, x1 = gwxLine(hx1) + 0.25;
      const z0 = gwxLine(hz0) - 0.25, z1 = gwxLine(hz1) + 0.25;
      if (x1 - x0 < 0.2 || z1 - z0 < 0.2) continue;
      const w = wpAddSilent(rollWeapon(), x0 + Math.random() * (x1 - x0), z0 + Math.random() * (z1 - z0), 0); // 只撒地面层
      buildingCount.set(bi, c + 1);
      added.push(w); done = true;
      break;
    }
    if (!done) break;
  }
  if (added.length) broadcastRoom(ROOM_MATCH, { type: 'wpAddBatch', weapons: added.map(snapWeapon) });
  if (added.length) console.log('[3d weapons] refilled', added.length, 'for online', n);
  return added.length;
}

// 高空随机散布点（格坐标）：对战地图在竞技场内随机，否则整张迷宫+广场上空随机
function randomSkyPos() {
  if (battleActive) {
    const x = BATTLE_INSIDE_MIN + Math.random() * (BATTLE_INSIDE_MAX - BATTLE_INSIDE_MIN);
    const z = BATTLE_INSIDE_MIN + Math.random() * (BATTLE_INSIDE_MAX - BATTLE_INSIDE_MIN);
    return { x: Math.round(x * 100) / 100, z: Math.round(z * 100) / 100 };
  }
  const x = MOVE_MIN_3D + 0.5 + Math.random() * (MOVE_MAX_3D - MOVE_MIN_3D - 1);
  const z = MOVE_MIN_3D + 0.5 + Math.random() * (MOVE_MAX_3D - MOVE_MIN_3D - 1);
  return { x: Math.round(x * 100) / 100, z: Math.round(z * 100) / 100 };
}
// 跳伞高度转“立牌中心 y”（与 move.y 语义一致，0.8 为贴地立牌中心）
function skyYPayload(p) {
  return Math.round((0.8 + (p.skyY || 0)) * 100) / 100;
}
// 将单个玩家送入跳伞开局：高空出生 + 随机散布，并单播 parachuteStart 让该端立即渲染
function startParachute(p) {
  if (!p || p.ws.readyState !== 1) return;
  const sk = randomSkyPos();
  p.parachuting = true;
  p.skyY = PARACHUTE_Y;
  p.chuteBoost = false; // 新一轮跳伞复位加速标记（防上一局残留的 Shift 状态）
  p.hp = MAX_HP; p.respawnAt = 0;
  p.x = sk.x; p.z = sk.z; p.angle = 0;
  try {
    p.ws.send(JSON.stringify({ type: 'parachuteStart', players: [{ id: p.id, x: p.x, z: p.z, angle: 0, y: skyYPayload(p) }] }));
  } catch (e) {}
  broadcastRoom(roomOf(p), { type: 'move', id: p.id, x: p.x, z: p.z, angle: 0, y: skyYPayload(p) }, p.id);
}
// 断线重连后“继续”原有跳伞：沿用当前剩余高度与坐标，不重置回 150 高空。
// 这样本人与他人看到的都只是断线前后的正常进度，避免他人视角出现“重新飞到高空又落下来”的反复动画。
function resumeParachute(p) {
  if (!p || p.ws.readyState !== 1) return;
  const y = skyYPayload(p);
  try { p.ws.send(JSON.stringify({ type: 'parachuteStart', players: [{ id: p.id, x: p.x, z: p.z, angle: p.angle || 0, y }] })); } catch (e) {}
  broadcastRoom(roomOf(p), { type: 'move', id: p.id, x: p.x, z: p.z, angle: p.angle || 0, y }, p.id);
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
          // 【改名修复】档案复用（断线重连/刷新后重进）必须同步采用本次 join 上报的新名字：
          // 旧逻辑沿用档案里的旧名，玩家在首页改名后“看起来没反应”，他人视角依旧显示 角色1/角色2。
          p.name = name;
          // 关键：本连接的 id 必须与档案 id 对齐，后续消息分发与 close 清理都按该 id 查表
          id = p.id;
          p.disconnectedAt = 0;
          p.hp = MAX_HP;
          p.respawnAt = 0;
          p.lastAttack = 0;
          p.deathCount = 0;
          // 复位对局残留标记：否则对战中掉线、战后重连的玩家会带着 escaped=true 回迷宫，
          // 导致其“成功离开了迷宫”广播被永久吞掉；team 残留也会影响队友色判定
          p.escaped = false;
          p.team = null;
          // 复位游戏开发态（无敌/一击/连击/距离/友伤），防止断线档案把上一局状态带进新对局；
          // 客户端 onopen 会按本地开关重新上报，只有真正开启的才会恢复
          p.god = false; p.onehit = false; p.infinit = false; p.unlimited = false; p.noRange = false; p.penetrate = false;
          p.medkitCount = 0; p.usingMedkit = false;
          if (p.medTimer) { clearTimeout(p.medTimer); p.medTimer = null; }
          // 跳伞/落地状态：仅“页面刷新（fresh）”才复位到地面。
          // 同一页面内的自动重连（网络抖动，fresh!==true）必须保留原状态，
          // 否则每次重连都把已落地的玩家重置成“空中状态”，再叠加下方的恢复逻辑，
          // 就会在他人视角出现“反复飞到高空又落下来”，本人也会因 parachuting=true 而无法拾取武器。
          if (m.fresh === true) { p.parachuting = false; p.skyY = 0; p.floorH = 0; }
          // 页面刷新（F5）会让前端背包归零 → 服务端记录的手持武器也必须清空，
          // 否则他人会继续在你立牌上看到一把你其实已经不在手上的枪。
          // 同页面自动重连（网络抖动，fresh!==true）保持不变，前端会自行重新上报手持武器。
          if (m.fresh === true) p.weapon = null;
          // 页面刷新（F5/强制刷新）属于“重新开局”：坐标复位到出生点。
          // 否则会带着刷新前的坐标回到场上（常正好叠在别人身上）→ 表现为“刷新后没有出现在出生点”。
          // 只认 fresh 标记：同一页面内的自动重连（网络抖动）保持原位，不打断对局。
          if (m.fresh === true) {
            const sp = mazeSpawnPos(p.id);
            if (sp) { p.x = sp.x; p.z = sp.z; }
            p.angle = 0;
          }
          PLAYERS_3D.set(p.id, p);
          joined = true;
          ws.send(JSON.stringify(welcomeFor(p, connectedCount())));
          // 重连同步：若该玩家之前处于死亡冷却，补发 respawn 让其前端解除死亡态，
          // 否则前端一直等 respawn（myDead=true）导致界面卡死/无法操作。
          p.ws.send(JSON.stringify({ type: 'respawn', id: p.id, x: p.x, z: p.z, hp: p.hp }));
          // 【关键修复】重连必须补发全局“开始游戏”状态 + 房间归属：
          //   旧逻辑只在新档案分支发过 gameState，重连分支从不发 → 客户端 gameOn 掉回默认 false，
          //   表现为“明明已经开局，界面却显示未开始，且拾取/攻击被前端直接拦掉”。
          p.ws.send(JSON.stringify({ type: 'gameState', on: gameOn }));
          // 若重连时他本就处于对局房，补发 enterMatch 让前端把 inMatch/gameOn/gamePhase 全部置为“对局中”。
          if (gameOn && roomOf(p) === ROOM_MATCH) {
            try { p.ws.send(JSON.stringify({ type: 'enterMatch' })); } catch (e) {}
          }
          broadcastRoom(roomOf(p), { type: 'join', player: snap3d(p) }, p.id);
          broadcastPlayers3d();
          console.log('[3d rejoin]', p.name, 'kills:', p.kills, 'room:', roomOf(p), 'gameOn:', gameOn, 'total:', PLAYERS_3D.size);
          // 分配阶段重连：仅在确有开发者时才进入等待分配；否则清掉无主的残留分配状态
          if (battlePlanning) { if (liveDev()) enterBattlePlanning(p); else resetBattleState(true); }
          if (battleActive) enterLiveBattle(p);
          sendZoneTo(p); // 重连补发毒圈当前状态（新一轮开局/已缩圈时也能立即看到）
          sendWeaponsTo(p); // 重连补发权威武器全量列表
          // 对局房重连的高度同步（关键修复）：
          //   - 断线时仍在空中 → 按“剩余高度”继续跳伞，绝不重置回 150 高空。
          //     旧逻辑无条件 startParachute，导致每次网络抖动重连都把玩家重新扔上天：
          //     他人视角表现为“反复飞到空中又降下来”，本人则因 parachuting=true 无法拾取武器。
          //   - 已落地 → 保持落地，并补播一次落地坐标，让本人/他人解除可能残留的空中态。
          if (gameOn && roomOf(p) === ROOM_MATCH) {
            if (p.parachuting) {
              resumeParachute(p);
            } else {
              const ly = Math.round((0.8 + (p.floorH || 0)) * 100) / 100;
              try { p.ws.send(JSON.stringify({ type: 'parachuteLand', id: p.id, x: p.x, z: p.z, y: ly })); } catch (e) {}
              broadcastRoom(roomOf(p), { type: 'move', id: p.id, x: p.x, z: p.z, angle: p.angle || 0, y: ly }, p.id);
            }
          }
          break;
        }
        ws.send(JSON.stringify({ type: 'welcome', id, players: roomPlayers(ROOM_PREP).map(snap3d), online: connectedCount() + 1, room: ROOM_PREP, phase: gamePhase }));
        ws.send(JSON.stringify(globalFlagsPayload())); // 中途加入/重连的玩家也要立即拿到当前全局参数（全局开关对所有人生效）
        // 出生点：入口无障碍区内挑“离其他人最远”的点，避免多人同时进入时叠在一起
        const npSpawn = mazeSpawnPos();
        const np = { id, name, role, x: npSpawn.x, z: npSpawn.z, angle: 0, escaped: false, hp: MAX_HP, lastAttack: 0, respawnAt: 0, kills: 0, god: false, onehit: false, infinit: false, unlimited: false, noRange: false, team: null, isBoss: false, deathCount: 0, token: token || null, disconnectedAt: 0, parachuting: false, skyY: 0, floorH: 0, weapon: null, ammo: {}, slots: [null, null], reloading: false, reloadTimer: null, lastShotAt: 0, medkitCount: 0, usingMedkit: false, medTimer: null, room: ROOM_PREP, ws };
        // 注意：token 只在 close 时写入 TOKENS_3D，此处不注册，
        // 否则同一 token 的第二个标签页会误当“重连”挤占第一个标签页/在他人视角制造分身。
        PLAYERS_3D.set(id, np);
        joined = true;
        // 重发一次 welcome（此刻已把自己算进人数，players 全长即在线总数，保证各端在线人数一致）
        ws.send(JSON.stringify(welcomeFor(np, connectedCount())));
        broadcastRoom(ROOM_PREP, { type: 'join', player: snap3d(np) }, id);
        // 新档案也补发一次 respawn：让刚打开/刷新页面的客户端立刻对齐服务端出生点（坐标权威在服务端）
        ws.send(JSON.stringify({ type: 'respawn', id, x: np.x, z: np.z, hp: MAX_HP }));
        // 新连接同步全局“开始游戏”状态（未开始时全场不可攻击）
        ws.send(JSON.stringify({ type: 'gameState', on: gameOn }));
        broadcastPlayers3d();
        console.log('[3d join]', name, 'total:', PLAYERS_3D.size);
        // 不在 join 时补枪：枪只在开局按人数公式一次性生成固定数量（用户要求，杜绝自动补枪）
        // 分配阶段新加入：仅在确有开发者时才进入等待分配；否则清掉无主的残留分配状态
        if (battlePlanning) { if (liveDev()) enterBattlePlanning(np); else resetBattleState(true); }
        if (battleActive) enterLiveBattle(np);
        sendZoneTo(np); // 新进入者同步毒圈当前状态
        // 新进入者同步权威武器全量列表（预备房也会收到，但前端仅在进入对局后才渲染）
        sendWeaponsTo(np);
        // 游戏进行中新加入：默认进预备房（看不到对局、无毒圈、无敌人），不再拉进对局。
        // 只有本轮对局结束时（endGame → 全部回预备房），下次“开始游戏”才会把他一起打包进对局。
        break;
      }
      case 'move': {
        const p = PLAYERS_3D.get(id);
        if (!p || !joined) return;
        // 死亡复活冷却期间禁止移动
        if (p.hp <= 0) return;
        if (battleActive) { p.x = clampBattle(Number(m.x)); p.z = clampBattle(Number(m.z)); }
        else { p.x = clamp3dMove(Number(m.x)); p.z = clamp3dMove(Number(m.z)); }
        p.angle = Number(m.angle) || p.angle;
        // 【关键·治“在高楼上一直一抽一抽”】客户端上报的 y = 0.8(立牌中心基准) + 脚底所在表面高度
        //（楼内地面层=0、楼顶=层数×2），这里把它同步回 p.floorH。
        //
        // 旧代码只在“跳伞落地”那一瞬间写过 floorH，之后再没更新过。于是玩家爬上楼以后：
        //   · move 广播带的是“玩家真实所在层”的 y（例如楼顶 12.8）
        //   · 但每 2 秒一次的 players 全量对账用的是旧 floorH 推算的 y（例如落地时的 0.8）
        // 两条消息交替刷新远端立牌的目标高度 → 立牌在楼顶与地面之间反复插值，
        // 别人视角里就是“一直往上飘一点又掉下来”的一抽一抽。
        // 顺带修正：丢枪（wpDrop）也用 p.floorH 决定枪落在哪一层，同步后不会再丢错楼层。
        if (!p.parachuting) {
          const ry = Number(m.y);
          if (Number.isFinite(ry)) p.floorH = Math.max(0, Math.round((ry - 0.8) * 100) / 100);
        }
        // 跳伞中：y 一律以服务端权威 skyY 为准（防客户端伪造高度/瞬间落地）；非跳伞才信任客户端 y
        // 只广播给同房间的人（预备房与对局房不串场）
        broadcastRoom(roomOf(p), { type: 'move', id, x: p.x, z: p.z, angle: p.angle, y: p.parachuting ? skyYPayload(p) : (Number(m.y) || 0.3) }, id);
        break;
      }
      case 'chuteGo': {
        // 客户端跳伞中申请高空传送：服务端确认跳伞状态（防绕过），并以权威 y 广播一次 move
        const p = PLAYERS_3D.get(id);
        if (!p || !joined || !gameOn || !p.parachuting) return;
        const cx = Number(m.x), cz = Number(m.z);
        if (Number.isFinite(cx) && Number.isFinite(cz)) {
          if (battleActive) { p.x = clampBattle(cx); p.z = clampBattle(cz); }
          else { p.x = clamp3dMove(cx); p.z = clamp3dMove(cz); }
        }
        broadcastRoom(roomOf(p), { type: 'move', id, x: p.x, z: p.z, angle: p.angle, y: skyYPayload(p) }, id);
        break;
      }
      case 'chuteBoost': {
        // 跳伞中按住 Shift 加速下落：客户端在 Shift 按下/抬起时上报，权威下落循环按同倍率推进，
        // 保证“本地动画落地的时机”与“服务端权威落地的时机”一致（不提前、不滞后）。
        const p = PLAYERS_3D.get(id);
        if (!p || !joined || !p.parachuting) return;
        p.chuteBoost = !!Number(m.on);
        break;
      }
      case 'chuteLandLocal': {
        // 客户端本地动画已落地：服务端幂等确认（可能已由权威循环落地，重复落地无副作用）。
        // 客户端上报 h=落地表面高度（楼顶/山体表面），服务端权威记录 floorH 并广播带高度落地，
        // 让其他玩家看到该玩家站上楼顶/山顶，而非固定贴地 0.8。
        const p = PLAYERS_3D.get(id);
        if (!p || !joined || !p.parachuting) return;
        const srvSurf = srvSurfaceHeightAt(srvGwxLine(p.x), srvGwxLine(p.z));
        // 权威表面优先：落在房屋上空时 floorH 至少为屋顶高度（杜绝穿顶落地到楼内）；
        // 山顶/地面沿用客户端上报值（锥面斜坡逐点高度由客户端动画精确给出）。
        const hRaw = Math.max(0, Math.min(PARACHUTE_Y, Number(m.h) || 0));
        const h = srvSurf > 0 ? Math.max(hRaw, srvSurf) : hRaw;
        p.skyY = 0; p.parachuting = false; p.floorH = h;
        broadcastRoom(roomOf(p), { type: 'parachuteLand', id: p.id, x: p.x, z: p.z, y: Math.round((0.8 + h) * 100) / 100 });
        broadcastRoom(roomOf(p), { type: 'move', id: p.id, x: p.x, z: p.z, angle: p.angle, y: Math.round((0.8 + h) * 100) / 100 }, p.id);
        break;
      }
      case 'attack': {
        const p = PLAYERS_3D.get(id);
        if (!p || !joined) return;
        // 未“开始游戏”时全场禁止攻击（开发者面板“开始游戏”后才可攻击）
        if (!gameOn) return;
        const now = Date.now();
        // 死亡冷却中不可攻击；攻击间隔限制连击
        if (p.hp <= 0) return;
        // 支持跳伞战斗：攻击者在空中同样可以攻击
        // 对战已分出胜负：停止攻击
        if (battleActive && battleOver) return;
        // 无限连击时跳过攻击间隔；否则按“当前武器开火间隔”限制连击
        // （fist/未知武器沿用 ATTACK_CD；枪械用 WEAPON_STATS.interval，AWM 1.5s / 沙鹰 0.33s / AK 0.1s）
        const atkStat = m.weapon ? WEAPON_STATS[String(m.weapon)] : null;
        const atkCd = atkStat ? Math.round(atkStat.interval * 1000) : ATTACK_CD;
        if (!p.infinit && now - p.lastAttack < atkCd) return;
        // 目标有效性校验（服务端权威）：
        //  1) 只认“真实在线”的玩家（isLivePlayer：socket 打开且心跳正常）——断线残留/心跳失效的记录不能被选中，
        //     否则会打到一个服务器上早已断开的人身上，真正的目标却不掉血（表现为“攻击无效”/幽灵角色）；
        //  2) 客户端可携带 targetId 指定它锁定的目标：指定了就只校验该目标，校验不过直接不出招，
        //     不再“就近改打别人”（避免打 A 却打到 B 的错乱）；旧客户端不带 targetId 时退化为“范围内最近的合法目标”；
        //  3) 命中判定把目标当“有体积的圆”：命中距离 = 攻击范围 + 命中半径。
        // 命中判定把目标当“有体积的圆”：命中距离 = 该武器攻击范围 + 命中半径。
        // 攻击范围按本次攻击携带的 weapon 区分（近战短、狙击长），与前端同一张 WEAPON_RANGE 表。
        const reach = p.noRange ? Infinity : (weaponRange(m.weapon) / CELL_SIZE_WORLD + HIT_RADIUS_GRID);
        const targetDistance = (o) => Math.sqrt((o.x - p.x) ** 2 + (o.z - p.z) ** 2);
        const validTarget = (o) => {
          if (!o || o.id === id) return false;
          if (!isLivePlayer(o)) return false; // 关键：只打真实在线的人
          if (roomOf(o) !== roomOf(p)) return false; // 关键：只打“同房间”的人（预备房与对局房不互相攻击）
          if (o.hp <= 0 || o.god) return false;
          // 支持跳伞战斗：跳伞中的目标（空中）可以被攻击
          if (battleActive && p.team != null && o.team === p.team) return false;
          if (targetDistance(o) > reach) return false;
          // 禁止隔墙攻击（服务端权威视线）：攻击者眼睛与目标身体中心之间被房屋/山体挡住 → 不出招。
          // 与前端 hasLineOfSight3D 同口径（前端负责提示一致性，此处负责防绕过/防改包）。
          // 例外：攻击者个人隔墙打（p.penetrate，子弹穿墙）开启时豁免视线校验，子弹穿透墙体攻击。
          if (!p.penetrate && !srvHasLOS(p.x, p.z, p.floorH || 0, o.x, o.z, o.floorH || 0)) return false;
          return true;
        };
        let target = null;
        if (m.targetId != null) {
          const want = PLAYERS_3D.get(String(m.targetId));
          if (validTarget(want)) target = want;
        } else {
          let best = Infinity;
          for (const o of PLAYERS_3D.values()) {
            if (!validTarget(o)) continue;
            const d = targetDistance(o);
            if (d < best) { best = d; target = o; }
          }
        }
        // 弹药校验与消耗（服务端权威）：枪械必须“弹夹有弹 + 不在换弹中”才能出招。
        // 空打（即使没锁定目标）也会扣弹、占用攻击间隔；unlimited（无限）豁免弹药
        //（个人开关 p.unlimited 或 开发者面板全局开关 GLOBAL_DEV.unlimited 任一开启即豁免）。
        if (atkStat && !p.unlimited && !GLOBAL_DEV.unlimited) {
          if (p.reloading) { try { p.ws.send(JSON.stringify({ type: 'wpDenied', id: '', reason: '换弹中…', kind: 'reload' })); } catch (e) {} break; }
          let si = (m.slot === 0 || m.slot === 1) ? m.slot : -1;
          if (!(si >= 0 && p.slots[si] === String(m.weapon))) si = slotForWeapon(p, String(m.weapon)); // 客户端槽位不匹配则按枪型兜底
          const am = si >= 0 ? p.ammo[si] : null;
          if (!am || am.mag <= 0) { try { p.ws.send(JSON.stringify({ type: 'wpDenied', id: '', reason: '弹匣已空，按 R 换弹' })); } catch (e) {} break; }
          am.mag--;
          sendAmmoTo(p, si);
        }
        p.lastAttack = now;
        // 校验不通过（目标已断线/已离开/超距/友军/无敌）→ 本次不出伤害（空打：弹已扣、间隔已占）
        if (!target) break;
        const dmg = weaponDamage(m.weapon);
        target.hp = Math.max(0, target.hp - (p.onehit ? target.hp : dmg));
        // 击退已取消：命中不再将目标沿攻击方向推开，角色保持原站定位置（不再位移）
        const nx = target.x, nz = target.z;
        const attackMsg = { type: 'attack', attackerId: id, attackerName: p.name, targetId: target.id, damage: dmg, hp: target.hp, nx, nz };
        // 无圈模式：目标死亡后会立即重跳伞（服务端补发了 respawn 让其他人重显立牌）。
        // 打上 rechute 标记，让客户端别再用本条的 hp=0 把它“再次隐藏”，也别把坐标拉回旧地面位置。
        if (target.hp <= 0 && gameMode === 'respawn') attackMsg.rechute = true;
        if (target.hp <= 0) {
          attackMsg.respawnDelay = applyKill3d(p, target, now);
          broadcastKills3d();
          // 对战计分：攻击者所在队伍 +1 分，先到 WIN_SCORE 的一队获胜（Boss 模式不走计分，胜负由 endBossBattle 判定）
          if (battleActive && !bossBattle && p.team != null && target.team !== p.team) {
            // 仅击杀敌方计分；友伤击杀队友不给本方加分，杜绝同队互杀刷分
            const t = p.team;
            const sc = (teamScores.get(t) || 0) + 1;
            teamScores.set(t, sc);
            broadcast3d({ type: 'teamScore', team: t, score: sc, teams: Array.from(teamScores.entries()) });
            if (sc >= WIN_SCORE) endBattle(t, now);
          }
        }
        broadcastRoom(roomOf(p), attackMsg);
        break;
      }
      case 'setgod': {
        const p = PLAYERS_3D.get(id);
        if (!p || !joined) return;
        p.god = !!m.on;
        break;
      }
      case 'setonehit': {
        const p = PLAYERS_3D.get(id);
        if (!p || !joined) return;
        p.onehit = !!m.on;
        break;
      }
      case 'setinfinit': {
        const p = PLAYERS_3D.get(id);
        if (!p || !joined) return;
        p.infinit = !!m.on;
        break;
      }
      case 'setunlimited': {
        const p = PLAYERS_3D.get(id);
        if (!p || !joined) return;
        p.unlimited = !!m.on;
        break;
      }
      case 'setnorange': {
        const p = PLAYERS_3D.get(id);
        if (!p || !joined) return;
        p.noRange = !!m.on;
        break;
      }
      case 'setpenetrate': {
        // 个人隔墙打（子弹穿墙，仅本人生效）：开启后攻击豁免服务端视线校验，子弹可穿透墙体打人。
        // 注意：只影响子弹（attack 校验），不影响移动（人物不能穿墙）；与个人穿墙/全局穿墙完全独立。
        const p = PLAYERS_3D.get(id);
        if (!p || !joined) return;
        p.penetrate = !!m.on;
        break;
      }
      case 'setnoChute': {
        // “无需跳伞”（全局开关，仅开发者可切）：影响下一局开局与无圈模式的每次复活
        const p = PLAYERS_3D.get(id);
        if (!p || !joined || !isDev(p)) return;
        noChute = !!m.on;
        broadcast3d(globalFlagsPayload()); // 同步全场勾选状态（面板互相同步）
        console.log('[3d game] noChute =', noChute, 'by', p.name);
        break;
      }
      case 'setglobal': {
        // 开发者面板“全局参数”（仅开发者可切）：key ∈ noChute/noclip/bhop/xray/unlimited；
        // 切换后向全场广播 devGlobal，所有客户端同步生效（本地机制开关 + 面板勾选状态）。
        const p = PLAYERS_3D.get(id);
        if (!p || !joined || !isDev(p)) return;
        const k = m.key;
        if (k === 'noChute') {
          noChute = !!m.on;
        } else if (k === 'noclip' || k === 'bhop' || k === 'xray' || k === 'unlimited') {
          GLOBAL_DEV[k] = !!m.on;
        } else {
          return; // 未知 key：直接忽略，不广播（防脏数据进全场）
        }
        broadcast3d(globalFlagsPayload());
        console.log('[3d game] global', k, '=', k === 'noChute' ? noChute : GLOBAL_DEV[k], 'by', p.name);
        break;
      }
      case 'wpPickup': {
        // 拾取武器（权威）：服务端校验“确有其枪 + 同房 + 存活 + 距离在拾取范围内”，通过才删除并广播。
        // 参数：{ type:'wpPickup', id:<武器id>, weapon:<枪型> }。客户端 x/z 只作为可信度较低的参考，最终以服务端记录为准。
        // 任一校验不通过都会回一条 wpDenied（带原因），确保客户端能给出明确反馈而不是“按了没反应”。
        const p = PLAYERS_3D.get(id);
        if (!p || !joined) return;
        const deny = (why) => { try { p.ws.send(JSON.stringify({ type: 'wpDenied', id: String(m.id), reason: why })); } catch (e) {} };
        if (!gameOn) { deny('对局未开始'); return; }
        if (roomOf(p) !== ROOM_MATCH) { deny('你不在对局中'); return; } // 武器只存在于对局房：预备房不可拾取
        if (p.hp <= 0) { deny('你已阵亡，无法拾取'); return; }
        if (p.parachuting) { deny('跳伞落地后才能拾取'); return; }
        const w = WEAPON_DROPS.get(String(m.id));
        if (!w) { deny('该武器已被他人拾取'); return; }
        // 距离校验：服务端权威坐标（格）→ 世界，与武器世界坐标比较。
        // 只算水平距离（口径与前端 aimedWeapon 一致）：枪立牌贴地，高度差不参与判定。
        const pwx = gwxLine(p.x), pwz = gwxLine(p.z);
        const hd = Math.hypot(pwx - w.x, pwz - w.z);
        if (hd > WEAPON_PICK_RANGE + 0.9) {
          // 提示里带上实际距离，便于玩家判断“还差多远”，也便于日后排查是坐标问题还是真的走远了
          deny('距离太远（' + hd.toFixed(1) + '/' + (WEAPON_PICK_RANGE + 0.9).toFixed(1) + '，请再靠近一些）');
          return;
        } // 超出拾取范围（+容差，容忍网络延迟/移动抖动）
        WEAPON_DROPS.delete(w.id);
        broadcastRoom(ROOM_MATCH, { type: 'wpRemove', id: w.id });
        // 回执给拾取者：确认拾取成功（带上枪型，前端据此入包/手持）。
        // 注意：武器字段必须用 weapon（不能再用 type，否则会覆盖消息的 type='wpPicked'）
        try { p.ws.send(JSON.stringify({ type: 'wpPicked', id: w.id, weapon: w.type })); } catch (e) {}
        break;
      }
      case 'wpDrop': {
        // 丢弃武器到地面（权威）：服务端按玩家当前坐标 + 朝向算出落点，新增一把枪并广播。
        // 参数：{ type:'wpDrop', weapon:<武器名> }（G 键丢当前手持 / 拾取替换时丢旧枪）
        const p = PLAYERS_3D.get(id);
        if (!p || !joined || !gameOn) return;
        if (roomOf(p) !== ROOM_MATCH) return; // 只有对局房才能丢枪（防在预备房凭空造枪）
        if (p.hp <= 0 || p.parachuting) return;
        const wt = String(m.weapon || '').trim();
        if (WEAPON_TYPES.indexOf(wt) < 0) return; // 非法枪型
        // 归属校验（防绕过）：只允许丢“服务端记录的当前手持武器”。
        // 例外：拾取替换流程里客户端先发 wpDrop(旧枪) 再发 setweapon(新枪)，消息有序到达，
        // 校验时 p.weapon 仍是旧枪，恰好通过 —— 两条路径都覆盖。
        if (p.weapon !== wt) {
          try { p.ws.send(JSON.stringify({ type: 'wpDenied', id: '', reason: '丢弃失败：你当前并没有手持这把武器' })); } catch (e) {}
          return;
        }
        const pwx = gwxLine(p.x), pwz = gwxLine(p.z);
        const a = Number(p.angle) || 0;
        const floor = p.floorH || 0;
        // 抛掷：从手里沿朝向抛出 2.4 距离（带抛物线动画），而不是原地轻轻一放。
        // 若落点与手之间隔着墙（把枪扔进墙里很怪），缩短到贴身 0.9 丢下。
        let off = 2.4;
        const handY = floor + 1.0;
        const tryLand = (d) => [pwx + (-Math.sin(a)) * d, pwz + (-Math.cos(a)) * d];
        let [nx, nz] = tryLand(off);
        if (srvSegBlocked(SRV_WALL_BOXES, pwx, handY, pwz, nx, floor + 0.5, nz, floor)) {
          off = 0.9; [nx, nz] = tryLand(off);
        }
        // 抛出起点：手部位置（身前 0.55、手高 1.0），客户端从这点飞向落点
        const fx = pwx + (-Math.sin(a)) * 0.55;
        const fz = pwz + (-Math.cos(a)) * 0.55;
        wpAdd(wt, nx, nz, floor, { fx, fy: handY, fz });
        // 丢出即脱手：清空服务端手持记录并广播（别人的视角里你变成空手），
        // 同时给丢弃者回执 wpDropped，前端收到后才清空自己的槽位（杜绝“枪还在手上”的错位）。
        p.weapon = null;
        // 丢枪即中断换弹（枪都不在手上了，换弹定时器必须清掉）
        p.reloading = false;
        if (p.reloadTimer) { clearTimeout(p.reloadTimer); p.reloadTimer = null; }
        // 背包里已没有这把枪 → 它所在槽位的弹药一并作废（弹夹跟枪走）
        const si = p.slots ? p.slots.indexOf(wt) : -1;
        if (si >= 0) delete p.ammo[si];
        broadcastRoom(roomOf(p), { type: 'setweapon', id: p.id, weapon: null }, p.id);
        try { p.ws.send(JSON.stringify({ type: 'wpDropped', weapon: wt })); } catch (e) {}
        break;
      }
      case 'setweapon': {
        // 上报/同步当前手持武器（null=空手）：服务端记录后随 snap3d 下发，
        // 让同房间其他人能在你的立牌上看到你手上拿着的枪。
        // 参数：{ type:'setweapon', weapon:<枪型|null> }
        const p = PLAYERS_3D.get(id);
        if (!p || !joined) return;
        const wt = m.weapon == null ? null : String(m.weapon).trim();
        if (wt && WEAPON_TYPES.indexOf(wt) < 0) return; // 非法枪型忽略（空手 null 合法）
        p.weapon = wt || null;
        // 首次持有该枪（拾取入包）：初始化该槽位弹药 = 满弹夹 + 0 备弹（用户要求“默认刚捡起来时的容量”）
        if (wt && WEAPON_STATS[wt]) {
          const si = slotForWeapon(p, wt);
          if (si >= 0) {
            const old = p.ammo[si];
            if (!old || old.weapon !== wt) {
              p.ammo[si] = { weapon: wt, mag: WEAPON_STATS[wt].mag, reserve: 0 };
              sendAmmoTo(p, si);
            }
          }
        }
        broadcastRoom(roomOf(p), { type: 'setweapon', id: p.id, weapon: p.weapon }, p.id);
        break;
      }
      case 'slots': {
        // 上报背包槽位（客户端槽位变化时同步）：服务端据此维护“哪些枪还在身上”，
        // 并兜底初始化弹药（重连/异常路径下 setweapon 可能未触发）。
        const p = PLAYERS_3D.get(id);
        if (!p || !joined) return;
        const list = Array.isArray(m.list) ? m.list : [];
        p.slots = [0, 1].map(i => {
          const t = list[i] == null ? null : String(list[i]).trim();
          return (t && WEAPON_TYPES.indexOf(t) >= 0) ? t : null;
        });
        // 按槽位维护弹药：槽位枪型变化则重装该槽位弹药，槽位清空则删除其弹药
        for (let i = 0; i < 2; i++) {
          const t = p.slots[i];
          if (t && WEAPON_STATS[t]) {
            const old = p.ammo[i];
            if (!old || old.weapon !== t) {
              p.ammo[i] = { weapon: t, mag: WEAPON_STATS[t].mag, reserve: 0 };
              sendAmmoTo(p, i);
            }
          } else if (p.ammo[i]) {
            delete p.ammo[i];
          }
        }
        break;
      }
      case 'reload': {
        // 换弹（服务端权威）：把备弹按需填进弹夹，耗时 = 该枪 reload 秒，期间禁止开火。
        const p = PLAYERS_3D.get(id);
        if (!p || !joined || !gameOn) return;
        // 所有拒绝分支必须回执 wpDenied（kind:'reload' 供客户端区分换弹类拒绝）：
        // 否则客户端先置了换弹标记、服务端却静默丢弃请求 → 两端状态错位，永久卡死在“换弹中”。
        const denyR = (why) => { try { p.ws.send(JSON.stringify({ type: 'wpDenied', id: '', reason: why, kind: 'reload' })); } catch (e) {} };
        if (roomOf(p) !== ROOM_MATCH || p.hp <= 0) { denyR('当前状态无法换弹'); return; }
        const wt = p.weapon;
        const stat = wt ? WEAPON_STATS[wt] : null;
        if (!stat) { denyR('空手无法换弹'); return; }
        if (p.reloading) return; // 已在换弹中（服务端定时器稍后会发 reloadDone，无需额外处理）
        let si = (m.slot === 0 || m.slot === 1) ? m.slot : -1;
        if (!(si >= 0 && p.slots[si] === wt)) si = slotForWeapon(p, wt); // 客户端槽位不匹配则按枪型兜底
        const am = si >= 0 ? p.ammo[si] : null;
        if (!am) { denyR('弹药状态异常，请重新拾取武器'); return; }
        if (am.mag >= stat.mag) { denyR('弹匣已满，无需换弹'); return; }
        if (am.reserve <= 0) { denyR('没有备用弹匣，去捡对应的弹匣吧'); return; }
        p.reloading = true;
        try { p.ws.send(JSON.stringify({ type: 'reloading', weapon: wt, time: stat.reload })); } catch (e) {}
        if (p.reloadTimer) clearTimeout(p.reloadTimer);
        p.reloadTimer = setTimeout(() => {
          p.reloading = false; p.reloadTimer = null;
          const am2 = p.ammo[si];
          if (!am2) return;
          const need = stat.mag - am2.mag;
          const take = Math.min(need, am2.reserve);
          if (take > 0) { am2.mag += take; am2.reserve -= take; }
          sendAmmoTo(p, si);
          try { p.ws.send(JSON.stringify({ type: 'reloadDone', weapon: wt, mag: am2.mag, reserve: am2.reserve })); } catch (e) {}
        }, stat.reload * 1000);
        break;
      }
      case 'magPickup': {
        // 拾取弹夹（权威）：校验同 wpPickup（确有其夹 + 同房 + 存活 + 水平距离），
        // 且必须持有对应枪械（不同弹夹不能装不同的枪）→ 成功后加入该枪备弹并广播移除。
        const p = PLAYERS_3D.get(id);
        if (!p || !joined) return;
        const deny = (why) => { try { p.ws.send(JSON.stringify({ type: 'wpDenied', id: String(m.id), reason: why })); } catch (e) {} };
        if (!gameOn) { deny('对局未开始'); return; }
        if (roomOf(p) !== ROOM_MATCH) { deny('你不在对局中'); return; }
        if (p.hp <= 0) { deny('你已阵亡，无法拾取'); return; }
        if (p.parachuting) { deny('跳伞落地后才能拾取'); return; }
        const mg = MAG_DROPS.get(String(m.id));
        if (!mg) { deny('该弹夹已被他人拾取'); return; }
        const spec = MAG_TYPES[mg.type];
        if (!spec) { deny('未知弹夹类型'); return; }
        const pwx = gwxLine(p.x), pwz = gwxLine(p.z);
        const hd = Math.hypot(pwx - mg.x, pwz - mg.z);
        if (hd > MAG_PICK_RANGE + 0.9) { deny('距离太远（' + hd.toFixed(1) + '/' + (MAG_PICK_RANGE + 0.9).toFixed(1) + '，请再靠近一些）'); return; }
        // 弹匣加到对应枪的槽位：手头正拿着这把枪就加到手头那把，否则加给第一个持有该型号的槽位
        let si = (p.weapon === spec.for) ? slotForWeapon(p, spec.for) : -1;
        if (si < 0) si = slotForWeapon(p, spec.for);
        if (si < 0) { deny('你没有持有 ' + spec.for + '，这个弹匣装不上去'); return; }
        const am = p.ammo[si];
        if (!am) { deny('弹药状态异常，请重新拾取武器'); return; }
        MAG_DROPS.delete(mg.id);
        broadcastRoom(ROOM_MATCH, { type: 'magRemove', id: mg.id });
        am.reserve += spec.ammo;
        sendAmmoTo(p, si);
        try { p.ws.send(JSON.stringify({ type: 'magPicked', mag: mg.type, for: spec.for, ammo: spec.ammo })); } catch (e) {}
        break;
      }
      case 'medPickup': {
        // 拾取血包（权威）：校验同 wpPickup（确有其包 + 同房 + 存活 + 水平距离）。
        // 成功：移除地上血包 → 背包血包计数 +1，回执 medPicked。
        const p = PLAYERS_3D.get(id);
        if (!p || !joined) return;
        const deny = (why) => { try { p.ws.send(JSON.stringify({ type: 'wpDenied', id: String(m.id), reason: why })); } catch (e) {} };
        if (!gameOn) { deny('对局未开始'); return; }
        if (roomOf(p) !== ROOM_MATCH) { deny('你不在对局中'); return; }
        if (p.hp <= 0) { deny('你已阵亡，无法拾取'); return; }
        if (p.parachuting) { deny('跳伞落地后才能拾取'); return; }
        const md = MED_DROPS.get(String(m.id));
        if (!md) { deny('该血包已被他人拾取'); return; }
        const pwx = gwxLine(p.x), pwz = gwxLine(p.z);
        const hd = Math.hypot(pwx - md.x, pwz - md.z);
        if (hd > WEAPON_PICK_RANGE + 0.9) { deny('距离太远（' + hd.toFixed(1) + '/' + (WEAPON_PICK_RANGE + 0.9).toFixed(1) + '，请再靠近一些）'); return; }
        MED_DROPS.delete(md.id);
        broadcastRoom(ROOM_MATCH, { type: 'medRemove', id: md.id });
        p.medkitCount = (p.medkitCount || 0) + 1;
        try { p.ws.send(JSON.stringify({ type: 'medPicked', count: p.medkitCount })); } catch (e) {}
        break;
      }
      case 'medUse': {
        // 使用血包（权威）：5 秒使用等待后回复 75 血（与装弹同构，期间前端移速 -20%）。
        // 校验：对局中 + 存活 + 未跳伞 + 未在使用 + 未在换弹 + 有血包 + 血量未满。
        const p = PLAYERS_3D.get(id);
        if (!p || !joined) return;
        const deny = (why) => { try { p.ws.send(JSON.stringify({ type: 'wpDenied', id: '', reason: why, kind: 'med' })); } catch (e) {} };
        if (!gameOn) { deny('对局未开始'); return; }
        if (roomOf(p) !== ROOM_MATCH) { deny('你不在对局中'); return; }
        if (p.hp <= 0) { deny('你已阵亡，无法使用'); return; }
        if (p.parachuting) { deny('跳伞落地后才能使用'); return; }
        if (p.usingMedkit) { deny('已经在使用血包了'); return; }
        if (p.reviving) { deny('救助中无法使用血包'); return; }
        if (p.reloading) { deny('换弹中无法使用血包'); return; }
        if ((p.medkitCount || 0) <= 0) { deny('没有血包，去地上捡吧'); return; }
        const maxHp = p.isBoss ? BOSS_HP : MAX_HP; // Boss 上限是 BOSS_HP，否则 Boss 永远被判“血量已满”无法自疗
        if (p.hp >= maxHp) { deny('血量已满，无需使用'); return; }
        p.usingMedkit = true;
        p.medkitCount -= 1;
        try { p.ws.send(JSON.stringify({ type: 'medUsing', time: MED_USE_TIME, count: p.medkitCount })); } catch (e) {}
        if (p.medTimer) clearTimeout(p.medTimer);
        p.medTimer = setTimeout(() => {
          p.usingMedkit = false; p.medTimer = null;
          p.hp = Math.min(maxHp, p.hp + MED_HEAL_HP);
          broadcastRoom(roomOf(p), { type: 'heal', id: p.id, hp: p.hp });
          try { p.ws.send(JSON.stringify({ type: 'medDone', hp: p.hp })); } catch (e) {}
        }, MED_USE_TIME * 1000);
        break;
      }
      case 'setgame': {
        // 开始/结束游戏（开发者面板）：房间机制（与“暗黑迷宫”一致）。
        // 开始 = 把“预备房”里的全体在线玩家打包进“对局房”并高空跳伞开局；
        //        模式 m.mode：'royale'（系统1·吃鸡，启动毒圈）| 'respawn'（系统2·无圈，不启动毒圈）。
        //        开局后新进入的人默认留在预备房，看不到对局、也不会被毒圈影响。中途加入者等下一局。
        // 结束 = 对局房全员回到预备房（连接不中断，等待下一次开始）。
        const p = PLAYERS_3D.get(id);
        if (!p || !joined || !isDev(p)) return;
        const want = !!m.on;
        if (want === gameOn) return; // 状态未变：忽略重复触发
        gameOn = want;
        if (gameOn) {
          gamePhase = 'playing';
          gameMode = (String(m.mode || '') === 'respawn') ? 'respawn' : 'royale'; // 面板两个开始按钮决定模式
          // 只有预备房里的人进本局：已在别处的不动
          const live = roomPlayers(ROOM_PREP);
          if (live.length === 0) { gameOn = false; gamePhase = 'prep'; gameMode = null; break; } // 无人可开局
          for (const o of live) {
            o.room = ROOM_MATCH;
            const sk = randomSkyPos();
            o.parachuting = !noChute;          // “无需跳伞”：开局直接落地，不进空中
            o.skyY = noChute ? 0 : PARACHUTE_Y;
            o.hp = MAX_HP; o.respawnAt = 0; o.deathCount = 0;
            o.weapon = null; // 开局一律空手（手上的枪在上一局结束/开局时应已落地）
            o.ammo = {}; o.slots = [null, null]; o.reloading = false; o.lastShotAt = 0;
            if (o.reloadTimer) { clearTimeout(o.reloadTimer); o.reloadTimer = null; } // 清掉上一局的换弹定时器
            o.medkitCount = 0; o.usingMedkit = false; // 开局清空血包（新一局一律不带血包）
            if (o.medTimer) { clearTimeout(o.medTimer); o.medTimer = null; }
            o.x = sk.x; o.z = sk.z; o.angle = 0;
            // 通知本人：进入对局房（前端据此切换 UI、清掉“等待对局”横幅；mode 让客户端知道是否吃鸡）
            try { o.ws.send(JSON.stringify({ type: 'enterMatch', mode: gameMode })); } catch (e) {}
          }
          broadcast3d({ type: 'gameState', on: true });
          if (noChute) {
            // “无需跳伞”快节奏：不下发 parachuteStart（那会触发全员跳伞 UI），
            // 改为逐人广播 respawn，让各端把自己/他人直接放到随机地面出生点
            for (const o of roomPlayers(ROOM_MATCH)) {
              broadcastRoom(ROOM_MATCH, { type: 'respawn', id: o.id, x: o.x, z: o.z, hp: o.hp });
            }
          } else {
            broadcastRoom(ROOM_MATCH, { type: 'parachuteStart', players: roomPlayers(ROOM_MATCH).map(o => ({ id: o.id, x: o.x, z: o.z, angle: o.angle, y: skyYPayload(o) })) });
          }
          broadcastPlayers3d(); // 重发名单：对局房的人互相可见、预备房的人仍只看到预备房
          // 服务端权威武器：开局按本局人数一次性生成，并把全量列表下发给对局房所有人（人人看到同一份）
          spawnWeapons(live.length);
          spawnMags(live.length); // 弹夹与枪同批权威生成（数量比枪多）
          spawnMeds(live.length); // 血包同批权威生成
          wpSpawnedOnline = Math.max(1, live.length);
          broadcastWeapons();
          broadcastMags();
          broadcastMeds();
          if (gameMode === 'royale') { broadcast3d({ type: 'royaleAlive', alive: live.length }); startZone(); } // 系统1·吃鸡：广播初始剩余人数 + 启动毒圈（1 分钟后开始缩圈）
          else { stopZone(); } // 系统2·无圈：不启动/清除毒圈
          console.log('[3d game]', p.name, 'started mode=' + gameMode + ', players -> match:', live.length);
        } else {
          gamePhase = 'prep';
          gameMode = null;
          for (const o of PLAYERS_3D.values()) {
            if (roomOf(o) !== ROOM_MATCH) continue;
            o.room = ROOM_PREP;
            o.parachuting = false; o.skyY = 0; o.floorH = 0;
            o.weapon = null; // 结束游戏：清空手持武器（前端背包也会随之清掉）
            const sp = (battleActive && o.battleSpawn) ? o.battleSpawn : mazeSpawnPos(o.id);
            o.x = sp.x; o.z = sp.z; o.angle = 0;
            o.hp = MAX_HP; o.respawnAt = 0;
            try { o.ws.send(JSON.stringify({ type: 'leaveMatch' })); } catch (e) {}
            try { o.ws.send(JSON.stringify({ type: 'respawn', id: o.id, x: o.x, z: o.z, hp: o.hp })); } catch (e) {}
            broadcastRoom(ROOM_PREP, { type: 'move', id: o.id, x: o.x, z: o.z, angle: 0, y: 0.8 }, o.id);
          }
          broadcast3d({ type: 'gameState', on: false });
          broadcastPlayers3d(); // 全员回到预备房：重发名单（此刻所有人同房，互相可见）
          // 结束游戏：清空权威武器。注意此刻所有人已回到预备房，必须用 broadcast3d 全量下发（不能只发 ROOM_MATCH，否则没人收得到）
          clearWeapons(); wpSpawnedOnline = 1; broadcast3d({ type: 'weapons', list: [] });
          clearMags(); broadcast3d({ type: 'mags', list: [] }); // 弹夹随对局结束一并清空
          clearMeds(); broadcast3d({ type: 'meds', list: [] }); // 血包随对局结束一并清空
          stopZone(); // 结束游戏：关闭毒圈
          console.log('[3d game]', p.name, 'ended, all back to prep');
        }
        break;
      }
      case 'devRespawn': {
        // 回出生点（开始/结束游戏按钮触发，开发者限定）：复活并复位权威坐标；
        // 对战中回竞技场出生点，否则回迷宫出生点（服务端权威随机避让其他在线玩家）
        const p = PLAYERS_3D.get(id);
        if (!p || !joined || !isDev(p)) return;
        const sp = (battleActive && p.battleSpawn) ? p.battleSpawn : mazeSpawnPos(id);
        p.x = sp.x; p.z = sp.z; p.angle = 0;
        p.hp = MAX_HP; p.respawnAt = 0;
        broadcastRoom(roomOf(p), { type: 'respawn', id: p.id, x: p.x, z: p.z, hp: p.hp });
        broadcastRoom(roomOf(p), { type: 'move', id: p.id, x: p.x, z: p.z, angle: 0, y: 0.8 }, id);
        break;
      }
      case 'devlogin': {
        // 开发者鉴权：密码与服务端约定一致后，赋予“开发者指令”权限
        const p = PLAYERS_3D.get(id);
        if (!p || !joined) return;
        if (String(m.pwd || '').toLowerCase() === DEV_PASSWORD) {
          p.dev = true;
          try { p.ws.send(JSON.stringify(globalFlagsPayload())); } catch (e) {} // 打开面板即拿到当前全局参数真实值
        }
        break;
      }
      case 'killall': {
        const p = PLAYERS_3D.get(id);
        if (!p || !joined || !isDev(p)) return;
        // 对局已分出胜负：停止全员击杀，避免打死后再被复活到迷宫坐标（竞技场外）
        if (battleActive && battleOver) return;
        const now = Date.now();
        // 除自己外，所有存活玩家都被击杀（开发者模式专用）；断线残留记录不算人，也不给它记击杀
        for (const o of PLAYERS_3D.values()) {
          if (o.id === id || o.hp <= 0 || !isLivePlayer(o)) continue;
          if (roomOf(o) !== roomOf(p)) continue; // 只击杀同房间的人
          broadcastRoom(roomOf(p), { type: 'attack', attackerId: id, attackerName: p.name, targetId: o.id, damage: 0, hp: 0, nx: o.x, nz: o.z });
          applyKill3d(p, o, now);
        }
        broadcastKills3d();
        break;
      }
      case 'pullall': {
        // 吸附：把所有人传送到发起者当前坐标（开发者模式专用）
        const p = PLAYERS_3D.get(id);
        if (!p || !joined || !isDev(p)) return;
        const tx = battleActive ? clampBattle(Number(m.x)) : clamp3dMove(Number(m.x));
        const tz = battleActive ? clampBattle(Number(m.z)) : clamp3dMove(Number(m.z));
        for (const o of PLAYERS_3D.values()) {
          if (o.id === id || o.hp <= 0 || !isLivePlayer(o)) continue; // 只吸附真实在线的人，避免把幽灵记录传送到场内
          if (roomOf(o) !== roomOf(p)) continue; // 只吸附同房间的人
          o.x = tx; o.z = tz; o.angle = p.angle;
          broadcastRoom(roomOf(o), { type: 'pull', id: o.id, x: tx, z: tz, angle: o.angle });
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
        // 先复位对战状态
        resetBattleState(true);
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
      case 'jumpGame': {
        // 开发者发起：所有屏幕瞬间锁定，进入 5 秒跳转倒计时。
        // 迷宫模式下 → 去对战(分配)；对战中再次点击 → 5 秒后返回迷宫(回出生点，排行榜击杀保持不变)。
        const p = PLAYERS_3D.get(id);
        if (!p || !joined || !isDev(p)) return;
        if (battleActive) {
          // 对战中再次点击跳转：清对局/排行积分，广播“返回迷宫”倒计时，5 秒后统一送所有人回出生点
          if (returnToMazeTimer) { clearTimeout(returnToMazeTimer); returnToMazeTimer = null; }
          battleActive = false;
          battleOver = false;
          battleDevId = null;
          battlePlanning = false;
          battleReturning = true;
          battleTeams.clear();
          teamScores.clear();
          const payload = JSON.stringify({ type: 'battlePrepare', count: 5, purpose: 'return' });
          for (const o of PLAYERS_3D.values()) {
            if (o.ws.readyState === 1) { try { o.ws.send(payload); } catch (e) {} }
          }
          // 倒计时到期才真正返回；期间若有人重新发起分配（battleDevId/planning 被占用）则不再执行
          returnToMazeTimer = setTimeout(() => {
            returnToMazeTimer = null;
            if (!battleActive && !battleOver && !battlePlanning && !battleDevId) returnAllToMaze();
          }, 5000);
          console.log('[3d battle] return-to-maze countdown started by', p.name);
          break;
        }
        if (returnToMazeTimer) { clearTimeout(returnToMazeTimer); returnToMazeTimer = null; }
        battleActive = false;
        battleOver = false;
        battleDevId = id;
        battlePlanning = true;
        battleReturning = false;
        battleTeams.clear();
        teamScores.clear();
        const payload = JSON.stringify({ type: 'battlePrepare', count: 5 });
        for (const o of PLAYERS_3D.values()) {
          if (o.ws.readyState === 1) { try { o.ws.send(payload); } catch (e) {} }
        }
        console.log('[3d battle] prepare started by', p.name);
        break;
      }
      case 'teamList': {
        // 分配阶段：开发者拉取当前在线玩家 + 队伍
        const p = PLAYERS_3D.get(id);
        if (!p || !joined || battleDevId !== id || !isDev(p)) return;
        if (battleTeams.size === 0) autoAssignTeams();
        const oArr = livePlayers();
        p.ws.send(JSON.stringify({
          type: 'teamAssign',
          players: oArr.map((o, i) => ({ id: o.id, name: o.name, team: battleTeams.get(o.id) ?? defaultTeamFor(oArr, i) }))
        }));
        break;
      }
      case 'teamSet': {
        // 开发者手动给某玩家指定队伍
        const p = PLAYERS_3D.get(id);
        if (!p || !joined || battleDevId !== id || !isDev(p)) return;
        const tgt = PLAYERS_3D.get(String(m.playerId));
        if (!tgt) return;
        battleTeams.set(tgt.id, Math.max(0, parseInt(m.team, 10) || 0));
        break;
      }
      case 'battleStart': {
        // 开发者确认开战：随机抽 Boss 开战
        const p = PLAYERS_3D.get(id);
        if (!p || !joined || battleDevId !== id || !isDev(p)) return;
        startBossBattle();
        break;
      }
      case 'bossRestart': {
        // 结算后点“继续开始”：重新随机抽 Boss，再来一局（仍留在竞技场，不返回迷宫）
        const p = PLAYERS_3D.get(id);
        if (!p || !joined) return;
        if (!bossBattle || !battleOver) return;
        startBossBattle();
        break;
      }
      case 'reviveStart': {
        // Boss 模式：救助倒地队友。校验：发起者存活且为人类、目标为倒地(空血)同队人类、距离够近、无人正在救。
        const p = PLAYERS_3D.get(id);
        if (!p || !joined) return;
        if (!bossBattle || !gameOn || !battleActive || battleOver) return;
        if (p.hp <= 0 || p.isBoss || p.reviving || p.usingMedkit) return;
        const t = PLAYERS_3D.get(String(m.targetId || ''));
        if (!t || !isLivePlayer(t) || t.hp > 0 || t.isBoss) return;      // 只能救倒地者
        if (p.team !== 0 || t.team !== 0) return;                        // 只有人类队(0)互相救助
        if (t.beingRevivedBy) return;                                    // 已有人在救
        if (Math.hypot(p.x - t.x, p.z - t.z) > REVIVE_RANGE) return;     // 必须靠近
        p.reviving = t.id;
        t.beingRevivedBy = p.id;
        broadcastRoom(ROOM_MATCH, { type: 'reviveStart', reviverId: p.id, targetId: t.id, time: REVIVE_TIME, reviverName: p.name, targetName: t.name });
        const reviver = p, target = t;
        reviver.reviveTimer = setTimeout(() => {
          reviver.reviveTimer = null;
          // 完成校验：对局仍在、双方仍在本局名单里、救助者存活、目标仍倒地、距离未拉开
          const rv2 = PLAYERS_3D.get(reviver.id), tg2 = PLAYERS_3D.get(target.id);
          if (!bossBattle || battleOver || rv2 !== reviver || tg2 !== target
              || reviver.hp <= 0 || reviver.reviving !== target.id || target.hp > 0
              || Math.hypot(reviver.x - target.x, reviver.z - target.z) > REVIVE_RANGE + 1.5) {
            cleanupRevive(reviver, target);
            broadcastRoom(ROOM_MATCH, { type: 'reviveFail', targetId: target.id, reviverId: reviver.id, reason: 'invalid' });
            return;
          }
          reviver.reviving = null;
          target.beingRevivedBy = null;
          target.hp = MAX_HP;
          broadcastRoom(ROOM_MATCH, { type: 'reviveDone', targetId: target.id, reviverId: reviver.id, hp: target.hp, targetName: target.name, reviverName: reviver.name });
          broadcastPlayers3d();
        }, REVIVE_TIME * 1000);
        break;
      }
      case 'reviveCancel': {
        // 救助者主动中断（如按移动键/再次点击取消）
        const p = PLAYERS_3D.get(id);
        if (!p || !p.reviving) return;
        const t = PLAYERS_3D.get(p.reviving);
        if (t) {
          cleanupRevive(p, t);
          broadcastRoom(ROOM_MATCH, { type: 'reviveFail', targetId: t.id, reviverId: p.id, reason: 'cancelled' });
        }
        break;
      }
      case 'returnMaze': {
        // 对战(含结束)后由任一人发起，全员退出对战回到迷宫
        // 对局进行中只有开发者可提前结束；已结束/分配阶段任意人可发起返回
        const p = PLAYERS_3D.get(id);
        if (!p || !joined) return;
        if (battleActive && !battleOver && !isDev(p)) return;
        returnAllToMaze();
        break;
      }
      case 'chat': {
        const p = PLAYERS_3D.get(id);
        if (!p || !joined) return;
        const text = String(m.text || '').slice(0, 200);
        if (!text.trim()) return;
        broadcast3d({ type: 'chat', name: p.name, text }, id);
        break;
      }
      case 'escape': {
        const p = PLAYERS_3D.get(id);
        if (!p || !joined || p.escaped) return;
        p.escaped = true;
        broadcast3d({ type: 'escape', name: p.name });
        console.log('[3d escape]', p.name);
        break;
      }
      case 'jump': {
        // 转发跳跃给同房间的人，让远端身体也能看到起跳
        const p = PLAYERS_3D.get(id);
        if (!p || !joined) return;
        broadcastRoom(roomOf(p), { type: 'jump', id }, id);
        break;
      }
    }
  });

  ws.on('close', () => {
    // 优先按本连接的 id 查档案；万一未命中（如历史遗留的 id 未对齐），按 socket 归属兜底查找，
    // 避免玩家记录永久残留在 PLAYERS_3D（排行榜出现幽灵、对局状态永不自动复位）。
    let p = PLAYERS_3D.get(id) || null;
    if (!p) p = Array.from(PLAYERS_3D.values()).find(o => o.ws === ws) || null;
    // 若该连接已被断线重连替换（p.ws 现在是新连接），忽略旧连接的关闭，避免误删/残留旧身体
    if (!p || p.ws !== ws) return;
    p.disconnectedAt = Date.now();
    // 保存断线档案，供该玩家用同一 token 重连恢复身份/击杀数（60 秒内）
    if (p.token) TOKENS_3D.set(p.token, p);
    const leaveRoom = roomOf(p); // 记录离场者所在房间（删除后 roomOf 取不到）
    PLAYERS_3D.delete(p.id);
    // 离线清理：该玩家所在队伍若已无人在线，连队伍归属与积分一起清掉，避免残留幽灵队伍
    if (pruneTeamIfEmpty(p.team)) broadcastTeamScores3d();
    // 发起分配的开发者中途离开 → 结束这次无主的分配，让仍在线的玩家回到迷宫，避免一直“正在分配中”
    if (battlePlanning && battleDevId === p.id) { resetBattleState(true); broadcastKills3d(); }
    // 一个玩家都没有了 → 复位所有对战状态，避免残留状态干扰下一次新开局
    if (PLAYERS_3D.size === 0) resetBattleState(false);
    broadcastRoom(leaveRoom, { type: 'leave', id: p.id });
    broadcastPlayers3d();
    broadcastKills3d(); // 断线即从排行榜取消该玩家，避免其击杀记录残留
    console.log('[3d leave]', p.name, 'remain:', PLAYERS_3D.size);
  });
  ws.on('error', () => {});
});

// 死亡复活冷却处理：冷却结束自动回到出生点并满血
setInterval(() => {
  const now = Date.now();
  for (const p of PLAYERS_3D.values()) {
    if (p.hp <= 0 && p.respawnAt) {
      if (now >= p.respawnAt) {
        p.respawnAt = 0;
        p.lastDeathTick = 0;
        // 无圈模式：倒计时走满后才触发跳伞复活（重跳伞自带广播，见 respawnChute）
        if (gameMode === 'respawn') { respawnChute(p); continue; }
        p.hp = MAX_HP;
        // 对战地图回队伍自己的角位附近随机点；迷宫模式回迷宫出生点
        if (battleActive && !battleOver) {
          const c = battleCornerGrid(p.team);
          const jx = (Math.random() - 0.5) * 2.4;
          const jz = (Math.random() - 0.5) * 2.4;
          p.x = clampG(c[0] + jx);
          p.z = clampG(c[1] + jz);
        } else {
          const sp = mazeSpawnPos(p.id); p.x = sp.x; p.z = sp.z;
        }
        p.angle = 0;
        p.lastAttack = 0;
        p.respawnAt = 0;
        p.lastDeathTick = 0;
        broadcast3d({ type: 'respawn', id: p.id, x: p.x, z: p.z, hp: p.hp });
        console.log('[3d respawn]', p.name);
      } else if (!p.lastDeathTick || now - p.lastDeathTick >= 400) {
        // 服务端权威倒计时：每隔约 0.4s 推一次剩余毫秒，让前端显示与服务端解锁严格一致，杜绝“到 0 还卡住”
        p.lastDeathTick = now;
        if (p.ws.readyState === 1) {
          try { p.ws.send(JSON.stringify({ type: 'deathTick', id: p.id, remain: p.respawnAt - now })); } catch (e) {}
        }
      }
    }
  }
}, 300);

// 跳伞下落推进（服务端权威）：每 100ms 匀速下降一次；高度落到“所在位置的表面”（屋顶/山顶/地面）
// 即落地并广播 parachuteLand + 贴地 move。旧版只认 skyY<=0，飘到房屋上空会穿过屋顶落到楼里。
setInterval(() => {
  if (!gameOn) return;
  for (const p of PLAYERS_3D.values()) {
    if (!p.parachuting || p.hp <= 0) continue;
    // 按住 Shift 加速下落（CHUTE_BOOST 倍，与前端常量一致）：客户端上报 chuteBoost 切换
    p.skyY -= PARACHUTE_DESCENT * (p.chuteBoost ? 2.5 : 1) * 0.1;
    const surf = srvSurfaceHeightAt(srvGwxLine(p.x), srvGwxLine(p.z));
    if (p.skyY <= surf) {
      p.skyY = 0; p.parachuting = false; p.floorH = surf; // 权威落点：屋顶/山顶可站住，不再穿顶
      broadcastRoom(roomOf(p), { type: 'parachuteLand', id: p.id, x: p.x, z: p.z, y: Math.round((0.8 + surf) * 100) / 100 });
      broadcastRoom(roomOf(p), { type: 'move', id: p.id, x: p.x, z: p.z, angle: p.angle, y: Math.round((0.8 + surf) * 100) / 100 }, p.id);
      console.log('[3d chute]', p.name, 'landed at h=' + surf);
    }
  }
}, 100);

// 毒圈伤害（服务端权威）：吃鸡对战（游戏已开始、非竞技场团队战、毒圈已激活）时，
// 每秒对“安全区圆外、已落地且存活”的玩家扣 5 血；血尽即视为被毒圈淘汰并进入复活倒计时。
// 只对“对局房”的玩家生效：预备房没有毒圈。
setInterval(() => {
  if (!gameOn || battleActive || !zone.active) return;
  const now = Date.now();
  for (const p of PLAYERS_3D.values()) {
    if (roomOf(p) !== ROOM_MATCH) continue; // 预备房不受毒圈影响
    if (!isLivePlayer(p) || p.hp <= 0 || p.parachuting) continue; // 跳伞中在空中不受毒圈影响
    // 格坐标 → 世界坐标，与前端一致：world = g*1.5 + 0.25
    const wx = p.x * CELL_SIZE_WORLD + 0.25, wz = p.z * CELL_SIZE_WORLD + 0.25;
    const dx = wx - zone.centerX, dz = wz - zone.centerZ;
    if ((dx * dx + dz * dz) <= (zone.radius * zone.radius)) continue; // 仍在安全区内
    p.hp = Math.max(0, p.hp - ZONE_CFG.dmgPerSec);
    const msg = { type: 'zoneHurt', id: p.id, hp: p.hp };
    if (p.hp <= 0) {
      if (gameMode === 'royale') {
        // 系统1·吃鸡：毒圈淘汰即回预备房（清空状态）并判定胜者
        eliminateToPrep(p);
        checkRoyaleWinner(now);
        msg.respawnDelay = null;
      } else {
        p.respawnAt = now + ZONE_CFG.deathRespawn; // 触发既有的复活/倒计时逻辑
        msg.respawnDelay = ZONE_CFG.deathRespawn;
      }
    }
    // 只广播给对局房（本人据此扣血并显示死亡，同房其他人据此刻远端血条）
    broadcastRoom(ROOM_MATCH, msg);
  }
}, 1000);

// 玩家心跳：定期清理断线连接（缩短周期，坏连接尽快移除，避免“还在线/进不去”）；并清理超时未重连的档案
setInterval(() => {
  const now = Date.now();
  for (const p of PLAYERS_3D.values()) {
    // 断线角色兜底清理：socket 已关闭/正在关闭（close 事件可能因代理断连、进程异常等原因收不到），
    // 权威地按“离场”处理，避免残留记录被当成可攻击目标、长期挂在排行榜/队伍/名单里。
    if (!p.ws || p.ws.readyState !== 1) {
      const leaveRoom = roomOf(p); // 记录离场者所在房间（删除后取不到）
      PLAYERS_3D.delete(p.id);
      p.disconnectedAt = now;
      if (p.token) TOKENS_3D.set(p.token, p);
      if (pruneTeamIfEmpty(p.team)) broadcastTeamScores3d();
      if (battlePlanning && battleDevId === p.id) resetBattleState(true);
      if (PLAYERS_3D.size === 0) resetBattleState(false);
      broadcastRoom(leaveRoom, { type: 'leave', id: p.id });
      broadcastPlayers3d();
      broadcastKills3d();
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
  broadcastPlayers3d();
}, 2000);

// 对战中“强制拉入”：每 2 秒把所有在线玩家拉进对战地图。
// 即使某方漏收了那一条一次性的 battleStart，也会在 2 秒内被强制拖进对战并放到服务端权威坐标，杜绝“进不去”。
setInterval(() => {
  if (!battleActive || battleOver) return;
  for (const p of PLAYERS_3D.values()) {
    if (p.ws.readyState !== 1) continue;
    let sx = p.x, sz = p.z;
    // 漏收 battleStart 的玩家仍持有迷宫坐标：若落在竞技场围墙外，直接拉到本队角落，
    // 否则会被永久卡在围墙外的“虚空”里无法参战（服务器权威坐标随后同步下发纠正）
    if (p.hp > 0 && (p.x < ARENA_MIN_G || p.x > ARENA_MAX_G || p.z < ARENA_MIN_G || p.z > ARENA_MAX_G)) {
      const c = battleCornerGrid(p.team);
      sx = clampG(c[0] + (Math.random() - 0.5) * 0.8);
      sz = clampG(c[1] + (Math.random() - 0.5) * 0.8);
      p.x = sx; p.z = sz;
    }
    try {
      p.ws.send(JSON.stringify({
        type: 'battlePulse',
        battleActive: true,
        myTeam: p.team,
        teams: Array.from(battleTeams.entries()),
        scores: Array.from(teamScores.entries()),
        x: sx, z: sz
      }));
    } catch (e) {}
  }
}, 2000);

// 强制监听 IPv4 0.0.0.0，确保宝塔用 127.0.0.1 反向代理也能连通（避免 tcp6-only 导致的 502）
server.listen(PORT, '0.0.0.0', () => {
  console.log('—— 3D 迷宫（独立后端） ——');
  console.log(`服务器已启动:  http://localhost:${PORT}`);
});