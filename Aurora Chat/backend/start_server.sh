#!/bin/bash
# Aurora Chat 后端服务器启动脚本（Linux 服务器用）
# 使用方法: bash start_server.sh
# 启动后在后台运行，关终端不影响

# ==================== 配置 ====================
SERVER_PORT=5004           # HTTP 端口（与 Android 端一致）
TCP_PORT=5005              # TCP 推送端口
LOG_FILE="aurora-server.log"  # 日志文件

# 浏览器访问文件服务器时使用 HTTPS，端口 5443
ENABLE_HTTPS=1             # 1 表示启用，0 表示禁用
HTTPS_PORT=5443            # HTTPS 端口（与 HTTP 5004 区分）
# 如果你有真实 SSL 证书，填写路径；留空则自动生成自签名证书
# HTTPS_CERT_FILE=""
# HTTPS_KEY_FILE=""
SERVER_DOMAIN="www.YOUR_SERVER_DOMAIN"  # 用于自签名证书包含的域名

# ==================== SMTP 邮箱配置 ====================
export SMTP_USERNAME="YOUR_SMTP_USERNAME"
export SMTP_PASSWORD="YOUR_SMTP_PASSWORD"
export SMTP_ADDR="smtp.qq.com:465"

# 切换到脚本所在目录
cd "$(dirname "$0")"
if [ ! -f "./aurora-server" ]; then
    cd /www/wwwroot/www.YOUR_SERVER_DOMAIN/backend
fi

echo "=========================================="
echo " Aurora Chat 后端服务启动脚本"
echo "=========================================="
echo "  HTTP 端口: $SERVER_PORT"
echo "  TCP 端口 : $TCP_PORT"
echo "  HTTPS 端口 : ${HTTPS_PORT:-未启用}"
echo "  日志文件 : $LOG_FILE"
echo "=========================================="

# 1. 停止旧进程
PID=$(pgrep -f "aurora-server" | grep -v $$)
if [ -n "$PID" ]; then
    echo "[警告] 检测到已有 aurora-server 进程 (PID: $PID)，正在停止..."
    kill $PID 2>/dev/null
    sleep 2
    if kill -0 $PID 2>/dev/null; then
        kill -9 $PID 2>/dev/null
        echo "已强制终止旧进程"
    else
        echo "旧进程已停止"
    fi
fi

# 2. 检查可执行文件
if [ ! -f "./aurora-server" ]; then
    echo "[错误] 未找到 aurora-server 可执行文件!"
    exit 1
fi

# 3. 导出环境变量并后台启动
export SERVER_PORT=$SERVER_PORT
export TCP_PORT=$TCP_PORT
if [ -n "$ENABLE_HTTPS" ]; then
    export ENABLE_HTTPS=$ENABLE_HTTPS
    export HTTPS_PORT=$HTTPS_PORT
fi
[ -n "$HTTPS_CERT_FILE" ] && export HTTPS_CERT_FILE=$HTTPS_CERT_FILE
[ -n "$HTTPS_KEY_FILE" ] && export HTTPS_KEY_FILE=$HTTPS_KEY_FILE
[ -n "$SERVER_DOMAIN" ] && export SERVER_DOMAIN=$SERVER_DOMAIN

echo ""
echo "正在后台启动服务器..."
nohup ./aurora-server > "$LOG_FILE" 2>&1 &
NEW_PID=$!
echo "服务器已启动 (PID: $NEW_PID)"

# 4. 检查启动状态
sleep 2
if kill -0 $NEW_PID 2>/dev/null; then
    echo ""
    echo "[✓] 服务器已在后台运行 (PID: $NEW_PID)"
    echo ""
    echo "查看实时日志:"
    echo "  tail -f $LOG_FILE"
    echo ""
    echo "停止服务器:"
    echo "  pkill -f aurora-server"
    echo ""
    echo "访问测试:"
    echo "  HTTP:  http://www.YOUR_SERVER_DOMAIN:$SERVER_PORT/api/health"
    if [ -n "$ENABLE_HTTPS" ]; then
        echo "  HTTPS: https://www.YOUR_SERVER_DOMAIN:$HTTPS_PORT/api/health"
    fi
else
    echo "[✗] 启动失败，查看日志:"
    echo "  cat $LOG_FILE"
    exit 1
fi
