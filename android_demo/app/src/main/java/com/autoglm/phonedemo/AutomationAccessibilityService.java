package com.autoglm.phonedemo;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AutomationAccessibilityService - 无障碍自动化服务
 * 
 * 功能说明：
 * 这是 Android 无障碍服务的实现，用于在用户手机上执行自动化操作。
 * 通过解析 AI 返回的操作指令，执行相应的手机操作。
 * 
 * 支持的操作类型（对应 server.py 返回的指令格式）：
 * 1. Launch - 启动应用
 *    格式: do(action="Launch", app="微信")
 * 
 * 2. Tap - 点击指定坐标
 *    格式: do(action="Tap", element=[x, y])
 * 
 * 3. Swipe - 滑动操作
 *    格式: do(action="Swipe", element=[[x1,y1], [x2,y2]])
 * 
 * 4. Type - 输入文字
 *    格式: do(action="Type", text="要输入的文字")
 * 
 * 5. Back - 返回上一页
 *    格式: do(action="Back")
 * 
 * 6. Home - 返回桌面
 *    格式: do(action="Home")
 * 
 * 7. Wait - 等待
 *    格式: do(action="Wait", time=1000)
 * 
 * 使用前提：
 * 用户需要在 系统设置 -> 无障碍 -> AutoGLM自动化服务 中开启此服务
 * 
 * 安全提示：
 * 无障碍服务拥有很高的权限，请确保只在可信环境下使用
 */
public class AutomationAccessibilityService extends AccessibilityService {
    
    // 日志标签
    private static final String TAG = "AutoGLM_Accessibility";
    
    // 单例实例（用于从其他组件访问服务）
    private static AutomationAccessibilityService instance;
    
    // 主线程 Handler
    private Handler mainHandler;
    
    /**
     * 获取服务实例
     * 
     * @return 服务实例，如果服务未运行则返回 null
     */
    public static AutomationAccessibilityService getInstance() {
        return instance;
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "无障碍服务已创建");
        mainHandler = new Handler(Looper.getMainLooper());
    }
    
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.d(TAG, "无障碍服务已连接");
    }
    
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 监听无障碍事件（当前实现不需要处理）
        // 可以在这里监听屏幕变化、窗口切换等事件
    }
    
    @Override
    public void onInterrupt() {
        Log.d(TAG, "无障碍服务被中断");
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        Log.d(TAG, "无障碍服务已销毁");
    }
    
    /**
     * 执行操作指令
     * 
     * 解析 AI 返回的操作指令字符串，执行相应操作
     * 
     * 指令格式示例：
     * - do(action="Launch", app="微信")
     * - do(action="Tap", element=[500, 800])
     * - do(action="Swipe", element=[[500,1000], [500,500]])
     * - do(action="Type", text="Hello")
     * - do(action="Back")
     * - do(action="Home")
     * 
     * @param actionString AI 返回的操作指令字符串
     * @param callback 执行结果回调
     */
    public void executeAction(String actionString, MainActivity.ActionExecutionCallback callback) {
        Log.d(TAG, "执行操作: " + actionString);
        
        try {
            // 解析操作指令
            ActionCommand command = parseActionCommand(actionString);
            
            if (command == null) {
                callback.onActionCompleted(false, "无法解析操作指令");
                return;
            }
            
            // 根据操作类型执行相应操作
            switch (command.action.toLowerCase()) {
                case "launch":
                    executeLaunch(command.app, callback);
                    break;
                    
                case "tap":
                    executeTap(command.x, command.y, callback);
                    break;
                    
                case "swipe":
                    executeSwipe(command.x, command.y, command.endX, command.endY, callback);
                    break;
                    
                case "type":
                    executeType(command.text, callback);
                    break;
                    
                case "back":
                    executeBack(callback);
                    break;
                    
                case "home":
                    executeHome(callback);
                    break;
                    
                case "wait":
                    executeWait(command.waitTime, callback);
                    break;
                    
                case "long press":
                case "longpress":
                    executeLongPress(command.x, command.y, callback);
                    break;
                    
                case "double tap":
                case "doubletap":
                    executeDoubleTap(command.x, command.y, callback);
                    break;
                    
                default:
                    callback.onActionCompleted(false, "不支持的操作类型: " + command.action);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "执行操作失败", e);
            callback.onActionCompleted(false, "执行失败: " + e.getMessage());
        }
    }
    
    /**
     * 解析操作指令字符串
     * 
     * 将 AI 返回的指令字符串解析为 ActionCommand 对象
     * 
     * 支持的格式：
     * - do(action="Launch", app="微信")
     * - do(action="Tap", element=[500, 800])
     * 
     * @param actionString 操作指令字符串
     * @return 解析后的 ActionCommand 对象
     */
    private ActionCommand parseActionCommand(String actionString) {
        ActionCommand command = new ActionCommand();
        
        // 提取 action 参数
        Pattern actionPattern = Pattern.compile("action\\s*=\\s*[\"']([^\"']+)[\"']");
        Matcher actionMatcher = actionPattern.matcher(actionString);
        if (actionMatcher.find()) {
            command.action = actionMatcher.group(1);
        } else {
            return null;
        }
        
        // 提取 app 参数（用于 Launch 操作）
        Pattern appPattern = Pattern.compile("app\\s*=\\s*[\"']([^\"']+)[\"']");
        Matcher appMatcher = appPattern.matcher(actionString);
        if (appMatcher.find()) {
            command.app = appMatcher.group(1);
        }
        
        // 提取 text 参数（用于 Type 操作）
        Pattern textPattern = Pattern.compile("text\\s*=\\s*[\"']([^\"']+)[\"']");
        Matcher textMatcher = textPattern.matcher(actionString);
        if (textMatcher.find()) {
            command.text = textMatcher.group(1);
        }
        
        // 提取 element 参数（用于 Tap/Swipe 操作）
        // 单点格式: element=[500, 800]
        Pattern elementPattern = Pattern.compile("element\\s*=\\s*\\[(\\d+)\\s*,\\s*(\\d+)\\]");
        Matcher elementMatcher = elementPattern.matcher(actionString);
        if (elementMatcher.find()) {
            command.x = Integer.parseInt(elementMatcher.group(1));
            command.y = Integer.parseInt(elementMatcher.group(2));
        }
        
        // 滑动格式: element=[[500,1000], [500,500]]
        Pattern swipePattern = Pattern.compile(
            "element\\s*=\\s*\\[\\s*\\[(\\d+)\\s*,\\s*(\\d+)\\]\\s*,\\s*\\[(\\d+)\\s*,\\s*(\\d+)\\]\\s*\\]");
        Matcher swipeMatcher = swipePattern.matcher(actionString);
        if (swipeMatcher.find()) {
            command.x = Integer.parseInt(swipeMatcher.group(1));
            command.y = Integer.parseInt(swipeMatcher.group(2));
            command.endX = Integer.parseInt(swipeMatcher.group(3));
            command.endY = Integer.parseInt(swipeMatcher.group(4));
        }
        
        // 提取 time 参数（用于 Wait 操作）
        Pattern timePattern = Pattern.compile("time\\s*=\\s*(\\d+)");
        Matcher timeMatcher = timePattern.matcher(actionString);
        if (timeMatcher.find()) {
            command.waitTime = Integer.parseInt(timeMatcher.group(1));
        }
        
        Log.d(TAG, "解析结果: action=" + command.action + 
            ", app=" + command.app + 
            ", x=" + command.x + ", y=" + command.y +
            ", endX=" + command.endX + ", endY=" + command.endY +
            ", text=" + command.text +
            ", waitTime=" + command.waitTime);
        
        return command;
    }
    
    /**
     * 执行启动应用操作
     * 
     * 根据应用名称启动对应的 App
     * 
     * @param appName 应用名称（如"微信"）
     * @param callback 执行结果回调
     */
    private void executeLaunch(String appName, MainActivity.ActionExecutionCallback callback) {
        Log.d(TAG, "启动应用: " + appName);
        
        // 获取应用包名（可以扩展为从配置文件读取）
        String packageName = getPackageNameForApp(appName);
        
        if (packageName == null) {
            callback.onActionCompleted(false, "未找到应用: " + appName);
            return;
        }
        
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                callback.onActionCompleted(true, "已启动 " + appName);
            } else {
                callback.onActionCompleted(false, "无法启动应用: " + appName);
            }
        } catch (Exception e) {
            Log.e(TAG, "启动应用失败", e);
            callback.onActionCompleted(false, "启动失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取应用包名
     * 
     * 根据应用名称返回对应的包名
     * 可以扩展为从配置文件或服务器获取
     * 
     * @param appName 应用名称
     * @return 应用包名，未找到返回 null
     */
    private String getPackageNameForApp(String appName) {
        // 常用应用包名映射
        // 参考 Open-AutoGLM 项目中的 phone_agent/config/apps.py
        switch (appName) {
            // 社交通讯
            case "微信": return "com.tencent.mm";
            case "QQ": return "com.tencent.mobileqq";
            case "微博": return "com.sina.weibo";
            
            // 电商购物
            case "淘宝": return "com.taobao.taobao";
            case "京东": return "com.jingdong.app.mall";
            case "拼多多": return "com.xunmeng.pinduoduo";
            
            // 美食外卖
            case "美团": return "com.sankuai.meituan";
            case "饿了么": return "me.ele";
            
            // 出行旅游
            case "携程": return "ctrip.android.view";
            case "12306": return "com.MobileTicket";
            case "滴滴出行": return "com.sdu.didi.psnger";
            
            // 视频娱乐
            case "bilibili":
            case "哔哩哔哩":
            case "B站": return "tv.danmaku.bili";
            case "抖音": return "com.ss.android.ugc.aweme";
            case "爱奇艺": return "com.qiyi.video";
            
            // 音乐音频
            case "网易云音乐": return "com.netease.cloudmusic";
            case "QQ音乐": return "com.tencent.qqmusic";
            case "喜马拉雅": return "com.ximalaya.ting.android";
            
            // 生活服务
            case "大众点评": return "com.dianping.v1";
            case "高德地图": return "com.autonavi.minimap";
            case "百度地图": return "com.baidu.BaiduMap";
            
            // 内容社区
            case "小红书": return "com.xingin.xhs";
            case "知乎": return "com.zhihu.android";
            case "豆瓣": return "com.douban.frodo";
            
            // 浏览器
            case "Chrome":
            case "谷歌浏览器": return "com.android.chrome";
            case "浏览器": return "com.android.browser";
            
            // 系统应用
            case "设置": return "com.android.settings";
            case "电话": return "com.android.dialer";
            case "短信": return "com.android.mms";
            case "相机": return "com.android.camera";
            case "相册": return "com.android.gallery3d";
            
            default:
                Log.w(TAG, "未配置的应用: " + appName);
                return null;
        }
    }
    
    /**
     * 执行点击操作
     * 
     * 使用无障碍服务的手势 API 在指定坐标执行点击
     * 
     * @param x X 坐标
     * @param y Y 坐标
     * @param callback 执行结果回调
     */
    private void executeTap(int x, int y, MainActivity.ActionExecutionCallback callback) {
        Log.d(TAG, "点击坐标: (" + x + ", " + y + ")");
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // 创建点击手势
            Path path = new Path();
            path.moveTo(x, y);
            
            GestureDescription.StrokeDescription stroke = 
                new GestureDescription.StrokeDescription(path, 0, 100);
            
            GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();
            
            // 执行手势
            dispatchGesture(gesture, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    super.onCompleted(gestureDescription);
                    mainHandler.post(() -> 
                        callback.onActionCompleted(true, "点击完成"));
                }
                
                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    super.onCancelled(gestureDescription);
                    mainHandler.post(() -> 
                        callback.onActionCompleted(false, "点击被取消"));
                }
            }, null);
        } else {
            callback.onActionCompleted(false, "系统版本过低，不支持手势操作");
        }
    }
    
    /**
     * 执行滑动操作
     * 
     * 从起点滑动到终点
     * 
     * @param startX 起点 X
     * @param startY 起点 Y
     * @param endX 终点 X
     * @param endY 终点 Y
     * @param callback 执行结果回调
     */
    private void executeSwipe(int startX, int startY, int endX, int endY, 
                              MainActivity.ActionExecutionCallback callback) {
        Log.d(TAG, "滑动: (" + startX + ", " + startY + ") -> (" + endX + ", " + endY + ")");
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // 创建滑动手势
            Path path = new Path();
            path.moveTo(startX, startY);
            path.lineTo(endX, endY);
            
            // 滑动持续时间 300ms
            GestureDescription.StrokeDescription stroke = 
                new GestureDescription.StrokeDescription(path, 0, 300);
            
            GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();
            
            // 执行手势
            dispatchGesture(gesture, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    super.onCompleted(gestureDescription);
                    mainHandler.post(() -> 
                        callback.onActionCompleted(true, "滑动完成"));
                }
                
                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    super.onCancelled(gestureDescription);
                    mainHandler.post(() -> 
                        callback.onActionCompleted(false, "滑动被取消"));
                }
            }, null);
        } else {
            callback.onActionCompleted(false, "系统版本过低，不支持手势操作");
        }
    }
    
    /**
     * 执行长按操作
     * 
     * @param x X 坐标
     * @param y Y 坐标
     * @param callback 执行结果回调
     */
    private void executeLongPress(int x, int y, MainActivity.ActionExecutionCallback callback) {
        Log.d(TAG, "长按: (" + x + ", " + y + ")");
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Path path = new Path();
            path.moveTo(x, y);
            
            // 长按持续时间 1000ms
            GestureDescription.StrokeDescription stroke = 
                new GestureDescription.StrokeDescription(path, 0, 1000);
            
            GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();
            
            dispatchGesture(gesture, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    super.onCompleted(gestureDescription);
                    mainHandler.post(() -> 
                        callback.onActionCompleted(true, "长按完成"));
                }
                
                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    super.onCancelled(gestureDescription);
                    mainHandler.post(() -> 
                        callback.onActionCompleted(false, "长按被取消"));
                }
            }, null);
        } else {
            callback.onActionCompleted(false, "系统版本过低，不支持手势操作");
        }
    }
    
    /**
     * 执行双击操作
     * 
     * @param x X 坐标
     * @param y Y 坐标
     * @param callback 执行结果回调
     */
    private void executeDoubleTap(int x, int y, MainActivity.ActionExecutionCallback callback) {
        Log.d(TAG, "双击: (" + x + ", " + y + ")");
        
        // 先执行第一次点击
        executeTap(x, y, new MainActivity.ActionExecutionCallback() {
            @Override
            public void onActionCompleted(boolean success, String message) {
                if (success) {
                    // 延迟 100ms 后执行第二次点击
                    mainHandler.postDelayed(() -> {
                        executeTap(x, y, callback);
                    }, 100);
                } else {
                    callback.onActionCompleted(false, "双击第一次点击失败");
                }
            }
        });
    }
    
    /**
     * 执行文字输入操作
     * 
     * ⚠️ 重要限制说明：
     * Android 无障碍服务本身不支持直接向文本框输入文字。
     * 本方法采用剪贴板方案：将文字复制到剪贴板，用户需要手动粘贴。
     * 
     * 完整的自动化文字输入解决方案：
     * 1. 使用 ADB Keyboard（推荐）- Open-AutoGLM 主项目采用的方案
     * 2. 通过 ADB 命令输入：adb shell input text "内容"
     * 3. 使用 Android 输入法框架（需要开发自定义输入法）
     * 
     * 本 Demo 为最小化实现，完整功能请参考 Open-AutoGLM 主项目
     * 
     * @param text 要输入的文字
     * @param callback 执行结果回调
     */
    private void executeType(String text, MainActivity.ActionExecutionCallback callback) {
        Log.d(TAG, "输入文字: " + text);
        
        // 通过剪贴板方式（Demo 简化实现）
        // 将文字复制到剪贴板，用户可以手动长按粘贴
        android.content.ClipboardManager clipboard = 
            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText("text", text);
        clipboard.setPrimaryClip(clip);
        
        // 返回成功，但提示用户需要手动粘贴
        // 生产环境建议：
        // 1. 集成 ADB Keyboard 自动输入
        // 2. 或通过 ADB shell 命令执行 input text
        callback.onActionCompleted(true, "文字「" + text + "」已复制到剪贴板，请长按输入框粘贴");
    }
    
    /**
     * 执行返回操作
     * 
     * @param callback 执行结果回调
     */
    private void executeBack(MainActivity.ActionExecutionCallback callback) {
        Log.d(TAG, "执行返回");
        
        boolean result = performGlobalAction(GLOBAL_ACTION_BACK);
        callback.onActionCompleted(result, result ? "返回成功" : "返回失败");
    }
    
    /**
     * 执行返回桌面操作
     * 
     * @param callback 执行结果回调
     */
    private void executeHome(MainActivity.ActionExecutionCallback callback) {
        Log.d(TAG, "返回桌面");
        
        boolean result = performGlobalAction(GLOBAL_ACTION_HOME);
        callback.onActionCompleted(result, result ? "已返回桌面" : "返回桌面失败");
    }
    
    /**
     * 执行等待操作
     * 
     * @param milliseconds 等待时间（毫秒）
     * @param callback 执行结果回调
     */
    private void executeWait(int milliseconds, MainActivity.ActionExecutionCallback callback) {
        Log.d(TAG, "等待 " + milliseconds + "ms");
        
        mainHandler.postDelayed(() -> {
            callback.onActionCompleted(true, "等待完成");
        }, milliseconds);
    }
    
    /**
     * 操作指令数据类
     * 
     * 用于存储解析后的操作参数
     */
    private static class ActionCommand {
        String action = "";     // 操作类型
        String app = "";        // 应用名称（Launch 操作）
        String text = "";       // 输入文字（Type 操作）
        int x = 0;              // X 坐标
        int y = 0;              // Y 坐标
        int endX = 0;           // 终点 X（Swipe 操作）
        int endY = 0;           // 终点 Y（Swipe 操作）
        int waitTime = 1000;    // 等待时间（Wait 操作）
    }
}
