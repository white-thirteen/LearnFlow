package com.tianji.authsdk.resource.interceptors;

import com.tianji.auth.common.constants.JwtConstants;
import com.tianji.common.utils.TokenContext;
import com.tianji.common.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class UserInfoInterceptorTest {

    @AfterEach
    void clearContext() {
        UserContext.removeUser();
        TokenContext.removeToken();
    }

    @Test
    void requestWithoutIdentityDoesNotInheritPreviousToken() throws Exception {
        UserContext.setUser(2L);
        TokenContext.setToken("previous-token");
        new UserInfoInterceptor().preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object());
        assertNull(UserContext.getUser());
        assertNull(TokenContext.getToken());
    }

    @Test
    void startingAsyncResponseClearsOriginalServletThread() {
        UserContext.setUser(2L);
        TokenContext.setToken("sse-token");
        new UserInfoInterceptor().afterConcurrentHandlingStarted(
                new MockHttpServletRequest(), new MockHttpServletResponse(), new Object());
        assertNull(UserContext.getUser());
        assertNull(TokenContext.getToken());
    }

    @Test
    void completionClearsBothIdentityAndToken() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(JwtConstants.USER_HEADER, "2");
        request.addHeader(JwtConstants.TOKEN_HEADER, "current-token");
        var response = new MockHttpServletResponse();
        var interceptor = new UserInfoInterceptor();
        interceptor.preHandle(request, response, new Object());
        assertEquals(2L, UserContext.getUser());
        assertEquals("current-token", TokenContext.getToken());
        interceptor.afterCompletion(request, response, new Object(), null);
        assertNull(UserContext.getUser());
        assertNull(TokenContext.getToken());
    }
}
