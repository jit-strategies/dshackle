/**
 * Copyright (c) 2023 EmeraldPay, Inc
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
package io.emeraldpay.dshackle.monitoring

import java.nio.ByteBuffer

/**
 * Encodes the values by adding a new line (\n) after each item
 */
class LogEncodingNewLine : LogEncoding {

    private val NL = "\n".toByteArray().first()

    override fun write(bytes: ByteArray): ByteBuffer {
        val wrt = ByteBuffer.allocateDirect(bytes.size + 1)
        wrt.put(bytes)
        wrt.put(NL)
        return wrt.flip()
    }
}
