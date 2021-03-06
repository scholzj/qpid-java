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
package org.apache.qpid.server.management.plugin;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.security.PrivilegedAction;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import javax.security.auth.Subject;
import javax.security.auth.x500.X500Principal;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.xml.bind.DatatypeConverter;

import org.apache.qpid.server.management.plugin.servlet.ServletConnectionPrincipal;
import org.apache.qpid.server.management.plugin.session.LoginLogoutReporter;
import org.apache.qpid.server.model.AuthenticationProvider;
import org.apache.qpid.server.model.Broker;
import org.apache.qpid.server.plugin.QpidServiceLoader;
import org.apache.qpid.server.security.SecurityManager;
import org.apache.qpid.server.security.SubjectCreator;
import org.apache.qpid.server.security.auth.AuthenticatedPrincipal;
import org.apache.qpid.server.security.auth.AuthenticationResult;
import org.apache.qpid.server.security.auth.AuthenticationResult.AuthenticationStatus;
import org.apache.qpid.server.security.auth.SubjectAuthenticationResult;
import org.apache.qpid.server.security.auth.UsernamePrincipal;
import org.apache.qpid.server.security.auth.manager.AnonymousAuthenticationManager;
import org.apache.qpid.server.security.auth.manager.ExternalAuthenticationManager;
import org.apache.qpid.server.security.auth.manager.UsernamePasswordAuthenticationProvider;
import org.apache.qpid.transport.network.security.ssl.SSLUtil;

public class HttpManagementUtil
{

    /**
     * Servlet context attribute holding a reference to a broker instance
     */
    public static final String ATTR_BROKER = "Qpid.broker";

    /**
     * Servlet context attribute holding a reference to plugin configuration
     */
    public static final String ATTR_MANAGEMENT_CONFIGURATION = "Qpid.managementConfiguration";

    private static final String ATTR_LOGIN_LOGOUT_REPORTER = "Qpid.loginLogoutReporter";
    private static final String ATTR_SUBJECT = "Qpid.subject";
    private static final String ATTR_LOG_ACTOR = "Qpid.logActor";

    private static final String ACCEPT_ENCODING_HEADER = "Accept-Encoding";
    private static final String CONTENT_ENCODING_HEADER = "Content-Encoding";
    private static final String GZIP_CONTENT_ENCODING = "gzip";

    private static final Collection<HttpRequestPreemptiveAuthenticator> AUTHENTICATORS;
    static
    {
        List<HttpRequestPreemptiveAuthenticator> authenticators = new ArrayList<>();
        for(HttpRequestPreemptiveAuthenticator authenticator : (new QpidServiceLoader()).instancesOf(HttpRequestPreemptiveAuthenticator.class))
        {
            authenticators.add(authenticator);
        }
        AUTHENTICATORS = Collections.unmodifiableList(authenticators);
    }

    public static String getRequestSpecificAttributeName(String name, HttpServletRequest request)
    {
        return name + "." + HttpManagement.getPort(request).getId();
    }

    public static Broker<?> getBroker(ServletContext servletContext)
    {
        return (Broker<?>) servletContext.getAttribute(ATTR_BROKER);
    }

    public static HttpManagementConfiguration getManagementConfiguration(ServletContext servletContext)
    {
        return (HttpManagementConfiguration) servletContext.getAttribute(ATTR_MANAGEMENT_CONFIGURATION);
    }

    public static Subject getAuthorisedSubject(HttpServletRequest request)
    {
        HttpSession session = request.getSession();
        return (Subject) session.getAttribute(getRequestSpecificAttributeName(ATTR_SUBJECT,request));
    }

    public static void checkRequestAuthenticatedAndAccessAuthorized(HttpServletRequest request, Broker broker,
            HttpManagementConfiguration managementConfig)
    {
        HttpSession session = request.getSession();
        Subject subject = getAuthorisedSubject(request);
        if (subject == null)
        {
            subject = tryToAuthenticate(request, managementConfig);
            if (subject == null)
            {
                throw new SecurityException("Only authenticated users can access the management interface");
            }

            Subject original = subject;
            subject = new Subject(false,
                                  original.getPrincipals(),
                                  original.getPublicCredentials(),
                                  original.getPrivateCredentials());
            subject.getPrincipals().add(new ServletConnectionPrincipal(request));
            subject.setReadOnly();

            assertManagementAccess(broker.getSecurityManager(), subject);

            saveAuthorisedSubject(request, subject);


        }
    }

    public static void assertManagementAccess(final SecurityManager securityManager, Subject subject)
    {
        Subject.doAs(subject, new PrivilegedAction<Void>()
        {
            @Override
            public Void run()
            {
                securityManager.accessManagement();
                return null;
            }
        });
    }

    public static void saveAuthorisedSubject(HttpServletRequest request, Subject subject)
    {
        HttpSession session = request.getSession();
        session.setAttribute(getRequestSpecificAttributeName(ATTR_SUBJECT, request), subject);

        // Cause the user logon to be logged.
        session.setAttribute(getRequestSpecificAttributeName(ATTR_LOGIN_LOGOUT_REPORTER, request),
                             new LoginLogoutReporter(subject, getBroker(session.getServletContext())));
    }

    public static Subject tryToAuthenticate(HttpServletRequest request, HttpManagementConfiguration managementConfig)
    {
        Subject subject = null;
        for(HttpRequestPreemptiveAuthenticator authenticator : AUTHENTICATORS)
        {
            subject = authenticator.attemptAuthentication(request, managementConfig);
            if(subject != null)
            {
                break;
            }
        }
        return subject;
    }

    public static OutputStream getOutputStream(final HttpServletRequest request, final HttpServletResponse response)
            throws IOException
    {
        return getOutputStream(request, response, getManagementConfiguration(request.getServletContext()));
    }

    public static OutputStream getOutputStream(final HttpServletRequest request, final HttpServletResponse response, HttpManagementConfiguration managementConfiguration)
            throws IOException
    {
        OutputStream outputStream;
        if(managementConfiguration.isCompressResponses()
           && Collections.list(request.getHeaderNames()).contains(ACCEPT_ENCODING_HEADER)
           && request.getHeader(ACCEPT_ENCODING_HEADER).contains(GZIP_CONTENT_ENCODING))
        {
            outputStream = new GZIPOutputStream(response.getOutputStream());
            response.setHeader(CONTENT_ENCODING_HEADER, GZIP_CONTENT_ENCODING);
        }
        else
        {
            outputStream = response.getOutputStream();
        }
        return outputStream;
    }

    public static String ensureFilenameIsRfc2183(final String requestedFilename)
    {
        return requestedFilename.replaceAll("[\\P{InBasic_Latin}\\\\:/\\p{Cntrl}]", "");
    }
}
