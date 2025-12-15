package com.autoglm.phonedemo;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * ChatMessage - 对话消息数据类
 * 
 * 用于存储对话历史中的单条消息
 * 包含消息内容、发送者类型、时间戳等信息
 */
public class ChatMessage {
    
    // 消息内容
    private String content;
    
    // 是否为用户消息（false 表示 AI 消息）
    private boolean isUserMessage;
    
    // 消息时间戳（毫秒）
    private long timestamp;
    
    /**
     * 构造函数
     * 
     * @param content 消息内容
     * @param isUserMessage 是否为用户消息
     * @param timestamp 时间戳（毫秒）
     */
    public ChatMessage(String content, boolean isUserMessage, long timestamp) {
        this.content = content;
        this.isUserMessage = isUserMessage;
        this.timestamp = timestamp;
    }
    
    /**
     * 获取消息内容
     */
    public String getContent() {
        return content;
    }
    
    /**
     * 设置消息内容
     */
    public void setContent(String content) {
        this.content = content;
    }
    
    /**
     * 是否为用户消息
     */
    public boolean isUserMessage() {
        return isUserMessage;
    }
    
    /**
     * 设置是否为用户消息
     */
    public void setUserMessage(boolean userMessage) {
        isUserMessage = userMessage;
    }
    
    /**
     * 获取时间戳
     */
    public long getTimestamp() {
        return timestamp;
    }
    
    /**
     * 设置时间戳
     */
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
    
    /**
     * 获取格式化的时间字符串
     * 
     * @return 格式化的时间，如 "10:30"
     */
    public String getFormattedTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }
    
    /**
     * 获取发送者名称
     * 
     * @return "我" 或 "AI"
     */
    public String getSenderName() {
        return isUserMessage ? "我" : "AI";
    }
}
