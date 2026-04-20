package com.yizhaoqi.smartpai.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import com.yizhaoqi.smartpai.utils.LogUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users/conversation")
public class ConversationController {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 查询对话历史，从Redis中获取
     */
    @GetMapping
    public ResponseEntity<?> getConversations(
            @RequestHeader("Authorization") String token,
            @RequestParam(required = false) String start_date,
            @RequestParam(required = false) String end_date) {

        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GET_CONVERSATIONS");
        String username = null;
        try {
            username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
            if (username == null || username.isEmpty()) {
                monitor.end("获取对话历史失败：无效token");
                throw new CustomException("无效的token", HttpStatus.UNAUTHORIZED);
            }

            LogUtils.logBusiness("GET_CONVERSATIONS", username, "开始查询用户对话历史");

            // 从JWT直接拿 userId，和 WebSocket 存的时候保持一致
            String userId = jwtUtils.extractUserIdFromToken(token.replace("Bearer ", ""));
            if (userId == null || userId.isBlank()) {
                // 兜底：从数据库查
                User user = userRepository.findByUsername(username)
                        .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));
                userId = user.getId().toString();
            }

            String key = "user:" + userId + ":current_conversation";
            String conversationId = redisTemplate.opsForValue().get(key);

            if (conversationId == null) {
                LogUtils.logBusiness("GET_CONVERSATIONS", username, "没有找到对话历史，userId: %s", userId);
                Map<String, Object> response = new HashMap<>();
                response.put("code", 200);
                response.put("message", "获取对话历史成功");
                response.put("data", new ArrayList<>());
                monitor.end("获取对话历史成功（空结果）");
                return ResponseEntity.ok().body(response);
            }

            LogUtils.logBusiness("GET_CONVERSATIONS", username, "找到对话会话ID: %s", conversationId);
            return getConversationsFromRedis(conversationId, username, start_date, end_date, monitor);

        } catch (CustomException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_CONVERSATIONS", username, "获取对话历史异常: %s", e, e.getMessage());
            monitor.end("获取对话历史异常: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("code", 500, "message", "服务器内部错误: " + e.getMessage()));
        }
    }

    /**
     * 从Redis获取对话历史（存的是List，用List读）
     */
    private ResponseEntity<?> getConversationsFromRedis(String conversationId, String username, String start_date, String end_date, LogUtils.PerformanceMonitor monitor) {
        String key = "conversation:" + conversationId;
        List<String> jsonMessages = redisTemplate.opsForList().range(key, 0, -1);

        List<Map<String, Object>> formattedConversations = new ArrayList<>();
        if (jsonMessages != null && !jsonMessages.isEmpty()) {
            // 解析时间范围
            LocalDateTime startDateTime = null;
            LocalDateTime endDateTime = null;

            if (start_date != null && !start_date.trim().isEmpty()) {
                try {
                    startDateTime = parseDateTime(start_date);
                } catch (Exception e) {
                    throw new CustomException("起始时间格式错误: " + start_date, HttpStatus.BAD_REQUEST);
                }
            }

            if (end_date != null && !end_date.trim().isEmpty()) {
                try {
                    endDateTime = parseDateTime(end_date);
                } catch (Exception e) {
                    throw new CustomException("结束时间格式错误: " + end_date, HttpStatus.BAD_REQUEST);
                }
            }

            // 逐条解析并过滤
            for (String jsonMessage : jsonMessages) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> message = objectMapper.readValue(jsonMessage,
                            new TypeReference<Map<String, Object>>() {});
                    String messageTimestamp = String.valueOf(message.getOrDefault("timestamp", "未知时间"));

                    // 时间过滤
                    if (startDateTime != null || endDateTime != null) {
                        if (!"未知时间".equals(messageTimestamp)) {
                            try {
                                LocalDateTime messageDateTime = LocalDateTime.parse(messageTimestamp,
                                        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
                                if (startDateTime != null && messageDateTime.isBefore(startDateTime)) {
                                    continue;
                                }
                                if (endDateTime != null && messageDateTime.isAfter(endDateTime)) {
                                    continue;
                                }
                            } catch (Exception e) {
                                // 时间戳格式不对，跳过过滤，保留该消息
                            }
                        } else if (startDateTime != null || endDateTime != null) {
                            continue;
                        }
                    }

                    Map<String, Object> messageWithTimestamp = new HashMap<>();
                    messageWithTimestamp.put("role", message.get("role"));
                    messageWithTimestamp.put("content", message.get("content"));
                    messageWithTimestamp.put("timestamp", messageTimestamp);
                    messageWithTimestamp.put("conversationId", conversationId);
                    if (message.get("referenceMappings") != null) {
                        messageWithTimestamp.put("referenceMappings", message.get("referenceMappings"));
                    }
                    formattedConversations.add(messageWithTimestamp);
                } catch (Exception e) {
                    LogUtils.logBusinessError("GET_CONVERSATIONS", username, "解析单条消息失败: %s", e, e.getMessage());
                }
            }

            LogUtils.logBusiness("GET_CONVERSATIONS", username, "从Redis中获取到 %d 条对话记录，过滤后剩余 %d 条，会话ID: %s",
                    jsonMessages.size(), formattedConversations.size(), conversationId);
            monitor.end("获取对话历史成功");
        } else {
            LogUtils.logBusiness("GET_CONVERSATIONS", username, "会话ID %s 在Redis中找不到对应的历史记录", conversationId);
            monitor.end("获取对话历史成功（空结果）");
        }

        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "获取对话历史成功");
        response.put("data", formattedConversations);
        return ResponseEntity.ok().body(response);
    }

    /**
     * 解析日期时间字符串，支持多种格式
     */
    private LocalDateTime parseDateTime(String dateTimeStr) {
        if (dateTimeStr == null || dateTimeStr.trim().isEmpty()) {
            return null;
        }

        try {
            return LocalDateTime.parse(dateTimeStr);
        } catch (DateTimeParseException e1) {
            try {
                if (dateTimeStr.length() == 16) {
                    return LocalDateTime.parse(dateTimeStr + ":00");
                }
                if (dateTimeStr.length() == 13) {
                    return LocalDateTime.parse(dateTimeStr + ":00:00");
                }
                if (dateTimeStr.length() == 10) {
                    return LocalDateTime.parse(dateTimeStr + "T00:00:00");
                }
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
                return LocalDateTime.parse(dateTimeStr, formatter);
            } catch (Exception e2) {
                throw new RuntimeException("无效的日期格式: " + dateTimeStr);
            }
        }
    }
}
