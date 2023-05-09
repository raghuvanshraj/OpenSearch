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

package org.opensearch.repositories.s3;

import org.apache.http.conn.ssl.DefaultHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.protocol.HttpContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.cluster.metadata.RepositoryMetadata;
import org.opensearch.common.Nullable;
import org.opensearch.common.Strings;
import org.opensearch.common.collect.MapBuilder;
import org.opensearch.common.settings.Settings;
import org.opensearch.repositories.s3.S3ClientSettings.IrsaCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ContainerCredentialsProvider;
import software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkSystemSetting;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.core.retry.backoff.BackoffStrategy;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SystemPropertyTlsKeyManagersProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.http.apache.internal.conn.SdkTlsSocketFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.StsClientBuilder;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleWithWebIdentityCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleWithWebIdentityRequest;

import javax.net.ssl.SSLContext;
import java.io.Closeable;
import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Path;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;

import static java.util.Collections.emptyMap;

class S3Service implements Closeable {
    private static final Logger logger = LogManager.getLogger(S3Service.class);

    private static final String STS_ENDPOINT_OVERRIDE_SYSTEM_PROPERTY = "com.amazonaws.sdk.stsEndpointOverride";

    private static final String DEFAULT_S3_ENDPOINT = "s3.amazonaws.com";

    private volatile Map<S3ClientSettings, AmazonS3Reference> clientsCache = emptyMap();

    /**
     * Client settings calculated from static configuration and settings in the keystore.
     */
    private volatile Map<String, S3ClientSettings> staticClientSettings;

    /**
     * Client settings derived from those in {@link #staticClientSettings} by combining them with settings
     * in the {@link RepositoryMetadata}.
     */
    private volatile Map<Settings, S3ClientSettings> derivedClientSettings = emptyMap();

    S3Service(final Path configPath) {
        staticClientSettings = MapBuilder.<String, S3ClientSettings>newMapBuilder()
            .put("default", S3ClientSettings.getClientSettings(Settings.EMPTY, "default", configPath))
            .immutableMap();
    }

    /**
     * Refreshes the settings for the AmazonS3 clients and clears the cache of
     * existing clients. New clients will be build using these new settings. Old
     * clients are usable until released. On release they will be destroyed instead
     * of being returned to the cache.
     */
    public synchronized void refreshAndClearCache(Map<String, S3ClientSettings> clientsSettings) {
        // shutdown all unused clients
        // others will shutdown on their respective release
        releaseCachedClients();
        this.staticClientSettings = MapBuilder.newMapBuilder(clientsSettings).immutableMap();
        derivedClientSettings = emptyMap();
        assert this.staticClientSettings.containsKey("default") : "always at least have 'default'";
        // clients are built lazily by {@link client}
    }

    /**
     * Attempts to retrieve a client by its repository metadata and settings from the cache.
     * If the client does not exist it will be created.
     */
    public AmazonS3Reference client(RepositoryMetadata repositoryMetadata) {
        final S3ClientSettings clientSettings = settings(repositoryMetadata);
        {
            final AmazonS3Reference clientReference = clientsCache.get(clientSettings);
            if (clientReference != null && clientReference.tryIncRef()) {
                return clientReference;
            }
        }
        synchronized (this) {
            final AmazonS3Reference existing = clientsCache.get(clientSettings);
            if (existing != null && existing.tryIncRef()) {
                return existing;
            }
            final AmazonS3Reference clientReference = new AmazonS3Reference(buildClient(clientSettings));
            clientReference.incRef();
            clientsCache = MapBuilder.newMapBuilder(clientsCache).put(clientSettings, clientReference).immutableMap();
            return clientReference;
        }
    }

    /**
     * Either fetches {@link S3ClientSettings} for a given {@link RepositoryMetadata} from cached settings or creates them
     * by overriding static client settings from {@link #staticClientSettings} with settings found in the repository metadata.
     * @param repositoryMetadata Repository Metadata
     * @return S3ClientSettings
     */
    S3ClientSettings settings(RepositoryMetadata repositoryMetadata) {
        final Settings settings = repositoryMetadata.settings();
        {
            final S3ClientSettings existing = derivedClientSettings.get(settings);
            if (existing != null) {
                return existing;
            }
        }
        final String clientName = S3Repository.CLIENT_NAME.get(settings);
        final S3ClientSettings staticSettings = staticClientSettings.get(clientName);
        if (staticSettings != null) {
            synchronized (this) {
                final S3ClientSettings existing = derivedClientSettings.get(settings);
                if (existing != null) {
                    return existing;
                }
                final S3ClientSettings newSettings = staticSettings.refine(settings);
                derivedClientSettings = MapBuilder.newMapBuilder(derivedClientSettings).put(settings, newSettings).immutableMap();
                return newSettings;
            }
        }
        throw new IllegalArgumentException(
            "Unknown s3 client name ["
                + clientName
                + "]. Existing client configs: "
                + Strings.collectionToDelimitedString(staticClientSettings.keySet(), ",")
        );
    }

    // proxy for testing
    AmazonS3WithCredentials buildClient(final S3ClientSettings clientSettings) {
        final S3ClientBuilder builder = S3Client.builder();

        final AwsCredentialsProvider credentials = buildCredentials(logger, clientSettings);
        builder.credentialsProvider(credentials);
        builder.httpClient(buildHttpClient(clientSettings));
        builder.overrideConfiguration(buildOverrideConfiguration(clientSettings));

        String endpoint = Strings.hasLength(clientSettings.endpoint) ? clientSettings.endpoint : DEFAULT_S3_ENDPOINT;
        if ((endpoint.startsWith("http://") || endpoint.startsWith("https://")) == false) {
            // Manually add the schema to the endpoint to work around https://github.com/aws/aws-sdk-java/issues/2274
            // TODO: Remove this once fixed in the AWS SDK
            endpoint = clientSettings.protocol.toString() + "://" + endpoint;
        }
        final String region = Strings.hasLength(clientSettings.region) ? clientSettings.region : null;
        logger.debug("using endpoint [{}] and region [{}]", endpoint, region);

        // If the endpoint configuration isn't set on the builder then the default behaviour is to try
        // and work out what region we are in and use an appropriate endpoint - see AwsClientBuilder#setRegion.
        // In contrast, directly-constructed clients use s3.amazonaws.com unless otherwise instructed. We currently
        // use a directly-constructed client, and need to keep the existing behaviour to avoid a breaking change,
        // so to move to using the builder we must set it explicitly to keep the existing behaviour.
        //
        // We do this because directly constructing the client is deprecated (was already deprecated in 1.1.223 too)
        // so this change removes that usage of a deprecated API.
        builder.endpointOverride(URI.create(endpoint));
        if (clientSettings.pathStyleAccess) {
            builder.forcePathStyle(true);
        }
        // TODO not supported in v2?
        // if (clientSettings.disableChunkedEncoding) {
        // builder.disableChunkedEncoding();
        // }
        final S3Client client = SocketAccess.doPrivileged(builder::build);
        return AmazonS3WithCredentials.create(client, credentials);
    }

    static SdkHttpClient buildHttpClient(S3ClientSettings clientSettings) {
        ApacheHttpClient.Builder clientBuilder = ApacheHttpClient.builder();

        // the response metadata cache is only there for diagnostics purposes,
        // but can force objects from every response to the old generation.
        // TODO setResponseMetadataCacheSize is not supported in v2
        // clientConfiguration.setResponseMetadataCacheSize(0);
        // TODO need to set an http: endpoint explicitly, https is default
        // clientConfiguration.setProtocol(clientSettings.protocol);

        if (clientSettings.proxySettings != ProxySettings.NO_PROXY_SETTINGS) {
            if (clientSettings.proxySettings.getType() == ProxySettings.ProxyType.SOCKS) {
                SocketAccess.doPrivilegedVoid(() -> {
                    if (clientSettings.proxySettings.isAuthenticated()) {
                        Authenticator.setDefault(new Authenticator() {
                            @Override
                            protected PasswordAuthentication getPasswordAuthentication() {
                                return new PasswordAuthentication(
                                    clientSettings.proxySettings.getUsername(),
                                    clientSettings.proxySettings.getPassword().toCharArray()
                                );
                            }
                        });
                    }
                    clientBuilder.socketFactory(createSocksSslConnectionSocketFactory(clientSettings.proxySettings.getAddress()));
                });
            } else {
                ProxyConfiguration.Builder proxyConfiguration = ProxyConfiguration.builder();
                if (clientSettings.proxySettings.getType() != ProxySettings.ProxyType.DIRECT) {
                    // TODO how to set proxy protocol?
                    // clientConfiguration.setProxyProtocol(clientSettings.proxySettings.getType().toProtocol());
                }
                // TODO check if this is the same as setting the host and port separately
                proxyConfiguration.endpoint(URI.create(clientSettings.proxySettings.getAddress().toString()));
                proxyConfiguration.username(clientSettings.proxySettings.getUsername());
                proxyConfiguration.password(clientSettings.proxySettings.getPassword());
                // clientConfiguration.setProxyHost(clientSettings.proxySettings.getHostName());
                // clientConfiguration.setProxyPort(clientSettings.proxySettings.getPort());
                clientBuilder.proxyConfiguration(proxyConfiguration.build());
            }
        }

        clientBuilder.socketTimeout(Duration.ofMillis(clientSettings.readTimeoutMillis));

        return clientBuilder.build();
    }

    static ClientOverrideConfiguration buildOverrideConfiguration(final S3ClientSettings clientSettings) {
        ClientOverrideConfiguration.Builder clientOverrideConfiguration = ClientOverrideConfiguration.builder();
        if (Strings.hasLength(clientSettings.signerOverride)) {
            // TODO need to check how signer override can be provided
            // clientOverrideConfiguration = clientOverrideConfiguration.putAdvancedOption(SdkAdvancedClientOption.SIGNER,
            // clientSettings.signerOverride)
        }
        RetryPolicy.Builder retryPolicy = RetryPolicy.builder().numRetries(clientSettings.maxRetries);
        if (clientSettings.throttleRetries) {
            retryPolicy = retryPolicy.throttlingBackoffStrategy(BackoffStrategy.defaultThrottlingStrategy());
        }
        return clientOverrideConfiguration.retryPolicy(retryPolicy.build()).build();
    }

    private static SSLConnectionSocketFactory createSocksSslConnectionSocketFactory(final InetSocketAddress address) {
        // This part was taken from AWS settings
        try {
            // TODO is this the right way to initialize SSLContext?
            final SSLContext sslCtx = SSLContext.getDefault();
            // TODO what are trust managers?
            sslCtx.init(SystemPropertyTlsKeyManagersProvider.create().keyManagers(), null, new SecureRandom());
            return new SdkTlsSocketFactory(sslCtx, new DefaultHostnameVerifier()) {
                @Override
                public Socket createSocket(final HttpContext ctx) throws IOException {
                    return new Socket(new Proxy(Proxy.Type.SOCKS, address));
                }
            };
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            // TODO exception handling needs to be reviewed
            return null;
        }
    }

    // pkg private for tests
    static AwsCredentialsProvider buildCredentials(Logger logger, S3ClientSettings clientSettings) {
        final S3BasicCredentials basicCredentials = clientSettings.credentials;
        final IrsaCredentials irsaCredentials = buildFromEnviroment(clientSettings.irsaCredentials);

        // If IAM Roles for Service Accounts (IRSA) credentials are configured, start with them first
        if (irsaCredentials != null) {
            logger.debug("Using IRSA credentials");

            StsClient stsClient = null;
            final String region = Strings.hasLength(clientSettings.region) ? clientSettings.region : null;

            if (region != null || basicCredentials != null) {
                stsClient = SocketAccess.doPrivileged(() -> {
                    StsClientBuilder builder = StsClient.builder();

                    // Use similar approach to override STS endpoint as SDKGlobalConfiguration.EC2_METADATA_SERVICE_OVERRIDE_SYSTEM_PROPERTY
                    final String stsEndpoint = System.getProperty(STS_ENDPOINT_OVERRIDE_SYSTEM_PROPERTY);
                    if (region != null && stsEndpoint != null) {
                        builder = builder.endpointOverride(URI.create(stsEndpoint));
                        builder = builder.region(Region.of(region));
                    }

                    if (basicCredentials != null) {
                        builder = builder.credentialsProvider(StaticCredentialsProvider.create(basicCredentials));
                    }

                    return builder.build();
                });
            }

            if (irsaCredentials.getIdentityTokenFile() == null) {
                final StsAssumeRoleCredentialsProvider.Builder stsCredentialsProviderBuilder = StsAssumeRoleCredentialsProvider.builder()
                    .stsClient(stsClient)
                    .refreshRequest(
                        AssumeRoleRequest.builder()
                            .roleArn(irsaCredentials.getRoleArn())
                            .roleSessionName(irsaCredentials.getRoleSessionName())
                            .build()
                    );

                final StsAssumeRoleCredentialsProvider stsCredentialsProvider = SocketAccess.doPrivileged(
                    stsCredentialsProviderBuilder::build
                );

                return new PrivilegedSTSAssumeRoleSessionCredentialsProvider<>(stsClient, stsCredentialsProvider);
            } else {
                final StsAssumeRoleWithWebIdentityCredentialsProvider.Builder stsCredentialsProviderBuilder =
                    StsAssumeRoleWithWebIdentityCredentialsProvider.builder()
                        .stsClient(stsClient)
                        .refreshRequest(
                            AssumeRoleWithWebIdentityRequest.builder()
                                .roleArn(irsaCredentials.getRoleArn())
                                .roleSessionName(irsaCredentials.getRoleSessionName())
                                // TODO need to see if there is a difference between the token (that is needed here) and the token file
                                // (which is being passed)
                                .webIdentityToken(irsaCredentials.getIdentityTokenFile())
                                .build()
                        );
                // new StsAssumeRoleWithWebIdentityCredentialsProvider.Builder(
                // irsaCredentials.getRoleArn(),
                // irsaCredentials.getRoleSessionName(),
                // irsaCredentials.getIdentityTokenFile()
                // ).withStsClient(stsClient);

                final StsAssumeRoleWithWebIdentityCredentialsProvider stsCredentialsProvider = SocketAccess.doPrivileged(
                    stsCredentialsProviderBuilder::build
                );

                return new PrivilegedSTSAssumeRoleSessionCredentialsProvider<>(stsClient, stsCredentialsProvider);
            }
        } else if (basicCredentials != null) {
            logger.debug("Using basic key/secret credentials");
            return StaticCredentialsProvider.create(basicCredentials);
        } else {
            logger.debug("Using instance profile credentials");
            return new PrivilegedInstanceProfileCredentialsProvider();
        }
    }

    private static IrsaCredentials buildFromEnviroment(IrsaCredentials defaults) {
        if (defaults == null) {
            return null;
        }

        String webIdentityTokenFile = defaults.getIdentityTokenFile();
        if (webIdentityTokenFile == null) {
            webIdentityTokenFile = System.getenv(SdkSystemSetting.AWS_WEB_IDENTITY_TOKEN_FILE.environmentVariable());
        }

        String roleArn = defaults.getRoleArn();
        if (roleArn == null) {
            roleArn = System.getenv(SdkSystemSetting.AWS_ROLE_ARN.environmentVariable());
        }

        String roleSessionName = defaults.getRoleSessionName();
        if (roleSessionName == null) {
            roleSessionName = System.getenv(SdkSystemSetting.AWS_ROLE_SESSION_NAME.environmentVariable());
        }

        return new IrsaCredentials(webIdentityTokenFile, roleArn, roleSessionName);
    }

    private synchronized void releaseCachedClients() {
        // the clients will shutdown when they will not be used anymore
        for (final AmazonS3Reference clientReference : clientsCache.values()) {
            clientReference.decRef();
        }

        // clear previously cached clients, they will be build lazily
        clientsCache = emptyMap();
        derivedClientSettings = emptyMap();
    }

    static class PrivilegedInstanceProfileCredentialsProvider implements AwsCredentialsProvider {
        private final AwsCredentialsProvider credentials;

        private PrivilegedInstanceProfileCredentialsProvider() {
            this.credentials = initializeProvider();
        }

        private AwsCredentialsProvider initializeProvider() {
            if (SdkSystemSetting.AWS_CONTAINER_CREDENTIALS_RELATIVE_URI.getStringValue().isPresent()
                || SdkSystemSetting.AWS_CONTAINER_CREDENTIALS_FULL_URI.getStringValue().isPresent()) {

                return ContainerCredentialsProvider.builder().asyncCredentialUpdateEnabled(true).build();
            }
            // InstanceProfileCredentialsProvider as last item of chain
            return InstanceProfileCredentialsProvider.builder().asyncCredentialUpdateEnabled(true).build();
        }

        @Override
        public AwsCredentials resolveCredentials() {
            return SocketAccess.doPrivileged(credentials::resolveCredentials);
        }
    }

    static class PrivilegedSTSAssumeRoleSessionCredentialsProvider<P extends AwsCredentialsProvider>
        implements
            AwsCredentialsProvider,
            Closeable {
        private final P credentials;
        private final StsClient stsClient;

        private PrivilegedSTSAssumeRoleSessionCredentialsProvider(@Nullable final StsClient stsClient, final P credentials) {
            this.stsClient = stsClient;
            this.credentials = credentials;
        }

        @Override
        public void close() throws IOException {
            SocketAccess.doPrivilegedIOException(() -> {
                if (stsClient != null) {
                    stsClient.close();
                }
                return null;
            });
        }

        @Override
        public AwsCredentials resolveCredentials() {
            return SocketAccess.doPrivileged(credentials::resolveCredentials);
        }
    }

    @Override
    public void close() {
        releaseCachedClients();
    }
}
