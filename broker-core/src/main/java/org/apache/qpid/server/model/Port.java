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
package org.apache.qpid.server.model;

import java.security.AccessControlException;
import java.util.Collection;
import java.util.Set;

@ManagedObject
public interface Port<X extends Port<X>> extends ConfiguredObject<X>
{
    String DURABLE                              = "durable";
    String LIFETIME_POLICY                      = "lifetimePolicy";
    String BINDING_ADDRESS                      = "bindingAddress";
    String PORT                                 = "port";
    String PROTOCOLS                            = "protocols";
    String TRANSPORTS                           = "transports";
    String TCP_NO_DELAY                         = "tcpNoDelay";
    String SEND_BUFFER_SIZE                     = "sendBufferSize";
    String RECEIVE_BUFFER_SIZE                  = "receiveBufferSize";
    String NEED_CLIENT_AUTH                     = "needClientAuth";
    String WANT_CLIENT_AUTH                     = "wantClientAuth";
    String AUTHENTICATION_PROVIDER              = "authenticationProvider";
    String KEY_STORE                            = "keyStore";
    String TRUST_STORES                         = "trustStores";

    // Attributes

    @ManagedAttribute(automate = true)
    String getBindingAddress();

    @ManagedAttribute(mandatory = true, automate = true)
    int getPort();

    @ManagedAttribute( automate = true )
    Set<Protocol> getProtocols();

    @ManagedAttribute( automate = true )
    Set<Transport> getTransports();

    @ManagedAttribute( automate = true )
    KeyStore getKeyStore();

    @ManagedAttribute( automate = true )
    Collection<TrustStore> getTrustStores();






    void addTransport(Transport transport) throws IllegalStateException,
                                                  AccessControlException,
                                                  IllegalArgumentException;

    Transport removeTransport(Transport transport) throws IllegalStateException,
                                                          AccessControlException,
                                                          IllegalArgumentException;


    void addProtocol(Protocol protocol) throws IllegalStateException,
                                               AccessControlException,
                                               IllegalArgumentException;

    Protocol removeProtocol(Protocol protocol) throws IllegalStateException,
                                                      AccessControlException,
                                                      IllegalArgumentException;


    //children
    Collection<VirtualHostAlias> getVirtualHostBindings();
    Collection<Connection> getConnections();
}
