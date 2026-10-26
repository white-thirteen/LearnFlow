package com.tianji.aigc.controller;

import cn.hutool.core.collection.CollStreamUtil;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.tianji.aigc.vo.KnowledgeDocVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 知识库（向量库）相关接口
 */
@Slf4j
@RestController
@RequestMapping("/embedding")
@RequiredArgsConstructor
public class EmbeddingController {

    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;
    private final ElasticsearchClient elasticsearchClient;

    /**
     * 向量库索引名，与 spring.ai.vectorstore.elasticsearch.index-name 保持一致
     */
    @Value("${spring.ai.vectorstore.elasticsearch.index-name:spring-ai-document-index}")
    private String indexName;

    /**
     * 文本转向量
     */
    @GetMapping
    public List<Float> embed(@RequestParam("message") String message) {
        float[] embedding = this.embeddingModel.embed(message);
        List<Float> result = new ArrayList<>(embedding.length);
        for (float value : embedding) {
            result.add(value);
        }
        return result;
    }

    /**
     * 保存文本到向量库
     */
    @PostMapping
    public void saveVectorStore(@RequestParam("messages") List<String> messages) {
        log.info("保存到向量数据库中，消息数据：{}", messages);
        //构建文档
        List<Document> documents = CollStreamUtil.toList(messages, message -> Document.builder()
                .text(message)
                .build());
        //存储到向量数据库中
        this.vectorStore.add(documents);
        log.info("保存到向量数据库成功, 数量：{}", messages.size());
    }

    /**
     * 删除文本
     */
    @DeleteMapping
    public void delete(@RequestParam("ids") List<String> ids) {
        log.info("从向量数据库中删除文本，ids：{}", ids);
        this.vectorStore.delete(ids);
    }

    /**
     * 内容搜索
     */
    @GetMapping("/search")
    public List<KnowledgeDocVO> search(@RequestParam("message") String message) {
        List<Document> documents = this.vectorStore.similaritySearch(SearchRequest.builder()
                .query(message)
                .topK(10)
                .build());
        return CollStreamUtil.toList(documents, KnowledgeDocVO::of);
    }

    /**
     * 搜索全部
     */
    @GetMapping("/search/all")
    @SuppressWarnings({"rawtypes", "unchecked"})
    public List<KnowledgeDocVO> searchAll() {
        try {
            SearchResponse<Map> response = this.elasticsearchClient.search(request -> request
                            .index(this.indexName)
                            .size(1000)
                            .query(query -> query.matchAll(matchAll -> matchAll)),
                    Map.class);
            return response.hits().hits().stream()
                    .map(hit -> {
                        Map source = hit.source();
                        if (source == null) {
                            return null;
                        }
                        Object metadata = source.get("metadata");
                        return KnowledgeDocVO.builder()
                                .id((String) source.get("id"))
                                .content((String) source.get("content"))
                                .metadata(metadata instanceof Map ? (Map<String, Object>) metadata : Map.of())
                                .score(hit.score())
                                .build();
                    })
                    .filter(Objects::nonNull)
                    .toList();
        } catch (IOException e) {
            log.error("查询向量库全部数据失败", e);
            throw new RuntimeException("查询向量库全部数据失败", e);
        }
    }
}
