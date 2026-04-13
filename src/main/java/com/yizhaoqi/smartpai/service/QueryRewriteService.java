package com.yizhaoqi.smartpai.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 查询改写服务 - 基于规则引擎实现查询语义增强
 *
 * 功能:
 * 1. 全角转半角规范化
 * 2. WWW 疑问词过滤 (什么/如何/哪里/谁)
 * 3. 同义词查表扩展
 * 4. 错别字/口语词初步处理
 *
 * 不使用 HyDE/ML 模型的原因:
 * - HyDE 有幻觉问题，生成的假设文档可能误导召回
 * - 额外 LLM 调用增加延迟
 * - 规则方法零延迟、可预测
 */
@Service
public class QueryRewriteService {

    private static final Logger logger = LoggerFactory.getLogger(QueryRewriteService.class);

    /** 同义词词典 */
    private Map<String, List<String>> synonymDict = new HashMap<>();

    /** WWW 疑问词正则 (前缀) */
    private static final Pattern WWW_PREFIX_PATTERN = Pattern.compile(
            "^(什么样的|哪家|请问|啥样|咋样了|什么时候|何时|何地|何人|是否|是不是|多少|" +
            "哪里|怎么|哪儿|怎么样|如何|哪些|是啥|啥是|吗|呢|吧|咋|什么|有没有|谁|哪位|哪个)\\s*",
            Pattern.CASE_INSENSITIVE);

    /** WWW 疑问词正则 (后缀) */
    private static final Pattern WWW_SUFFIX_PATTERN = Pattern.compile(
            "\\s*(什么样|哪家|一下|那家|啥样|咋样了|什么时候|何时|何地|何人|是否|" +
            "是不是|多少|哪里|怎么|哪儿|怎么样|如何|哪些|是啥|啥是|吗|呢|吧|咋|" +
            "有什么|有没有|谁|哪位|哪个|啊)$",
            Pattern.CASE_INSENSITIVE);

    /** 英文 WWW 疑问词 */
    private static final Pattern EN_WWW_PATTERN = Pattern.compile(
            "(^|\\s)(what|who|how|which|where|why|when|is|are|were|was|do|does|did|" +
            "don't|doesn't|didn't|has|have|be|there|you|me|your|my|just|please|may|" +
            "i|should|would|will|can|could)\\s*",
            Pattern.CASE_INSENSITIVE);

    /** 特殊字符正则 (保留中文标点) */
    private static final Pattern SPECIAL_CHARS_PATTERN = Pattern.compile(
            "[ :|\\r\\n\\t,，。？?/`!！&^%%()\\[\\]{}<>\\-\\-\\*\\\"'\\\\]+");

    /** 同义词词典路径 */
    @Value("${query-rewrite.synonym-path:classpath:dict/synonym.txt}")
    private String synonymPath;

    /** 是否启用同义词扩展 */
    @Value("${query-rewrite.synonym-enabled:true}")
    private boolean synonymEnabled;

    /** 同义词扩展最大数量 */
    @Value("${query-rewrite.synonym-max:5}")
    private int synonymMax;

    /** 是否启用 WWW 过滤 */
    @Value("${query-rewrite.www-filter-enabled:true}")
    private boolean wwwFilterEnabled;

    /** 是否启用全角转半角 */
    @Value("${query-rewrite.fullwidth-normalization-enabled:true}")
    private boolean fullwidthNormalizationEnabled;

    @PostConstruct
    public void init() {
        loadSynonymDict();
        logger.info("QueryRewriteService 初始化完成 - 同义词词典大小: {}, 启用状态: WWW过滤={}, 同义词扩展={}",
                synonymDict.size(), wwwFilterEnabled, synonymEnabled);
    }

    /**
     * 加载同义词词典
     */
    private void loadSynonymDict() {
        try {
            ClassPathResource resource = new ClassPathResource(synonymPath);
            if (!resource.exists()) {
                logger.warn("同义词词典文件不存在: {}, 降级为纯规则处理", synonymPath);
                initializeDefaultSynonyms();
                return;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    String[] parts = line.split("[,\\t]");
                    if (parts.length >= 2) {
                        String term = parts[0].toLowerCase().trim();
                        List<String> synonyms = new ArrayList<>();
                        for (int i = 1; i < parts.length; i++) {
                            String syn = parts[i].toLowerCase().trim();
                            if (!syn.isEmpty() && !syn.equals(term)) {
                                synonyms.add(syn);
                            }
                        }
                        if (!synonyms.isEmpty()) {
                            synonymDict.put(term, synonyms);
                        }
                    }
                }
            }
            logger.info("同义词词典加载完成，共 {} 个词条", synonymDict.size());
        } catch (Exception e) {
            logger.error("加载同义词词典失败: {}, 降级为纯规则处理", e.getMessage());
            initializeDefaultSynonyms();
        }
    }

    /**
     * 初始化默认同义词 (内置基础同义词)
     */
    private void initializeDefaultSynonyms() {
        // 基础领域同义词
        synonymDict.put("人工智能", Arrays.asList("AI", "机器学习", "深度学习"));
        synonymDict.put("深度学习", Arrays.asList("神经网络", "CNN", "RNN", "机器学习"));
        synonymDict.put("机器学习", Arrays.asList("ML", "模式识别", "统计学习"));
        synonymDict.put("大数据", Arrays.asList("数据挖掘", "数据分析", "海量数据"));
        synonymDict.put("云计算", Arrays.asList("云服务", "IaaS", "PaaS", "SaaS"));
        synonymDict.put("区块链", Arrays.asList("分布式账本", "比特币", "以太坊"));
        synonymDict.put("物联网", Arrays.asList("IoT", "智能硬件", "传感器网络"));
        synonymDict.put("网络安全", Arrays.asList("信息安全", "网络攻防", "数据安全"));
        synonymDict.put("软件工程", Arrays.asList("软件开发", "编程", "系统开发"));
        synonymDict.put("数据库", Arrays.asList("DB", "数据存储", "SQL", "NoSQL"));
        synonymDict.put("前端", Arrays.asList("前端开发", "Web前端", "HTML", "CSS", "JavaScript"));
        synonymDict.put("后端", Arrays.asList("后端开发", "服务端", "服务器开发"));
        synonymDict.put("算法", Arrays.asList("计算方法", "数据算法", "搜索算法"));
        synonymDict.put("模型", Arrays.asList("数学模型", "预测模型", "机器学习模型"));
        synonymDict.put("训练", Arrays.asList("模型训练", "学习", "迭代优化"));
        synonymDict.put("推理", Arrays.asList("预测", "推断", "模型预测"));
        synonymDict.put("神经网络", Arrays.asList("深度神经网络", "DNN", "神经元网络"));
        synonymDict.put("自然语言处理", Arrays.asList("NLP", "文本处理", "语言理解"));
        synonymDict.put("计算机视觉", Arrays.asList("CV", "图像处理", "视觉识别"));
        synonymDict.put("语音识别", Arrays.asList("ASR", "语音转文字", "语音处理"));
        logger.info("已初始化默认同义词词典，共 {} 个词条", synonymDict.size());
    }

    /**
     * 执行完整的查询改写流程
     *
     * @param query 原始查询
     * @return 改写后的查询结果
     */
    public RewriteResult rewrite(String query) {
        if (query == null || query.trim().isEmpty()) {
            return new RewriteResult("", query, Collections.emptyList());
        }

        String original = query;
        String processed = query;

        // Step 1: 全角转半角
        if (fullwidthNormalizationEnabled) {
            processed = normalizeFullWidth(processed);
        }

        // Step 2: 移除特殊字符
        processed = removeSpecialChars(processed);

        // Step 3: WWW 过滤
        if (wwwFilterEnabled) {
            processed = removeWWW(processed);
        }

        // Step 4: 同义词扩展
        List<String> expandedTerms = Collections.emptyList();
        if (synonymEnabled && !processed.isEmpty()) {
            expandedTerms = expandSynonyms(processed);
        }

        // Step 5: 合并原始处理结果和同义词
        String finalQuery = processed;
        if (!expandedTerms.isEmpty()) {
            finalQuery = processed + " " + String.join(" ", expandedTerms);
        }

        RewriteResult result = new RewriteResult(finalQuery.trim(), original, expandedTerms);

        logger.debug("[QueryRewriteService] 原始查询: {}, WWW过滤后: {}, 同义词扩展: {}, 最终查询: {}",
                original, processed, expandedTerms, finalQuery);

        return result;
    }

    /**
     * 仅执行同义词扩展 (不修改原始查询)
     *
     * @param query 原始查询
     * @return 扩展后的查询词列表
     */
    public List<String> expand(String query) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return expandSynonyms(query.trim().toLowerCase());
    }

    /**
     * 全角转半角
     *
     * 全角字符范围: \uFF01-\uFF5E
     * 转换公式: 半角 = 全角 - 0xFEE0
     * 空格: \u3000 -> \u0020
     */
    public String normalizeFullWidth(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c >= '\uFF01' && c <= '\uFF5E') {
                // 全角转半角 (减去固定偏移量)
                sb.append((char) (c - 0xFEE0));
            } else if (c == '\u3000') {
                // 全角空格转半角空格
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 移除特殊字符 (保留有语义的标点)
     */
    public String removeSpecialChars(String text) {
        if (text == null) {
            return null;
        }
        return SPECIAL_CHARS_PATTERN.matcher(text).replaceAll(" ").trim();
    }

    /**
     * 移除 WWW 疑问词
     *
     * 规则:
     * 1. 移除前缀疑问词 (什么是、怎么、哪里等)
     * 2. 移除后缀疑问词 (吗、呢、吧等)
     * 3. 移除英文疑问词
     */
    public String removeWWW(String text) {
        if (text == null) {
            return null;
        }
        String result = text;

        // 移除前缀疑问词
        result = WWW_PREFIX_PATTERN.matcher(result).replaceFirst("");

        // 移除后缀疑问词
        result = WWW_SUFFIX_PATTERN.matcher(result).replaceFirst("");

        // 移除英文疑问词
        result = EN_WWW_PATTERN.matcher(result).replaceAll(" ");

        // 清理多余空格
        result = result.replaceAll("\\s+", " ").trim();

        return result;
    }

    /**
     * 同义词查表扩展
     *
     * @param term 查询词
     * @return 同义词列表 (最多 synonymMax 个)
     */
    public List<String> expandSynonyms(String term) {
        if (term == null || term.isEmpty() || synonymDict.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> synonyms = new LinkedHashSet<>();
        String normalizedTerm = term.toLowerCase().trim();

        // 直接查找
        List<String> directSynonyms = synonymDict.get(normalizedTerm);
        if (directSynonyms != null) {
            for (String syn : directSynonyms) {
                if (synonyms.size() >= synonymMax) break;
                synonyms.add(syn);
            }
        }

        // 分词后查找 (对短词效果更好)
        String[] words = normalizedTerm.split("\\s+");
        for (String word : words) {
            if (word.length() < 2) continue;
            List<String> wordSynonyms = synonymDict.get(word);
            if (wordSynonyms != null) {
                for (String syn : wordSynonyms) {
                    if (synonyms.size() >= synonymMax) break;
                    if (!syn.equals(word)) {
                        synonyms.add(syn);
                    }
                }
            }
        }

        return new ArrayList<>(synonyms);
    }

    /**
     * 判断是否为短查询 (触发 Multi-Query 的条件)
     */
    public boolean isShortQuery(String query) {
        if (query == null) {
            return false;
        }
        // 按字符计数，中文按字计，英文按词计
        int chineseChars = 0;
        int englishWords = 0;
        for (char c : query.toCharArray()) {
            if (c >= 0x4E00 && c <= 0x9FA5) {
                chineseChars++;
            } else if (Character.isLetterOrDigit(c)) {
                englishWords++;
            }
        }
        // 中文 < 5 字 或 英文 < 3 词视为短查询
        return chineseChars > 0 && chineseChars < 5 || englishWords > 0 && englishWords < 3;
    }

    /**
     * 改写结果封装
     */
    public record RewriteResult(
            /** 最终改写后的查询 */
            String rewrittenQuery,
            /** 原始查询 */
            String originalQuery,
            /** 扩展的同义词列表 */
            List<String> expandedTerms
    ) {
    }
}