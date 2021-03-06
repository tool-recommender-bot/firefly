package com.firefly.server.http2.router.impl;

import com.firefly.codec.oauth2.model.*;
import com.firefly.codec.oauth2.model.message.types.GrantType;
import com.firefly.codec.oauth2.model.message.types.ResponseType;
import com.firefly.server.http2.SimpleRequest;
import com.firefly.server.http2.SimpleResponse;
import com.firefly.server.http2.router.HTTPSession;
import com.firefly.server.http2.router.RouterManager;
import com.firefly.server.http2.router.RoutingContext;
import com.firefly.server.http2.router.handler.template.TemplateHandlerSPILoader;
import com.firefly.server.http2.router.spi.HTTPBodyHandlerSPI;
import com.firefly.server.http2.router.spi.HTTPSessionHandlerSPI;
import com.firefly.server.http2.router.spi.TemplateHandlerSPI;
import com.firefly.utils.StringUtils;
import com.firefly.utils.concurrent.Promise;
import com.firefly.utils.function.Action1;
import com.firefly.utils.json.JsonArray;
import com.firefly.utils.json.JsonObject;
import com.firefly.utils.lang.GenericTypeReference;

import javax.servlet.http.Part;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import static com.firefly.codec.oauth2.model.OAuth.*;

/**
 * @author Pengtao Qiu
 */
public class RoutingContextImpl implements RoutingContext {

    private final SimpleRequest request;
    private final NavigableSet<RouterManager.RouterMatchResult> routers;
    private volatile RouterManager.RouterMatchResult current;
    private volatile HTTPBodyHandlerSPI httpBodyHandlerSPI;
    private volatile HTTPSessionHandlerSPI httpSessionHandlerSPI;
    private final TemplateHandlerSPI templateHandlerSPI = TemplateHandlerSPILoader.getInstance().getTemplateHandlerSPI();
    private volatile boolean asynchronousRead;
    private volatile ConcurrentLinkedDeque<Promise<?>> handlerPromiseQueue;
    private AuthorizationRequest authorizationRequest;
    private AuthorizationCodeAccessTokenRequest authorizationCodeAccessTokenRequest;
    private PasswordAccessTokenRequest passwordAccessTokenRequest;
    private ClientCredentialAccessTokenRequest clientCredentialAccessTokenRequest;
    private RefreshingTokenRequest refreshingTokenRequest;

    public RoutingContextImpl(SimpleRequest request, NavigableSet<RouterManager.RouterMatchResult> routers) {
        this.request = request;
        this.routers = routers;
    }

    @Override
    public Object getAttribute(String key) {
        return request.get(key);
    }

    @Override
    public Object setAttribute(String key, Object value) {
        return request.put(key, value);
    }

    @Override
    public Object removeAttribute(String key) {
        return request.remove(key);
    }

    @Override
    public ConcurrentHashMap<String, Object> getAttributes() {
        return request.getAttributes();
    }

    @Override
    public SimpleResponse getResponse() {
        return request.getResponse();
    }

    @Override
    public SimpleResponse getAsyncResponse() {
        return request.getAsyncResponse();
    }

    @Override
    public SimpleRequest getRequest() {
        return request;
    }

    @Override
    public String getRouterParameter(String name) {
        return current.getParameters().get(name);
    }

    @Override
    public RoutingContext content(Action1<ByteBuffer> content) {
        request.content(content);
        asynchronousRead = true;
        return this;
    }

    @Override
    public RoutingContext contentComplete(Action1<SimpleRequest> contentComplete) {
        request.contentComplete(contentComplete);
        asynchronousRead = true;
        return this;
    }

    @Override
    public RoutingContext messageComplete(Action1<SimpleRequest> messageComplete) {
        request.messageComplete(messageComplete);
        asynchronousRead = true;
        return this;
    }

    @Override
    public boolean isAsynchronousRead() {
        return asynchronousRead;
    }

    @Override
    public boolean next() {
        current = routers.pollFirst();
        return Optional.ofNullable(current)
                       .map(RouterManager.RouterMatchResult::getRouter)
                       .map(c -> (RouterImpl) c)
                       .map(RouterImpl::getHandler)
                       .map(handler -> {
                           handler.handle(this);
                           return true;
                       })
                       .orElse(false);
    }

    @Override
    public boolean hasNext() {
        return !routers.isEmpty();
    }

    @Override
    public <T> RoutingContext complete(Promise<T> promise) {
        ConcurrentLinkedDeque<Promise<T>> queue = createHandlerPromiseQueueIfAbsent();
        queue.push(promise);
        return this;
    }

    @Override
    public <T> boolean next(Promise<T> promise) {
        return complete(promise).next();
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> void succeed(T t) {
        Optional.ofNullable(handlerPromiseQueue)
                .map(ConcurrentLinkedDeque::pop)
                .map(p -> (Promise<T>) p)
                .ifPresent(p -> p.succeeded(t));
    }

    @Override
    public void fail(Throwable x) {
        Optional.ofNullable(handlerPromiseQueue)
                .map(ConcurrentLinkedDeque::pop)
                .ifPresent(p -> p.failed(x));
    }

    @SuppressWarnings("unchecked")
    private <T> ConcurrentLinkedDeque<Promise<T>> createHandlerPromiseQueueIfAbsent() {
        if (handlerPromiseQueue == null) {
            handlerPromiseQueue = new ConcurrentLinkedDeque<>();
        }
        return (ConcurrentLinkedDeque) handlerPromiseQueue;
    }

    @Override
    public void close() throws IOException {
        request.getResponse().close();
    }

    @Override
    public String getParameter(String name) {
        return Optional.ofNullable(httpBodyHandlerSPI)
                       .map(s -> s.getParameter(name))
                       .orElse(null);
    }

    @Override
    public List<String> getParameterValues(String name) {
        return Optional.ofNullable(httpBodyHandlerSPI)
                       .map(s -> s.getParameterValues(name))
                       .orElse(Collections.emptyList());
    }

    @Override
    public Map<String, List<String>> getParameterMap() {
        return Optional.ofNullable(httpBodyHandlerSPI)
                       .map(HTTPBodyHandlerSPI::getParameterMap)
                       .orElse(Collections.emptyMap());
    }

    @Override
    public Collection<Part> getParts() {
        return Optional.ofNullable(httpBodyHandlerSPI)
                       .map(HTTPBodyHandlerSPI::getParts)
                       .orElse(Collections.emptyList());
    }

    @Override
    public Part getPart(String name) {
        return Optional.ofNullable(httpBodyHandlerSPI)
                       .map(s -> s.getPart(name))
                       .orElse(null);
    }

    @Override
    public InputStream getInputStream() {
        return Optional.ofNullable(httpBodyHandlerSPI)
                       .map(HTTPBodyHandlerSPI::getInputStream)
                       .orElse(null);
    }

    @Override
    public BufferedReader getBufferedReader() {
        return Optional.ofNullable(httpBodyHandlerSPI)
                       .map(HTTPBodyHandlerSPI::getBufferedReader)
                       .orElse(null);
    }

    @Override
    public String getStringBody(String charset) {
        return Optional.ofNullable(httpBodyHandlerSPI)
                       .map(s -> s.getStringBody(charset))
                       .orElseGet(() -> request.getStringBody(charset));
    }

    @Override
    public String getStringBody() {
        return Optional.ofNullable(httpBodyHandlerSPI)
                       .map(HTTPBodyHandlerSPI::getStringBody)
                       .orElseGet(request::getStringBody);
    }

    @Override
    public <T> T getJsonBody(Class<T> clazz) {
        return Optional.ofNullable(httpBodyHandlerSPI)
                       .map(s -> s.getJsonBody(clazz))
                       .orElseGet(() -> request.getJsonBody(clazz));
    }

    @Override
    public <T> T getJsonBody(GenericTypeReference<T> typeReference) {
        return Optional.ofNullable(httpBodyHandlerSPI)
                       .map(s -> s.getJsonBody(typeReference))
                       .orElseGet(() -> request.getJsonBody(typeReference));

    }

    @Override
    public JsonObject getJsonObjectBody() {
        return Optional.ofNullable(httpBodyHandlerSPI)
                       .map(HTTPBodyHandlerSPI::getJsonObjectBody)
                       .orElseGet(request::getJsonObjectBody);
    }

    @Override
    public JsonArray getJsonArrayBody() {
        return Optional.ofNullable(httpBodyHandlerSPI)
                       .map(HTTPBodyHandlerSPI::getJsonArrayBody)
                       .orElseGet(request::getJsonArrayBody);
    }

    public void setHTTPBodyHandlerSPI(HTTPBodyHandlerSPI httpBodyHandlerSPI) {
        this.httpBodyHandlerSPI = httpBodyHandlerSPI;
    }

    @Override
    public CompletableFuture<HTTPSession> getSessionById(String id) {
        return Optional.ofNullable(httpSessionHandlerSPI).map(s -> s.getSessionById(id)).orElse(null);
    }

    @Override
    public CompletableFuture<HTTPSession> getSession() {
        return Optional.ofNullable(httpSessionHandlerSPI).map(HTTPSessionHandlerSPI::getSession).orElse(null);
    }

    @Override
    public CompletableFuture<HTTPSession> getSession(boolean create) {
        return Optional.ofNullable(httpSessionHandlerSPI).map(s -> s.getSession(create)).orElse(null);
    }

    @Override
    public CompletableFuture<HTTPSession> getAndCreateSession(int maxAge) {
        return Optional.ofNullable(httpSessionHandlerSPI).map(s -> s.getAndCreateSession(maxAge)).orElse(null);
    }

    @Override
    public CompletableFuture<HTTPSession> getAndCreateSession(int maxAge, String domain) {
        return Optional.ofNullable(httpSessionHandlerSPI).map(s -> s.getAndCreateSession(maxAge, domain)).orElse(null);
    }

    @Override
    public CompletableFuture<HTTPSession> createSession(int maxAge) {
        return Optional.ofNullable(httpSessionHandlerSPI).map(s -> s.createSession(maxAge)).orElse(null);
    }

    @Override
    public CompletableFuture<HTTPSession> createSession(int maxAge, String domain) {
        return Optional.ofNullable(httpSessionHandlerSPI).map(s -> s.createSession(maxAge, domain)).orElse(null);
    }

    @Override
    public CompletableFuture<Integer> getSessionSize() {
        return Optional.ofNullable(httpSessionHandlerSPI).map(HTTPSessionHandlerSPI::getSessionSize).orElse(null);
    }

    @Override
    public CompletableFuture<Boolean> removeSessionById(String id) {
        return Optional.ofNullable(httpSessionHandlerSPI).map(s -> s.removeSessionById(id)).orElse(null);
    }

    @Override
    public CompletableFuture<Boolean> removeSession() {
        return Optional.ofNullable(httpSessionHandlerSPI)
                       .map(HTTPSessionHandlerSPI::removeSession)
                       .orElse(null);
    }

    @Override
    public CompletableFuture<Boolean> updateSession(HTTPSession httpSession) {
        return Optional.ofNullable(httpSessionHandlerSPI)
                       .map(s -> s.updateSession(httpSession))
                       .orElse(null);
    }

    @Override
    public boolean isRequestedSessionIdFromURL() {
        return Optional.ofNullable(httpSessionHandlerSPI)
                       .map(HTTPSessionHandlerSPI::isRequestedSessionIdFromURL)
                       .orElse(false);
    }

    @Override
    public boolean isRequestedSessionIdFromCookie() {
        return Optional.ofNullable(httpSessionHandlerSPI)
                       .map(HTTPSessionHandlerSPI::isRequestedSessionIdFromCookie)
                       .orElse(false);
    }

    @Override
    public String getRequestedSessionId() {
        return Optional.ofNullable(httpSessionHandlerSPI)
                       .map(HTTPSessionHandlerSPI::getRequestedSessionId)
                       .orElse(null);
    }

    @Override
    public String getSessionIdParameterName() {
        return Optional.ofNullable(httpSessionHandlerSPI)
                       .map(HTTPSessionHandlerSPI::getSessionIdParameterName)
                       .orElse(null);
    }

    public void setHTTPSessionHandlerSPI(HTTPSessionHandlerSPI httpSessionHandlerSPI) {
        this.httpSessionHandlerSPI = httpSessionHandlerSPI;
    }

    @Override
    public void renderTemplate(String resourceName, Object scope) {
        templateHandlerSPI.renderTemplate(this, resourceName, scope);
    }

    @Override
    public void renderTemplate(String resourceName, Object[] scopes) {
        templateHandlerSPI.renderTemplate(this, resourceName, scopes);
    }

    @Override
    public void renderTemplate(String resourceName, List<Object> scopes) {
        templateHandlerSPI.renderTemplate(this, resourceName, scopes);
    }

    @Override
    public AuthorizationRequest getAuthorizationRequest() {
        if (authorizationRequest == null) {
            AuthorizationRequest req = new AuthorizationRequest();
            req.setResponseType(getParameter(OAUTH_RESPONSE_TYPE));
            req.setClientId(getParameter(OAUTH_CLIENT_ID));
            req.setRedirectUri(getParameter(OAUTH_REDIRECT_URI));
            req.setScope(getParameter(OAUTH_SCOPE));
            req.setState(getParameter(OAUTH_STATE));

            if (!StringUtils.hasText(req.getResponseType())) {
                throw OAuth.oauthProblem(OAuthError.CodeResponse.INVALID_REQUEST)
                           .description("The response type must be not null.")
                           .state(req.getState());
            }

            if (ResponseType.from(req.getResponseType()) == null) {
                throw OAuth.oauthProblem(OAuthError.CodeResponse.UNSUPPORTED_RESPONSE_TYPE)
                           .description("The response type '" + req.getResponseType() + "' is not supported.")
                           .state(req.getState());
            }
            authorizationRequest = req;
        }
        return authorizationRequest;
    }

    @Override
    public AuthorizationCodeAccessTokenRequest getAuthorizationCodeAccessTokenRequest() {
        if (authorizationCodeAccessTokenRequest == null) {
            AuthorizationCodeAccessTokenRequest req = new AuthorizationCodeAccessTokenRequest();
            req.setGrantType(getParameter(OAUTH_GRANT_TYPE));
            req.setClientId(getParameter(OAUTH_CLIENT_ID));
            req.setCode(getParameter(OAUTH_CODE));
            req.setRedirectUri(getParameter(OAUTH_REDIRECT_URI));

            if (!StringUtils.hasText(req.getCode())) {
                throw OAuth.oauthProblem(OAuthError.TokenResponse.INVALID_REQUEST)
                           .description("The code must be not null.");
            }
            if (!StringUtils.hasText(req.getGrantType())) {
                throw OAuth.oauthProblem(OAuthError.TokenResponse.INVALID_REQUEST)
                           .description("The grant type must be not null.");
            }
            if (!req.getGrantType().equals(GrantType.AUTHORIZATION_CODE.toString())) {
                throw OAuth.oauthProblem(OAuthError.TokenResponse.INVALID_GRANT)
                           .description("The grant type must be 'authorization_code'.");
            }
            authorizationCodeAccessTokenRequest = req;
        }
        return authorizationCodeAccessTokenRequest;
    }

    @Override
    public PasswordAccessTokenRequest getPasswordAccessTokenRequest() {
        if (passwordAccessTokenRequest == null) {
            PasswordAccessTokenRequest req = new PasswordAccessTokenRequest();
            req.setGrantType(getParameter(OAUTH_GRANT_TYPE));
            req.setUsername(getParameter(OAUTH_USERNAME));
            req.setPassword(getParameter(OAUTH_PASSWORD));
            req.setScope(getParameter(OAUTH_SCOPE));

            if (!StringUtils.hasText(req.getUsername())) {
                throw OAuth.oauthProblem(OAuthError.TokenResponse.INVALID_REQUEST)
                           .description("The username must be not null.");
            }
            if (!StringUtils.hasText(req.getPassword())) {
                throw OAuth.oauthProblem(OAuthError.TokenResponse.INVALID_REQUEST)
                           .description("The password must be not null.");
            }
            if (!StringUtils.hasText(req.getGrantType())) {
                throw OAuth.oauthProblem(OAuthError.TokenResponse.INVALID_REQUEST)
                           .description("The grant type must be not null.");
            }
            if (!req.getGrantType().equals(GrantType.PASSWORD.toString())) {
                throw OAuth.oauthProblem(OAuthError.TokenResponse.INVALID_GRANT)
                           .description("The grant type must be 'password'.");
            }
            passwordAccessTokenRequest = req;
        }
        return passwordAccessTokenRequest;
    }

    @Override
    public ClientCredentialAccessTokenRequest getClientCredentialAccessTokenRequest() {
        if (clientCredentialAccessTokenRequest == null) {
            ClientCredentialAccessTokenRequest req = new ClientCredentialAccessTokenRequest();
            req.setGrantType(getParameter(OAUTH_GRANT_TYPE));
            req.setClientId(getParameter(OAUTH_CLIENT_ID));
            req.setClientSecret(getParameter(OAUTH_CLIENT_SECRET));

            if (!StringUtils.hasText(req.getGrantType())) {
                throw OAuth.oauthProblem(OAuthError.TokenResponse.INVALID_REQUEST)
                           .description("The grant type must be not null.");
            }
            if (!req.getGrantType().equals(GrantType.CLIENT_CREDENTIALS.toString())) {
                throw OAuth.oauthProblem(OAuthError.TokenResponse.INVALID_GRANT)
                           .description("The grant type must be 'client_credentials'.");
            }
            clientCredentialAccessTokenRequest = req;
        }
        return clientCredentialAccessTokenRequest;
    }

    @Override
    public RefreshingTokenRequest getRefreshingTokenRequest() {
        if (refreshingTokenRequest == null) {
            RefreshingTokenRequest req = new RefreshingTokenRequest();
            req.setClientId(getParameter(OAUTH_CLIENT_ID));
            req.setRefreshToken(getParameter(OAUTH_REFRESH_TOKEN));
            req.setScope(getParameter(OAUTH_SCOPE));
            req.setGrantType(getParameter(OAUTH_GRANT_TYPE));

            if (!StringUtils.hasText(req.getRefreshToken())) {
                throw OAuth.oauthProblem(OAuthError.TokenResponse.INVALID_REQUEST)
                           .description("The refresh token must be not null.");
            }
            if (!StringUtils.hasText(req.getGrantType())) {
                throw OAuth.oauthProblem(OAuthError.TokenResponse.INVALID_REQUEST)
                           .description("The grant type must be not null.");
            }
            if (!req.getGrantType().equals(GrantType.REFRESH_TOKEN.toString())) {
                throw OAuth.oauthProblem(OAuthError.TokenResponse.INVALID_GRANT)
                           .description("The grant type must be 'refresh_token'.");
            }
            refreshingTokenRequest = req;
        }
        return refreshingTokenRequest;
    }
}
