#!/bin/bash
#
# PhoneAgent 一键启动脚本
# 用法: ./start.sh
#

set -e

echo ""
echo "========================================"
echo "  PhoneAgent 一键启动脚本"
echo "========================================"
echo ""

# 设置环境变量
export PYTHONPATH=/home/aistudio/external-libraries/lib/python3.10/site-packages:$PYTHONPATH

# 清理旧进程
echo "[1/4] 清理旧进程..."
pkill -f "model_server. py" 2>/dev/null || true
pkill -f "proxy_server.py" 2>/dev/null || true
sleep 2

# 检查模型文件
echo "[2/4] 检查模型文件..."
if [ ! -d "$HOME/models/ZhipuAI/AutoGLM-Phone-9B" ]; then
    echo "❌ 错误: 模型文件不存在!"
    echo "   请确保模型位于:  ~/models/ZhipuAI/AutoGLM-Phone-9B"
    exit 1
fi
echo "✅ 模型文件存在"

# 启动模型服务
echo "[3/4] 启动模型服务（后台运行）..."
cd ~
nohup python model_server.py > model_server. log 2>&1 &
MODEL_PID=$!
echo "   模型服务 PID: $MODEL_PID"
echo "   日志文件: ~/model_server.log"

# 等待模型加载
echo ""
echo "等待模型加载（约 3-5 分钟）..."
echo "提示: 可以在另一个终端运行 'tail -f ~/model_server.log' 查看加载进度"
echo ""

MAX_WAIT=300  # 最长等待 5 分钟
WAITED=0
while [ $WAITED -lt $MAX_WAIT ]; do
    # 检查进程是否还在
    if !  kill -0 $MODEL_PID 2>/dev/null; then
        echo ""
        echo "❌ 模型服务异常退出!"
        echo "   请查看日志:  tail -50 ~/model_server.log"
        exit 1
    fi
    
    # 检查健康状态
    HEALTH=$(curl -s http://127.0.0.1:8080/health 2>/dev/null || echo "")
    if echo "$HEALTH" | grep -q '"model_loaded":  true'; then
        echo ""
        echo "✅ 模型加载完成!"
        break
    fi
    
    # 显示进度
    WAITED=$((WAITED + 5))
    PROGRESS=$((WAITED * 100 / MAX_WAIT))
    printf "\r   等待中... %d 秒 (%d%%)" $WAITED $PROGRESS
    sleep 5
done

if [ $WAITED -ge $MAX_WAIT ]; then
    echo ""
    echo "⚠️  等待超时，但服务可能仍在加载中"
    echo "   请查看日志: tail -f ~/model_server.log"
fi

# 启动代理服务
echo ""
echo "[4/4] 启动代理服务..."
python proxy_server.py
