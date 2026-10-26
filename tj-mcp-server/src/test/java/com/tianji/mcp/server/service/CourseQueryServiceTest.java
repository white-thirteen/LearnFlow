package com.tianji.mcp.server.service;

import com.tianji.api.client.course.CourseClient;
import com.tianji.api.client.search.SearchClient;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CourseQueryServiceTest {

    private final SearchClient search = mock(SearchClient.class);
    private final CourseClient courses = mock(CourseClient.class);
    private final CourseQueryService service = new CourseQueryService(search, courses);

    @Test
    void keepsSearchOrderAndFiltersUnpublishedCourses() {
        when(search.queryCoursesIdByName("Java")).thenReturn(List.of(2L, 1L, 3L, 2L));
        var first = course(1L, "Java基础", 2);
        first.setPrice(19900);
        when(courses.getSimpleInfoList(List.of(2L, 1L, 3L)))
                .thenReturn(List.of(first, course(3L, "未上架课程", 1), course(2L, "Java进阶", 2)));

        var result = service.searchCourses(" Java ");

        assertEquals(List.of(2L, 1L), result.stream().map(item -> item.id()).toList());
        assertEquals(19900, result.get(1).priceFen());
    }

    @Test
    void validatesBeforeCallingBusinessServices() {
        assertThrows(IllegalArgumentException.class, () -> service.searchCourses("  "));
        assertThrows(IllegalArgumentException.class, () -> service.searchCourses("a".repeat(101)));
        verifyNoInteractions(search, courses);
    }

    @Test
    void emptySearchDoesNotCallCourseService() {
        when(search.queryCoursesIdByName("未知课程")).thenReturn(List.of());
        assertTrue(service.searchCourses("未知课程").isEmpty());
        verifyNoInteractions(courses);
    }

    @Test
    void limitsTheBatchSize() {
        when(search.queryCoursesIdByName("Java")).thenReturn(List.of(1L, 2L, 3L, 4L, 5L, 6L));
        when(courses.getSimpleInfoList(List.of(1L, 2L, 3L, 4L, 5L))).thenReturn(List.of());
        assertTrue(service.searchCourses("Java").isEmpty());
        verify(courses).getSimpleInfoList(List.of(1L, 2L, 3L, 4L, 5L));
    }

    @Test
    void businessFailureIsNotReportedAsNoMatches() {
        when(search.queryCoursesIdByName("Java")).thenThrow(new IllegalStateException("search unavailable"));
        assertThrows(IllegalStateException.class, () -> service.searchCourses("Java"));
        verifyNoInteractions(courses);
    }

    private static CourseSimpleInfoDTO course(long id, String name, int status) {
        var course = new CourseSimpleInfoDTO();
        course.setId(id);
        course.setName(name);
        course.setStatus(status);
        return course;
    }
}
