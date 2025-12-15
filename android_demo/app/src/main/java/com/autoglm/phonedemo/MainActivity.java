package com.autoglm.phonedemo;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.autoglm.phonedemo.databinding.ActivityMainBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MainActivity - 主界面 Activity
 * 
 * 功能说明：
 * 1. 提供服务器连接配置界面
 * 2. 提供语音输入按钮，长按录音
 * 3. 显示对话历史列表
 * 4. 将语音识别结果发送到 AIstudio 服务器
 * 5. 接收服务器返回的操作指令并执行
 * 
 * 使用流程：
 * 1. 用户输入 AIstudio 部署的 server.py 地址
 * 2. 点击"连接服务器"按钮建立连接
 * 3. 长按语音按钮说话
 * 4. 语音识别结果发送到服务器
 * 5. 服务器返回操作指令
 * 6. 通过无障碍服务执行操作
 * 
 * 权限说明：
 * - RECORD_AUDIO: 语音录制
 * - INTERNET: 网络通信
 */
public class MainActivity extends AppCompatActivity {
    
    // 日志标签
    private static final String TAG = "AutoGLM_MainActivity";
    
    // 权限请求码
    private static final int REQUEST_AUDIO_PERMISSION = 100;
    
    // ViewBinding 对象，用于访问布局中的视图
    private ActivityMainBinding binding;
    
    // 网络请求客户端
    private ApiClient apiClient;
    
    // 对话历史适配器
    private ChatAdapter chatAdapter;
    
    // 对话历史数据
    private List<ChatMessage> chatHistory;
    
    // 主线程 Handler，用于更新 UI
    private Handler mainHandler;
    
    // 连接状态
    private boolean isConnected = false;
    
    // 语音识别结果启动器
    private ActivityResultLauncher<Intent> speechRecognizerLauncher;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // ========================================
        // 初始化 ViewBinding
        // ========================================
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        
        // 初始化 Handler
        mainHandler = new Handler(Looper.getMainLooper());
        
        // ========================================
        // 初始化组件
        // ========================================
        initApiClient();
        initChatHistory();
        initSpeechRecognizer();
        initViews();
        
        // ========================================
        // 检查并请求权限
        // ========================================
        checkAndRequestPermissions();
        
        // ========================================
        // 检查无障碍服务状态
        // ========================================
        checkAccessibilityService();
    }
    
    /**
     * 初始化 API 客户端
     * 
     * 创建用于与 AIstudio 服务器通信的 HTTP 客户端
     */
    private void initApiClient() {
        apiClient = new ApiClient();
    }
    
    /**
     * 初始化对话历史列表
     * 
     * 设置 RecyclerView 适配器和布局管理器
     */
    private void initChatHistory() {
        chatHistory = new ArrayList<>();
        chatAdapter = new ChatAdapter(chatHistory);
        
        // 配置 RecyclerView
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true); // 新消息从底部显示
        binding.rvChatHistory.setLayoutManager(layoutManager);
        binding.rvChatHistory.setAdapter(chatAdapter);
    }
    
    /**
     * 初始化语音识别
     * 
     * 注册语音识别结果回调
     */
    private void initSpeechRecognizer() {
        speechRecognizerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    // 获取语音识别结果
                    ArrayList<String> matches = result.getData()
                        .getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                    
                    if (matches != null && !matches.isEmpty()) {
                        String recognizedText = matches.get(0);
                        Log.d(TAG, "语音识别结果: " + recognizedText);
                        
                        // 处理识别结果
                        onSpeechRecognized(recognizedText);
                    }
                }
                
                // 恢复按钮状态
                updateSpeakButtonState(false);
            }
        );
    }
    
    /**
     * 初始化视图和事件监听
     */
    private void initViews() {
        // ========================================
        // 连接按钮点击事件
        // ========================================
        binding.btnConnect.setOnClickListener(v -> {
            if (isConnected) {
                // 断开连接
                disconnectServer();
            } else {
                // 连接服务器
                connectServer();
            }
        });
        
        // ========================================
        // 语音按钮触摸事件
        // ========================================
        binding.btnSpeak.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    // 按下开始录音
                    startSpeechRecognition();
                    return true;
                    
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    // 松开或取消，系统语音识别会自动处理
                    // 结果在 speechRecognizerLauncher 回调中处理
                    return true;
            }
            return false;
        });
    }
    
    /**
     * 检查并请求必要权限
     */
    private void checkAndRequestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            // 请求录音权限
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO},
                REQUEST_AUDIO_PERMISSION);
        }
    }
    
    /**
     * 检查无障碍服务是否已启用
     * 
     * 无障碍服务用于执行自动化操作（点击、滑动等）
     */
    private void checkAccessibilityService() {
        if (!isAccessibilityServiceEnabled()) {
            // 显示提示对话框
            new AlertDialog.Builder(this)
                .setTitle("需要开启无障碍服务")
                .setMessage("为了执行手机自动化操作，请开启 AutoGLM 无障碍服务。")
                .setPositiveButton("去设置", (dialog, which) -> {
                    // 跳转到无障碍设置页面
                    Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    startActivity(intent);
                })
                .setNegativeButton("稍后", null)
                .show();
        }
    }
    
    /**
     * 检查无障碍服务是否已启用
     * 
     * @return true 如果服务已启用
     */
    private boolean isAccessibilityServiceEnabled() {
        String serviceName = getPackageName() + "/" + 
            AutomationAccessibilityService.class.getCanonicalName();
        
        String enabledServices = Settings.Secure.getString(
            getContentResolver(),
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        
        return enabledServices != null && enabledServices.contains(serviceName);
    }
    
    /**
     * 连接服务器
     * 
     * 获取用户输入的服务器地址，测试连接
     */
    private void connectServer() {
        String serverUrl = binding.etServerUrl.getText().toString().trim();
        
        if (serverUrl.isEmpty()) {
            Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 更新 UI 状态
        updateConnectionStatus(ConnectionStatus.CONNECTING);
        
        // 设置 API 客户端的服务器地址
        apiClient.setBaseUrl(serverUrl);
        
        // 测试连接（调用 /health 接口）
        apiClient.checkHealth(new ApiClient.ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                mainHandler.post(() -> {
                    isConnected = true;
                    updateConnectionStatus(ConnectionStatus.CONNECTED);
                    Toast.makeText(MainActivity.this, 
                        getString(R.string.toast_connected), Toast.LENGTH_SHORT).show();
                    
                    // 启用语音按钮
                    binding.btnSpeak.setEnabled(true);
                    binding.tvStatus.setText("准备就绪，长按按钮说话");
                });
            }
            
            @Override
            public void onError(String error) {
                mainHandler.post(() -> {
                    isConnected = false;
                    updateConnectionStatus(ConnectionStatus.ERROR);
                    Toast.makeText(MainActivity.this, 
                        "连接失败: " + error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }
    
    /**
     * 断开服务器连接
     */
    private void disconnectServer() {
        isConnected = false;
        updateConnectionStatus(ConnectionStatus.DISCONNECTED);
        binding.btnSpeak.setEnabled(false);
        binding.tvStatus.setText(getString(R.string.status_disconnected));
        Toast.makeText(this, getString(R.string.toast_disconnected), Toast.LENGTH_SHORT).show();
    }
    
    /**
     * 更新连接状态 UI
     * 
     * @param status 连接状态枚举
     */
    private void updateConnectionStatus(ConnectionStatus status) {
        int statusColor;
        String statusText;
        String buttonText;
        
        switch (status) {
            case CONNECTING:
                statusColor = ContextCompat.getColor(this, R.color.status_processing);
                statusText = getString(R.string.status_connecting);
                buttonText = getString(R.string.btn_connect);
                break;
            case CONNECTED:
                statusColor = ContextCompat.getColor(this, R.color.status_connected);
                statusText = getString(R.string.status_connected);
                buttonText = getString(R.string.btn_disconnect);
                break;
            case ERROR:
                statusColor = ContextCompat.getColor(this, R.color.status_disconnected);
                statusText = getString(R.string.status_error);
                buttonText = getString(R.string.btn_connect);
                break;
            default:
                statusColor = ContextCompat.getColor(this, R.color.status_disconnected);
                statusText = getString(R.string.status_disconnected);
                buttonText = getString(R.string.btn_connect);
                break;
        }
        
        // 更新状态指示器颜色
        GradientDrawable background = (GradientDrawable) binding.viewConnectionStatus.getBackground();
        background.setColor(statusColor);
        
        // 更新状态文字
        binding.tvConnectionStatus.setText(statusText);
        
        // 更新按钮文字
        binding.btnConnect.setText(buttonText);
    }
    
    /**
     * 开始语音识别
     * 
     * 使用系统自带的语音识别服务
     */
    private void startSpeechRecognition() {
        // 检查是否已连接服务器
        if (!isConnected) {
            Toast.makeText(this, "请先连接服务器", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 更新按钮状态
        updateSpeakButtonState(true);
        
        // 创建语音识别 Intent
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, 
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINESE.toString());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "请说出您想让手机执行的操作...");
        
        try {
            speechRecognizerLauncher.launch(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, getString(R.string.error_no_speech_service), 
                Toast.LENGTH_LONG).show();
            updateSpeakButtonState(false);
        }
    }
    
    /**
     * 更新语音按钮状态
     * 
     * @param isRecording 是否正在录音
     */
    private void updateSpeakButtonState(boolean isRecording) {
        if (isRecording) {
            binding.tvSpeakHint.setText(getString(R.string.btn_speaking));
        } else {
            binding.tvSpeakHint.setText(getString(R.string.btn_speak));
        }
    }
    
    /**
     * 处理语音识别结果
     * 
     * 将识别出的文字发送到服务器，获取操作指令
     * 
     * @param text 识别出的文字
     */
    private void onSpeechRecognized(String text) {
        // 添加用户消息到对话历史
        addChatMessage(text, true);
        
        // 更新状态
        binding.tvStatus.setText(getString(R.string.status_processing));
        
        // 发送到服务器
        apiClient.sendChat(text, new ApiClient.ApiCallback<String>() {
            @Override
            public void onSuccess(String actionResponse) {
                mainHandler.post(() -> {
                    // 添加 AI 回复到对话历史
                    addChatMessage(actionResponse, false);
                    
                    // 解析并执行操作
                    executeAction(actionResponse);
                    
                    // 恢复状态
                    binding.tvStatus.setText("准备就绪，长按按钮说话");
                });
            }
            
            @Override
            public void onError(String error) {
                mainHandler.post(() -> {
                    // 添加错误消息到对话历史
                    addChatMessage("错误: " + error, false);
                    
                    // 恢复状态
                    binding.tvStatus.setText("发生错误，请重试");
                });
            }
        });
    }
    
    /**
     * 添加消息到对话历史
     * 
     * @param message 消息内容
     * @param isUser true 表示用户消息，false 表示 AI 消息
     */
    private void addChatMessage(String message, boolean isUser) {
        ChatMessage chatMessage = new ChatMessage(
            message,
            isUser,
            System.currentTimeMillis()
        );
        
        chatHistory.add(chatMessage);
        chatAdapter.notifyItemInserted(chatHistory.size() - 1);
        
        // 滚动到最新消息
        binding.rvChatHistory.scrollToPosition(chatHistory.size() - 1);
    }
    
    /**
     * 执行服务器返回的操作指令
     * 
     * 解析 AI 返回的操作指令，通过无障碍服务执行
     * 
     * @param actionResponse 服务器返回的操作指令
     */
    private void executeAction(String actionResponse) {
        // 检查无障碍服务是否已启用
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, getString(R.string.toast_enable_accessibility), 
                Toast.LENGTH_LONG).show();
            return;
        }
        
        // 更新状态
        binding.tvStatus.setText(getString(R.string.status_executing));
        
        // 通过无障碍服务执行操作
        AutomationAccessibilityService service = AutomationAccessibilityService.getInstance();
        if (service != null) {
            // 解析操作指令并执行
            service.executeAction(actionResponse, new ActionExecutionCallback() {
                @Override
                public void onActionCompleted(boolean success, String message) {
                    mainHandler.post(() -> {
                        if (success) {
                            Toast.makeText(MainActivity.this, 
                                getString(R.string.toast_action_completed), Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this, 
                                "操作执行失败: " + message, Toast.LENGTH_LONG).show();
                        }
                        
                        // 恢复状态
                        binding.tvStatus.setText("准备就绪，长按按钮说话");
                    });
                }
            });
        } else {
            Toast.makeText(this, getString(R.string.error_accessibility_not_enabled), 
                Toast.LENGTH_LONG).show();
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, 
                                          @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == REQUEST_AUDIO_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "录音权限已授予");
            } else {
                Toast.makeText(this, getString(R.string.error_permission_denied), 
                    Toast.LENGTH_LONG).show();
            }
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }
    
    /**
     * 连接状态枚举
     */
    private enum ConnectionStatus {
        DISCONNECTED,   // 未连接
        CONNECTING,     // 连接中
        CONNECTED,      // 已连接
        ERROR           // 连接错误
    }
    
    /**
     * 操作执行回调接口
     */
    public interface ActionExecutionCallback {
        void onActionCompleted(boolean success, String message);
    }
}
