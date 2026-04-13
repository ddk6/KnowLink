package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.MinerUParseResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * MinerU 解析结果 Repository
 */
public interface MinerUParseResultRepository extends JpaRepository<MinerUParseResultEntity, Long> {

    /**
     * 根据文件 MD5 查询解析结果
     */
    Optional<MinerUParseResultEntity> findByFileMd5(String fileMd5);

    /**
     * 检查是否存在解析结果
     */
    boolean existsByFileMd5(String fileMd5);

    /**
     * 删除指定文件的解析结果
     */
    @Transactional
    void deleteByFileMd5(String fileMd5);
}
