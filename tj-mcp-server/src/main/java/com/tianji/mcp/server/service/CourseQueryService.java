package com.tianji.mcp.server.service;

import com.tianji.api.client.course.CourseClient;
import com.tianji.api.client.search.SearchClient;
import com.tianji.api.constants.CourseStatus;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import com.tianji.mcp.server.dto.response.CourseSummaryDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CourseQueryService {

    private static final int MAX_RESULTS = 5;

    private final SearchClient searchClient;
    private final CourseClient courseClient;

    public List<CourseSummaryDTO> searchCourses(String keyword) {
        Assert.hasText(keyword, "课程关键词不能为空");
        String normalized = keyword.trim();
        Assert.isTrue(normalized.length() <= 100, "课程关键词不能超过100个字符");

        // 真实业务查询：先搜索课程 ID，再批量获取摘要，不在 MCP 服务中维护课程数据。
        var matches = searchClient.queryCoursesIdByName(normalized);
        Assert.state(matches != null, "课程搜索服务返回了空响应");
        var ids = matches.stream().filter(Objects::nonNull).distinct().limit(MAX_RESULTS).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        var courses = courseClient.getSimpleInfoList(ids);
        Assert.state(courses != null, "课程服务返回了空响应");
        var byId = courses.stream()
                .filter(Objects::nonNull)
                .filter(course -> Objects.equals(course.getStatus(), CourseStatus.SHELF.getValue()))
                .collect(Collectors.toMap(CourseSimpleInfoDTO::getId, Function.identity(), (first, next) -> first));

        // 保留搜索顺序，只返回已上架课程，避免过期索引暴露下架课程。
        return ids.stream().map(byId::get).filter(Objects::nonNull)
                .map(course -> new CourseSummaryDTO(course.getId(), course.getName(), course.getCoverUrl(),
                        course.getPrice(), course.getFree(), course.getSectionNum()))
                .toList();
    }
}
