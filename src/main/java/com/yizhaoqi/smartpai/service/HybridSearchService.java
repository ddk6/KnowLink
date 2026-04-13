package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.client.RerankClient;
import com.yizhaoqi.smartpai.config.RerankProperties;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.model.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 混合搜索服务，结合文本匹配和向量相似度搜索
 * 支持权限过滤，确保用户只能搜索其有权限访问的文档
 */
@Service
public class HybridSearchService {

    private static final Logger logger = LoggerFactory.getLogger(HybridSearchService.class);

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgTagCacheService orgTagCacheService;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private RerankClient rerankClient;

    @Autowired
    private RerankProperties rerankProperties;

    @Autowired
    private QueryRewriteService queryRewriteService;

    /**
     * 使用文本匹配和向量相似度进行混合搜索，支持权限过滤
     * 采用两阶段排序: RRF 融合 + Cross-Encoder 重排
     *
     * @param query  查询字符串
     * @param userId 用户ID
     * @param topK   返回结果数量
     * @return 搜索结果列表
     */
    public List<SearchResult> searchWithPermission(String query, String userId, int topK) {
        logger.debug("开始带权限搜索，查询: {}, 用户ID: {}, topK: {}", query, userId, topK);

        // ========== 查询改写 (Query Rewrite) ==========
        QueryRewriteService.RewriteResult rewriteResult = queryRewriteService.rewrite(query);
        String rewrittenQuery = rewriteResult.rewrittenQuery();

        logger.debug("[QueryRewrite] 原始查询: \"{}\" -> 改写后: \"{}\", 扩展词: {}",
                rewriteResult.originalQuery(), rewrittenQuery, rewriteResult.expandedTerms());

        // 使用改写后的查询进行搜索
        String searchQuery = rewrittenQuery.isEmpty() ? query : rewrittenQuery;

        try {

            // 获取用户有效的组织标签（包含层级关系）
            List<String> userEffectiveTags = getUserEffectiveOrgTags(userId);
            logger.debug("用户 {} 的有效组织标签: {}", userId, userEffectiveTags);

            // 获取用户的数据库ID用于权限过滤
            String userDbId = getUserDbId(userId);
            logger.debug("用户 {} 的数据库ID: {}", userId, userDbId);

            // 生成查询向量 (使用改写后的查询)
            final List<Float> queryVector = embedToVectorList(searchQuery, userId);

            // 如果向量生成失败，仅使用文本匹配
            if (queryVector == null) {
                logger.warn("向量生成失败，仅使用文本匹配进行搜索");
                return textOnlySearchWithPermission(searchQuery, userDbId, userEffectiveTags, topK);
            }

            // ========== Stage 1: RRF 融合 ==========
            int recallK = topK * 30; // 扩大召回窗口

            // 1.1 KNN 向量搜索获取排名
            Map<String, Integer> knnRankMap = executeKnnSearchForRanking(
                    queryVector, userDbId, userEffectiveTags, recallK);
            logger.debug("KNN 召回文档数: {}", knnRankMap.size());

            // 1.2 BM25 搜索获取排名 (使用改写后的查询)
            Map<String, Integer> bm25RankMap = executeBm25SearchForRanking(
                    searchQuery, userDbId, userEffectiveTags, recallK);
            logger.debug("BM25 召回文档数: {}", bm25RankMap.size());

            // 1.3 RRF 融合
            List<String> fusedDocIds = rrfFusion(knnRankMap, bm25RankMap,
                    rerankProperties.getKnnWeight(),
                    rerankProperties.getBm25Weight(),
                    rerankProperties.getRrfK());
            logger.debug("RRF 融合后文档数: {}", fusedDocIds.size());

            // ========== Stage 2: Cross-Encoder 重排 ==========
            int rerankTopN = Math.min(rerankProperties.getRerankTopN(), fusedDocIds.size());

            if (rerankTopN == 0) {
                logger.warn("RRF 融合结果为空，返回空列表");
                return Collections.emptyList();
            }

            List<String> candidateDocIds = fusedDocIds.subList(0, rerankTopN);

            // 获取候选文档内容
            List<CandidateDoc> candidateDocs = getCandidateDocs(candidateDocIds);

            if (candidateDocs.isEmpty()) {
                logger.warn("候选文档为空，返回空列表");
                return Collections.emptyList();
            }

            // 调用 Rerank API
            List<RerankClient.RerankResult> rerankResults = rerankClient.rerank(query,
                    candidateDocs.stream().map(cd -> cd.doc().getTextContent()).toList());

            // 构建最终结果
            List<SearchResult> finalResults = buildFinalResults(rerankResults, candidateDocs, topK);

            logger.debug("返回最终搜索结果数量: {}", finalResults.size());
            attachFileNames(finalResults);
            return finalResults;

        } catch (Exception e) {
            logger.error("带权限的搜索失败: {}", e.getMessage(), e);
            // 发生异常时尝试使用纯文本搜索作为后备方案
            try {
                logger.info("尝试使用纯文本搜索作为后备方案");
                return textOnlySearchWithPermission(searchQuery, getUserDbId(userId), getUserEffectiveOrgTags(userId), topK);
            } catch (Exception fallbackError) {
                logger.error("后备搜索也失败", fallbackError);
                return Collections.emptyList();
            }
        }
    }

    /**
     * 执行 KNN 向量搜索，获取排名信息
     * 返回: docId -> rank (1-based, rank越小分数越高)
     */
    private Map<String, Integer> executeKnnSearchForRanking(
            List<Float> queryVector,
            String userDbId,
            List<String> userEffectiveTags,
            int recallK) {

        try {
            SearchResponse<EsDocument> response = esClient.search(s -> {
                        s.index("knowledge_base");
                        s.size(recallK);
                        s.knn(kn -> kn
                                .field("vector")
                                .queryVector(queryVector)
                                .k(recallK)
                                .numCandidates(recallK)
                        );
                        // 权限过滤
                        s.query(q -> q.bool(b -> b
                                .filter(f -> f.bool(bf -> bf
                                        .should(s1 -> s1.term(t -> t.field("userId").value(userDbId)))
                                        .should(s2 -> s2.term(t -> t.field("public").value(true)))
                                        .should(buildOrgTagFilter(userEffectiveTags))
                                ))
                        ));
                        return s;
                    }, EsDocument.class);

            Map<String, Integer> rankMap = new HashMap<>();
            List<Hit<EsDocument>> hits = response.hits().hits();
            for (int i = 0; i < hits.size(); i++) {
                String docId = hits.get(i).id();  // 使用 ES 的实际 _id
                rankMap.put(docId, i + 1); // 1-based rank
            }
            return rankMap;

        } catch (Exception e) {
            logger.error("KNN 搜索失败: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 执行 BM25 搜索，获取排名信息
     * 返回: docId -> rank (1-based)
     */
    private Map<String, Integer> executeBm25SearchForRanking(
            String query,
            String userDbId,
            List<String> userEffectiveTags,
            int recallK) {

        try {
            SearchResponse<EsDocument> response = esClient.search(s -> {
                        s.index("knowledge_base");
                        s.size(recallK);
                        s.query(q -> q.bool(b -> b
                                .must(m -> m.match(ma -> ma
                                        .field("textContent")
                                        .query(query)
                                        .operator(Operator.And)
                                ))
                                .filter(f -> f.bool(bf -> bf
                                        .should(s1 -> s1.term(t -> t.field("userId").value(userDbId)))
                                        .should(s2 -> s2.term(t -> t.field("public").value(true)))
                                        .should(buildOrgTagFilter(userEffectiveTags))
                                ))
                        ));
                        return s;
                    }, EsDocument.class);

            Map<String, Integer> rankMap = new HashMap<>();
            List<Hit<EsDocument>> hits = response.hits().hits();
            for (int i = 0; i < hits.size(); i++) {
                String docId = hits.get(i).id();  // 使用 ES 的实际 _id
                rankMap.put(docId, i + 1);
            }
            return rankMap;

        } catch (Exception e) {
            logger.error("BM25 搜索失败: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * RRF (Reciprocal Rank Fusion) 融合
     * RRF_score(doc) = Σ(weight_i / (k + rank_i))
     *
     * @param knnRankMap  KNN 排名 (docId -> rank)
     * @param bm25RankMap BM25 排名 (docId -> rank)
     * @param knnWeight   KNN 权重
     * @param bm25Weight  BM25 权重
     * @param k           RRF k 参数 (通常 60)
     * @return 融合后的文档 ID 列表，按 RRF 分数降序
     */
    private List<String> rrfFusion(
            Map<String, Integer> knnRankMap,
            Map<String, Integer> bm25RankMap,
            double knnWeight,
            double bm25Weight,
            int k) {

        Map<String, Double> rrfScores = new HashMap<>();

        // 所有文档 ID 集合
        Set<String> allDocs = new HashSet<>();
        allDocs.addAll(knnRankMap.keySet());
        allDocs.addAll(bm25RankMap.keySet());

        for (String docId : allDocs) {
            int knnRank = knnRankMap.getOrDefault(docId, Integer.MAX_VALUE);
            int bm25Rank = bm25RankMap.getOrDefault(docId, Integer.MAX_VALUE);

            // 计算 RRF 分数
            double knnRrf = knnRank == Integer.MAX_VALUE ? 0 : knnWeight / (k + knnRank);
            double bm25Rrf = bm25Rank == Integer.MAX_VALUE ? 0 : bm25Weight / (k + bm25Rank);

            rrfScores.put(docId, knnRrf + bm25Rrf);
        }

        // 按 RRF score 降序排序
        List<String> sortedDocIds = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();

        logger.debug("RRF 融合完成 - 参与融合文档数: {}, Top5 分数: {}",
                sortedDocIds.size(),
                sortedDocIds.stream().limit(5)
                        .map(id -> id + ":" + String.format("%.4f", rrfScores.get(id)))
                        .toList());

        return sortedDocIds;
    }

    /**
     * 根据 docId 列表获取候选文档
     */
    private List<CandidateDoc> getCandidateDocs(List<String> docIds) {
        if (docIds.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            SearchResponse<EsDocument> response = esClient.search(s -> {
                        s.index("knowledge_base");
                        s.size(docIds.size());
                        s.query(q -> q.ids(id -> id.values(docIds)));
                        return s;
                    }, EsDocument.class);

            // 建立 docId -> doc 映射
            Map<String, EsDocument> docMap = new HashMap<>();
            for (Hit<EsDocument> hit : response.hits().hits()) {
                if (hit.source() != null) {
                    docMap.put(hit.id(), hit.source());  // 使用 ES 的实际 _id
                }
            }

            // 按 docIds 顺序返回
            List<CandidateDoc> result = new ArrayList<>();
            for (String docId : docIds) {
                EsDocument doc = docMap.get(docId);
                if (doc != null) {
                    result.add(new CandidateDoc(docId, doc));
                }
            }
            return result;

        } catch (Exception e) {
            logger.error("获取候选文档失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 构建最终搜索结果
     */
    private List<SearchResult> buildFinalResults(
            List<RerankClient.RerankResult> rerankResults,
            List<CandidateDoc> candidateDocs,
            int topK) {

        // 建立 index -> CandidateDoc 映射
        Map<Integer, CandidateDoc> indexDocMap = new HashMap<>();
        for (int i = 0; i < candidateDocs.size(); i++) {
            indexDocMap.put(i, candidateDocs.get(i));
        }

        List<SearchResult> results = new ArrayList<>();
        for (int i = 0; i < rerankResults.size() && results.size() < topK; i++) {
            RerankClient.RerankResult rr = rerankResults.get(i);
            CandidateDoc doc = indexDocMap.get(rr.originalIndex());

            if (doc != null && doc.doc() != null) {
                results.add(new SearchResult(
                        doc.doc().getFileMd5(),
                        doc.doc().getChunkId(),
                        doc.doc().getTextContent(),
                        rr.score(), // 使用 rerank 分数
                        doc.doc().getUserId(),
                        doc.doc().getOrgTag(),
                        doc.doc().isPublic(),
                        null,
                        doc.doc().getPageNumber(),
                        doc.doc().getAnchorText(),
                        "RERANK",
                        doc.doc().getTextContent()
                ));
            }
        }
        return results;
    }

    /** 构建组织标签过滤条件 */
    private co.elastic.clients.elasticsearch._types.query_dsl.Query buildOrgTagFilter(List<String> userEffectiveTags) {
        if (userEffectiveTags.isEmpty()) {
            return co.elastic.clients.elasticsearch._types.query_dsl.Query.of(q -> q.matchNone(mn -> mn));
        } else if (userEffectiveTags.size() == 1) {
            return co.elastic.clients.elasticsearch._types.query_dsl.Query.of(q -> q.term(t -> t.field("orgTag").value(userEffectiveTags.get(0))));
        } else {
            return co.elastic.clients.elasticsearch._types.query_dsl.Query.of(q -> q.bool(b -> {
                for (String tag : userEffectiveTags) {
                    b.should(s -> s.term(t -> t.field("orgTag").value(tag)));
                }
                return b;
            }));
        }
    }

    /** List<Float> -> List<Double> */
    /** 候选文档记录 */
    private record CandidateDoc(String docId, EsDocument doc) {
    }

    /**
     * 仅使用文本匹配的带权限搜索方法
     */
    private List<SearchResult> textOnlySearchWithPermission(String query, String userDbId, List<String> userEffectiveTags, int topK) {
        try {
            logger.debug("开始执行纯文本搜索，用户数据库ID: {}, 标签: {}", userDbId, userEffectiveTags);

            SearchResponse<EsDocument> response = esClient.search(s -> s
                    .index("knowledge_base")
                    .query(q -> q
                            .bool(b -> b
                                    // 匹配内容相关性
                                    .must(m -> m
                                            .match(ma -> ma
                                                    .field("textContent")
                                                    .query(query)
                                            )
                                    )
                                    // 权限过滤
                                    .filter(f -> f
                                            .bool(bf -> bf
                                                    // 条件1: 用户可以访问自己的文档
                                                    .should(s1 -> s1
                                                            .term(t -> t
                                                                    .field("userId")
                                                                    .value(userDbId)
                                                            )
                                                    )
                                                    // 条件2: 用户可以访问公开的文档
                                                    .should(s2 -> s2
                                                            .term(t -> t
                                                                    .field("public")
                                                                    .value(true)
                                                            )
                                                    )
                                                    // 条件3: 用户可以访问其所属组织的文档（包含层级关系）
                                                    .should(s3 -> {
                                                        if (userEffectiveTags.isEmpty()) {
                                                            return s3.matchNone(mn -> mn);
                                                        } else if (userEffectiveTags.size() == 1) {
                                                            // 单个标签使用 term 查询
                                                            return s3.term(t -> t
                                                                    .field("orgTag")
                                                                    .value(userEffectiveTags.get(0))
                                                            );
                                                        } else {
                                                            // 多个标签使用 bool should 组合多个 term 查询
                                                            return s3.bool(innerBool -> {
                                                                userEffectiveTags.forEach(tag ->
                                                                        innerBool.should(sh -> sh.term(t -> t
                                                                                .field("orgTag")
                                                                                .value(tag)
                                                                        ))
                                                                );
                                                                return innerBool;
                                                            });
                                                        }
                                                    })
                                            )
                                    )
                            )
                    )
                    .minScore(0.3d)
                    .size(topK),
                    EsDocument.class
            );

            logger.debug("纯文本查询执行完成，命中数量: {}, 最大分数: {}", 
                response.hits().total().value(), response.hits().maxScore());

            List<SearchResult> results = response.hits().hits().stream()
                    .map(hit -> {
                        assert hit.source() != null;
                        logger.debug("纯文本搜索结果 - 文件: {}, 块: {}, 分数: {}, 内容: {}", 
                            hit.source().getFileMd5(), hit.source().getChunkId(), hit.score(), 
                            hit.source().getTextContent().substring(0, Math.min(50, hit.source().getTextContent().length())));
                        return new SearchResult(
                                hit.source().getFileMd5(),
                                hit.source().getChunkId(),
                                hit.source().getTextContent(),
                                hit.score(),
                                hit.source().getUserId(),
                                hit.source().getOrgTag(),
                                hit.source().isPublic(),
                                null,
                                hit.source().getPageNumber(),
                                hit.source().getAnchorText(),
                                "TEXT_ONLY",
                                hit.source().getTextContent()
                        );
                    })
                    .toList();

            logger.debug("返回纯文本搜索结果数量: {}", results.size());
            attachFileNames(results);
            return results;
        } catch (Exception e) {
            logger.error("纯文本搜索失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 原始搜索方法，不包含权限过滤，保留向后兼容性
     */
    public List<SearchResult> search(String query, int topK) {
        try {
            logger.debug("开始混合检索，查询: {}, topK: {}", query, topK);
            logger.warn("使用了没有权限过滤的搜索方法，建议使用 searchWithPermission 方法");

            // 生成查询向量
            final List<Float> queryVector = embedToVectorList(query, "system");
            
            // 如果向量生成失败，仅使用文本匹配
            if (queryVector == null) {
                logger.warn("向量生成失败，仅使用文本匹配进行搜索");
                return textOnlySearch(query, topK);
            }

            SearchResponse<EsDocument> response = esClient.search(s -> {
                        s.index("knowledge_base");
                        int recallK = topK * 30;
                        s.knn(kn -> kn
                                .field("vector")
                                .queryVector(queryVector)
                                .k(recallK)
                                .numCandidates(recallK)
                        );

                        // 过滤仅保留包含关键词的文本
                        s.query(q -> q.match(m -> m.field("textContent").query(query)));

                        // rescore BM25
                        s.rescore(r -> r
                                .windowSize(recallK)
                                .query(rq -> rq
                                        .queryWeight(0.2d)
                                        .rescoreQueryWeight(1.0d)
                                        .query(rqq -> rqq.match(m -> m
                                                .field("textContent")
                                                .query(query)
                                                .operator(Operator.And)
                                        ))
                                )
                        );
                        s.size(topK);
                        return s;
                    }, EsDocument.class);

            return response.hits().hits().stream()
                    .map(hit -> {
                        assert hit.source() != null;
                        return new SearchResult(
                                hit.source().getFileMd5(),
                                hit.source().getChunkId(),
                                hit.source().getTextContent(),
                                hit.score(),
                                null,
                                null,
                                false,
                                null,
                                hit.source().getPageNumber(),
                                hit.source().getAnchorText(),
                                "HYBRID",
                                hit.source().getTextContent()
                        );
                    })
                    .toList();
        } catch (Exception e) {
            logger.error("搜索失败", e);
            // 发生异常时尝试使用纯文本搜索作为后备方案
            try {
                logger.info("尝试使用纯文本搜索作为后备方案");
                return textOnlySearch(query, topK);
            } catch (Exception fallbackError) {
                logger.error("后备搜索也失败", fallbackError);
                throw new RuntimeException("搜索完全失败", fallbackError);
            }
        }
    }

    /**
     * 仅使用文本匹配的搜索方法
     */
    private List<SearchResult> textOnlySearch(String query, int topK) throws Exception {
        SearchResponse<EsDocument> response = esClient.search(s -> s
                .index("knowledge_base")
                .query(q -> q
                        .match(m -> m
                                .field("textContent")
                                .query(query)
                        )
                )
                .size(topK),
                EsDocument.class
        );

        return response.hits().hits().stream()
                .map(hit -> {
                    assert hit.source() != null;
                    return new SearchResult(
                            hit.source().getFileMd5(),
                            hit.source().getChunkId(),
                            hit.source().getTextContent(),
                            hit.score(),
                            null,
                            null,
                            false,
                            null,
                            hit.source().getPageNumber(),
                            hit.source().getAnchorText(),
                            "TEXT_ONLY",
                            hit.source().getTextContent()
                    );
                })
                .toList();
    }

    /**
     * 生成查询向量，返回 List<Float>，失败时返回 null
     */
    private List<Float> embedToVectorList(String text, String requesterId) {
        try {
            List<float[]> vecs = embeddingClient.embed(List.of(text), requesterId, EmbeddingClient.UsageType.QUERY);
            if (vecs == null || vecs.isEmpty()) {
                logger.warn("生成的向量为空");
                return null;
            }
            float[] raw = vecs.get(0);
            List<Float> list = new ArrayList<>(raw.length);
            for (float v : raw) {
                list.add(v);
            }
            return list;
        } catch (Exception e) {
            logger.error("生成向量失败", e);
            return null;
        }
    }
    
    /**
     * 获取用户的有效组织标签（包含层级关系）
     */
    private List<String> getUserEffectiveOrgTags(String userId) {
        logger.debug("获取用户有效组织标签，用户ID: {}", userId);
        try {
            // 获取用户名
            User user;
            try {
                Long userIdLong = Long.parseLong(userId);
                logger.debug("解析用户ID为Long: {}", userIdLong);
                user = userRepository.findById(userIdLong)
                    .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过ID找到用户: {}", user.getUsername());
            } catch (NumberFormatException e) {
                // 如果userId不是数字格式，则假设它就是username
                logger.debug("用户ID不是数字格式，作为用户名查找: {}", userId);
                user = userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过用户名找到用户: {}", user.getUsername());
            }
            
            // 通过orgTagCacheService获取用户的有效标签集合
            List<String> effectiveTags = orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
            logger.debug("用户 {} 的有效组织标签: {}", user.getUsername(), effectiveTags);
            return effectiveTags;
        } catch (Exception e) {
            logger.error("获取用户有效组织标签失败: {}", e.getMessage(), e);
            return Collections.emptyList(); // 返回空列表作为默认值
        }
    }

    /**
     * 获取用户的数据库ID用于权限过滤
     */
    private String getUserDbId(String userId) {
        logger.debug("获取用户数据库ID，用户ID: {}", userId);
        try {
            // 获取用户名
            User user;
            try {
                Long userIdLong = Long.parseLong(userId);
                logger.debug("解析用户ID为Long: {}", userIdLong);
                user = userRepository.findById(userIdLong)
                    .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过ID找到用户: {}", user.getUsername());
                return userIdLong.toString(); // 如果输入已经是数字ID，直接返回
            } catch (NumberFormatException e) {
                // 如果userId不是数字格式，则假设它就是username
                logger.debug("用户ID不是数字格式，作为用户名查找: {}", userId);
                user = userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过用户名找到用户: {}, ID: {}", user.getUsername(), user.getId());
                return user.getId().toString(); // 返回用户的数据库ID
            }
        } catch (Exception e) {
            logger.error("获取用户数据库ID失败: {}", e.getMessage(), e);
            throw new RuntimeException("获取用户数据库ID失败", e);
        }
    }

    private void attachFileNames(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        try {
            // 收集所有唯一的 fileMd5
            Set<String> md5Set = results.stream()
                    .map(SearchResult::getFileMd5)
                    .collect(Collectors.toSet());
            List<FileUpload> uploads = fileUploadRepository.findByFileMd5In(new java.util.ArrayList<>(md5Set));
            Map<String, String> md5ToName = uploads.stream()
                    .collect(Collectors.toMap(FileUpload::getFileMd5, FileUpload::getFileName, (existing, replacement) -> existing));
            // 填充文件名
            results.forEach(r -> r.setFileName(md5ToName.get(r.getFileMd5())));
        } catch (Exception e) {
            logger.error("补充文件名失败", e);
        }
    }
}
