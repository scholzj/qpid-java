/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.qpid.server.management.plugin.auth;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.AccessControlException;
import java.security.AccessController;
import java.security.Principal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.security.auth.Subject;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.AbstractHttpConnection;
import org.eclipse.jetty.server.Request;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import org.apache.qpid.server.management.plugin.HttpManagement;
import org.apache.qpid.server.management.plugin.HttpManagementConfiguration;
import org.apache.qpid.server.management.plugin.HttpManagementUtil;
import org.apache.qpid.server.management.plugin.HttpRequestInteractiveAuthenticator;
import org.apache.qpid.server.model.Broker;
import org.apache.qpid.server.model.port.HttpPort;
import org.apache.qpid.server.security.SecurityManager;
import org.apache.qpid.server.security.SubjectCreator;
import org.apache.qpid.server.security.auth.AuthenticatedPrincipal;
import org.apache.qpid.server.security.auth.AuthenticationResult;
import org.apache.qpid.server.security.auth.SubjectAuthenticationResult;
import org.apache.qpid.server.security.auth.manager.oauth2.OAuth2AuthenticationProvider;
import org.apache.qpid.test.utils.QpidTestCase;

public class OAuth2InteractiveAuthenticatorTest extends QpidTestCase
{
    private static final String TEST_AUTHORIZATION_ENDPOINT = "testAuthEndpoint";
    private static final int TEST_PORT = 64756;
    private static final int TEST_REMOTE_PORT = 0;
    private static final String TEST_OAUTH2_SCOPE = "testScope";
    private static final String TEST_REQUEST_HOST = "http://localhost";
    private static final String TEST_REQUEST_PATH = "/foo/bar";
    private static final String TEST_REQUEST_QUERY = "?baz=fnord";
    private static final String TEST_REQUEST = TEST_REQUEST_HOST + ":" + TEST_PORT + TEST_REQUEST_PATH + TEST_REQUEST_QUERY;
    private static final String TEST_CLIENT_ID = "testClientId";
    private static final String TEST_STATE = "testState";
    private static final String TEST_VALID_AUTHORIZATION_CODE = "testValidAuthorizationCode";
    private static final String TEST_INVALID_AUTHORIZATION_CODE = "testInvalidAuthorizationCode";
    private static final String TEST_UNAUTHORIZED_AUTHORIZATION_CODE = "testUnauthorizedAuthorizationCode";
    private static final String TEST_AUTHORIZED_USER = "testAuthorizedUser";
    private static final String TEST_UNAUTHORIZED_USER = "testUnauthorizedUser";
    private static final String ATTR_SUBJECT = "Qpid.subject"; // this is private in HttpManagementUtil
    public static final String TEST_REMOTE_HOST = "testRemoteHost";

    private OAuth2InteractiveAuthenticator _authenticator;
    private HttpManagementConfiguration _mockConfiguration;
    private OAuth2AuthenticationProvider<?> _mockAuthProvider;

    @Override
    public void setUp() throws Exception
    {
        super.setUp();
        _mockAuthProvider = createMockOAuth2AuthenticationProvider();
        _mockConfiguration = mock(HttpManagementConfiguration.class);
        when(_mockConfiguration.getAuthenticationProvider(any(HttpServletRequest.class))).thenReturn(_mockAuthProvider);

        _authenticator = new OAuth2InteractiveAuthenticator();
    }

    public void testInitialRedirect() throws Exception
    {
        Map<String, Object> sessionAttributes = new HashMap<>();
        HttpServletRequest mockRequest = createMockRequest(TEST_REQUEST_HOST, TEST_REQUEST_PATH,
                                                           Collections.singletonMap("baz", "fnord"), sessionAttributes);
        HttpRequestInteractiveAuthenticator.AuthenticationHandler authenticationHandler = _authenticator.getAuthenticationHandler(mockRequest,
                                                                                                                                  _mockConfiguration);
        assertNotNull("Authenticator does not feel responsible", authenticationHandler);
        assertTrue("Authenticator has failed unexpectedly", !(authenticationHandler instanceof OAuth2InteractiveAuthenticator.FailedAuthenticationHandler));

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        authenticationHandler.handleAuthentication(mockResponse);

        ArgumentCaptor<String> argument = ArgumentCaptor.forClass(String.class);
        verify(mockResponse).sendRedirect(argument.capture());
        Map<String, String> params = getRedirectParameters(argument.getValue());

        assertTrue("Wrong redirect host", argument.getValue().startsWith(TEST_AUTHORIZATION_ENDPOINT));
        assertEquals("Wrong response_type", "code", params.get("response_type"));
        assertEquals("Wrong client_id", TEST_CLIENT_ID, params.get("client_id"));
        assertEquals("Wrong redirect_uri", TEST_REQUEST_HOST, params.get("redirect_uri"));
        assertEquals("Wrong scope", TEST_OAUTH2_SCOPE, params.get("scope"));
        String stateAttrName = HttpManagementUtil.getRequestSpecificAttributeName(OAuth2InteractiveAuthenticator.STATE_NAME, mockRequest);
        assertNotNull("State was not set on the session",
                      sessionAttributes.get(stateAttrName));
        assertEquals("Wrong state",
                     (String) sessionAttributes.get(stateAttrName),
                     params.get("state"));
    }

    public void testValidLogin() throws Exception
    {
        Map<String, Object> sessionAttributes = new HashMap<>();
        sessionAttributes.put(OAuth2InteractiveAuthenticator.STATE_NAME, TEST_STATE);
        sessionAttributes.put(OAuth2InteractiveAuthenticator.ORIGINAL_REQUEST_URI_SESSION_ATTRIBUTE, TEST_REQUEST);
        sessionAttributes.put(OAuth2InteractiveAuthenticator.REDIRECT_URI_SESSION_ATTRIBUTE, TEST_REQUEST_HOST);
        Map<String, String> requestParameters = new HashMap<>();
        requestParameters.put("state", TEST_STATE);
        requestParameters.put("code", TEST_VALID_AUTHORIZATION_CODE);
        HttpServletRequest mockRequest = createMockRequest(TEST_REQUEST_HOST, TEST_REQUEST_PATH, requestParameters, sessionAttributes);

        HttpRequestInteractiveAuthenticator.AuthenticationHandler authenticationHandler = _authenticator.getAuthenticationHandler(mockRequest,
                                                                                                                                  _mockConfiguration);
        assertNotNull("Authenticator does not feel responsible", authenticationHandler);
        assertTrue("Authenticator has failed unexpectedly", !(authenticationHandler instanceof OAuth2InteractiveAuthenticator.FailedAuthenticationHandler));

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        authenticationHandler.handleAuthentication(mockResponse);

        ArgumentCaptor<String> argument = ArgumentCaptor.forClass(String.class);
        verify(mockResponse).sendRedirect(argument.capture());

        assertEquals("Wrong redirect", TEST_REQUEST, argument.getValue());
        String attrSubject = HttpManagementUtil.getRequestSpecificAttributeName(ATTR_SUBJECT, mockRequest);
        assertNotNull("No subject on session", sessionAttributes.get(attrSubject));
        assertTrue("Subject on session is no a Subject", sessionAttributes.get(attrSubject) instanceof Subject);
        final Set<Principal> principals = ((Subject) sessionAttributes.get(attrSubject)).getPrincipals();
        assertEquals("Subject created with unexpected principal", TEST_AUTHORIZED_USER, principals.iterator().next().getName());
    }

    public void testNoStateOnSession() throws Exception
    {
        Map<String, Object> sessionAttributes = new HashMap<>();
        sessionAttributes.put(OAuth2InteractiveAuthenticator.ORIGINAL_REQUEST_URI_SESSION_ATTRIBUTE, TEST_REQUEST);
        sessionAttributes.put(OAuth2InteractiveAuthenticator.REDIRECT_URI_SESSION_ATTRIBUTE, TEST_REQUEST_HOST);
        Map<String, String> requestParameters = new HashMap<>();
        requestParameters.put("state", TEST_STATE);
        requestParameters.put("code", TEST_VALID_AUTHORIZATION_CODE);
        HttpServletRequest mockRequest = createMockRequest(TEST_REQUEST_HOST, TEST_REQUEST_PATH, requestParameters, sessionAttributes);

        HttpRequestInteractiveAuthenticator.AuthenticationHandler authenticationHandler = _authenticator.getAuthenticationHandler(mockRequest,
                                                                                                                                  _mockConfiguration);
        assertNotNull("Authenticator does not feel responsible", authenticationHandler);
        assertTrue("Authenticator did not fail with no state on session", authenticationHandler instanceof OAuth2InteractiveAuthenticator.FailedAuthenticationHandler);
    }

    public void testNoStateOnRequest() throws Exception
    {
        Map<String, Object> sessionAttributes = new HashMap<>();
        sessionAttributes.put(OAuth2InteractiveAuthenticator.STATE_NAME, TEST_STATE);
        sessionAttributes.put(OAuth2InteractiveAuthenticator.ORIGINAL_REQUEST_URI_SESSION_ATTRIBUTE, TEST_REQUEST);
        sessionAttributes.put(OAuth2InteractiveAuthenticator.REDIRECT_URI_SESSION_ATTRIBUTE, TEST_REQUEST_HOST);
        Map<String, String> requestParameters = new HashMap<>();
        requestParameters.put("code", TEST_VALID_AUTHORIZATION_CODE);
        HttpServletRequest mockRequest = createMockRequest(TEST_REQUEST_HOST, TEST_REQUEST_PATH, requestParameters, sessionAttributes);

        HttpRequestInteractiveAuthenticator.AuthenticationHandler authenticationHandler = _authenticator.getAuthenticationHandler(mockRequest,
                                                                                                                                  _mockConfiguration);
        assertNotNull("Authenticator does not feel responsible", authenticationHandler);
        assertTrue("Authenticator did not fail with no state on request", authenticationHandler instanceof OAuth2InteractiveAuthenticator.FailedAuthenticationHandler);
    }

    public void testWrongStateOnRequest() throws Exception
    {
        Map<String, Object> sessionAttributes = new HashMap<>();
        sessionAttributes.put(OAuth2InteractiveAuthenticator.STATE_NAME, TEST_STATE);
        sessionAttributes.put(OAuth2InteractiveAuthenticator.ORIGINAL_REQUEST_URI_SESSION_ATTRIBUTE, TEST_REQUEST);
        sessionAttributes.put(OAuth2InteractiveAuthenticator.REDIRECT_URI_SESSION_ATTRIBUTE, TEST_REQUEST_HOST);
        Map<String, String> requestParameters = new HashMap<>();
        requestParameters.put("state", "WRONG" + TEST_STATE);
        requestParameters.put("code", TEST_VALID_AUTHORIZATION_CODE);
        HttpServletRequest mockRequest = createMockRequest(TEST_REQUEST_HOST, TEST_REQUEST_PATH, requestParameters, sessionAttributes);

        HttpRequestInteractiveAuthenticator.AuthenticationHandler authenticationHandler = _authenticator.getAuthenticationHandler(mockRequest,
                                                                                                                                  _mockConfiguration);
        assertNotNull("Authenticator does not feel responsible", authenticationHandler);
        assertTrue("Authenticator did not fail with wrong state on request", authenticationHandler instanceof OAuth2InteractiveAuthenticator.FailedAuthenticationHandler);
    }

    public void testInvalidAuthorizationCode() throws Exception
    {
        Map<String, Object> sessionAttributes = new HashMap<>();
        sessionAttributes.put(OAuth2InteractiveAuthenticator.STATE_NAME, TEST_STATE);
        sessionAttributes.put(OAuth2InteractiveAuthenticator.ORIGINAL_REQUEST_URI_SESSION_ATTRIBUTE, TEST_REQUEST);
        sessionAttributes.put(OAuth2InteractiveAuthenticator.REDIRECT_URI_SESSION_ATTRIBUTE, TEST_REQUEST_HOST);
        Map<String, String> requestParameters = new HashMap<>();
        requestParameters.put("state", TEST_STATE);
        requestParameters.put("code", TEST_INVALID_AUTHORIZATION_CODE);
        HttpServletRequest mockRequest = createMockRequest(TEST_REQUEST_HOST, TEST_REQUEST_PATH, requestParameters, sessionAttributes);

        HttpRequestInteractiveAuthenticator.AuthenticationHandler authenticationHandler = _authenticator.getAuthenticationHandler(mockRequest,
                                                                                                                                  _mockConfiguration);
        assertNotNull("Authenticator does not feel responsible", authenticationHandler);
        assertTrue("Authenticator has failed unexpectedly", !(authenticationHandler instanceof OAuth2InteractiveAuthenticator.FailedAuthenticationHandler));

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        authenticationHandler.handleAuthentication(mockResponse);
        verify(mockResponse).sendError(eq(401));

    }

    public void testUnauthorizedAuthorizationCode() throws Exception
    {
        Map<String, Object> sessionAttributes = new HashMap<>();
        sessionAttributes.put(OAuth2InteractiveAuthenticator.STATE_NAME, TEST_STATE);
        sessionAttributes.put(OAuth2InteractiveAuthenticator.ORIGINAL_REQUEST_URI_SESSION_ATTRIBUTE, TEST_REQUEST);
        sessionAttributes.put(OAuth2InteractiveAuthenticator.REDIRECT_URI_SESSION_ATTRIBUTE, TEST_REQUEST_HOST);
        Map<String, String> requestParameters = new HashMap<>();
        requestParameters.put("state", TEST_STATE);
        requestParameters.put("code", TEST_UNAUTHORIZED_AUTHORIZATION_CODE);
        HttpServletRequest mockRequest = createMockRequest(TEST_REQUEST_HOST, TEST_REQUEST_PATH, requestParameters, sessionAttributes);

        HttpRequestInteractiveAuthenticator.AuthenticationHandler authenticationHandler = _authenticator.getAuthenticationHandler(mockRequest,
                                                                                                                                  _mockConfiguration);
        assertNotNull("Authenticator does not feel responsible", authenticationHandler);
        assertTrue("Authenticator has failed unexpectedly", !(authenticationHandler instanceof OAuth2InteractiveAuthenticator.FailedAuthenticationHandler));

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        authenticationHandler.handleAuthentication(mockResponse);
        verify(mockResponse).sendError(eq(403), any(String.class));
    }

    private Map<String, String> getRedirectParameters(final String redirectLocation)
    {
        AbstractHttpConnection mockConnection = mock(AbstractHttpConnection.class);
        HttpFields requestFields = new HttpFields();
        when(mockConnection.getRequestFields()).thenReturn(requestFields);
        Request request = new Request(mockConnection);
        request.setUri(new HttpURI(redirectLocation));
        request.setRequestURI(redirectLocation);
        request.setContentType("text/html");
        final Map<String,String[]> parameterMap = request.getParameterMap();
        Map<String,String> parameters = new HashMap<>();
        for (Map.Entry<String, String[]> paramEntry : parameterMap.entrySet())
        {
            assertEquals(String.format("param '%s' specified more than once", paramEntry.getKey()), 1, paramEntry.getValue().length);
            parameters.put(paramEntry.getKey(), paramEntry.getValue()[0]);
        }
        return parameters;
    }

    private OAuth2AuthenticationProvider<?> createMockOAuth2AuthenticationProvider() throws URISyntaxException
    {
        OAuth2AuthenticationProvider authenticationProvider = mock(OAuth2AuthenticationProvider.class);
        Broker mockBroker = mock(Broker.class);
        SecurityManager mockSecurityManager = mock(SecurityManager.class);
        SubjectCreator mockSubjectCreator = mock(SubjectCreator.class);
        SubjectAuthenticationResult mockSuccessfulSubjectAuthenticationResult = mock(SubjectAuthenticationResult.class);
        SubjectAuthenticationResult mockUnauthorizedSubjectAuthenticationResult = mock(SubjectAuthenticationResult.class);
        final Subject successfulSubject = new Subject(true, Collections.singleton(new AuthenticatedPrincipal(
                TEST_AUTHORIZED_USER)), Collections.emptySet(), Collections.emptySet());
        final Subject unauthorizedSubject = new Subject(true, Collections.singleton(new AuthenticatedPrincipal(
                TEST_UNAUTHORIZED_USER)), Collections.emptySet(), Collections.emptySet());
        AuthenticationResult mockSuccessfulAuthenticationResult = mock(AuthenticationResult.class);
        AuthenticationResult mockUnauthorizedAuthenticationResult = mock(AuthenticationResult.class);
        AuthenticationResult failedAuthenticationResult = new AuthenticationResult(AuthenticationResult.AuthenticationStatus.ERROR,
                                                                                   new Exception("authentication failed"));
        SubjectAuthenticationResult failedSubjectAuthenticationResult = new SubjectAuthenticationResult(failedAuthenticationResult);

        doAnswer(new Answer()
        {
            @Override
            public Object answer(final InvocationOnMock invocationOnMock) throws Throwable
            {
                final Subject subject = Subject.getSubject(AccessController.getContext());
                if (!subject.getPrincipals().iterator().next().getName().equals(TEST_AUTHORIZED_USER))
                {
                    throw new AccessControlException("access denied");
                }
                return null;
            }
        }).when(mockSecurityManager).accessManagement();
        when(mockBroker.getSecurityManager()).thenReturn(mockSecurityManager);

        when(authenticationProvider.getAuthorizationEndpointURI()).thenReturn(new URI(TEST_AUTHORIZATION_ENDPOINT));
        when(authenticationProvider.getClientId()).thenReturn(TEST_CLIENT_ID);
        when(authenticationProvider.getScope()).thenReturn(TEST_OAUTH2_SCOPE);
        when(authenticationProvider.getParent(Broker.class)).thenReturn(mockBroker);
        when(authenticationProvider.getSubjectCreator(any(Boolean.class))).thenReturn(mockSubjectCreator);
        when(authenticationProvider.authenticateViaAuthorizationCode(TEST_VALID_AUTHORIZATION_CODE, TEST_REQUEST_HOST)).thenReturn(mockSuccessfulAuthenticationResult);
        when(authenticationProvider.authenticateViaAuthorizationCode(TEST_INVALID_AUTHORIZATION_CODE, TEST_REQUEST_HOST)).thenReturn(failedAuthenticationResult);
        when(authenticationProvider.authenticateViaAuthorizationCode(TEST_UNAUTHORIZED_AUTHORIZATION_CODE, TEST_REQUEST_HOST)).thenReturn(mockUnauthorizedAuthenticationResult);

        when(mockSuccessfulSubjectAuthenticationResult.getSubject()).thenReturn(successfulSubject);
        when(mockUnauthorizedSubjectAuthenticationResult.getSubject()).thenReturn(unauthorizedSubject);

        when(mockSubjectCreator.createResultWithGroups(mockSuccessfulAuthenticationResult)).thenReturn(mockSuccessfulSubjectAuthenticationResult);
        when(mockSubjectCreator.createResultWithGroups(mockUnauthorizedAuthenticationResult)).thenReturn(mockUnauthorizedSubjectAuthenticationResult);
        when(mockSubjectCreator.createResultWithGroups(failedAuthenticationResult)).thenReturn(failedSubjectAuthenticationResult);

        return authenticationProvider;
    }

    private HttpServletRequest createMockRequest(String host, String path,
                                                 final Map<String, String> query,
                                                 Map<String, Object> sessionAttributes) throws IOException
    {
        final HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        UUID portId = UUID.randomUUID();
        HttpPort port = mock(HttpPort.class);
        when(mockRequest.getAttribute(eq("org.apache.qpid.server.model.Port"))).thenReturn(port);
        when(port.getId()).thenReturn(portId);
        when(mockRequest.getParameterNames()).thenReturn(Collections.enumeration(query.keySet()));
        doAnswer(new Answer()
        {
            @Override
            public Object answer(final InvocationOnMock invocationOnMock) throws Throwable
            {
                final Object[] arguments = invocationOnMock.getArguments();
                assertEquals("Unexpected number of arguments", 1, arguments.length);
                final String paramName = (String) arguments[0];
                return new String[]{query.get(paramName)};
            }
        }).when(mockRequest).getParameterValues(any(String.class));
        when(mockRequest.isSecure()).thenReturn(false);
        Map<String,Object> originalAttrs = new HashMap<>(sessionAttributes);
        sessionAttributes.clear();
        for(Map.Entry<String,Object> entry : originalAttrs.entrySet())
        {
            sessionAttributes.put(HttpManagementUtil.getRequestSpecificAttributeName(entry.getKey(), mockRequest), entry.getValue());
        }
        final HttpSession mockHttpSession = createMockHttpSession(sessionAttributes);
        when(mockRequest.getSession()).thenReturn(mockHttpSession);
        when(mockRequest.getServletPath()).thenReturn("");
        when(mockRequest.getPathInfo()).thenReturn(path);
        final StringBuffer url = new StringBuffer(host + path);
        when(mockRequest.getRequestURL()).thenReturn(url);
        when(mockRequest.getRemoteHost()).thenReturn(TEST_REMOTE_HOST);
        when(mockRequest.getRemotePort()).thenReturn(TEST_REMOTE_PORT);


        return mockRequest;
    }

    private HttpSession createMockHttpSession(final Map<String, Object> sessionAttributes)
    {
        final HttpSession httpSession = mock(HttpSession.class);
        doAnswer(new Answer()
        {
            @Override
            public Object answer(final InvocationOnMock invocation) throws Throwable
            {
                final Object[] arguments = invocation.getArguments();
                assertEquals(2, arguments.length);
                sessionAttributes.put((String) arguments[0], arguments[1]);
                return null;
            }
        }).when(httpSession).setAttribute(any(String.class), any(Object.class));
        doAnswer(new Answer()
        {
            @Override
            public Object answer(final InvocationOnMock invocation) throws Throwable
            {
                final Object[] arguments = invocation.getArguments();
                assertEquals(1, arguments.length);
                return sessionAttributes.get((String) arguments[0]);
            }
        }).when(httpSession).getAttribute(any(String.class));
        ServletContext mockServletContext = mock(ServletContext.class);
        when(httpSession.getServletContext()).thenReturn(mockServletContext);
        return httpSession;
    }
}
