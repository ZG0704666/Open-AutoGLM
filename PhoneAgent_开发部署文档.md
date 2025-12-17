# PhoneAgent 开发部署文档

> 基于 Open-AutoGLM 的手机自动化 AI Agent 部署指南  
> 最后更新：2025-12-17（已同步最新实现：Transformers 模型服务 + 代理/Ngrok）

---

## 目录

1. [项目概述](#1-项目概述)
2. [系统架构](#2-系统架构)
3. [环境要求](#3-环境要求)
4. [部署步骤](#4-部署步骤)
5. [配置文件说明](#5-配置文件说明)
6. [API 接口文档](#6-api-接口文档)
7. [客户端配置](#7-客户端配置)
8. [常见问题与解决方案](#8-常见问题与解决方案)
9. [开发历程与问题记录](#9-开发历程与问题记录)
10. [附录](#10-附录)

---

## 1. 项目概述

### 1.1 项目目标

将 Open-AutoGLM（AutoGLM-Phone-9B）模型部署在百度 AIStudio 云端，通过 ngrok 暴露公网 API，使 Android 应用（如 Operit）能够调用该 API 实现手机自动化操作。

### 1.2 功能说明

- 用户在手机端通过语音或文字输入指令
- 指令发送到云端 AutoGLM 模型进行理解
- 模型返回具体的操作指令（点击、滑动、输入等）
- 手机端执行相应操作

### 1.3 技术栈（更新）

| 组件 | 技术选型 | 说明 |
|------|----------|------|
| 模型 | AutoGLM-Phone-9B | 智谱开源的手机自动化视觉语言模型 |
| 推理引擎 | Transformers (Glm4vForConditionalGeneration) | 优先使用已验证的 Transformers 加载/推理实现 |
| API 服务 | FastAPI (model_server.py / proxy_server.py) | model_server.py 提供模型推理接口；proxy_server.py 提供代理 + ngrok |
| 内网穿透 | ngrok (pyngrok) | 将本地服务暴露到公网 |
| 云平台 | 百度 AIStudio | 提供 V100 32GB GPU |
| 客户端 | Operit | Android AI Agent 应用 |

---

## 2. 系统架构

### 2.1 整体架构图（当前实现）

```
┌───────────────────────────────────────────────┐
│                  百度 AIStudio                 │
│  ┌──────────────┐    ┌───────────────────┐    │
│  │ proxy_server │ ──► │ model_server.py   │    │
│  │ (ngrok,API)  │ HTTP│ (Transformers)    │    │
│  │ port:8000    │    │ port:8080         │    │
│  └──────┬───────┘    └────────┬──────────┘    │
│         │ ngrok/public_url         │          │
└─────────┴─────────────────────────┴──────────┘
          │                                     │
          ▼                                     ▼
    手机客户端 (Operit)                    模型文件 (AutoGLM-Phone-9B)
    使用 base_url/v1/...                         /home/aistudio/models/...
```

### 2.2 数据流程

```
1. 用户在 Operit 中输入指令："打开淘宝搜索耳机"
                    │
                    ▼
2.  Operit 发送 POST 请求到 ngrok 公网地址
   POST https://xxx.ngrok-free.app/v1/chat/completions
                    │
                    ▼
3. proxy_server.py 接收请求，验证 API Key
                    │
                    ▼
4. proxy_server.py 转发请求到本地 model_server (127.0.0.1:8080)
                    │
                    ▼
5. model_server.py 调用 AutoGLM 模型进行推理
                    │
                    ▼
6. 模型返回操作指令：
   "Launch app='淘宝' → Tap(500, 200) → Type text='耳机' → Tap(800, 200)"
                    │
                    ▼
7. 响应原路返回给 Operit
                    │
                    ▼
8. Operit 解析指令并执行手机操作
```

---

## 3. 环境要求

### 3.1 硬件要求

| 项目 | 最低要求 | 推荐配置 |
|------|----------|----------|
| GPU | V100 16GB | V100 32GB |
| 内存 | 32GB | 64GB |
| 存储 | 50GB | 100GB |

### 3.2 软件环境（已验证版本/建议）

| 软件 | 建议版本 | 说明 |
|------|----------|------|
| Python | 3.10 | AIStudio 默认环境 |
| PyTorch | 2.x | 系统预装 |
| transformers | >=4.50 | 能加载 Glm4v |
| FastAPI | >=0.70 | Web 框架 |
| httpx | >=0.20 | HTTP 客户端 |
| pyngrok | >=5.x | 内网穿透（可选） |

### 3.3 模型文件

模型存放路径：`/home/aistudio/models/ZhipuAI/AutoGLM-Phone-9B`

模型大小：约 18GB

---

## 4. 部署步骤

### 4.1 准备工作

#### 4.1.1 检查模型文件

```bash
ls -la ~/models/ZhipuAI/AutoGLM-Phone-9B/
```

确保模型文件完整。

#### 4.1.2 设置 Python 环境变量

```bash
export PYTHONPATH=/home/aistudio/external-libraries/lib/python3.10/site-packages:$PYTHONPATH
```

#### 4.1.3 检查 ngrok（可选）

```bash
ls -la ~/ngrok
```

确保 ngrok 可执行文件存在。

### 4.2 创建核心服务文件

#### 4.2.1 model_server.py（模型推理服务）

在 `/home/aistudio/` 目录下创建 `model_server.py`，使用 Transformers 加载模型：

```python
#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AutoGLM-Phone-9B 推理服务（输出本地 API URL 与模型名称）
"""
import os
import sys
import time
import uuid
import logging
import asyncio
from typing import List, Optional
from concurrent.futures import ThreadPoolExecutor

os.environ["OMP_NUM_THREADS"] = "4"
os.environ["TOKENIZERS_PARALLELISM"] = "false"

sys.path.insert(0, "/home/aistudio/external-libraries/lib/python3.10/site-packages")

from fastapi import FastAPI, HTTPException, Header
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
import uvicorn
import torch

# ================== 配置区 ==================
MODEL_PATH = "models/ZhipuAI/AutoGLM-Phone-9B"
MODEL_NAME = "autoglm-phone-9b"          # 对外展示的模型名称
API_KEY = "sk-phoneagent-12345"
HOST = "0.0.0.0"
PORT = 8080
MAX_NEW_TOKENS = 1024
# ============================================

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S"
)
log = logging.getLogger("model-server")

app = FastAPI(title="AutoGLM-Phone-9B API Server", version="1.0.0")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

# 模型加载和推理实现...
# （这里应包含完整的模型加载、生成响应等函数）

if __name__ == "__main__":
    # 运行 uvicorn
    uvicorn.run("model_server:app", host=HOST, port=PORT, log_level="warning")
```

#### 4.2.2 proxy_server.py（代理服务）

在 `/home/aistudio/` 目录下创建 `proxy_server.py`：

```python
#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
PhoneAgent 代理服务器（显示 public_url/base_url 与模型名称）
包含临时 /v1/show_key（仅测试用）
"""
import os
import sys
import time
import logging
from typing import Optional

sys.path.insert(0, "/home/aistudio/external-libraries/lib/python3.10/site-packages")

from fastapi import FastAPI, Request, HTTPException, Header
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
import httpx
import uvicorn
from pyngrok import ngrok, conf as pyngrok_conf

# ================== 配置区 ==================
NGROK_AUTH_TOKEN = "你的ngrok_token"
LOCAL_NGROK_PATH = "/home/aistudio/ngrok"
API_KEY = "sk-phoneagent-12345"
MODEL_BACKEND = "http://127.0.0.1:8080"
MODEL_NAME = "autoglm-phone-9b"
PROXY_PORT = 8000
REQUEST_TIMEOUT = 300.0
# ============================================

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger("proxy-server")

app = FastAPI(title="PhoneAgent Proxy Server", version="1.0.0")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

# 代理服务实现...
# （这里应包含完整的代理转发、API Key验证等函数）

if __name__ == "__main__":
    uvicorn.run("proxy_server:app", host="0.0.0.0", port=PROXY_PORT, log_level="info")
```

### 4.3 启动服务

#### 4.3.1 终端 1：启动模型推理服务

```bash
# 设置环境变量
export PYTHONPATH=/home/aistudio/external-libraries/lib/python3.10/site-packages:$PYTHONPATH

# 启动模型服务
python3 ~/model_server.py
```

**启动成功标志：**
```
========================================
  AutoGLM 推理服务已启动
========================================
  模型名称: autoglm-phone-9b
  模型路径: models/ZhipuAI/AutoGLM-Phone-9B
  本地 API Base URL: http://0.0.0.0:8080/v1
  授权 API Key: sk-phoneagent-12345
  健康检查: http://0.0.0.0:8080/health
========================================
```

#### 4.3.2 终端 2：启动代理服务

等待模型服务启动完成后（约 3-5 分钟），在新终端执行：

```bash
python3 ~/proxy_server.py
```

**启动成功标志：**
```
========================================
  PhoneAgent 代理服务已启动
========================================
  模型名称: autoglm-phone-9b
  模型后端 (local): http://127.0.0.1:8080
  本地代理 API Base URL: http://127.0.0.1:8000/v1
  公网代理 API Base URL: https://xxxx.ngrok-free.app/v1
  API Key (测试用): sk-phoneagent-12345
========================================
  在手机上访问： <base_url>/v1/show_key 来获取 base_url 与 api_key（仅测试用）
========================================
```

### 4.4 一键启动脚本（推荐）

创建 `~/start.sh`：

```bash
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
pkill -f "model_server.py" 2>/dev/null || true
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
nohup python model_server.py > model_server.log 2>&1 &
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
    if ! kill -0 $MODEL_PID 2>/dev/null; then
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
```

运行：

```bash
chmod +x ~/start.sh
./start.sh
```

---

## 5. 配置文件说明

### 5.1 model_server.py 配置项

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `MODEL_PATH` | `models/ZhipuAI/AutoGLM-Phone-9B` | 模型文件路径 |
| `MODEL_NAME` | `autoglm-phone-9b` | 对外暴露的模型名称 |
| `API_KEY` | `sk-phoneagent-12345` | API 认证密钥 |
| `HOST` | `0.0.0.0` | 监听地址 |
| `PORT` | `8080` | 服务端口 |
| `MAX_NEW_TOKENS` | `1024` | 最大生成 token 数 |

### 5.2 proxy_server.py 配置项

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `NGROK_AUTH_TOKEN` | - | ngrok 认证 token |
| `LOCAL_NGROK_PATH` | `/home/aistudio/ngrok` | ngrok 可执行文件路径 |
| `API_KEY` | `sk-phoneagent-12345` | 与模型服务一致的 API Key |
| `MODEL_BACKEND` | `http://127.0.0.1:8080` | 模型服务地址 |
| `MODEL_NAME` | `autoglm-phone-9b` | 模型名称 |
| `PROXY_PORT` | `8000` | 代理服务端口 |
| `REQUEST_TIMEOUT` | `300.0` | 请求超时时间（秒） |

---

## 6. API 接口文档

### 6.1 健康检查（模型服务）

**请求：**
```
GET /health
```

**响应：**
```json
{
  "status": "ok",
  "model_loaded": true,
  "model": "autoglm-phone-9b"
}
```

### 6.2 健康检查（代理服务）

**请求：**
```
GET /health
```

**响应：**
```json
{
  "status": "ok",
  "public_url": "https://xxxx.ngrok-free.app",
  "backend": "http://127.0.0.1:8080",
  "backend_ok": true,
  "backend_status": "ready"
}
```

### 6.3 获取模型列表

**请求：**
```
GET /v1/models
```

**响应：**
```json
{
  "object": "list",
  "data": [
    {
      "id": "autoglm-phone-9b",
      "object": "model",
      "created": 1702700000,
      "owned_by": "phoneagent"
    }
  ]
}
```

### 6.4 聊天补全（OpenAI 兼容）

**请求：**
```
POST /v1/chat/completions
Content-Type: application/json
Authorization: Bearer sk-phoneagent-12345

{
  "model": "autoglm-phone-9b",
  "messages": [
    {"role": "system", "content": "你是一个手机自动化助手"},
    {"role": "user", "content": "打开淘宝搜索耳机"}
  ],
  "temperature": 0.7,
  "max_tokens": 1024,
  "stream": false
}
```

**响应：**
```json
{
  "id": "chatcmpl-xxx",
  "object": "chat.completion",
  "created": 1702700000,
  "model": "autoglm-phone-9b",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "Launch app=\"淘宝\"\nTap(500, 200)\nType text=\"耳机\""
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 50,
    "completion_tokens": 30,
    "total_tokens": 80
  }
}
```

### 6.5 临时 Key 显示接口（仅测试用）

**请求：**
```
GET /v1/show_key
```

**响应：**
```json
{
  "api_key": "sk-phoneagent-12345",
  "base_url": "https://xxxx.ngrok-free.app/v1",
  "note": "仅供临时测试，完成后请删除此接口并更换 API Key"
}
```

> **安全提醒：** 此接口仅用于测试，生产环境请删除。

### 6.6 旧版聊天接口（兼容性）

**请求：**
```
POST /chat
Content-Type: application/json

{
  "prompt": "打开淘宝搜索耳机",
  "system": "你是一个手机自动化助手"
}
```

**响应：**
```json
{
  "action": "Launch app=\"淘宝\"\nTap(500, 200)\nType text=\"耳机\""
}
```

---

## 7. 客户端配置

### 7.1 Operit 配置

1. 打开 Operit 应用
2. 进入设置 → API 配置 → 自定义
3. 填写以下信息：

| 配置项 | 值 |
|--------|-----|
| API 地址 | `https://你的ngrok地址.ngrok-free.app/v1` |
| API Key | `sk-phoneagent-12345` |
| 模型名称 | `autoglm-phone-9b` |

4. 保存配置

### 7.2 测试连接

在 Operit 中输入测试指令：

```
打开设置
```

如果手机自动打开设置应用，说明配置成功。

### 7.3 快速获取配置信息

在手机浏览器中访问：
```
https://你的ngrok地址.ngrok-free.app/v1/show_key
```

可以直接获取到 `base_url` 和 `api_key` 信息。

---

## 8. 常见问题与解决方案

### 8.1 模型加载失败

**错误：**
```
No module named transformers
```

**解决：**
```bash
export PYTHONPATH=/home/aistudio/external-libraries/lib/python3.10/site-packages:$PYTHONPATH
```

### 8.2 显存不足

**错误：**
```
CUDA out of memory
```

**解决：**
```bash
# 1. 释放占用显存的进程
pkill -f python
nvidia-smi  # 确认显存已释放

# 2. 重新启动服务
./start.sh
```

### 8.3 SGLang/vLLM 与 GPU 兼容性问题

**错误：**
```
SGLang only supports sm75 and above.
```

**说明：** V100 的 compute capability 是 7.0（sm70），不支持 SGLang。vLLM 在某些 GLM4v 场景也会出现多模态处理报错。

**解决：** 使用 Transformers (Glm4vForConditionalGeneration) 作为 fallback（已验证可用）。

### 8.4 API Key 验证失败

**错误：**
```
{"detail":"Invalid API Key"}
```

**解决：** 确保客户端配置的 API Key 与 server.py 中的 `API_KEY` 一致。

### 8.5 后端连接失败

**错误：**
```
{"detail":"模型服务未启动，请先运行模型服务"}
```

**解决：** 确保 model_server.py 服务已在端口 8080 正常运行。

### 8.6 ngrok 隧道创建失败

**错误：**
```
ngrok 启动或创建隧道失败
```

**解决：**
1. 检查 ngrok token 是否正确
2. 检查 ngrok 可执行文件是否存在
3. 如无需公网访问，可忽略此错误，使用本地 IP 访问

---

## 9. 开发历程与问题记录

### 9.1 阶段一：环境探索

**目标：** 复现 Open-AutoGLM 官方 Demo

**完成情况：** ✅ 已完成

**主要工作：**
- 克隆仓库并安装依赖
- 连接手机进行 ADB 调试
- 成功运行"打开小红书搜索美食"示例

### 9.2 阶段二：服务端开发

**目标：** 将模型部署为 API 服务

**遇到的问题：**

1. **transformers 直接加载失败**
   - 错误：`'Glm4vModel' object has no attribute 'chat'`
   - 原因：AutoGLM 是视觉语言模型，需要正确加载
   - 解决：改用 Glm4vForConditionalGeneration

2. **vLLM/SGLang 兼容性问题**
   - SGLang 要求 GPU compute capability >= 7.5（V100 是 7.0）
   - vLLM 在多模态处理时出现报错
   - 解决：使用 Transformers 作为主要推理引擎

3. **显存不足**
   - 错误：`CUDA out of memory`
   - 原因：其他进程占用了显存
   - 解决：`pkill -f python` 释放显存

### 9.3 阶段三：API 兼容与优化

**目标：** 提供稳定、兼容的 API 接口

**主要改动：**
- 添加 `/v1/chat/completions` 接口（OpenAI 兼容）
- 添加 `/v1/models` 接口
- 添加 `/v1/show_key` 临时接口（便于测试）
- 优化错误处理和超时机制

### 9.4 阶段四：部署与测试

**目标：** 稳定部署并支持客户端调用

**实现：**
- 创建一键启动脚本 `start.sh`
- 添加健康检查机制
- 优化日志输出和信息展示
- 支持 ngrok 公网暴露

### 9.5 最终架构

```
客户端 (Operit / Android)
      │
      │ HTTPS (公网)
      ▼
proxy_server.py (端口 8000)
      │
      │ HTTP (本地)
      ▼
model_server.py (端口 8080)
      │
      ▼
Transformers + Glm4vForConditionalGeneration
      │
      ▼
AutoGLM-Phone-9B 模型
```

---

## 10. 附录

### A. 文件清单

| 文件 | 路径 | 说明 |
|------|------|------|
| model_server.py | `/home/aistudio/model_server.py` | 模型推理服务主程序 |
| proxy_server.py | `/home/aistudio/proxy_server.py` | 代理服务主程序 |
| start.sh | `/home/aistudio/start.sh` | 一键启动脚本 |
| ngrok | `/home/aistudio/ngrok` | ngrok 可执行文件（可选） |
| 模型文件 | `/home/aistudio/models/ZhipuAI/AutoGLM-Phone-9B/` | 模型权重文件 |
| model_server.log | `/home/aistudio/model_server.log` | 模型服务运行日志 |

### B. 常用命令

```bash
# 查看显存使用
nvidia-smi

# 查看进程
ps aux | grep python

# 杀掉 Python 进程
pkill -f python

# 查看端口占用
netstat -tlnp | grep 8080
netstat -tlnp | grep 8000

# 测试 API
curl http://127.0.0.1:8080/health
curl http://127.0.0.1:8000/health

# 获取配置信息
curl http://127.0.0.1:8000/v1/show_key
```

### C. 测试命令示例

```bash
# 测试聊天接口
curl -X POST http://127.0.0.1:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer sk-phoneagent-12345" \
  -d '{
    "model": "autoglm-phone-9b",
    "messages": [
      {"role": "user", "content": "打开设置"}
    ],
    "temperature": 0.7,
    "max_tokens": 1024,
    "stream": false
  }'
```

### D. 参考链接

- [Open-AutoGLM 官方仓库](https://github.com/THUDM/Open-AutoGLM)
- [Transformers 文档](https://huggingface.co/docs/transformers)
- [FastAPI 文档](https://fastapi.tiangolo.com/)
- [ngrok 文档](https://ngrok.com/docs)
- [Operit 应用](https://github.com/AdrianMao/Operit)

---

**文档版本：** v3.0  
**最后更新：** 2025-12-17  
**维护者：** PhoneAgent 开发团队