package com.autoglm.phonedemo;

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.autoglm.phonedemo.databinding.ItemChatMessageBinding;

import java.util.List;

/**
 * ChatAdapter - 对话历史列表适配器
 * 
 * 用于在 RecyclerView 中显示对话消息列表
 * 根据消息类型（用户/AI）显示不同的样式
 * 
 * 功能说明：
 * - 用户消息靠右显示，蓝色背景
 * - AI 消息靠左显示，灰色背景
 * - 显示发送者名称和时间戳
 */
public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ChatViewHolder> {
    
    // 对话历史数据
    private List<ChatMessage> messages;
    
    /**
     * 构造函数
     * 
     * @param messages 对话消息列表
     */
    public ChatAdapter(List<ChatMessage> messages) {
        this.messages = messages;
    }
    
    @NonNull
    @Override
    public ChatViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // 使用 ViewBinding 加载布局
        ItemChatMessageBinding binding = ItemChatMessageBinding.inflate(
            LayoutInflater.from(parent.getContext()), 
            parent, 
            false
        );
        return new ChatViewHolder(binding);
    }
    
    @Override
    public void onBindViewHolder(@NonNull ChatViewHolder holder, int position) {
        ChatMessage message = messages.get(position);
        holder.bind(message);
    }
    
    @Override
    public int getItemCount() {
        return messages.size();
    }
    
    /**
     * ViewHolder - 单条消息的视图持有者
     */
    static class ChatViewHolder extends RecyclerView.ViewHolder {
        
        private ItemChatMessageBinding binding;
        
        public ChatViewHolder(ItemChatMessageBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
        
        /**
         * 绑定消息数据到视图
         * 
         * @param message 消息对象
         */
        public void bind(ChatMessage message) {
            // 设置消息内容
            binding.tvMessage.setText(message.getContent());
            
            // 设置发送者名称
            binding.tvSender.setText(message.getSenderName());
            
            // 设置时间戳
            binding.tvTimestamp.setText(message.getFormattedTime());
            
            // 根据消息类型设置样式
            if (message.isUserMessage()) {
                // 用户消息 - 靠右显示，蓝色背景
                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) 
                    binding.bubbleContainer.getLayoutParams();
                params.gravity = Gravity.END;
                binding.bubbleContainer.setLayoutParams(params);
                
                binding.bubbleContainer.setBackgroundResource(R.drawable.bg_bubble_user);
                binding.tvSender.setTextColor(
                    ContextCompat.getColor(itemView.getContext(), R.color.primary));
            } else {
                // AI 消息 - 靠左显示，灰色背景
                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) 
                    binding.bubbleContainer.getLayoutParams();
                params.gravity = Gravity.START;
                binding.bubbleContainer.setLayoutParams(params);
                
                binding.bubbleContainer.setBackgroundResource(R.drawable.bg_bubble_assistant);
                binding.tvSender.setTextColor(
                    ContextCompat.getColor(itemView.getContext(), R.color.status_connected));
            }
        }
    }
}
