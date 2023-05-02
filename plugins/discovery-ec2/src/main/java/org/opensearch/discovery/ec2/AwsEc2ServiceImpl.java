/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.discovery.ec2;

import java.net.URI;
import java.time.Duration;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.OpenSearchException;
import org.opensearch.common.Strings;
import org.opensearch.common.util.LazyInitializable;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.Ec2ClientBuilder;
import java.util.concurrent.atomic.AtomicReference;

class AwsEc2ServiceImpl implements AwsEc2Service {

    private static final Logger logger = LogManager.getLogger(AwsEc2ServiceImpl.class);

    private final AtomicReference<LazyInitializable<AmazonEc2ClientReference, OpenSearchException>> lazyClientReference =
        new AtomicReference<>();

    private Ec2Client buildClient(Ec2ClientSettings clientSettings) {
        final AwsCredentialsProvider awsCredentialsProvider = buildCredentials(logger, clientSettings);
        final ApacheHttpClient.Builder clientBuilder = buildHttpClient(logger, clientSettings);
        return buildClient(awsCredentialsProvider, clientBuilder, clientSettings.endpoint);
    }

    // proxy for testing
    protected Ec2Client buildClient(
        AwsCredentialsProvider awsCredentialsProvider,
        ApacheHttpClient.Builder apacheHttpClientBuilder,
        String endpoint
    ) {
        Ec2ClientBuilder builder = Ec2Client.builder()
            .httpClientBuilder(apacheHttpClientBuilder)
            .credentialsProvider(awsCredentialsProvider);

        if (Strings.hasText(endpoint)) {
            logger.debug("using explicit ec2 endpoint [{}]", endpoint);
            // TODO: builder.end(new AwsClientBuilder.EndpointConfiguration(endpoint, null));
        }
        return SocketAccess.doPrivileged(builder::build);
    }

    static ProxyConfiguration buildProxyConfiguration(Logger logger, Ec2ClientSettings clientSettings) {
        if (Strings.hasText(clientSettings.proxyHost)) {
            // TODO: remove this leniency, these settings should exist together and be validated
            return ProxyConfiguration.builder()
                .endpoint(URI.create("https://" + clientSettings.proxyHost + ":" + clientSettings.proxyPort))
                .username(clientSettings.proxyUsername)
                .password(clientSettings.proxyPassword)
                .build();
        } else {
            return null;
        }
    }

    // pkg private for tests
    static ApacheHttpClient.Builder buildHttpClient(Logger logger, Ec2ClientSettings clientSettings) {
        // clientConfiguration.setProtocol(clientSettings.protocol);
        // Increase the number of retries in case of 5xx API responses
        // clientConfiguration.setMaxErrorRetry(10);
        return ApacheHttpClient.builder()
            .proxyConfiguration(buildProxyConfiguration(logger, clientSettings))
            .socketTimeout(Duration.ofMillis(clientSettings.readTimeoutMillis));
    }

    // pkg private for tests
    static AwsCredentialsProvider buildCredentials(Logger logger, Ec2ClientSettings clientSettings) {
        final AwsCredentials credentials = clientSettings.credentials;
        if (credentials == null) {
            logger.debug("Using default credentials provider");
            return DefaultCredentialsProvider.create();
        } else {
            logger.debug("Using basic key/secret credentials");
            return StaticCredentialsProvider.create(credentials);
        }
    }

    @Override
    public AmazonEc2ClientReference client() {
        final LazyInitializable<AmazonEc2ClientReference, OpenSearchException> clientReference = this.lazyClientReference.get();
        if (clientReference == null) {
            throw new IllegalStateException("Missing ec2 client configs");
        }
        return clientReference.getOrCompute();
    }

    /**
     * Refreshes the settings for the AmazonEC2 client. The new client will be build
     * using these new settings. The old client is usable until released. On release it
     * will be destroyed instead of being returned to the cache.
     */
    @Override
    public void refreshAndClearCache(Ec2ClientSettings clientSettings) {
        final LazyInitializable<AmazonEc2ClientReference, OpenSearchException> newClient = new LazyInitializable<>(
            () -> new AmazonEc2ClientReference(buildClient(clientSettings)),
            clientReference -> clientReference.incRef(),
            clientReference -> clientReference.decRef()
        );
        final LazyInitializable<AmazonEc2ClientReference, OpenSearchException> oldClient = this.lazyClientReference.getAndSet(newClient);
        if (oldClient != null) {
            oldClient.reset();
        }
    }

    @Override
    public void close() {
        final LazyInitializable<AmazonEc2ClientReference, OpenSearchException> clientReference = this.lazyClientReference.getAndSet(null);
        if (clientReference != null) {
            clientReference.reset();
        }
    }
}
