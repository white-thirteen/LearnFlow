package com.tianji.mcp.server;

import com.tianji.api.client.course.CourseClient;
import com.tianji.api.client.search.SearchClient;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 使用真实 HTTP/SSE、工具发现和 tools/call；只替换下游业务服务，测试不依赖 Nacos 和数据库。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.mcp.client.enabled=false", "spring.cloud.nacos.discovery.enabled=false"
})
class McpCourseToolIntegrationTest {

    @LocalServerPort
    private int port;
    @MockBean
    private SearchClient searchClient;
    @MockBean
    private CourseClient courseClient;
    private McpSyncClient client;

    @BeforeEach
    void connect() {
        // 使用 SDK 的 JDK HTTP 客户端，避免在 WebMVC 服务端测试中引入另一套 Web 服务端传输。
        var transport = HttpClientSseClientTransport.builder("http://localhost:" + port)
                .sseEndpoint("/sse").build();
        client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("integration-client", "1.0.0"))
                .requestTimeout(Duration.ofSeconds(10)).build();
        client.initialize();
    }

    @AfterEach
    void disconnect() {
        if (client != null) {
            client.closeGracefully();
        }
    }

    @Test
    void discoversToolAndInvokesItThroughSpringAiCallback() {
        var course = new CourseSimpleInfoDTO();
        course.setId(101L);
        course.setName("Java入门");
        course.setStatus(2);
        course.setPrice(9900);
        when(searchClient.queryCoursesIdByName("Java")).thenReturn(List.of(101L));
        when(courseClient.getSimpleInfoList(List.of(101L))).thenReturn(List.of(course));

        assertEquals(List.of("search_courses"), client.listTools().tools().stream().map(McpSchema.Tool::name).toList());
        var callbacks = new SyncMcpToolCallbackProvider(List.of(client)).getToolCallbacks();
        assertEquals(1, callbacks.length);
        String result = callbacks[0].call("{\"keyword\":\"Java\"}");

        assertTrue(result.contains("101"));
        assertTrue(result.contains("9900"));
        verify(searchClient).queryCoursesIdByName("Java");
        verify(courseClient).getSimpleInfoList(List.of(101L));
    }

    @Test
    void invalidArgumentsProduceMcpToolError() {
        var result = client.callTool(new McpSchema.CallToolRequest("search_courses", Map.of("keyword", " ")));
        assertTrue(Boolean.TRUE.equals(result.isError()));
        verifyNoInteractions(searchClient, courseClient);
    }
}
