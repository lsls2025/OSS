#!/bin/bash
# Aurora Chat 后端部署脚本
# 用法: chmod +x deploy.sh && ./deploy.sh

# 强制杀旧进程
OLD_PID=$(pgrep -f aurora-server 2>/dev/null)
if [ -n "$OLD_PID" ]; then
    echo "正在结束旧进程 PID: $OLD_PID"
    kill -9 $OLD_PID 2>/dev/null
    sleep 1
fi

# 确认端口已释放（默认5004）
PORT=${SERVER_PORT:-5004}
fuser -k ${PORT}/tcp 2>/dev/null

# 清理 nohup 输出
rm -f nohup.out server.log

# 给执行权限
chmod +x ./aurora-server

# 后台启动（关闭终端也不会停）
nohup ./aurora-server > server.log 2>&1 &
NEW_PID=$!
echo "新进程已启动, PID: $NEW_PID"
echo "查看日志: tail -f server.log"
