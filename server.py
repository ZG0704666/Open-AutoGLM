#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
最终版 server.py（内置启动前 wrapper，自动设置 OMP/MKL/OPENBLAS 等环境变量并重启自身以确保生效）
说明：
- 该脚本在启动时会检查标志 PA_SERVER_WRAPPED；如果未设置，则设置以下环境变量并使用 os.execv 重新启动当前 Python 进程：
    OMP_NUM_THREADS=1
    MKL_NUM_THREADS=1
    OPENBLAS_NUM_THREADS=1
    VECLIB_MAXIMUM_THREADS=1
    OMP_PROC_BIND=true
- 这样可以保证底层 C/Fortran 线程库在进程初始化前读取到这些环境变量，显著降低加载大模型时的 CPU 线程调度开销，从而加速冷启动。
- 之后脚本会加载模型（优先使用 .safetensors 并尝试直接装到 cuda:0），并暴露 /health 和 /chat 接口。
- 为调试友好，终端只输出关键日志（开始加载、加载成功、ngrok 公网地址、错误）。
"""

import os
import sys

# ------------------ 启动前 wrapper：设置环境变量并重启进程 ------------------
WRAP_FLAG = "PA_SERVER_WRAPPED"
if os.environ.get(WRAP_FLAG) != "1":
    # 设置推荐的线程环境变量（如果用户已经显式设置则保留原值）
    env_changes = {
        "OMP_NUM_THREADS": "1",
        "MKL_NUM_THREADS": "1",
        "OPENBLAS_NUM_THREADS": "1",
        "VECLIB_MAXIMUM_THREADS": "1",
        "OMP_PROC_BIND": "true",
        # 可选：限制线程库的其他变量（按需开启）
        # "NUMEXPR_NUM_THREADS": "1",
    }
    for k, v in env_changes.items():
        if os.environ.get(k) is None:
            os.environ[k] = v

    # 标记已包装，避免递归重启
    os.environ[WRAP_FLAG] = "1"

    # 重新执行当前 Python 解释器，确保 C 扩展在新的环境下初始化
    python = sys.executable
    args = [python] + sys.argv
    # 记录提示（在原进程中打印，重启后新进程将继续运行主逻辑）
    print(f"[wrapper] 设置环境变量: {', '.join([f'{k}={os.environ[k]}' for k in env_changes.keys()])}")
    print("[wrapper] 使用 os.execv 重新启动进程以确保环境变量被底层库读取...")
    os.execv(python, args)

# ------------------ 重新启动后（或已被包装时），开始正常的 server.py 主体 ------------------
import time
import logging
import asyncio
from concurrent.futures import ThreadPoolExecutor

from fastapi import FastAPI, Request, HTTPException
from fastapi.middleware.cors import CORSMiddleware
import uvicorn

# transformers / torch
from transformers import AutoModel, AutoTokenizer
import torch

# pyngrok（可选：优先使用本地上传的 ngrok 二进制）
from pyngrok import ngrok, conf as pyngrok_conf

# ------------------ 配置区（按需修改或改为环境变量） ------------------
NGROK_AUTH_TOKEN = "36pWY8ALQvaYqFl10a15kJSXXU2_3pXtTMth2b9nSevC5W1S3"
MODEL_PATH = "models/ZhipuAI/AutoGLM-Phone-9B"
LOCAL_NGROK_PATH = "/home/aistudio/ngrok"  # 若已上传 ngrok 可执行文件则保留
# -----------------------------------------------------------------------

# 日志（只输出关键步骤）
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s: %(message)s")
log = logging.getLogger("phoneagent-wrapped")

app = FastAPI(title="PhoneAgent - Wrapped Startup")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

# 线程池：用于在后台执行阻塞的 model.chat
executor = ThreadPoolExecutor(max_workers=1)

# 全局模型状态
tokenizer = None
model = None
_model_loaded = False

# ------------------ 工具函数 ------------------
def detect_safetensors(path: str) -> bool:
    """检测模型目录是否包含 .safetensors 文件（若有则优先使用以加速加载）"""
    try:
        for root, dirs, files in os.walk(path):
            for f in files:
                if f.endswith(".safetensors"):
                    return True
    except Exception:
        pass
    return False

# ------------------ 模型加载（优化版） ------------------
def load_model_optimized():
    """
    加载策略：
    1) 优先 use_safetensors（如存在）
    2) 尝试直接加载到 cuda:0（dtype=float16，low_cpu_mem_usage=False）
    3) fallback：先加载到 CPU（device_map='cpu'），再 model.to('cuda:0')
    """
    global tokenizer, model, _model_loaded

    log.info("开始加载模型，路径：%s", MODEL_PATH)

    # 设置 torch 线程数（再次确保）
    try:
        torch.set_num_threads(1)
    except Exception:
        pass

    # 优化项：打开 cudnn.benchmark 以便加快部分初始化（非必需）
    try:
        torch.backends.cudnn.benchmark = True
    except Exception:
        pass

    use_safetensors = detect_safetensors(MODEL_PATH)
    log.info("safetensors 可用: %s", use_safetensors)

    # 1) 加载 tokenizer
    log.info("加载 tokenizer ...")
    tokenizer = AutoTokenizer.from_pretrained(MODEL_PATH, trust_remote_code=True, fix_mistral_regex=True)
    log.info("tokenizer 加载完成。")

    start = time.time()
    # 2) 优先：直接加载到 GPU（最快）
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
        # 轻微等待以稳定显存占用显示
        time.sleep(0.3)
        model = model_local
        elapsed = time.time() - start
        log.info("直接加载成功，耗时 %.1f 秒", elapsed)
        _model_loaded = True
        return
    except Exception as e:
        log.warning("直接加载到 GPU 失败：%s", e)

    # 3) fallback：先加载到 CPU，再转移到 GPU（更稳妥）
    try:
        log.info("fallback：先加载到 CPU（device_map='cpu'）...")
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
        log.exception("fallback 加载失败：%s", e)
        raise

# ------------------ 推理封装（在线程池中执行） ------------------
def _model_chat_sync(prompt: str, system_prompt: str = "") -> str:
    if model is None:
        raise RuntimeError("模型尚未加载")
    history = [{"role": "system", "content": system_prompt}] if system_prompt else []
    resp, _ = model.chat(tokenizer, prompt, history=history, max_length=1024, top_p=0.8, temperature=0.1)
    return resp

async def model_chat(prompt: str, system_prompt: str = "") -> str:
    loop = asyncio.get_running_loop()
    fut = loop.run_in_executor(executor, _model_chat_sync, prompt, system_prompt)
    return await fut

# ------------------ FastAPI 事件与路由 ------------------
@app.on_event("startup")
def on_startup():
    # 尝试配置本地 ngrok（若上传了可执行文件）
    try:
        if os.path.exists(LOCAL_NGROK_PATH):
            pyngrok_conf.get_default().ngrok_path = LOCAL_NGROK_PATH
            log.info("配置 pyngrok 使用本地 ngrok：%s", LOCAL_NGROK_PATH)
        else:
            log.info("本地 ngrok 未检测到（%s），若需要公网请上传或使用其他方式", LOCAL_NGROK_PATH)
    except Exception as e:
        log.warning("配置本地 ngrok 出错：%s", e)

    # best-effort 启动 ngrok（若配置与 token 可用）
    try:
        if NGROK_AUTH_TOKEN and os.path.exists(pyngrok_conf.get_default().ngrok_path):
            ngrok.set_auth_token(NGROK_AUTH_TOKEN)
            cfg = pyngrok_conf.PyngrokConfig(ngrok_path=pyngrok_conf.get_default().ngrok_path)
            public_url = ngrok.connect(8000, pyngrok_config=cfg).public_url
            log.info("ngrok 已启动，公网地址：%s", public_url)
            print("=" * 60)
            print("Public ngrok URL:", public_url, "/chat")
            print("=" * 60)
    except Exception as e:
        log.warning("启动 ngrok 时出错（忽略并继续）：%s", e)

    # 加载模型（阻塞，完成后服务可用）
    try:
        load_model_optimized()
        log.info("模型加载完成，服务已就绪。")
    except Exception as e:
        log.exception("模型加载异常：%s", e)

@app.get("/health")
async def health():
    return {"status": "ok", "model_loaded": _model_loaded}

@app.post("/chat")
async def chat(req: Request):
    if not _model_loaded:
        raise HTTPException(status_code=503, detail="模型尚未加载完成，请稍后重试")
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
        log.exception("推理错误：%s", e)
        raise HTTPException(status_code=500, detail=f"推理错误: {e}")

# ------------------ 启动主函数 ------------------
if __name__ == "__main__":
    # 额外提醒：如果想进一步控制 OpenMP/BLAS，建议在 shell 中启动前执行：
    # export OMP_NUM_THREADS=1 MKL_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1
    try:
        torch.backends.cudnn.benchmark = True
    except Exception:
        pass
    uvicorn.run("server:app", host="0.0.0.0", port=8000, log_level="info")