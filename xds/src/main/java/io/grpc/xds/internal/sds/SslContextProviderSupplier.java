/*
 * Copyright 2020 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.xds.internal.sds;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import io.grpc.xds.EnvoyServerProtoData.BaseTlsContext;
import io.grpc.xds.EnvoyServerProtoData.DownstreamTlsContext;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.TlsContextManager;
import io.netty.handler.ssl.SslContext;

/**
 * Enables Client or server side to initialize this object with the received {@link BaseTlsContext}
 * and communicate it to the consumer i.e. {@link SdsProtocolNegotiators}
 * to lazily evaluate the {@link SslContextProvider}. The supplier prevents credentials leakage in
 * cases where the user is not using xDS credentials but the client/server contains a non-default
 * {@link BaseTlsContext}.
 */
public final class SslContextProviderSupplier implements Closeable {

  private final BaseTlsContext tlsContext;
  private final TlsContextManager tlsContextManager;
  private SslContextProvider sslContextProvider;
  private boolean shutdown;

  public SslContextProviderSupplier(
      BaseTlsContext tlsContext, TlsContextManager tlsContextManager) {
    this.tlsContext = checkNotNull(tlsContext, "tlsContext");
    this.tlsContextManager = checkNotNull(tlsContextManager, "tlsContextManager");
  }

  public BaseTlsContext getTlsContext() {
    return tlsContext;
  }

  /** Updates SslContext via the passed callback. */
  public synchronized void updateSslContext(final SslContextProvider.Callback callback) {
    checkNotNull(callback, "callback");
    try {
      if (!shutdown) {
        if (sslContextProvider == null) {
          sslContextProvider = getSslContextProvider();
        }
      }
      // we want to increment the ref-count so call findOrCreate again...
      final SslContextProvider toRelease = getSslContextProvider();
      toRelease.addCallback(
          new SslContextProvider.Callback(callback.getExecutor()) {

            @Override
            public void updateSecret(SslContext sslContext) {
              callback.updateSecret(sslContext);
              releaseSslContextProvider(toRelease);
            }

            @Override
            public void onException(Throwable throwable) {
              callback.onException(throwable);
              releaseSslContextProvider(toRelease);
            }
          });
    } catch (final Throwable throwable) {
      callback.getExecutor().execute(new Runnable() {
        @Override
        public void run() {
          callback.onException(throwable);
        }
      });
    }
  }

  private void releaseSslContextProvider(SslContextProvider toRelease) {
    if (tlsContext instanceof UpstreamTlsContext) {
      tlsContextManager.releaseClientSslContextProvider(toRelease);
    } else {
      tlsContextManager.releaseServerSslContextProvider(toRelease);
    }
  }

  private SslContextProvider getSslContextProvider() {
    return tlsContext instanceof UpstreamTlsContext
        ? tlsContextManager.findOrCreateClientSslContextProvider((UpstreamTlsContext) tlsContext)
        : tlsContextManager.findOrCreateServerSslContextProvider((DownstreamTlsContext) tlsContext);
  }

  @VisibleForTesting public boolean isShutdown() {
    return shutdown;
  }

  /** Called by consumer when tlsContext changes. */
  @Override
  public synchronized void close() {
    if (sslContextProvider != null) {
      if (tlsContext instanceof UpstreamTlsContext) {
        tlsContextManager.releaseClientSslContextProvider(sslContextProvider);
      } else {
        tlsContextManager.releaseServerSslContextProvider(sslContextProvider);
      }
    }
    sslContextProvider = null;
    shutdown = true;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("tlsContext", tlsContext)
        .add("tlsContextManager", tlsContextManager)
        .add("sslContextProvider", sslContextProvider)
        .add("shutdown", shutdown)
        .toString();
  }
}
