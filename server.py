#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
针对 V100(32GB) 的优化版 server.py（中文注释）
- 实用优化：限制线程、优先 safetensors、清理 cuda cache、启用 cudnn.benchmark
- 提供 /health 和 /chat 接口，模型推理在线程池中运行以不阻塞主事件循环
- 这是最小化的 demo，便于接入 Android 客户端进行联调
"""
import os
import time
import logging
import asyncio
from concurrent.futures import ThreadPoolExecutor

from fastapi import FastAPI, Request, HTTPException
from fastapi.middleware.cors import CORSMiddleware
import uvicorn

# 强烈建议提前安装：pip install safetensors
from transformers import AutoModel, AutoTokenizer
import torch

# 你之前上传的 ngrok 逻辑保持不变（best-effort）
from pyngrok import ngrok, conf as pyngrok_conf

# ---------------- 配置区 ----------------
NGROK_AUTH_TOKEN = "36pWY8ALQvaYqFl10a15kJSXXU-3pXtTMth2b9nSevC5W1S3"
MODEL_PATH = "models/ZhipuAI/AutoGLM-Phone-9B"
LOCAL_NGROK_PATH = "/home/aistudio/ngrok"
# ----------------------------------------

# 日志（简洁）
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s: %(message)s")
log = logging.getLogger("phoneagent-opt")

app = FastAPI(title="PhoneAgent - V100 Optimized")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

# 线程池用于运行阻塞模型推理
executor = ThreadPoolExecutor(max_workers=1)

# 全局模型变量与状态
tokenizer = None
model = None
_model_loaded = False

# ---------------- 小工具 ----------------
def detect_safetensors(path: str) -> bool:
    try:
        for root, dirs, files in os.walk(path):
            for f in files:
                if f.endswith(".safetensors"):
                    return True
    except Exception:
        pass
    return False

# ---------------- 加载优化策略 ----------------
def load_model_optimized():
    """
    关键优化点：
    1. 通过环境变量 + torch.set_num_threads 限制多线程开销（IO/CPU密集场景有帮助）
    2. 优先 use_safetensors（若存在），利用 mmap 加速
    3. 直接尝试 device_map='cuda:0' + dtype=float16，并关闭 low_cpu_mem_usage（利用主机内存换速度）
    4. fallback: 若直接加载失败，先加载到 CPU 再 .to('cuda:0')
    """
    global tokenizer, model, _model_loaded

    log.info("模型路径: %s", MODEL_PATH)
    # 限制线程（减小 Python/BLAS 在加载时的调度开销）
    try:
        torch.set_num_threads(1)
    except Exception:
        pass

    # 清理 CUDA cache（如果之前有残留）
    if torch.cuda.is_available():
        try:
            torch.cuda.empty_cache()
            torch.backends.cudnn.benchmark = True
        except Exception:
            pass

    use_safetensors = detect_safetensors(MODEL_PATH)
    log.info("safetensors 检测结果: %s", use_safetensors)

    log.info("加载 tokenizer ...")
    tokenizer = AutoTokenizer.from_pretrained(MODEL_PATH, trust_remote_code=True, fix_mistral_regex=True)
    log.info("tokenizer 加载完成。")

    start = time.time()
    # 优先：直接加载到 GPU
    try:
        log.info("尝试直接加载模型到 cuda:0（dtype=float16，low_cpu_mem_usage=False）...")
        model_local = AutoModel.from_pretrained(
            MODEL_PATH,
            trust_remote_code=True,
            dtype=torch.float16,
            device_map="cuda:0",
            low_cpu_mem_usage=False,
            use_safetensors=use_safetensors
        ).eval()
        # 小延迟确保显存稳定
        time.sleep(0.5)
        model = model_local
        elapsed = time.time() - start
        log.info("直接加载成功，耗时 %.1f 秒", elapsed)
        _model_loaded = True
        return
    except Exception as e:
        log.warning("直接加载到 GPU 失败: %s", e)

    # fallback：先加载到 CPU，再搬到 GPU（通常稳妥但会略慢）
    try:
        log.info("fallback: 先加载到 CPU ...")
        model_local = AutoModel.from_pretrained(
            MODEL_PATH,
            trust_remote_code=True,
            dtype=torch.float16,
            device_map="cpu",
            low_cpu_mem_usage=False,
            use_safetensors=use_safetensors
        ).eval()
        if torch.cuda.is_available():
            log.info("将模型从 CPU 转移到 cuda:0 ...")
            model_local.to("cuda:0")
        model = model_local
        elapsed = time.time() - start
        log.info("fallback 加载成功，耗时 %.1f 秒", elapsed)
        _model_loaded = True
        return
    except Exception as e:
        log.exception("fallback 加载也失败: %s", e)
        raise

# ---------------- 模型推理封装 ----------------
def _model_chat_sync(prompt: str, system_prompt: str = "") -> str:
    if model is None:
        raise RuntimeError("model not loaded")
    history = [{"role":"system","content":system_prompt}] if system_prompt else []
    resp, _ = model.chat(tokenizer, prompt, history=history, max_length=1024, top_p=0.8, temperature=0.1)
    return resp

async def model_chat(prompt: str, system_prompt: str = "") -> str:
    loop = asyncio.get_running_loop()
    fut = loop.run_in_executor(executor, _model_chat_sync, prompt, system_prompt)
    return await fut

# ---------------- FastAPI 事件与路由 ----------------
@app.on_event("startup")
def on_startup():
    # 1) 尝试使用本地 ngrok（若存在）
    try:
        if os.path.exists(LOCAL_NGROK_PATH):
            pyngrok_conf.get_default().ngrok_path = LOCAL_NGROK_PATH
            log.info("配置 pyngrok 使用本地 ngrok：%s", LOCAL_NGROK_PATH)
        # 启动 ngrok（best-effort）
        if NGROK_AUTH_TOKEN and os.path.exists(pyngrok_conf.get_default().ngrok_path):
            ngrok.set_auth_token(NGROK_AUTH_TOKEN)
            cfg = pyngrok_conf.PyngrokConfig(ngrok_path=pyngrok_conf.get_default().ngrok_path)
            public_url = ngrok.connect(8000, pyngrok_config=cfg).public_url
            log.info("ngrok 启动，公网地址：%s", public_url)
            print("="*60)
            print("Public ngrok URL:", public_url, "/chat")
            print("="*60)
    except Exception as e:
        log.warning("ngrok 启动或配置出错（忽略并继续）：%s", e)

    # 2) 加载模型（阻塞）
    load_model_optimized()

@app.get("/health")
async def health():
    return {"status":"ok","model_loaded": _model_loaded}

@app.post("/chat")
async def chat(req: Request):
    if not _model_loaded:
        raise HTTPException(status_code=503, detail="模型尚未加载完成，请稍后再试")
    body = await req.json()
    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="期待 JSON 对象")
    prompt = body.get("prompt")
    if not prompt:
        raise HTTPException(status_code=400, detail="缺少 prompt 字段")
    system = body.get("system", "你是一个手机自动化助手。请输出具体的操作指令。")
    try:
        res = await model_chat(prompt, system)
        return {"action": res}
    except Exception as e:
        log.exception("推理错误: %s", e)
        raise HTTPException(status_code=500, detail=f"推理错误: {e}")

if __name__ == "__main__":
    # 推荐在 shell 运行前 export OMP_NUM_THREADS=1 MKL_NUM_THREADS=1
    try:
        torch.backends.cudnn.benchmark = True
    except Exception:
        pass
    uvicorn.run("server:app", host="0.0.0.0", port=8000, log_level="info")
