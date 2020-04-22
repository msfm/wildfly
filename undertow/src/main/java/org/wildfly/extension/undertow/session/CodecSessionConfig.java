/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2017, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.wildfly.extension.undertow.session;

import java.util.List;
import java.util.Map;

import org.jboss.as.web.session.SessionIdentifierCodec;
import org.wildfly.extension.undertow.logging.UndertowLogger;

import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.Cookie;
import io.undertow.server.session.Session;
import io.undertow.server.session.SessionConfig;
import io.undertow.server.session.SessionManager;
import io.undertow.servlet.api.Deployment;
import io.undertow.util.AttachmentKey;

/**
 * {@link SessionConfig} decorator that performs encoding/decoding of the session identifier.
 * In this way, routing is completely opaque to the request, session, and session manager.
 * @author Paul Ferraro
 */
public class CodecSessionConfig implements SessionConfig {
    private static final AttachmentKey<Boolean> SESSION_ID_SET = AttachmentKey.create(Boolean.class);

    private final SessionConfig config;
    private final SessionIdentifierCodec codec;
    private final SessionManager sessionManager;
    private final String sessionCookieName;

    public CodecSessionConfig(SessionConfig config, SessionIdentifierCodec codec) {
        this.config = config;
        this.codec = codec;
        this.sessionManager = null;
        this.sessionCookieName = null;
    }

    public CodecSessionConfig(SessionConfig config, SessionIdentifierCodec codec, Deployment deployment) {
        this.config = config;
        this.codec = codec;
        this.sessionManager = deployment.getSessionManager();
        this.sessionCookieName = deployment.getServletContext().getSessionCookieConfig().getName();
    }

    @Override
    public void setSessionId(HttpServerExchange exchange, String sessionId) {
        exchange.putAttachment(SESSION_ID_SET, Boolean.TRUE);
        this.config.setSessionId(exchange, this.codec.encode(sessionId).toString());
    }

    @Override
    public void clearSession(HttpServerExchange exchange, String sessionId) {
        this.config.clearSession(exchange, this.codec.encode(sessionId).toString());
    }

    @Override
    public String findSessionId(HttpServerExchange exchange) {
        String encodedSessionId = this.config.findSessionId(exchange);
        if (encodedSessionId == null) return null;
        CharSequence sessionId = this.codec.decode(encodedSessionId);
        if (sessionManager != null && sessionCookieName != null) {
            // chech if the session exists in the session manager
            Session session = sessionManager.getSession(sessionId.toString());
            if (session == null) {
                sessionId = null; // the previous decoded sessionId does not exist
                // Obtain DUPLICATES_REQUEST_COOKIES from exchange attachment
                final Map<String, List<Cookie>> dupRequestCookieMap = exchange.getAttachment(HttpServerExchange.DUPLICATES_REQUEST_COOKIES);
                if (dupRequestCookieMap != null) {
                    final List<Cookie> list = dupRequestCookieMap.get(sessionCookieName);
                    for (Cookie cookie : list) {
                        // check if the session exists in the session manager
                        encodedSessionId = cookie.getValue();
                        sessionId = this.codec.decode(encodedSessionId);
                        session = sessionManager.getSession(sessionId.toString());
                        if (session != null) {
                            UndertowLogger.ROOT_LOGGER.debugf("##### Found a session [%s] from duplicate request cookies [%s]", session, encodedSessionId);
                            break;
                        }
                    }
                }
            }
        }
        if (sessionId != null) {
            // Check if the encoding for this session has changed
            CharSequence reencodedSessionId = this.codec.encode(sessionId);
            if ((exchange.getAttachment(SESSION_ID_SET) == null) && !encodedSessionId.contentEquals(reencodedSessionId)) {
                this.config.setSessionId(exchange, reencodedSessionId.toString());
            }
            return sessionId.toString();
        } else {
            return null;
        }
    }

    @Override
    public SessionCookieSource sessionCookieSource(HttpServerExchange exchange) {
        return this.config.sessionCookieSource(exchange);
    }

    @Override
    public String rewriteUrl(String originalUrl, String sessionId) {
        return this.config.rewriteUrl(originalUrl, this.codec.encode(sessionId).toString());
    }
}
