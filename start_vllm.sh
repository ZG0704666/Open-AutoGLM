#!/bin/bash
# 启动 vLLM 模型服务（后台运行）

echo "正在启动 vLLM 模型服务..."

python3 -m vllm. entrypoints.openai.api_server \
  --served-model-name autoglm-phone-9b \
  --allowed-local-media-path / \
  --trust-remote-code \
  --dtype half \
  --max-model-len 8192 \
  --model models/ZhipuAI/AutoGLM-Phone-9B \
  --host 0.0.0.0 \
  --port 8080

echo "vLLM 服务已启动在端口 8080"