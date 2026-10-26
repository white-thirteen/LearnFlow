package com.tianji.aigc.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.document.Document;

import java.util.Map;

/**
 * 向量库（知识库）文档
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocVO {

    /**
     * 文档id
     */
    private String id;

    /**
     * 文档内容
     */
    private String content;

    /**
     * 文档元数据
     */
    private Map<String, Object> metadata;

    /**
     * 相似度得分，仅搜索时有值
     */
    private Double score;

    public static KnowledgeDocVO of(Document document) {
        return KnowledgeDocVO.builder()
                .id(document.getId())
                .content(document.getText())
                .metadata(document.getMetadata())
                .score(document.getScore())
                .build();
    }
}
