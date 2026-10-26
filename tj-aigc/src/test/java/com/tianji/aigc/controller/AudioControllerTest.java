package com.tianji.aigc.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.aigc.config.DashScopeProperties;
import com.tianji.aigc.service.impl.AudioServiceImpl;
import com.tianji.aigc.service.impl.DashScopeTtsClient;
import com.tianji.authsdk.resource.interceptors.UserInfoInterceptor;
import com.tianji.common.autoconfigure.mvc.advice.CommonExceptionAdvice;
import com.tianji.common.autoconfigure.mvc.advice.WrapperResponseBodyAdvice;
import com.tianji.common.autoconfigure.mvc.converter.WrapperResponseMessageConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static com.tianji.auth.common.constants.JwtConstants.USER_HEADER;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AudioControllerTest {

    private MockMvc mvc;
    private DashScopeProperties properties;
    private DashScopeTtsClient client;

    @BeforeEach
    void setUp() {
        properties = new DashScopeProperties();
        properties.setKey("test-key");
        client = mock(DashScopeTtsClient.class);
        var jackson = new MappingJackson2HttpMessageConverter();
        var commonAdvice = new CommonExceptionAdvice();
        mvc = MockMvcBuilders.standaloneSetup(new AudioController(new AudioServiceImpl(properties, client), jackson.getObjectMapper()))
                .setControllerAdvice(new AudioExceptionAdvice(commonAdvice), commonAdvice, new WrapperResponseBodyAdvice())
                .setMessageConverters(new ByteArrayHttpMessageConverter(), new StringHttpMessageConverter(),
                        new WrapperResponseMessageConverter(jackson), jackson)
                .addInterceptors(new UserInfoInterceptor())
                .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/audio/tts", "/audio/tts-stream"})
    void gatewayGetsUnwrappedMp3FramesWithoutContentLengthAndPreservesUtf8(String path) throws Exception {
        when(client.synthesize("你好，Java！", "test-key", properties.getTts()))
                .thenReturn(Flux.just(new byte[]{73, 68, 51}, new byte[]{1, 2}));
        var pending = mvc.perform(gatewayRequest(path).header(USER_HEADER, "2").content("你好，Java！"))
                .andExpect(request().asyncStarted()).andReturn();
        awaitResult(pending);
        mvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(content().contentType("audio/mp3"))
                .andExpect(content().bytes(new byte[]{73, 68, 51, 1, 2}))
                .andExpect(header().doesNotExist("Content-Length"))
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(client).synthesize("你好，Java！", "test-key", properties.getTts());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/audio/tts", "/audio/tts-stream"})
    void jsonContentTypeWithRawTextStillSendsOriginalText(String path) throws Exception {
        String text = " 你好，Java！\n第二行。 ";
        when(client.synthesize(text, "test-key", properties.getTts())).thenReturn(Flux.just(new byte[]{1, 2}));
        var pending = mvc.perform(gatewayRequest(path).header(USER_HEADER, "2")
                        .contentType(MediaType.APPLICATION_JSON).content(text.getBytes(StandardCharsets.UTF_8)))
                .andExpect(request().asyncStarted()).andReturn();
        awaitResult(pending);
        mvc.perform(asyncDispatch(pending)).andExpect(content().contentType("audio/mp3"))
                .andExpect(content().bytes(new byte[]{1, 2}));
        verify(client).synthesize(text, "test-key", properties.getTts());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/audio/tts", "/audio/tts-stream"})
    void jsonStringBodyIsDecodedBeforeSynthesis(String path) throws Exception {
        String text = "你好，\"Java\"！\n第二行。";
        when(client.synthesize(text, "test-key", properties.getTts())).thenReturn(Flux.just(new byte[]{1, 2}));
        var pending = mvc.perform(gatewayRequest(path).header(USER_HEADER, "2")
                        .contentType(MediaType.APPLICATION_JSON).content(new ObjectMapper().writeValueAsBytes(text)))
                .andExpect(request().asyncStarted()).andReturn();
        awaitResult(pending);
        mvc.perform(asyncDispatch(pending)).andExpect(content().contentType("audio/mp3"))
                .andExpect(content().bytes(new byte[]{1, 2}));
        verify(client).synthesize(text, "test-key", properties.getTts());
    }

    @Test
    void textPlainPreservesLiteralQuotes() throws Exception {
        String text = "\"你好\"";
        when(client.synthesize(text, "test-key", properties.getTts())).thenReturn(Flux.just(new byte[]{1}));
        var pending = mvc.perform(gatewayRequest().header(USER_HEADER, "2").content(text))
                .andExpect(request().asyncStarted()).andReturn();
        awaitResult(pending);
        mvc.perform(asyncDispatch(pending)).andExpect(content().bytes(new byte[]{1}));
        verify(client).synthesize(text, "test-key", properties.getTts());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"text\":\"你好\"}", "[\"你好\"]", "\"未闭合", "\"你好\" 123", "\" \""})
    void invalidJsonBodyReturnsParameterErrorWithoutCallingCloud(String body) throws Exception {
        mvc.perform(gatewayRequest("/audio/tts-stream").header(USER_HEADER, "2")
                        .contentType(MediaType.APPLICATION_JSON).content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("code").value(400));
        verifyNoInteractions(client);
    }

    @Test
    void firstFrameReachesHttpResponseBeforeUpstreamFinishes() throws Exception {
        Sinks.Many<byte[]> source = Sinks.many().unicast().onBackpressureBuffer();
        when(client.synthesize(anyString(), anyString(), any())).thenReturn(source.asFlux());
        var pending = mvc.perform(gatewayRequest().header(USER_HEADER, "2").content("你好"))
                .andExpect(request().asyncStarted()).andReturn();
        source.tryEmitNext(new byte[]{1, 2}).orThrow();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertArrayEquals(new byte[]{1, 2}, pending.getResponse().getContentAsByteArray()));
        assertTrue(source.currentSubscriberCount() > 0);
        assertTrue(pending.getResponse().isCommitted());
        source.tryEmitNext(new byte[]{3}).orThrow();
        source.tryEmitComplete().orThrow();
        awaitResult(pending);
        mvc.perform(asyncDispatch(pending)).andExpect(content().bytes(new byte[]{1, 2, 3}));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/audio/tts", "/audio/tts-stream"})
    void missingLoginReturnsJsonEvenWhenClientOnlyAcceptsAudio(String path) throws Exception {
        mvc.perform(gatewayRequest(path).accept("audio/mp3").content("你好"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("code").value(401));
        verifyNoInteractions(client);
    }

    @Test
    void emptyAndMissingBodyReturnJsonParameterError() throws Exception {
        for (String text : new String[]{"", " \n"}) {
            mvc.perform(gatewayRequest().header(USER_HEADER, "2").content(text))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("code").value(400));
        }
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/audio/tts", "/audio/tts-stream"})
    void upstreamFailureBeforeFirstFrameReturnsJsonOnAsyncDispatch(String path) throws Exception {
        when(client.synthesize(anyString(), anyString(), any())).thenReturn(Flux.error(new IllegalStateException("private")));
        var pending = mvc.perform(gatewayRequest(path).header(USER_HEADER, "2").content("你好"))
                .andExpect(request().asyncStarted()).andReturn();
        awaitResult(pending);
        mvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("code").value(500))
                .andExpect(jsonPath("msg").value("语音合成失败，请稍后重试"));
    }

    @Test
    void upstreamFailureAfterFirstFrameDoesNotAppendJsonToMp3() throws Exception {
        Sinks.Many<byte[]> source = Sinks.many().unicast().onBackpressureBuffer();
        when(client.synthesize(anyString(), anyString(), any())).thenReturn(source.asFlux());
        var pending = mvc.perform(gatewayRequest().header(USER_HEADER, "2").content("你好"))
                .andExpect(request().asyncStarted()).andReturn();
        source.tryEmitNext(new byte[]{73, 68, 51}).orThrow();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertTrue(pending.getResponse().isCommitted()));
        source.tryEmitError(new IllegalStateException("upstream failed")).orThrow();
        awaitResult(pending);
        mvc.perform(asyncDispatch(pending))
                .andExpect(content().contentType("audio/mp3"))
                .andExpect(content().bytes(new byte[]{73, 68, 51}));
        assertEquals(0, source.currentSubscriberCount());
    }

    @Test
    void timeoutBeforeFirstFrameReturnsJsonWith504BusinessCode() throws Exception {
        properties.getTts().setTimeoutMs(150);
        when(client.synthesize(anyString(), anyString(), any())).thenReturn(Flux.never());
        var pending = mvc.perform(gatewayRequest().header(USER_HEADER, "2").content("你好"))
                .andExpect(request().asyncStarted()).andReturn();
        awaitResult(pending);
        mvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("code").value(504));
    }

    @Test
    void emptyAudioReturnsJsonInsteadOfAnEmptySuccessfulStream() throws Exception {
        when(client.synthesize(anyString(), anyString(), any())).thenReturn(Flux.empty());
        var pending = mvc.perform(gatewayRequest().header(USER_HEADER, "2").content("你好"))
                .andExpect(request().asyncStarted()).andReturn();
        awaitResult(pending);
        mvc.perform(asyncDispatch(pending))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("code").value(500));
    }

    @Test
    void missingConfigurationReturnsJsonWithoutCallingCloud() throws Exception {
        properties.setKey(null);
        mvc.perform(gatewayRequest().header(USER_HEADER, "2").content("你好"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("code").value(500));
        verifyNoInteractions(client);
    }

    @Test
    void directServiceErrorsKeepOriginalHttpStatusAndPlainText() throws Exception {
        mvc.perform(post("/audio/tts").contentType("text/plain;charset=UTF-8").accept("audio/mp3").content("你好"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN));
    }

    private void awaitResult(MvcResult pending) {
        pending.getAsyncResult(5000);
    }

    private MockHttpServletRequestBuilder gatewayRequest() {
        return gatewayRequest("/audio/tts");
    }

    private MockHttpServletRequestBuilder gatewayRequest(String path) {
        return post(path)
                .header("x-request-from", "gateway")
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .accept("audio/mp3", MediaType.APPLICATION_JSON_VALUE);
    }
}
