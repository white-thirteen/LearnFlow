package com.tianji.mcp.server.dto.response;

/**
 * 对外公开的课程摘要。价格单位为分，不包含内部编辑字段。
 */
public record CourseSummaryDTO(Long id, String name, String coverUrl, Integer priceFen,
                               Boolean free, Integer sectionNum) {
}
