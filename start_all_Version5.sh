#!/bin/bash
# 一键启动所有服务

echo "========================================"
echo "  PhoneAgent 一键部署脚本"
echo "========================================"

# 1. 启动 vLLM（后台运行）
echo ""
echo "[1/2] 启动 vLLM 模型服务..."
echo ""

nohup python3 -m vllm.entrypoints.openai.api_server \
  --served-model-name autoglm-phone-9b \
  --trust-remote-code \
  --dtype half \
  --max-model-len 8192 \
  --model models/ZhipuAI/AutoGLM-Phone-9B \
  --host 0.0.0.0 \
  --port 8080 \
  > vllm. log 2>&1 &

VLLM_PID=$! 
echo "vLLM 进程 ID: $VLLM_PID"
echo "日志文件: vllm. log"

# 2. 等待 vLLM 启动
echo ""
echo "等待 vLLM 启动（可能需要 2-5 分钟）..."
echo ""

for i in {1..60}; do
    if curl -s http://127.0.0.1:8080/health > /dev/null 2>&1; then
        echo "vLLM 服务已就绪!"
        break
    fi
    echo "等待中... ($i/60)"
    sleep 5
done

# 3. 启动代理服务
echo ""
echo "[2/2] 启动代理服务..."
echo ""

python server.py