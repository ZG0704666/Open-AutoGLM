# PhoneAgent 开发部署文档

> 基于 Open-AutoGLM 的手机自动化 AI Agent 部署指南
> 
> 最后更新：2024-12-16

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

---

## 1. 项目概述

### 1.1 项目目标

将 Open-AutoGLM 模型部署到百度 AIStudio 云端，通过 ngrok 暴露公网 API，使 Android 应用（如 Operit）能够调用该 API 实现手机自动化操作。

### 1.2 功能说明

- 用户在手机端通过语音或文字输入指令
- 指令发送到云端 AutoGLM 模型进行理解
- 模型返回具体的操作指令（点击、滑动、输入等）
- 手机端执行相应操作

### 1.3 技术栈

| 组件 | 技术选型 | 说明 |
|------|----------|------|
| 模型 | AutoGLM-Phone-9B | 智谱开源的手机自动化视觉语言模型 |
| 推理引擎 | vLLM | 高性能 LLM 推理框架 |
| API 服务 | FastAPI | Python 异步 Web 框架 |
| 内网穿透 | ngrok | 将本地服务暴露到公网 |
| 云平台 | 百度 AIStudio | 提供 V100 32GB GPU |
| 客户端 | Operit | Android AI Agent 应用 |

---

## 2. 系统架构

### 2.1 整体架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                        百度 AIStudio                             │
│  ┌─────────────────┐         ┌─────────────────┐                │
│  │   server.py     │ ──────► │   vLLM Server   │                │
│  │  (代理服务)      │  HTTP   │  (模型推理)      │                │
│  │  端口: 8000     │         │  端口:  8080      │                │
│  └────────┬────────┘         └─────────────────┘                │
│           │                                                      │
│           │ ngrok                                                │
└───────────┼─────────────────────────────────────────────────────┘
            │
            │ HTTPS (公网)
            ▼
┌─────────────────────────────────────────────────────────────────┐
│                      用户手机 (Android)                          │
│  ┌─────────────────┐                                            │
│  │   Operit App    │                                            │
│  │  (AI Agent)     │                                            │
│  └─────────────────┘                                            │
└─────────────────────────────────────────────────────────────────┘
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
3. server.py 接收请求，验证 API Key
                    │
                    ▼
4. server.py 转发请求到本地 vLLM 服务 (127.0.0.1:8080)
                    │
                    ▼
5. vLLM 调用 AutoGLM 模型进行推理
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

### 3.2 软件环境

| 软件 | 版本 | 说明 |
|------|------|------|
| Python | 3.10 | AIStudio 默认环境 |
| vLLM | 0.12.0 | LLM 推理引擎 |
| FastAPI | 0.124.0 | Web 框架 |
| PyTorch | 2.9.0 | 深度学习框架 |
| transformers | 4.57.3 | Hugging Face 模型库 |

### 3.3 模型文件

模型存放路径：`/home/aistudio/models/ZhipuAI/AutoGLM-Phone-9B`

模型大小：约 18GB

---

## 4. 部署步骤

### 4.1 准备工作

#### 4.1.1 检查 vLLM 安装

```bash
pip show vllm
```

如果未安装：

```bash
pip install vllm
```

#### 4.1.2 检查模型文件

```bash
ls -la ~/models/ZhipuAI/AutoGLM-Phone-9B/
```

确保模型文件完整。

#### 4.1.3 检查 ngrok

```bash
ls -la ~/ngrok
```

确保 ngrok 可执行文件存在。

### 4.2 创建 server.py

在 `/home/aistudio/` 目录下创建 `server.py`：

```python
#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
PhoneAgent 代理服务器 - 转发到本地 vLLM
提供 OpenAI 兼容的 API 接口
"""
import os
import sys

# 添加 external-libraries 到 Python 路径
sys.path.insert(0, "/home/aistudio/external-libraries/lib/python3.10/site-packages")

import time
import logging
import json
from typing import Optional

from fastapi import FastAPI, Request, HTTPException, Header
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse, JSONResponse
import httpx
import uvicorn

from pyngrok import ngrok, conf as pyngrok_conf

# ================== 配置区 ==================
NGROK_AUTH_TOKEN = "你的ngrok_token"
LOCAL_NGROK_PATH = "/home/aistudio/ngrok"
MY_API_KEY = "sk-phoneagent-12345"
VLLM_BACKEND = "http://127.0.0.1:8080"
PROXY_PORT = 8000
# ============================================

logging.basicConfig(level=logging. INFO, format="%(asctime)s %(levelname)s: %(message)s")
log = logging.getLogger("phoneagent-proxy")

app = FastAPI(title="PhoneAgent Proxy Server")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

http_client = None


def verify_api_key(authorization: str = None) -> bool:
    if not authorization:
        return False
    if authorization.startswith("Bearer "):
        token = authorization[7:]
    else:
        token = authorization
    return token == MY_API_KEY


@app.on_event("startup")
async def on_startup():
    global http_client
    http_client = httpx.AsyncClient(timeout=120.0)
    
    try:
        if os.path.exists(LOCAL_NGROK_PATH):
            pyngrok_conf.get_default().ngrok_path = LOCAL_NGROK_PATH
        
        if NGROK_AUTH_TOKEN and os.path.exists(pyngrok_conf.get_default().ngrok_path):
            ngrok.set_auth_token(NGROK_AUTH_TOKEN)
            cfg = pyngrok_conf.PyngrokConfig(ngrok_path=pyngrok_conf.get_default().ngrok_path)
            public_url = ngrok.connect(PROXY_PORT, pyngrok_config=cfg).public_url
            
            print("")
            print("=" * 60)
            print("  PhoneAgent 代理服务已启动!")
            print("=" * 60)
            print(f"  公网地址: {public_url}")
            print(f"  API Key:   {MY_API_KEY}")
            print(f"  后端:      {VLLM_BACKEND}")
            print("=" * 60)
            print("  在 Operit 中配置:")
            print(f"    API 地址: {public_url}/v1")
            print(f"    API Key:  {MY_API_KEY}")
            print(f"    模型名称: autoglm-phone-9b")
            print("=" * 60)
            print("")
    except Exception as e:
        log.warning("ngrok 启动失败: %s", e)


@app.on_event("shutdown")
async def on_shutdown():
    global http_client
    if http_client:
        await http_client.aclose()


@app.get("/health")
async def health():
    backend_ok = False
    try:
        resp = await http_client.get(f"{VLLM_BACKEND}/health", timeout=5.0)
        backend_ok = resp.status_code == 200
    except Exception: 
        pass
    return {"status": "ok", "backend":  VLLM_BACKEND, "backend_ok": backend_ok}


@app.get("/v1/models")
async def list_models(authorization: Optional[str] = Header(None)):
    try:
        resp = await http_client.get(f"{VLLM_BACKEND}/v1/models", timeout=10.0)
        if resp.status_code == 200:
            return resp. json()
    except Exception as e:
        log.warning("获取模型列表失败: %s", e)
    
    return {
        "object":  "list",
        "data":  [{"id": "autoglm-phone-9b", "object": "model", "created": int(time.time()), "owned_by": "phoneagent"}]
    }


@app.post("/v1/chat/completions")
async def chat_completions(request: Request, authorization: Optional[str] = Header(None)):
    if not verify_api_key(authorization):
        raise HTTPException(status_code=401, detail="Invalid API Key")
    
    try:
        body = await request.json()
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Invalid JSON: {e}")
    
    log.info("收到请求: model=%s, stream=%s", body.get("model"), body.get("stream", False))
    
    headers = {"Content-Type": "application/json"}
    
    try:
        if body.get("stream", False):
            async def stream_generator():
                try:
                    async with http_client.stream(
                        "POST",
                        f"{VLLM_BACKEND}/v1/chat/completions",
                        json=body,
                        headers=headers,
                        timeout=120.0
                    ) as resp:
                        async for chunk in resp.aiter_bytes():
                            yield chunk
                except Exception as e:
                    log. error("流式请求错误: %s", e)
                    yield f"data: {json.dumps({'error': {'message': str(e)}})}\n\n"
            
            return StreamingResponse(stream_generator(), media_type="text/event-stream")
        else:
            resp = await http_client.post(
                f"{VLLM_BACKEND}/v1/chat/completions",
                json=body,
                headers=headers,
                timeout=120.0
            )
            
            if resp.status_code != 200:
                log.error("后端返回错误: %s - %s", resp.status_code, resp.text)
                raise HTTPException(status_code=resp.status_code, detail=resp.text)
            
            return JSONResponse(content=resp.json())
            
    except httpx.TimeoutException:
        raise HTTPException(status_code=504, detail="Backend timeout")
    except httpx.ConnectError:
        raise HTTPException(status_code=503, detail="vLLM 服务未启动，请先运行 vLLM")
    except HTTPException: 
        raise
    except Exception as e:
        log.exception("后端请求失败: %s", e)
        raise HTTPException(status_code=502, detail=f"Backend error: {e}")


@app.post("/chat")
async def chat_legacy(request: Request):
    """旧版接口，兼容原有调用方式"""
    try:
        body = await request. json()
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Invalid JSON: {e}")
    
    prompt = body.get("prompt", "")
    system = body.get("system", "你是一个手机自动化助手。请输出具体的操作指令。")
    
    if not prompt:
        raise HTTPException(status_code=400, detail="Missing 'prompt' field")
    
    openai_body = {
        "model": "autoglm-phone-9b",
        "messages": [
            {"role": "system", "content": system},
            {"role":  "user", "content": prompt}
        ],
        "temperature": 0.7,
        "max_tokens": 1024,
        "stream": False
    }
    
    try:
        resp = await http_client.post(
            f"{VLLM_BACKEND}/v1/chat/completions",
            json=openai_body,
            headers={"Content-Type": "application/json"},
            timeout=120.0
        )
        
        if resp.status_code != 200:
            raise HTTPException(status_code=resp.status_code, detail=resp.text)
        
        result = resp.json()
        action = result.get("choices", [{}])[0].get("message", {}).get("content", "")
        return {"action": action}
        
    except Exception as e:
        log.exception("请求失败: %s", e)
        raise HTTPException(status_code=500, detail=str(e))


if __name__ == "__main__": 
    print("")
    print("=" * 60)
    print("  启动 PhoneAgent 代理服务")
    print("  请确保 vLLM 已在端口 8080 运行")
    print("=" * 60)
    print("")
    
    uvicorn.run("server:app", host="0.0.0.0", port=PROXY_PORT, log_level="info")
```

### 4.3 启动服务

#### 4.3.1 终端 1：启动 vLLM 模型服务

```bash
# 1. 释放可能占用的显存
pkill -f python
sleep 3

# 2. 检查显存状态
nvidia-smi

# 3. 设置环境变量
export PYTHONPATH=/home/aistudio/external-libraries/lib/python3.10/site-packages:$PYTHONPATH

# 4. 启动 vLLM（V100 32G 完整配置）
python3 -m vllm.entrypoints.openai.api_server \
  --served-model-name autoglm-phone-9b \
  --trust-remote-code \
  --dtype half \
  --max-model-len 8192 \
  --gpu-memory-utilization 0.9 \
  --model models/ZhipuAI/AutoGLM-Phone-9B \
  --host 0.0.0.0 \
  --port 8080
```

**启动成功标志：**

```
INFO:     Uvicorn running on http://0.0.0.0:8080 (Press CTRL+C to quit)
```

#### 4.3.2 终端 2：启动代理服务

等待 vLLM 启动完成后（约 2-5 分钟），在新终端执行：

```bash
python ~/server.py
```

**启动成功标志：**

```
============================================================
  PhoneAgent 代理服务已启动!
============================================================
  公网地址: https://xxx.ngrok-free.app
  API Key:  sk-phoneagent-12345
  后端:     http://127.0.0.1:8080
============================================================
  在 Operit 中配置:
    API 地址: https://xxx.ngrok-free.app/v1
    API Key:  sk-phoneagent-12345
    模型名称: autoglm-phone-9b
============================================================
```

### 4.4 一键启动脚本（可选）

创建 `start_all.sh`：

```bash
#!/bin/bash

echo "========================================"
echo "  PhoneAgent 一键启动脚本"
echo "========================================"

# 1. 释放显存
echo "[1/3] 释放显存..."
pkill -f "vllm" 2>/dev/null
pkill -f "server. py" 2>/dev/null
sleep 3

# 2. 设置环境变量
export PYTHONPATH=/home/aistudio/external-libraries/lib/python3.10/site-packages:$PYTHONPATH

# 3. 后台启动 vLLM
echo "[2/3] 启动 vLLM 服务..."
nohup python3 -m vllm.entrypoints.openai.api_server \
  --served-model-name autoglm-phone-9b \
  --trust-remote-code \
  --dtype half \
  --max-model-len 8192 \
  --gpu-memory-utilization 0.9 \
  --model models/ZhipuAI/AutoGLM-Phone-9B \
  --host 0.0.0.0 \
  --port 8080 \
  > vllm. log 2>&1 &

# 4. 等待 vLLM 启动
echo "等待 vLLM 启动（约 2-5 分钟）..."
for i in {1..60}; do
    if curl -s http://127.0.0.1:8080/health > /dev/null 2>&1; then
        echo "vLLM 服务已就绪!"
        break
    fi
    echo "等待中... ($i/60)"
    sleep 5
done

# 5. 启动代理服务
echo "[3/3] 启动代理服务..."
python ~/server.py
```

运行：

```bash
chmod +x start_all.sh
./start_all.sh
```

---

## 5. 配置文件说明

### 5.1 server.py 配置项

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `NGROK_AUTH_TOKEN` | - | ngrok 认证 token |
| `LOCAL_NGROK_PATH` | `/home/aistudio/ngrok` | ngrok 可执行文件路径 |
| `MY_API_KEY` | `sk-phoneagent-12345` | 自定义 API Key |
| `VLLM_BACKEND` | `http://127.0.0.1:8080` | vLLM 服务地址 |
| `PROXY_PORT` | `8000` | 代理服务端口 |

### 5.2 vLLM 启动参数

| 参数 | 值 | 说明 |
|------|-----|------|
| `--served-model-name` | `autoglm-phone-9b` | 对外暴露的模型名称 |
| `--trust-remote-code` | - | 允许执行模型自定义代码 |
| `--dtype` | `half` | 使用 FP16 精度 |
| `--max-model-len` | `8192` | 最大序列长度 |
| `--gpu-memory-utilization` | `0.9` | GPU 显存使用率 |
| `--model` | 模型路径 | 模型文件位置 |
| `--host` | `0.0.0.0` | 监听地址 |
| `--port` | `8080` | 服务端口 |

---

## 6. API 接口文档

### 6.1 健康检查

**请求：**
```
GET /health
```

**响应：**
```json
{
  "status": "ok",
  "backend": "http://127.0.0.1:8080",
  "backend_ok":  true
}
```

### 6.2 获取模型列表

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

### 6.3 聊天补全（OpenAI 兼容）

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
  "temperature":  0.7,
  "max_tokens": 1024,
  "stream": false
}
```

**响应：**
```json
{
  "id": "chatcmpl-xxx",
  "object": "chat.completion",
  "created":  1702700000,
  "model": "autoglm-phone-9b",
  "choices":  [
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
    "completion_tokens":  30,
    "total_tokens":  80
  }
}
```

### 6.4 旧版聊天接口

**请求：**
```
POST /chat
Content-Type:  application/json

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
| API 地址 | `https://你的ngrok地址. ngrok-free.app/v1` |
| API Key | `sk-phoneagent-12345` |
| 模型名称 | `autoglm-phone-9b` |

4. 保存配置

### 7.2 测试连接

在 Operit 中输入测试指令：

```
打开设置
```

如果手机自动打开设置应用，说明配置成功。

---

## 8. 常见问题与解决方案

### 8.1 vLLM 找不到模块

**错误：**
```
No module named vllm
```

**解决：**
```bash
export PYTHONPATH=/home/aistudio/external-libraries/lib/python3.10/site-packages:$PYTHONPATH
```

### 8.2 显存不足

**错误：**
```
ValueError: Free memory on device (13.06/31.73 GiB) on startup is less than desired GPU memory utilization
```

**解决：**
```bash
# 1. 杀掉占用显存的进程
pkill -f python
nvidia-smi  # 确认显存已释放

# 2. 降低显存使用率
--gpu-memory-utilization 0.5
--max-model-len 4096
```

### 8.3 FlashAttention 不支持

**错误：**
```
FA2 is only supported on devices with compute capability >= 8
```

**说明：** V100 的 compute capability 是 7.0，不支持 FA2。vLLM 会自动回退到其他实现，可以忽略此警告。

### 8.4 流式响应不支持

**错误：**
```
{"detail":"暂不支持流式响应"}
```

**解决：** 当前版本已支持流式响应。如果仍然报错，请在 Operit 中关闭"流式输出"选项。

### 8.5 API Key 验证失败

**错误：**
```
{"detail":"Invalid API Key"}
```

**解决：** 确保 Operit 中配置的 API Key 与 server.py 中的 `MY_API_KEY` 一致。

### 8.6 后端连接失败

**错误：**
```
{"detail":"vLLM 服务未启动，请先运行 vLLM"}
```

**解决：** 确保 vLLM 服务已在端口 8080 正常运行。

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
   - 原因：AutoGLM 是视觉语言模型，需要用 vLLM 等专用框架
   - 解决：改用 vLLM 部署

2. **vLLM 模块找不到**
   - 错误：`No module named vllm`
   - 原因：vLLM 安装在 external-libraries，但 Python 默认不加载
   - 解决：设置 PYTHONPATH 环境变量

3. **显存不足**
   - 错误：`Free memory on device (13.06/31.73 GiB)`
   - 原因：其他进程占用了显存
   - 解决：`pkill -f python` 释放显存

### 9.3 阶段三：API 兼容

**目标：** 提供 OpenAI 兼容的 API 接口

**主要改动：**
- 添加 `/v1/chat/completions` 接口
- 添加 `/v1/models` 接口
- 支持流式响应（stream=true）
- 添加 API Key 验证

### 9.4 阶段四：内网穿透

**目标：** 将 API 暴露到公网

**方案：** 使用 ngrok + pyngrok

**实现：**
- 在 server.py 启动时自动建立 ngrok 隧道
- 打印公网地址供客户端配置

### 9.5 最终架构

```
客户端 (Operit)
      │
      │ HTTPS
      ▼
ngrok (公网地址)
      │
      │ HTTP
      ▼
server.py (端口 8000)
      │
      │ HTTP
      ▼
vLLM (端口 8080)
      │
      ▼
AutoGLM-Phone-9B 模型
```

---

## 附录

### A. 文件清单

| 文件 | 路径 | 说明 |
|------|------|------|
| server.py | `/home/aistudio/server. py` | 代理服务主程序 |
| ngrok | `/home/aistudio/ngrok` | ngrok 可执行文件 |
| 模型文件 | `/home/aistudio/models/ZhipuAI/AutoGLM-Phone-9B/` | 模型权重文件 |
| vllm. log | `/home/aistudio/vllm.log` | vLLM 运行日志 |

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

# 测试 API
curl http://127.0.0.1:8080/health
curl http://127.0.0.1:8000/health
```

### C. 参考链接

- [Open-AutoGLM 官方仓库](https://github.com/THUDM/Open-AutoGLM)
- [vLLM 文档](https://docs.vllm.ai/)
- [ngrok 文档](https://ngrok.com/docs)
- [Operit 应用](https://github.com/AdrianMao/Operit)

---

**文档版本：** v1.0
**最后更新：** 2024-12-16
**作者：** PhoneAgent 开发团队