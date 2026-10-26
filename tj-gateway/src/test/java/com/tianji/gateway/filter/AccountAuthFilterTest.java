package com.tianji.gateway.filter;

import com.tianji.authsdk.gateway.util.AuthUtil;
import com.tianji.common.domain.R;
import com.tianji.common.domain.dto.LoginUserDTO;
import com.tianji.gateway.config.AuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static com.tianji.auth.common.constants.JwtConstants.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class AccountAuthFilterTest {

    @Test
    void forwardsVerifiedUserAndTokenWithMutatedExchange() {
        var auth = mock(AuthUtil.class);
        var user = new LoginUserDTO();
        user.setUserId(2L);
        var authenticated = R.ok(user);
        when(auth.parseToken("login-token")).thenReturn(authenticated);
        var properties = new AuthProperties();
        properties.setExcludePath(Set.of());
        var filter = new AccountAuthFilter(auth, properties);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/ais/chat")
                .header(AUTHORIZATION_HEADER, "login-token")
                .header(USER_HEADER, "forged-user")
                .header(TOKEN_HEADER, "forged-token"));
        var forwardedUser = new AtomicReference<String>();
        var forwardedToken = new AtomicReference<String>();

        filter.filter(exchange, forwarded -> {
            forwardedUser.set(forwarded.getRequest().getHeaders().getFirst(USER_HEADER));
            forwardedToken.set(forwarded.getRequest().getHeaders().getFirst(TOKEN_HEADER));
            return Mono.empty();
        }).block();

        assertEquals("2", forwardedUser.get());
        assertEquals("login-token", forwardedToken.get());
        verify(auth).checkAuth("POST:/ais/chat", authenticated);
    }
}
