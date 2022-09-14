package io.emeraldpay.dshackle.upstream.signature

import io.emeraldpay.dshackle.upstream.Upstream

class NoSigner : ResponseSigner {
    override fun sign(nonce: Long, message: ByteArray, source: String): ResponseSigner.Signature? {
        return null
    }
}
