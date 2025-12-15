package com.autoglm.phonedemo;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * ApiClient - 网络请求客户端
 * 
 * 功能说明：
 * 用于与 AIstudio 部署的 server.py 服务进行通信
 * 
 * 接口对应关系（参考仓库中的 server.py）：
 * 1. GET /health - 健康检查接口，用于测试服务器连接
 * 2. POST /chat - 聊天接口，发送用户指令，获取操作响应
 * 
 * 请求格式（POST /chat）：
 * {
 *     "prompt": "用户的语音指令",
 *     "system": "可选的系统提示词"
 * }
 * 
 * 响应格式：
 * {
 *     "action": "AI 生成的操作指令"
 * }
 * 
 * 使用示例：
 * ```java
 * ApiClient client = new ApiClient();
 * client.setBaseUrl("http://your-aistudio-server:8000");
 * client.sendChat("打开微信", new ApiCallback<String>() {
 *     @Override
 *     public void onSuccess(String result) {
 *         // 处理操作指令
 *     }
 *     @Override
 *     public void onError(String error) {
 *         // 处理错误
 *     }
 * });
 * ```
 */
public class ApiClient {
    
    // 日志标签
    private static final String TAG = "AutoGLM_ApiClient";
    
    // JSON 媒体类型
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    
    // 服务器基础地址
    private String baseUrl = "";
    
    // HTTP 客户端
    private OkHttpClient httpClient;
    
    // JSON 解析器
    private Gson gson;
    
    /**
     * 构造函数
     * 
     * 初始化 HTTP 客户端，配置超时时间
     * 超时设置说明：
     * - connectTimeout: 连接超时 10 秒
     * - readTimeout: 读取超时 60 秒（AI 推理可能需要较长时间）
     * - writeTimeout: 写入超时 30 秒
     */
    public ApiClient() {
        httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)   // 连接超时
            .readTimeout(60, TimeUnit.SECONDS)      // 读取超时（AI 推理需要时间）
            .writeTimeout(30, TimeUnit.SECONDS)     // 写入超时
            .build();
        
        gson = new Gson();
    }
    
    /**
     * 设置服务器基础地址
     * 
     * @param baseUrl 服务器地址，如 "http://xxx:8000"
     */
    public void setBaseUrl(String baseUrl) {
        // 移除末尾的斜杠
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        this.baseUrl = baseUrl;
        Log.d(TAG, "设置服务器地址: " + this.baseUrl);
    }
    
    /**
     * 健康检查 - 测试服务器连接
     * 
     * 对应 server.py 中的 GET /health 接口
     * 响应格式：{"status":"ok","model_loaded": true}
     * 
     * @param callback 回调接口，成功返回 true，失败返回错误信息
     */
    public void checkHealth(ApiCallback<Boolean> callback) {
        String url = baseUrl + "/health";
        Log.d(TAG, "健康检查: " + url);
        
        Request request = new Request.Builder()
            .url(url)
            .get()
            .build();
        
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "健康检查失败", e);
                callback.onError("网络错误: " + e.getMessage());
            }
            
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (ResponseBody body = response.body()) {
                    if (response.isSuccessful() && body != null) {
                        String jsonStr = body.string();
                        Log.d(TAG, "健康检查响应: " + jsonStr);
                        
                        JsonObject json = gson.fromJson(jsonStr, JsonObject.class);
                        String status = json.has("status") ? 
                            json.get("status").getAsString() : "";
                        boolean modelLoaded = json.has("model_loaded") && 
                            json.get("model_loaded").getAsBoolean();
                        
                        if ("ok".equals(status) && modelLoaded) {
                            callback.onSuccess(true);
                        } else if ("ok".equals(status)) {
                            callback.onError("模型尚未加载完成");
                        } else {
                            callback.onError("服务器状态异常");
                        }
                    } else {
                        callback.onError("HTTP 错误: " + response.code());
                    }
                } catch (Exception e) {
                    Log.e(TAG, "解析响应失败", e);
                    callback.onError("解析响应失败: " + e.getMessage());
                }
            }
        });
    }
    
    /**
     * 发送聊天消息 - 获取 AI 操作指令
     * 
     * 对应 server.py 中的 POST /chat 接口
     * 
     * 请求格式：
     * {
     *     "prompt": "用户输入的指令",
     *     "system": "你是一个手机自动化助手。请输出具体的操作指令。"
     * }
     * 
     * 响应格式：
     * {
     *     "action": "do(action=\"Launch\", app=\"微信\")"
     * }
     * 
     * @param prompt 用户的语音指令
     * @param callback 回调接口，成功返回操作指令字符串
     */
    public void sendChat(String prompt, ApiCallback<String> callback) {
        String url = baseUrl + "/chat";
        Log.d(TAG, "发送聊天请求: " + prompt);
        
        // 构建请求体
        JsonObject requestJson = new JsonObject();
        requestJson.addProperty("prompt", prompt);
        requestJson.addProperty("system", "你是一个手机自动化助手。请输出具体的操作指令。");
        
        String requestBody = gson.toJson(requestJson);
        Log.d(TAG, "请求体: " + requestBody);
        
        Request request = new Request.Builder()
            .url(url)
            .post(RequestBody.create(requestBody, JSON))
            .build();
        
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "聊天请求失败", e);
                callback.onError("网络错误: " + e.getMessage());
            }
            
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (ResponseBody body = response.body()) {
                    if (response.isSuccessful() && body != null) {
                        String jsonStr = body.string();
                        Log.d(TAG, "聊天响应: " + jsonStr);
                        
                        JsonObject json = gson.fromJson(jsonStr, JsonObject.class);
                        String action = json.has("action") ? 
                            json.get("action").getAsString() : "";
                        
                        if (!action.isEmpty()) {
                            callback.onSuccess(action);
                        } else {
                            callback.onError("未获取到操作指令");
                        }
                    } else {
                        String errorMsg = "HTTP 错误: " + response.code();
                        if (body != null) {
                            try {
                                String errorBody = body.string();
                                JsonObject errorJson = gson.fromJson(errorBody, JsonObject.class);
                                if (errorJson.has("detail")) {
                                    errorMsg = errorJson.get("detail").getAsString();
                                }
                            } catch (Exception ignored) {}
                        }
                        callback.onError(errorMsg);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "解析响应失败", e);
                    callback.onError("解析响应失败: " + e.getMessage());
                }
            }
        });
    }
    
    /**
     * API 回调接口
     * 
     * 用于异步返回 API 请求结果
     * 
     * @param <T> 成功时返回的数据类型
     */
    public interface ApiCallback<T> {
        /**
         * 请求成功回调
         * 
         * @param result 返回的数据
         */
        void onSuccess(T result);
        
        /**
         * 请求失败回调
         * 
         * @param error 错误信息
         */
        void onError(String error);
    }
}
