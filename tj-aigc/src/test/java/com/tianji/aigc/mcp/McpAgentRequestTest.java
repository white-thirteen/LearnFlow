package com.tianji.aigc.mcp;

import com.tianji.aigc.agent.AbstractAgent;
import com.tianji.aigc.agent.ConsultAgent;
import com.tianji.aigc.agent.RouteAgent;
import com.tianji.aigc.config.AIProperties;
import com.tianji.aigc.config.SpringAIConfig;
import com.tianji.aigc.config.SystemPromptConfig;
import com.tianji.aigc.mcp.config.McpToolProperties;
import com.tianji.aigc.mcp.tool.McpToolRegistry;
import com.tianji.aigc.enums.AgentTypeEnum;
import com.tianji.aigc.service.ChatSessionService;
import com.tianji.aigc.tools.CourseTools;
import com.tianji.api.client.course.CourseClient;
import com.tianji.common.utils.UserContext;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 真实 SpringAIConfig / Agent / ChatClient / 工具执行管理器；模型决策和远程 MCP 响应可控，避免调用百炼。
 */
class McpAgentRequestTest {

    private static final String CLIENT_NAME = "tianji-aigc-mcp-client - tianji-course";
    private static final String REMOTE_NAME = McpToolUtils.prefixedToolName(CLIENT_NAME, "search_courses");

    @AfterEach
    void clearUser() {
        UserContext.removeUser();
        AbstractAgent.GENERATE_STATUS.clear();
    }

    @Test
    void consultRequestMergesLocalAndMcpToolsAndExecutesRemoteCallback() {
        var remote = mock(McpSyncClient.class);
        when(remote.getClientInfo()).thenReturn(new McpSchema.Implementation(CLIENT_NAME, "1.0.0"));
        when(remote.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(new McpSchema.Tool(
                "search_courses", "搜索课程", "{\"type\":\"object\",\"properties\":{\"keyword\":{\"type\":\"string\"}}}")), null));
        when(remote.callTool(any())).thenReturn(new McpSchema.CallToolResult("[{\"id\":101,\"name\":\"Java入门\"}]", false));
        var provider = new SyncMcpToolCallbackProvider(List.of(remote));
        var properties = new McpToolProperties();
        properties.getAgentTools().put(AgentTypeEnum.CONSULT, List.of(REMOTE_NAME));
        var registry = new McpToolRegistry(properties, true, provider::getToolCallbacks);
        var model = new ToolCallingTestModel();
        var prompts = prompts();
        var vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(new Document("可使用课程工具实时查询")));
        var agent = new ConsultAgent(prompts, vectorStore, new CourseTools(mock(CourseClient.class)));
        wire(agent, model, registry);

        var events = agent.processStream("搜索Java课程", "mcp-session").collectList().block(Duration.ofSeconds(10));

        assertNotNull(events);
        assertTrue(events.stream().anyMatch(event -> String.valueOf(event.getEventData()).contains("Java入门")));
        assertTrue(model.toolNames.contains("queryCourseById"), "本地工具应保留");
        assertTrue(model.toolNames.contains(REMOTE_NAME), "远程工具应挂载到同一次请求");
        verify(remote).callTool(new McpSchema.CallToolRequest("search_courses", Map.of("keyword", "Java")));
    }

    @Test
    void routeRequestDoesNotReceiveBusinessTools() {
        var model = new ToolCallingTestModel();
        var registry = new McpToolRegistry(new McpToolProperties(), true,
                () -> { throw new AssertionError("路由 Agent 不应发现远程工具"); });
        var agent = new RouteAgent(prompts());
        wire(agent, model, registry);

        assertEquals("CONSULT", agent.process("搜索Java课程", "route-session"));
        assertTrue(model.toolNames.isEmpty());
    }

    private static SystemPromptConfig prompts() {
        var prompts = new SystemPromptConfig(null, new AIProperties());
        prompts.getConsultAgentSystemMessage().set("你是课程咨询助手，按用户要求搜索课程。");
        prompts.getRouteAgentSystemMessage().set("分类问题，返回 CONSULT。");
        return prompts;
    }

    private static void wire(AbstractAgent agent, ChatModel model, McpToolRegistry registry) {
        UserContext.setUser(1L);
        var memory = MessageWindowChatMemory.builder().build();
        ChatClient client = new SpringAIConfig().chatClient(ChatClient.builder(model), new SimpleLoggerAdvisor());
        ReflectionTestUtils.setField(agent, "chatClient", client);
        ReflectionTestUtils.setField(agent, "chatMemory", memory);
        ReflectionTestUtils.setField(agent, "messageChatMemoryAdvisor", MessageChatMemoryAdvisor.builder(memory).build());
        ReflectionTestUtils.setField(agent, "chatSessionService", mock(ChatSessionService.class));
        ReflectionTestUtils.setField(agent, "mcpToolRegistry", registry);
    }

    private static class ToolCallingTestModel implements ChatModel {
        private List<String> toolNames = List.of();

        @Override
        public ChatOptions getDefaultOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            var options = (ToolCallingChatOptions) prompt.getOptions();
            toolNames = options.getToolCallbacks().stream().map(callback -> callback.getToolDefinition().name()).toList();
            if (!toolNames.contains(REMOTE_NAME)) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("CONSULT"))));
            }
            var request = new ChatResponse(List.of(new Generation(new AssistantMessage("", Map.of(), List.of(
                    new AssistantMessage.ToolCall("call-1", "function", REMOTE_NAME, "{\"keyword\":\"Java\"}"))))));
            var execution = ToolCallingManager.builder().build().executeToolCalls(prompt, request);
            String result = execution.conversationHistory().stream()
                    .filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                    .flatMap(message -> message.getResponses().stream()).map(ToolResponseMessage.ToolResponse::responseData)
                    .findFirst().orElseThrow();
            assertTrue(result.contains("101"));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("找到课程：Java入门"))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> Flux.just(call(prompt)));
        }
    }
}
