package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.config.MinioConfig;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.ChunkInfoRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UploadServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private MinioClient minioClient;

    @Mock
    private FileUploadRepository fileUploadRepository;

    @Mock
    private ChunkInfoRepository chunkInfoRepository;

    @Mock
    private MinioConfig minioConfig;

    private UploadService uploadService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        uploadService = new UploadService();
        ReflectionTestUtils.setField(uploadService, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(uploadService, "minioClient", minioClient);
        ReflectionTestUtils.setField(uploadService, "fileUploadRepository", fileUploadRepository);
        ReflectionTestUtils.setField(uploadService, "chunkInfoRepository", chunkInfoRepository);
        ReflectionTestUtils.setField(uploadService, "minioConfig", minioConfig);
    }

    @Test
    void uploadChunkRejectsWhenFileAlreadyCompleted() throws Exception {
        FileUpload fileUpload = new FileUpload();
        fileUpload.setFileMd5("md5");
        fileUpload.setUserId("1");
        fileUpload.setStatus(FileUpload.STATUS_COMPLETED);

        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", "1"))
                .thenReturn(Optional.of(fileUpload));

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "demo".getBytes());

        CustomException exception = assertThrows(
                CustomException.class,
                () -> uploadService.uploadChunk("md5", 0, 1024L, "test.pdf", file, "TEAM_A", false, "1")
        );

        assertEquals("文件已完成合并，不允许继续上传分片", exception.getMessage());
        verifyNoInteractions(chunkInfoRepository);
    }

    @Test
    void uploadChunkRejectsWhenFileIsMerging() throws Exception {
        FileUpload fileUpload = new FileUpload();
        fileUpload.setFileMd5("md5");
        fileUpload.setUserId("1");
        fileUpload.setStatus(FileUpload.STATUS_MERGING);

        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", "1"))
                .thenReturn(Optional.of(fileUpload));

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "demo".getBytes());

        CustomException exception = assertThrows(
                CustomException.class,
                () -> uploadService.uploadChunk("md5", 0, 1024L, "test.pdf", file, "TEAM_A", false, "1")
        );

        assertEquals("文件正在合并中，请稍后重试", exception.getMessage());
        verifyNoInteractions(chunkInfoRepository);
    }
}
