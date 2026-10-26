package com.tianji.mcp.server.tool;

import com.tianji.mcp.server.dto.response.CourseSummaryDTO;
import com.tianji.mcp.server.service.CourseQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CourseQueryTools {

    private final CourseQueryService courseQueryService;

    @Tool(name = "search_courses", description = "按课程关键词搜索天机学堂当前已上架课程，最多返回5条。"
            + "用于选课、课程推荐和课程名称查询；结果包含课程ID、名称、封面、价格priceFen（单位分）、是否免费及小节数。"
            + "没有匹配课程时返回空列表，不生成虚构课程。")
    public List<CourseSummaryDTO> searchCourses(
            @ToolParam(description = "课程关键词，例如Java或Spring，长度1至100个字符") String keyword) {
        log.info("MCP tools/call: search_courses");
        var courses = courseQueryService.searchCourses(keyword);
        log.info("MCP search_courses completed, count={}", courses.size());
        return courses;
    }
}
