package com.yizhaoqi.smartpai.consumer;

import com.yizhaoqi.smartpai.config.KafkaConfig;
import com.yizhaoqi.smartpai.config.MinerUProperties;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.MinerUParseResult;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.MinerUParseResultRepository;
import com.yizhaoqi.smartpai.service.MinerUService;
import com.yizhaoqi.smartpai.service.ParseService;
import com.yizhaoqi.smartpai.service.VectorizationService;
import io.minio.errors.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;

@Service
@Slf4j
public class FileProcessingConsumer {

    private final ParseService parseService;
    private final VectorizationService vectorizationService;
    private final FileUploadRepository fileUploadRepository;
    private final MinerUParseResultRepository mineruParseResultRepository;
    private final MinerUService minerUService;
    private final MinerUProperties minerUProperties;
    @Autowired
    private KafkaConfig kafkaConfig;


    public FileProcessingConsumer(
            ParseService parseService,
            VectorizationService vectorizationService,
            FileUploadRepository fileUploadRepository,
            MinerUParseResultRepository mineruParseResultRepository,
            MinerUService minerUService,
            MinerUProperties minerUProperties
    ) {
        this.parseService = parseService;
        this.vectorizationService = vectorizationService;
        this.fileUploadRepository = fileUploadRepository;
        this.mineruParseResultRepository = mineruParseResultRepository;
        this.minerUService = minerUService;
        this.minerUProperties = minerUProperties;
    }

    @KafkaListener(topics = "#{kafkaConfig.getFileProcessingTopic()}", groupId = "#{kafkaConfig.getFileProcessingGroupId()}")
    public void processTask(FileProcessingTask task) {
        log.info("Received task: {}", task);
        log.info("文件权限信息: userId={}, orgTag={}, isPublic={}",
                task.getUserId(), task.getOrgTag(), task.isPublic());

        // 更新解析状态为 PROCESSING
        updateParseStatus(task.getFileMd5(), "PROCESSING", "MINERU");

        try {
            // ========== 根据配置选择解析器 ==========
            if (minerUProperties.isEnabled()) {
                // ========== MinerU 解析流程 ==========
                processWithMinerU(task);
            } else {
                // ========== 原有 Tika 解析流程 ==========
                processWithTika(task);
            }

            // 更新解析状态为 COMPLETED
            updateParseStatus(task.getFileMd5(), "COMPLETED", null);

        } catch (Exception e) {
            log.error("文件解析失败: fileMd5={}", task.getFileMd5(), e);

            // 检查是否应该降级到 Tika
            if (minerUProperties.isEnabled() && isMinerURelatedError(e)) {
                log.warn("[MinerU] MinerU 解析失败，尝试降级到 Tika: {}", task.getFileMd5());
                try {
                    processWithTika(task);
                    updateParseStatus(task.getFileMd5(), "COMPLETED", "TIKA");
                    log.info("[MinerU] Tika 降级解析成功: {}", task.getFileMd5());
                } catch (Exception tikaEx) {
                    log.error("[MinerU] Tika 降级解析也失败: {}", task.getFileMd5(), tikaEx);
                    updateParseStatus(task.getFileMd5(), "FAILED", "TIKA");
                    throw new RuntimeException("Both MinerU and Tika parsing failed", tikaEx);
                }
            } else {
                updateParseStatus(task.getFileMd5(), "FAILED", null);
                throw new RuntimeException("Error processing task", e);
            }
        }
    }

    /**
     * MinerU 解析流程
     */
    private void processWithMinerU(FileProcessingTask task) throws Exception {
        log.info("[MinerU] 开始 MinerU 解析: fileMd5={}", task.getFileMd5());

        // 1. 下载文件到临时路径
        Path filePath = downloadFileToTemp(task.getFilePath(), task.getFileMd5());
        log.info("[MinerU] 文件下载到临时路径: {}", filePath);

        try {
            // 2. 调用 MinerU API 解析
            MinerUService.MinerUParseResult parseResult = minerUService.uploadAndParse(
                    filePath,
                    task.getFileName(),
                    task.getFileMd5()
            );

            // 3. 保存 MinerU 解析结果
            saveMinerUResult(task.getFileMd5(), parseResult);

            // 4. 向量化处理
            VectorizationService.VectorizationUsageResult vectorizationResult = vectorizationService.vectorizeWithUsage(
                    task.getFileMd5(),
                    task.getUserId(),
                    task.getOrgTag(),
                    task.isPublic(),
                    task.getUserId()
            );
            updateActualEmbeddingUsage(task, vectorizationResult);
            log.info("[MinerU] 向量化完成: fileMd5={}", task.getFileMd5());

        } finally {
            // 5. 清理临时文件
            try {
                Files.deleteIfExists(filePath);
            } catch (IOException e) {
                log.warn("[MinerU] 清理临时文件失败: {}", filePath);
            }
        }
    }

    /**
     * 原有 Tika 解析流程
     */
    private void processWithTika(FileProcessingTask task) throws Exception {
        log.info("[Tika] 开始 Tika 解析: fileMd5={}", task.getFileMd5());

        InputStream fileStream = null;
        try {
            // 下载文件
            fileStream = downloadFileFromStorage(task.getFilePath());
            if (fileStream == null) {
                throw new IOException("流为空");
            }

            // 强制转换为可缓存流
            if (!fileStream.markSupported()) {
                fileStream = new BufferedInputStream(fileStream);
            }

            // 解析文件
            parseService.parseAndSave(task.getFileMd5(), fileStream,
                    task.getUserId(), task.getOrgTag(), task.isPublic());
            log.info("[Tika] 文件解析完成: fileMd5={}", task.getFileMd5());

            // 向量化处理
            VectorizationService.VectorizationUsageResult vectorizationResult = vectorizationService.vectorizeWithUsage(
                    task.getFileMd5(),
                    task.getUserId(),
                    task.getOrgTag(),
                    task.isPublic(),
                    task.getUserId()
            );
            updateActualEmbeddingUsage(task, vectorizationResult);
            log.info("[Tika] 向量化完成: fileMd5={}", task.getFileMd5());

        } finally {
            if (fileStream != null) {
                try {
                    fileStream.close();
                } catch (IOException e) {
                    log.error("[Tika] 关闭文件流失败", e);
                }
            }
        }
    }

    /**
     * 保存 MinerU 解析结果到数据库
     */
    private void saveMinerUResult(String fileMd5, MinerUService.MinerUParseResult parseResult) {
        MinerUParseResult entity = new MinerUParseResult();
        entity.setFileMd5(fileMd5);
        entity.setFullMd(parseResult.getFullMd());
        entity.setContentJson(parseResult.getContentJson());
        entity.setLayoutJson(parseResult.getLayoutJson());
        entity.setMineruBatchId(parseResult.getMineruBatchId());
        entity.setParseStatus(parseResult.getParseStatus());
        entity.setParseError(parseResult.getParseError());
        mineruParseResultRepository.save(entity);
        log.info("[MinerU] 解析结果已保存: fileMd5={}", fileMd5);
    }

    /**
     * 更新文件解析状态
     */
    private void updateParseStatus(String fileMd5, String status, String parseMethod) {
        try {
            fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5)
                    .ifPresent(fileUpload -> {
                        fileUpload.setParseStatus(status);
                        if (parseMethod != null) {
                            fileUpload.setParseMethod(parseMethod);
                        }
                        if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                            fileUpload.setParsedAt(LocalDateTime.now());
                        }
                        fileUploadRepository.save(fileUpload);
                        log.debug("更新解析状态: fileMd5={}, status={}, method={}", fileMd5, status, parseMethod);
                    });
        } catch (Exception e) {
            log.warn("更新解析状态失败: fileMd5={}", fileMd5, e);
        }
    }

    /**
     * 判断是否为 MinerU 相关错误（用于决定是否降级）
     */
    private boolean isMinerURelatedError(Exception e) {
        String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        return message.contains("mineru") ||
                message.contains("timeout") ||
                message.contains("connection") ||
                message.contains("network");
    }

    /**
     * 下载文件到临时路径（用于 MinerU）
     */
    private Path downloadFileToTemp(String filePath, String fileMd5) throws Exception {
        Path tempFile = Files.createTempFile("mineru_input_", "_" + fileMd5);

        try (InputStream in = downloadFileFromStorage(filePath);
             OutputStream out = Files.newOutputStream(tempFile)) {
            if (in == null) {
                throw new IOException("无法下载文件: " + filePath);
            }
            in.transferTo(out);
        }

        return tempFile;
    }

    /**
     * 模拟从存储系统下载文件
     *
     * @param filePath 文件路径或 URL
     * @return 文件输入流
     */
    private InputStream downloadFileFromStorage(String filePath) throws ServerException, InsufficientDataException, ErrorResponseException, IOException, NoSuchAlgorithmException, InvalidKeyException, InvalidResponseException, XmlParserException, InternalException {
        log.info("Downloading file from storage: {}", filePath);

        try {
            // 如果是文件系统路径
            File file = new File(filePath);
            if (file.exists()) {
                log.info("Detected file system path: {}", filePath);
                return new FileInputStream(file);
            }

            // 如果是远程 URL
            if (filePath.startsWith("http://") || filePath.startsWith("https://")) {
                log.info("Detected remote URL: {}", filePath);
                URL url = new URL(filePath);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(30000); // 连接超时30秒
                connection.setReadTimeout(180000);   // 读取超时时间3分钟

                // 添加必要的请求头
                connection.setRequestProperty("User-Agent", "SmartPAI-FileProcessor/1.0");

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    log.info("Successfully connected to URL, starting download...");
                    return connection.getInputStream();
                } else if (responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                    log.error("Access forbidden - possible expired presigned URL");
                    throw new IOException("Access forbidden - the presigned URL may have expired");
                } else {
                    log.error("Failed to download file, HTTP response code: {} for URL: {}", responseCode, filePath);
                    throw new IOException(String.format("Failed to download file, HTTP response code: %d", responseCode));
                }
            }

            // 如果既不是文件路径也不是 URL
            throw new IllegalArgumentException("Unsupported file path format: " + filePath);
        } catch (Exception e) {
            log.error("Error downloading file from storage: {}", filePath, e);
            return null; // 或者抛出异常
        }
    }

    private void updateActualEmbeddingUsage(
            FileProcessingTask task,
            VectorizationService.VectorizationUsageResult vectorizationResult
    ) {
        if (task == null || vectorizationResult == null || task.getFileMd5() == null || task.getUserId() == null) {
            return;
        }

        FileUpload fileUpload = fileUploadRepository
                .findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(task.getFileMd5(), task.getUserId())
                .orElse(null);
        if (fileUpload == null) {
            log.warn("回写实际 Embedding 用量失败，未找到文件记录: fileMd5={}, userId={}", task.getFileMd5(), task.getUserId());
            return;
        }

        fileUpload.setActualEmbeddingTokens((long) vectorizationResult.actualEmbeddingTokens());
        fileUpload.setActualChunkCount(vectorizationResult.actualChunkCount());
        fileUploadRepository.save(fileUpload);
    }
}
