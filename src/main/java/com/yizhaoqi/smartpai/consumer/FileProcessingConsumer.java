package com.yizhaoqi.smartpai.consumer;

import com.yizhaoqi.smartpai.config.KafkaConfig;
import com.yizhaoqi.smartpai.config.MinerUProperties;
import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.MinerUParseResult;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
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
    private final DocumentVectorRepository documentVectorRepository;
    private final MinerUService minerUService;
    private final MinerUProperties minerUProperties;
    @Autowired
    private KafkaConfig kafkaConfig;


    public FileProcessingConsumer(
            ParseService parseService,
            VectorizationService vectorizationService,
            FileUploadRepository fileUploadRepository,
            MinerUParseResultRepository mineruParseResultRepository,
            DocumentVectorRepository documentVectorRepository,
            MinerUService minerUService,
            MinerUProperties minerUProperties
    ) {
        this.parseService = parseService;
        this.vectorizationService = vectorizationService;
        this.fileUploadRepository = fileUploadRepository;
        this.mineruParseResultRepository = mineruParseResultRepository;
        this.documentVectorRepository = documentVectorRepository;
        this.minerUService = minerUService;
        this.minerUProperties = minerUProperties;
    }

    //这是处理文件解析任务的方法
    //从Kafka主题中监听文件解析任务
    //根据任务类型，选择不同的解析器
    //如果解析器失败，尝试降级到 Tika 解析
    //如果 Tika 解析也失败，更新任务状态为 FAILED
    //如果解析成功，更新任务状态为 COMPLETED
    @KafkaListener(topics = "#{kafkaConfig.getFileProcessingTopic()}", groupId = "#{kafkaConfig.getFileProcessingGroupId()}")
    public void processTask(FileProcessingTask task) {
        log.info("Received task: {}", task);
        log.info("文件权限信息: userId={}, orgTag={}, isPublic={}",
                task.getUserId(), task.getOrgTag(), task.isPublic());

        // 更新解析状态为 PROCESSING  这个状态存在哪个表
        // 这个状态存在mineru_parse_result表中
        // 这个表是MinerU解析结果的存储表
        // 这个表的字段有：fileMd5, parser, status, createdAt, updatedAt
        //这个表存在哪？
        // 这个表存在数据库中
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

        }
        //异常处理
        //如果MinerU解析失败，尝试降级到 Tika 解析
        //如果 Tika 解析也失败，更新任务状态为 FAILED
        //如果解析成功，更新任务状态为 COMPLETED
        //
        catch (Exception e) {
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
        //如果文件是本地文件，直接返回文件流 然后调用MinerU API解析文件
        //如果文件是远程URL，使用HTTP GET请求下载文件
        //这里返回的是临时文件的路径
        Path filePath = downloadFileToTemp(task.getFilePath(), task.getFileMd5());
        log.info("[MinerU] 文件下载到临时路径: {}", filePath);

        try {
            // 2. 调用 MinerU API 解析 将文件名、文件路径、文件MD5值上传到MinerU服务器，等待解析完成，下载解析结果
            //这个解析结果是一个ZIP文件，包含解析后的JSON文件
            MinerUService.MinerUParseResult parseResult = minerUService.uploadAndParse(
                    filePath,
                    task.getFileName(),
                    task.getFileMd5()
            );

            // 3. 保存 MinerU 解析结果到 mineru_parse_result 表
            //这个表是MinerU解析结果的存储表 字段有：fileMd5, parser, status, createdAt, updatedAt
            //这个表存在数据库中
            saveMinerUResult(task.getFileMd5(), parseResult);

            // 4. V3: 保存 chunks 到 document_vectors 表（带 V3 metadata）
            //这个表存的是分块后的文本内容
            //这个表存在数据库中
            saveMinerUChunksToDocumentVector(task, parseResult);

            // 5. 向量化处理
            //这里调用向量化服务，将MinerU解析结果中的文本内容向量化
            //向量化结果是一个JSON文件，包含向量信息 和 向量使用情况
            //task就是MinerU解析结果的存储表 字段有：fileMd5, parser, status, createdAt, updatedAt、userId、orgTag、isPublic、userId等
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
            // 6. 清理临时文件
            //这里删除的是临时文件的路径
            try {
                Files.deleteIfExists(filePath);
            } catch (IOException e) {
                log.warn("[MinerU] 清理临时文件失败: {}", filePath);
            }
        }
    }

    /**
     * V3: 将 MinerU chunks 保存到 document_vectors 表（带 V3 metadata）
     */
    //这里调用向量化服务，将MinerU解析结果中的文本内容向量化
    //向量化结果是一个JSON文件，包含向量信息 和 向量使用情况
    //task就是MinerU解析结果的存储表 字段有：fileMd5, parser, status, createdAt, updatedAt、userId、orgTag、isPublic、userId等
    //向量化结果会记录向量化使用的模型版本，以及向量化消耗的计算资源
    //向量化结果会保存到数据库中
    //向量化结果会返回给前端
    private void saveMinerUChunksToDocumentVector(FileProcessingTask task, MinerUService.MinerUParseResult parseResult) {
        if (parseResult.getChunks() == null || parseResult.getChunks().isEmpty()) {
            log.warn("[MinerU] MinerU 返回的 chunks 为空，跳过保存: fileMd5={}", task.getFileMd5());
            return;
        }

        log.info("[MinerU] 开始保存 {} 个 chunks 到 document_vectors: fileMd5={}",
                parseResult.getChunks().size(), task.getFileMd5());

        for (MinerUService.TextChunk minerUChunk : parseResult.getChunks()) {
            DocumentVector vector = new DocumentVector();
            vector.setFileMd5(task.getFileMd5());
            vector.setChunkId(minerUChunk.getChunkId());
            vector.setTextContent(minerUChunk.getContent());
            vector.setPageNumber(minerUChunk.getPageNumber());
            vector.setAnchorText(minerUChunk.getAnchorText());
            vector.setUserId(task.getUserId());
            vector.setOrgTag(task.getOrgTag());
            vector.setPublic(task.isPublic());

            // V3 metadata
            vector.setSectionPath(minerUChunk.getSectionPath());
            vector.setChunkType(minerUChunk.getChunkType());
            vector.setKeyClause(minerUChunk.isKeyClause());
            vector.setTokenCount(minerUChunk.getTokenCount());

            documentVectorRepository.save(vector);
        }

        log.info("[MinerU] 保存 chunks 完成: fileMd5={}, count={}",
                task.getFileMd5(), parseResult.getChunks().size());
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
    //这是保存MinerU解析结果的方法
    //根据文件MD5保存解析结果
    //为啥要保存解析结果？因为MinerU解析结果是一个JSON文件，包含解析后的文本内容、布局信息、元数据等
    //我们需要将这些信息保存到数据库中，方便后续的检索和分析
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
        //把minerU的解析结果保存到数据库中 以便后续的分块和向量化
        //分块也是先从数据库中获取解析结果，再进行分块操作
        //分完块后，需要将分块结果保存到数据库中
        //然后再从数据库中获取分块结果，进行向量化操作
        //这个过程中用到了两次数据库操作，一次是保存解析结果，一次是保存分块结果
    }

    /**
     * 更新文件解析状态
     */
    //这是更新文件解析状态的方法
    //根据文件MD5更新解析状态和解析方法
    //如果解析状态为 COMPLETED 或 FAILED，更新解析时间
    //如果解析状态为 FAILED，更新解析错误信息
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
    //minerU比Tika解析文件多了一个临时路径的创建
    //临时路径是为了在MinerU API解析文件时，能够直接上传文件到MinerU服务器

    //这里返回的是什么？
    //返回的是临时路径的临时文件路径
    private Path downloadFileToTemp(String filePath, String fileMd5) throws Exception {
        Path tempFile = Files.createTempFile("mineru_input_", "_" + fileMd5);

            //从存储系统minio下载文件到临时路径
        //
        try (InputStream in = downloadFileFromStorage(filePath);
             OutputStream out = Files.newOutputStream(tempFile)) {
            if (in == null) {
                throw new IOException("无法下载文件: " + filePath);
            }
            in.transferTo(out);
        }
        //返回临时文件路径
        return tempFile;
    }

    /**
     * 模拟从存储系统下载文件
     *
     * @param filePath 文件路径或 URL
     * @return 文件输入流
     */
    //这是从存储系统minio下载文件的方法
    //如果文件是本地文件，直接返回文件流
    //如果文件是远程URL，使用HTTP GET请求下载文件
    //tika解析器直接从文件系统读取文件，不需要临时路径 因此会直接调用downloadFileFromStorage方法返回文件流
    //但是minerU解析器需要临时路径 因此会先调用downloadFileToTemp方法下载文件到临时文件
    //然后再从临时文件读取文件流
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

    //这是更新实际Embedding用量的方法
    //如果任务或向量结果为空，直接返回
    //如果文件记录不存在，也返回
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
