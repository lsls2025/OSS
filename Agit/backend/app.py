import os
import sqlite3
import datetime
import secrets

from flask import Flask, request, jsonify, g, Blueprint
from werkzeug.security import generate_password_hash, check_password_hash
from waitress import serve

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DB_PATH = os.path.join(BASE_DIR, "agit_users.db")
TOKEN_EXP_DAYS = 30

app = Flask(__name__)
app.json.ensure_ascii = False
api = Blueprint("api", __name__, url_prefix="/backend")


def get_db():
    db = getattr(g, "_database", None)
    if db is None:
        db = g._database = sqlite3.connect(DB_PATH)
        db.row_factory = sqlite3.Row
    return db


@app.teardown_appcontext
def close_db(exception):
    db = getattr(g, "_database", None)
    if db is not None:
        db.close()


def init_db():
    with sqlite3.connect(DB_PATH) as db:
        db.execute(
            """
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT UNIQUE NOT NULL,
                password_hash TEXT NOT NULL,
                avatar TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            )
            """
        )
        db.execute(
            """
            CREATE TABLE IF NOT EXISTS sessions (
                token TEXT PRIMARY KEY,
                user_id INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )
            """
        )
        try:
            db.execute("ALTER TABLE users ADD COLUMN avatar TEXT NOT NULL DEFAULT ''")
        except Exception:
            pass
        db.execute(
            """
            CREATE TABLE IF NOT EXISTS remote_repos (
                id TEXT PRIMARY KEY,
                owner_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                is_private INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
            """
        )
        for col, coltype in [
            ("owner_id", "INTEGER NOT NULL DEFAULT 0"),
            ("name", "TEXT NOT NULL DEFAULT ''"),
            ("description", "TEXT NOT NULL DEFAULT ''"),
            ("is_private", "INTEGER NOT NULL DEFAULT 0"),
            ("created_at", "INTEGER NOT NULL DEFAULT 0"),
            ("allow_connect", "INTEGER NOT NULL DEFAULT 1"),
            ("connect_password", "TEXT NOT NULL DEFAULT ''"),
        ]:
            try:
                db.execute(f"ALTER TABLE remote_repos ADD COLUMN {col} {coltype}")
            except Exception:
                pass
        db.execute(
            """
            CREATE TABLE IF NOT EXISTS repo_connections (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                repo_id TEXT NOT NULL,
                connector_id INTEGER NOT NULL,
                owner_id INTEGER NOT NULL,
                permission TEXT NOT NULL DEFAULT 'read',
                created_at INTEGER NOT NULL,
                UNIQUE(repo_id, connector_id)
            )
            """
        )
        try:
            db.execute("ALTER TABLE repo_connections ADD COLUMN permission TEXT NOT NULL DEFAULT 'read'")
        except Exception:
            pass
        db.execute(
            """
            CREATE TABLE IF NOT EXISTS repo_blocks (
                owner_id INTEGER NOT NULL,
                blocked_id INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                PRIMARY KEY(owner_id, blocked_id)
            )
            """
        )
        db.commit()


def row_to_dict(row):
    return {k: row[k] for k in row.keys() if k != "password_hash"}


def create_session(user_id: int) -> str:
    token = secrets.token_hex(32)
    now = int(datetime.datetime.utcnow().timestamp())
    get_db().execute(
        "INSERT INTO sessions (token, user_id, created_at) VALUES (?, ?, ?)",
        (token, user_id, now),
    )
    get_db().commit()
    return token


def get_current_user():
    auth = request.headers.get("Authorization", "")
    if not auth.startswith("Bearer "):
        return None, (jsonify({"error": "未提供 token"}), 401)
    token = auth[7:]
    sess = get_db().execute(
        "SELECT user_id, created_at FROM sessions WHERE token = ?", (token,)
    ).fetchone()
    if not sess:
        return None, (jsonify({"error": "token 无效"}), 401)
    if datetime.datetime.utcnow().timestamp() - sess["created_at"] > TOKEN_EXP_DAYS * 86400:
        return None, (jsonify({"error": "token 已过期，请重新登录"}), 401)
    row = get_db().execute("SELECT * FROM users WHERE id = ?", (sess["user_id"],)).fetchone()
    if not row:
        return None, (jsonify({"error": "用户不存在"}), 404)
    return row, None


@api.route("/health", methods=["GET"])
def health():
    return jsonify({"status": "ok"})


@api.route("/register", methods=["POST"])
def register():
    data = request.get_json(silent=True) or {}
    username = (data.get("username") or "").strip()
    password = data.get("password") or ""

    if not username or not password:
        return jsonify({"error": "账号和密码不能为空"}), 400
    if len(username) < 2 or len(username) > 32:
        return jsonify({"error": "账号长度 2-32 位"}), 400
    if len(password) < 6:
        return jsonify({"error": "密码至少 6 位"}), 400

    db = get_db()
    if db.execute("SELECT 1 FROM users WHERE username = ?", (username,)).fetchone():
        return jsonify({"error": "账号已存在"}), 409

    pw_hash = generate_password_hash(password)
    now = int(datetime.datetime.utcnow().timestamp())
    cur = db.execute(
        "INSERT INTO users (username, password_hash, avatar, created_at) VALUES (?, ?, ?, ?)",
        (username, pw_hash, "", now),
    )
    db.commit()
    user_id = cur.lastrowid
    token = create_session(user_id)

    return (
        jsonify(
            {
                "token": token,
                "user": {"id": user_id, "username": username, "avatar": "", "created_at": now},
            }
        ),
        201,
    )


@api.route("/login", methods=["POST"])
def login():
    data = request.get_json(silent=True) or {}
    username = (data.get("username") or "").strip()
    password = data.get("password") or ""

    if not username or not password:
        return jsonify({"error": "账号和密码不能为空"}), 400

    db = get_db()
    row = db.execute("SELECT * FROM users WHERE username = ?", (username,)).fetchone()
    if not row or not check_password_hash(row["password_hash"], password):
        return jsonify({"error": "账号或密码错误"}), 401

    token = create_session(row["id"])
    return jsonify({"token": token, "user": row_to_dict(row)}), 200


@api.route("/me", methods=["GET"])
def me():
    user, err = get_current_user()
    if err:
        return err
    return jsonify({"user": row_to_dict(user)}), 200


@api.route("/change-password", methods=["POST"])
def change_password():
    user, err = get_current_user()
    if err:
        return err
    data = request.get_json(silent=True) or {}
    old = data.get("old_password") or ""
    new = data.get("new_password") or ""
    if len(new) < 6:
        return jsonify({"error": "新密码至少 6 位"}), 400
    if not check_password_hash(user["password_hash"], old):
        return jsonify({"error": "当前密码错误"}), 401
    db = get_db()
    db.execute(
        "UPDATE users SET password_hash = ? WHERE id = ?",
        (generate_password_hash(new), user["id"]),
    )
    db.commit()
    return jsonify({"message": "密码已更新"}), 200


@api.route("/avatar", methods=["POST"])
def upload_avatar():
    user, err = get_current_user()
    if err:
        return err
    data = request.get_json(silent=True) or {}
    avatar = data.get("avatar") or ""
    if avatar and not avatar.startswith("data:image/"):
        return jsonify({"error": "头像格式不正确"}), 400
    if len(avatar) > 800000:
        return jsonify({"error": "头像过大，请换小图"}), 400
    db = get_db()
    db.execute("UPDATE users SET avatar = ? WHERE id = ?", (avatar, user["id"]))
    db.commit()
    return jsonify({"message": "已更新", "avatar": avatar}), 200


# ---------------- 远程仓库 / 连接 / 拉黑 ----------------

@api.route("/repos", methods=["POST"])
def create_remote_repo():
    user, err = get_current_user()
    if err:
        return err
    data = request.get_json(silent=True) or {}
    name = (data.get("name") or "").strip()
    description = (data.get("description") or "").strip()
    is_private = 1 if data.get("is_private") else 0
    if not name:
        return jsonify({"error": "仓库名不能为空"}), 400
    rid = secrets.token_hex(8)
    now = int(datetime.datetime.utcnow().timestamp())
    get_db().execute(
        "INSERT INTO remote_repos (id, owner_id, name, description, is_private, allow_connect, connect_password, created_at) VALUES (?,?,?,?,?,?,?,?)",
        (rid, user["id"], name, description, is_private, 1, "", now),
    )
    get_db().commit()
    return (
        jsonify(
            {
                "id": rid,
                "name": name,
                "description": description,
                "is_private": bool(is_private),
                "owner_id": user["id"],
            }
        ),
        201,
    )


@api.route("/repos", methods=["GET"])
def list_my_repos():
    user, err = get_current_user()
    if err:
        return err
    owned = get_db().execute(
        "SELECT * FROM remote_repos WHERE owner_id = ?", (user["id"],)
    ).fetchall()
    conns = get_db().execute(
        "SELECT r.* FROM remote_repos r JOIN repo_connections c ON c.repo_id = r.id WHERE c.connector_id = ?",
        (user["id"],),
    ).fetchall()
    result = []
    for r in owned:
        result.append(
            {
                "id": r["id"],
                "name": r["name"],
                "description": r["description"],
                "is_private": bool(r["is_private"]),
                "owner_id": r["owner_id"],
                "role": "owner",
            }
        )
    for r in conns:
        result.append(
            {
                "id": r["id"],
                "name": r["name"],
                "description": r["description"],
                "is_private": bool(r["is_private"]),
                "owner_id": r["owner_id"],
                "role": "connected",
            }
        )
    return jsonify({"repos": result}), 200


@api.route("/repos/search", methods=["GET"])
def search_public_repos():
    user, err = get_current_user()
    if err:
        return err
    q = (request.args.get("q") or "").strip()
    like = f"%{q}%"
    try:
        page = max(0, int(request.args.get("page", 0) or 0))
        size = min(100, max(1, int(request.args.get("size", 20) or 20)))
    except Exception:
        page, size = 0, 20
    rows = get_db().execute(
        "SELECT * FROM remote_repos WHERE is_private = 0 AND (name LIKE ? OR description LIKE ?) "
        f"ORDER BY created_at DESC LIMIT {int(size)} OFFSET {int(page * size)}",
        (like, like),
    ).fetchall()
    out = [
        {"id": r["id"], "name": r["name"], "description": r["description"], "owner_id": r["owner_id"],
         "allow_connect": bool(r.get("allow_connect", 1))}
        for r in rows
    ]
    return jsonify({"repos": out, "page": page, "size": size}), 200


@api.route("/repos/<rid>", methods=["GET"])
def get_repo_info(rid):
    repo = get_db().execute("SELECT * FROM remote_repos WHERE id = ?", (rid,)).fetchone()
    if not repo:
        return jsonify({"error": "仓库不存在"}), 404
    owner = get_db().execute("SELECT username FROM users WHERE id = ?", (repo["owner_id"],)).fetchone()
    return jsonify({
        "id": repo["id"],
        "name": repo["name"],
        "description": repo["description"],
        "owner_id": repo["owner_id"],
        "owner_username": owner["username"] if owner else "",
        "is_private": bool(repo["is_private"]),
        "allow_connect": bool(repo.get("allow_connect", 1)),
    }), 200


@api.route("/repos/<rid>", methods=["PUT"])
def update_remote_repo(rid):
    user, err = get_current_user()
    if err:
        return err
    repo = get_db().execute("SELECT * FROM remote_repos WHERE id = ?", (rid,)).fetchone()
    if not repo or repo["owner_id"] != user["id"]:
        return jsonify({"error": "无权操作"}), 403
    data = request.get_json(silent=True) or {}
    name = data.get("name")
    description = data.get("description")
    is_private = data.get("is_private")
    allow_connect = data.get("allow_connect")
    connect_password = data.get("connect_password")
    sets, vals = [], []
    if name is not None:
        name = (name or "").strip()
        if not name:
            return jsonify({"error": "仓库名不能为空"}), 400
        sets.append("name = ?"); vals.append(name)
    if description is not None:
        sets.append("description = ?"); vals.append((description or "").strip())
    if is_private is not None:
        sets.append("is_private = ?"); vals.append(1 if is_private else 0)
    if allow_connect is not None:
        sets.append("allow_connect = ?"); vals.append(1 if allow_connect else 0)
    if connect_password is not None:
        sets.append("connect_password = ?"); vals.append(connect_password or "")
    if not sets:
        return jsonify({"message": "无变更"}), 200
    vals.append(rid)
    get_db().execute(
        "UPDATE remote_repos SET {} WHERE id = ?".format(", ".join(sets)), vals
    )
    get_db().commit()
    return jsonify({"message": "已更新"}), 200


@api.route("/repos/<rid>/settings", methods=["GET"])
def repo_settings(rid):
    user, err = get_current_user()
    if err:
        return err
    repo = get_db().execute("SELECT * FROM remote_repos WHERE id = ?", (rid,)).fetchone()
    if not repo or repo["owner_id"] != user["id"]:
        return jsonify({"error": "无权查看"}), 403
    return jsonify({
        "allow_connect": bool(repo.get("allow_connect", 1)),
        "connect_password": repo.get("connect_password", "") or "",
    }), 200


@api.route("/repos/<rid>/connect", methods=["POST"])
def connect_repo(rid):
    user, err = get_current_user()
    if err:
        return err
    repo = get_db().execute("SELECT * FROM remote_repos WHERE id = ?", (rid,)).fetchone()
    if not repo:
        return jsonify({"error": "仓库不存在"}), 404
    if repo["owner_id"] == user["id"]:
        return jsonify({"error": "不能连接自己的仓库"}), 400
    if repo["is_private"]:
        return jsonify({"error": "私有仓库不可被连接"}), 403
    if not repo.get("allow_connect", 1):
        return jsonify({"error": "该仓库未开放连接"}), 403
    pwd = repo.get("connect_password", "") or ""
    if pwd:
        req_pwd = (request.get_json(silent=True) or {}).get("password") or ""
        if req_pwd != pwd:
            return jsonify({"error": "连接密码错误"}), 403
    blocked = get_db().execute(
        "SELECT 1 FROM repo_blocks WHERE owner_id = ? AND blocked_id = ?",
        (repo["owner_id"], user["id"]),
    ).fetchone()
    if blocked:
        return jsonify({"error": "你已被该仓库拥有者拉黑"}), 403
    now = int(datetime.datetime.utcnow().timestamp())
    try:
        get_db().execute(
            "INSERT INTO repo_connections (repo_id, connector_id, owner_id, created_at) VALUES (?,?,?,?)",
            (rid, user["id"], repo["owner_id"], now),
        )
        get_db().commit()
    except Exception:
        get_db().rollback()
        return jsonify({"error": "已经连接过该仓库"}), 400
    return jsonify({"message": "已连接"}), 200


@api.route("/repos/<rid>/connections", methods=["GET"])
def list_connections(rid):
    user, err = get_current_user()
    if err:
        return err
    repo = get_db().execute("SELECT * FROM remote_repos WHERE id = ?", (rid,)).fetchone()
    if not repo or repo["owner_id"] != user["id"]:
        return jsonify({"error": "无权查看"}), 403
    rows = get_db().execute(
        "SELECT c.created_at, c.permission, u.id as uid, u.username, u.avatar FROM repo_connections c "
        "JOIN users u ON u.id = c.connector_id WHERE c.repo_id = ? ORDER BY c.created_at DESC",
        (rid,),
    ).fetchall()
    out = [
        {
            "user": {"id": r["uid"], "username": r["username"], "avatar": r["avatar"]},
            "permission": r["permission"],
            "created_at": r["created_at"],
        }
        for r in rows
    ]
    return jsonify({"connections": out}), 200


@api.route("/repos/<rid>/connections/<int:uid>", methods=["DELETE"])
def remove_connection(rid, uid):
    user, err = get_current_user()
    if err:
        return err
    repo = get_db().execute("SELECT * FROM remote_repos WHERE id = ?", (rid,)).fetchone()
    if not repo or repo["owner_id"] != user["id"]:
        return jsonify({"error": "无权操作"}), 403
    get_db().execute(
        "DELETE FROM repo_connections WHERE repo_id = ? AND connector_id = ?", (rid, uid)
    )
    get_db().commit()
    return jsonify({"message": "已取消连接"}), 200


@api.route("/repos/<rid>/block", methods=["POST"])
def block_user(rid):
    user, err = get_current_user()
    if err:
        return err
    repo = get_db().execute("SELECT * FROM remote_repos WHERE id = ?", (rid,)).fetchone()
    if not repo or repo["owner_id"] != user["id"]:
        return jsonify({"error": "无权操作"}), 403
    data = request.get_json(silent=True) or {}
    uid = data.get("user_id")
    if not uid:
        return jsonify({"error": "缺少 user_id"}), 400
    now = int(datetime.datetime.utcnow().timestamp())
    get_db().execute(
        "DELETE FROM repo_connections WHERE repo_id = ? AND connector_id = ?", (rid, uid)
    )
    get_db().execute(
        "INSERT OR IGNORE INTO repo_blocks (owner_id, blocked_id, created_at) VALUES (?,?,?)",
        (user["id"], uid, now),
    )
    get_db().commit()
    return jsonify({"message": "已拉黑"}), 200


@api.route("/repos/<rid>/connections/<int:uid>/permission", methods=["POST"])
def set_connection_permission(rid, uid):
    user, err = get_current_user()
    if err:
        return err
    repo = get_db().execute("SELECT * FROM remote_repos WHERE id = ?", (rid,)).fetchone()
    if not repo or repo["owner_id"] != user["id"]:
        return jsonify({"error": "无权操作"}), 403
    perm = (request.get_json(silent=True) or {}).get("permission")
    if perm not in ("read", "modify"):
        return jsonify({"error": "无效的权限值"}), 400
    cur = get_db().execute(
        "UPDATE repo_connections SET permission = ? WHERE repo_id = ? AND connector_id = ?",
        (perm, rid, uid),
    )
    if cur.rowcount == 0:
        return jsonify({"error": "该用户未连接此仓库"}), 404
    get_db().commit()
    return jsonify({"message": "已更新权限", "permission": perm}), 200


@api.route("/my-connectors", methods=["GET"])
def my_connectors():
    user, err = get_current_user()
    if err:
        return err
    rows = get_db().execute(
        "SELECT c.repo_id, r.name as repo_name, c.permission as perm, u.id as uid, u.username, u.avatar FROM repo_connections c "
        "JOIN remote_repos r ON r.id = c.repo_id JOIN users u ON u.id = c.connector_id "
        "WHERE c.owner_id = ? ORDER BY c.created_at DESC",
        (user["id"],),
    ).fetchall()
    out = [
        {
            "repo_id": r["repo_id"],
            "repo_name": r["repo_name"],
            "permission": r["perm"],
            "user": {"id": r["uid"], "username": r["username"], "avatar": r["avatar"]},
        }
        for r in rows
    ]
    return jsonify({"connectors": out}), 200


@api.route("/blocks", methods=["GET"])
def list_blocks():
    user, err = get_current_user()
    if err:
        return err
    rows = get_db().execute(
        "SELECT b.blocked_id, u.username, u.avatar FROM repo_blocks b "
        "JOIN users u ON u.id = b.blocked_id WHERE b.owner_id = ?",
        (user["id"],),
    ).fetchall()
    out = [{"id": r["blocked_id"], "username": r["username"], "avatar": r["avatar"]} for r in rows]
    return jsonify({"blocks": out}), 200


@api.route("/blocks", methods=["DELETE"])
def unblock():
    user, err = get_current_user()
    if err:
        return err
    data = request.get_json(silent=True) or {}
    uid = data.get("user_id")
    if not uid:
        return jsonify({"error": "缺少 user_id"}), 400
    get_db().execute(
        "DELETE FROM repo_blocks WHERE owner_id = ? AND blocked_id = ?", (user["id"], uid)
    )
    get_db().commit()
    return jsonify({"message": "已解除拉黑"}), 200


@api.route("/version", methods=["GET"])
def version():
    return jsonify({"version": "1.0.0", "min_version": "1.0.0"}), 200


app.register_blueprint(api)


@app.errorhandler(500)
def _handle_500(e):
    return jsonify({"error": f"服务器内部错误: {e}"}), 500


if __name__ == "__main__":
    init_db()
    serve(app, host="0.0.0.0", port=3399)
