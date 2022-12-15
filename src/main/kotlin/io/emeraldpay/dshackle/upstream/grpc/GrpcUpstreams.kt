/**
 * Copyright (c) 2020 EmeraldPay, Inc
 * Copyright (c) 2019 ETCDEV GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.emeraldpay.dshackle.upstream.grpc

import io.emeraldpay.api.proto.BlockchainOuterClass
import io.emeraldpay.api.proto.ReactorBlockchainGrpc
import io.emeraldpay.dshackle.BlockchainType
import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.Defaults
import io.emeraldpay.dshackle.FileResolver
import io.emeraldpay.dshackle.config.AuthConfig
import io.emeraldpay.dshackle.config.UpstreamsConfig
import io.emeraldpay.dshackle.startup.UpstreamChangeEvent
import io.emeraldpay.dshackle.upstream.DefaultUpstream
import io.emeraldpay.dshackle.upstream.UpstreamAvailability
import io.emeraldpay.dshackle.upstream.rpcclient.JsonRpcGrpcClient
import io.emeraldpay.dshackle.upstream.rpcclient.RpcMetrics
import io.grpc.netty.NettyChannelBuilder
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Timer
import io.netty.handler.ssl.ApplicationProtocolConfig
import io.netty.handler.ssl.ClientAuth
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import org.apache.commons.lang3.StringUtils
import org.apache.commons.lang3.exception.ExceptionUtils
import org.slf4j.LoggerFactory
import reactor.core.Disposable
import reactor.core.publisher.Flux
import java.io.IOException
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class GrpcUpstreams(
    private val id: String,
    private val hash: Byte,
    private val role: UpstreamsConfig.UpstreamRole,
    private val host: String,
    private val port: Int,
    private val auth: AuthConfig.ClientTlsAuth? = null,
    private val fileResolver: FileResolver,
    private val nodeRating: Int,
    private val labels: UpstreamsConfig.Labels
) {
    private val log = LoggerFactory.getLogger(GrpcUpstreams::class.java)

    var timeout = Defaults.timeout

    private var client: ReactorBlockchainGrpc.ReactorBlockchainStub? = null
    private val known = HashMap<Chain, DefaultUpstream>()
    private val lock = ReentrantLock()

    fun start(): Flux<UpstreamChangeEvent> {
        val chanelBuilder = NettyChannelBuilder.forAddress(host, port)
            // some messages are very large. many of them in megabytes, some even in gigabytes (ex. ETH Traces)
            .maxInboundMessageSize(Defaults.maxMessageSize)
            .enableRetry()
            .maxRetryAttempts(3)
        if (auth != null && StringUtils.isNotEmpty(auth.ca)) {
            chanelBuilder
                .useTransportSecurity()
                .sslContext(withTls(auth))
        } else {
            log.warn("Using insecure connection to $host:$port")
            chanelBuilder.usePlaintext()
        }

        val client = ReactorBlockchainGrpc.newReactorStub(chanelBuilder.build())
        this.client = client

        val statusSubscription = AtomicReference<Disposable>()

        val updates = Flux.interval(Duration.ZERO, Duration.ofSeconds(20))
            .flatMap {
                client.describe(BlockchainOuterClass.DescribeRequest.newBuilder().build())
            }.onErrorContinue { t, _ ->
                if (ExceptionUtils.indexOfType(t, IOException::class.java) >= 0) {
                    log.warn("gRPC upstream $host:$port is unavailable. (${t.javaClass}: ${t.message})")
                    known.values.forEach {
                        it.setStatus(UpstreamAvailability.UNAVAILABLE)
                    }
                } else {
                    log.error("Failed to get description from $host:$port", t)
                }
            }.flatMap { value ->
                processDescription(value)
            }.doOnNext {
                val subscription = client.subscribeStatus(BlockchainOuterClass.StatusRequest.newBuilder().build())
                    .subscribe { value ->
                        val chain = Chain.byId(value.chain.number)
                        if (chain != Chain.UNSPECIFIED) {
                            known[chain]?.onStatus(value)
                        }
                    }
                statusSubscription.updateAndGet { prev ->
                    prev?.dispose()
                    subscription
                }
            }.doOnError { t ->
                log.error("Failed to process update from gRPC upstream $id", t)
            }

        return updates
    }

    fun processDescription(value: BlockchainOuterClass.DescribeResponse): Flux<UpstreamChangeEvent> {
        val current = value.chainsList.filter {
            Chain.byId(it.chain.number) != Chain.UNSPECIFIED
        }.mapNotNull { chainDetails ->
            try {
                val chain = Chain.byId(chainDetails.chain.number)
                val up = getOrCreate(chain)
                (up.upstream as GrpcUpstream).update(chainDetails)
                up
            } catch (e: Throwable) {
                log.warn("Skip unsupported upstream ${chainDetails.chain} on $id: ${e.message}")
                null
            }
        }

        val added = current.filter {
            it.type == UpstreamChangeEvent.ChangeType.ADDED
        }

        val removed = known.filterNot { kv ->
            val stillCurrent = current.any { c -> c.chain == kv.key }
            stillCurrent
        }.map {
            UpstreamChangeEvent(it.key, known.remove(it.key)!!, UpstreamChangeEvent.ChangeType.REMOVED)
        }
        return Flux.fromIterable(removed + added)
    }

    internal fun withTls(auth: AuthConfig.ClientTlsAuth): SslContext {
        val sslContext = SslContextBuilder.forClient()
            .clientAuth(ClientAuth.REQUIRE)
        sslContext.trustManager(fileResolver.resolve(auth.ca!!).inputStream())
        if (StringUtils.isNotEmpty(auth.key) && StringUtils.isNoneEmpty(auth.certificate)) {
            sslContext.keyManager(
                fileResolver.resolve(auth.certificate!!).inputStream(),
                fileResolver.resolve(auth.key!!).inputStream()
            )
        } else {
            log.warn("Connect to remote using only CA certificate")
        }
        val alpn = ApplicationProtocolConfig(
            ApplicationProtocolConfig.Protocol.ALPN,
            ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
            ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
            "grpc-exp", "h2"
        )
        sslContext.applicationProtocolConfig(alpn)
        return sslContext.build()
    }

    fun getOrCreate(chain: Chain): UpstreamChangeEvent {
        val metricsTags = listOf(
            Tag.of("upstream", id),
            Tag.of("chain", chain.chainCode)
        )

        val metrics = RpcMetrics(
            Timer.builder("upstream.grpc.conn")
                .description("Request time through a Dshackle/gRPC connection")
                .tags(metricsTags)
                .publishPercentileHistogram()
                .register(Metrics.globalRegistry),
            Counter.builder("upstream.grpc.fail")
                .description("Number of failures of Dshackle/gRPC requests")
                .tags(metricsTags)
                .register(Metrics.globalRegistry)
        )

        val blockchainType = BlockchainType.from(chain)
        if (blockchainType == BlockchainType.EVM_POW) {
            return getOrCreateEthereum(chain, metrics)
        } else if (blockchainType == BlockchainType.BITCOIN) {
            return getOrCreateBitcoin(chain, metrics)
        } else if (blockchainType == BlockchainType.EVM_POS) {
            return getOrCreateEthereumPos(chain, metrics)
        } else {
            throw IllegalArgumentException("Unsupported blockchain: $chain")
        }
    }

    fun getOrCreateEthereum(chain: Chain, metrics: RpcMetrics): UpstreamChangeEvent {
        lock.withLock {
            val current = known[chain]
            return if (current == null) {
                val rpcClient = JsonRpcGrpcClient(client!!, chain, metrics)
                val created = EthereumGrpcUpstream(id, hash, role, chain, client!!, rpcClient, labels)
                created.timeout = this.timeout
                known[chain] = created
                created.start()
                UpstreamChangeEvent(chain, created, UpstreamChangeEvent.ChangeType.ADDED)
            } else {
                UpstreamChangeEvent(chain, current, UpstreamChangeEvent.ChangeType.REVALIDATED)
            }
        }
    }

    fun getOrCreateEthereumPos(chain: Chain, metrics: RpcMetrics): UpstreamChangeEvent {
        lock.withLock {
            val current = known[chain]
            return if (current == null) {
                val rpcClient = JsonRpcGrpcClient(client!!, chain, metrics)
                val created = EthereumPosGrpcUpstream(id, hash, role, chain, client!!, rpcClient, nodeRating, labels)
                created.timeout = this.timeout
                known[chain] = created
                created.start()
                UpstreamChangeEvent(chain, created, UpstreamChangeEvent.ChangeType.ADDED)
            } else {
                UpstreamChangeEvent(chain, current, UpstreamChangeEvent.ChangeType.REVALIDATED)
            }
        }
    }

    fun getOrCreateBitcoin(chain: Chain, metrics: RpcMetrics): UpstreamChangeEvent {
        lock.withLock {
            val current = known[chain]
            return if (current == null) {
                val rpcClient = JsonRpcGrpcClient(client!!, chain, metrics)
                val created = BitcoinGrpcUpstream(id, role, chain, client!!, rpcClient, labels)
                created.timeout = this.timeout
                known[chain] = created
                created.start()
                UpstreamChangeEvent(chain, created, UpstreamChangeEvent.ChangeType.ADDED)
            } else {
                UpstreamChangeEvent(chain, current, UpstreamChangeEvent.ChangeType.REVALIDATED)
            }
        }
    }

    fun get(chain: Chain): DefaultUpstream {
        return known[chain]!!
    }
}
