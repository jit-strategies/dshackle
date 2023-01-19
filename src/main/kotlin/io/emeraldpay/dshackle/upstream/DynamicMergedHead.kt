package io.emeraldpay.dshackle.upstream

import io.emeraldpay.dshackle.commons.DynamicMergeFlux
import io.emeraldpay.dshackle.data.BlockContainer
import io.emeraldpay.dshackle.upstream.forkchoice.ForkChoice
import org.slf4j.LoggerFactory
import reactor.core.Disposable
import reactor.core.scheduler.Scheduler

open class DynamicMergedHead(
    forkChoice: ForkChoice,
    private val label: String = "",
    scheduler: Scheduler
) : AbstractHead(forkChoice, upstreamId = label), Lifecycle {

    companion object {
        private val log = LoggerFactory.getLogger(DynamicMergedHead::class.java)
    }

    private var subscription: Disposable? = null
    private val dynamicFlux: DynamicMergeFlux<String, BlockContainer> = DynamicMergeFlux(scheduler)

    override fun isRunning(): Boolean {
        return subscription != null
    }

    override fun start() {
        super.start()
        subscription?.dispose()
        subscription = super.follow(
            dynamicFlux.asFlux()
        )
    }

    override fun stop() {
        super.stop()
        log.info("Stop dynamic merged head for [$label]")
        dynamicFlux.stop()
        subscription?.dispose()
        subscription = null
    }

    fun addHead(upstream: Upstream) {
        log.debug("adding upstream head of [${upstream.getId()}] to dynamic head of [$label]. Current heads ${dynamicFlux.getKeys()}")
        dynamicFlux.add(upstream.getHead().getFlux(), upstream.getId())
    }

    fun removeHead(id: String) {
        log.debug("removing upstream head of [$id] from dynamic head $label. Current heads ${dynamicFlux.getKeys()}")
        dynamicFlux.remove(id)
    }
}
