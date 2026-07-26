/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2026 Kenny Root
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

package org.connectbot.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class RemoteMouseTrackingTest {
    private val tracking = RemoteMouseTracking()

    @Test
    fun disabledMouseTracking_doesNotEncodeWheelEvents() {
        assertFalse(tracking.isEnabled)
        assertNull(tracking.encodeWheelEvent(MouseWheelDirection.UP, row = 0, column = 0))
    }

    @Test
    fun splitModeSequences_enableSgrMouseWheelEncoding() {
        tracking.consume("\u001B[?100".toByteArray())
        tracking.consume("2h\u001B[?10".toByteArray())
        tracking.consume("06h".toByteArray())

        assertTrue(tracking.isEnabled)
        assertArrayEquals(
            "\u001B[<64;10;5M".toByteArray(StandardCharsets.US_ASCII),
            tracking.encodeWheelEvent(MouseWheelDirection.UP, row = 4, column = 9),
        )
        assertArrayEquals(
            "\u001B[<65;10;5M".toByteArray(StandardCharsets.US_ASCII),
            tracking.encodeWheelEvent(MouseWheelDirection.DOWN, row = 4, column = 9),
        )
    }

    @Test
    fun combinedModeSequence_enablesTrackingAndProtocol() {
        tracking.consume("\u001B[?1000;1006h".toByteArray())

        assertTrue(tracking.isEnabled)
        assertArrayEquals(
            "\u001B[<64;1;1M".toByteArray(StandardCharsets.US_ASCII),
            tracking.encodeWheelEvent(MouseWheelDirection.UP, row = 0, column = 0),
        )
    }

    @Test
    fun disablingMouseTracking_stopsWheelEncoding() {
        tracking.consume("\u001B[?1002h\u001B[?1006h".toByteArray())
        tracking.consume("\u001B[?1002l".toByteArray())

        assertFalse(tracking.isEnabled)
        assertNull(tracking.encodeWheelEvent(MouseWheelDirection.UP, row = 0, column = 0))
    }

    @Test
    fun disablingExtendedProtocol_fallsBackToX10Encoding() {
        tracking.consume("\u001B[?1000h\u001B[?1006h\u001B[?1006l".toByteArray())

        assertArrayEquals(
            byteArrayOf(0x1b, 0x5b, 0x4d, 0x60, 0x2b, 0x26),
            tracking.encodeWheelEvent(MouseWheelDirection.UP, row = 5, column = 10),
        )
    }

    @Test
    fun c1CsiSequence_isRecognized() {
        tracking.consume(
            byteArrayOf(
                0x9b.toByte(),
                0x3f,
                0x31,
                0x30,
                0x30,
                0x30,
                0x68,
            ),
        )

        assertTrue(tracking.isEnabled)
    }

    @Test
    fun reset_clearsParserAndMouseState() {
        tracking.consume("\u001B[?1002h\u001B[?1006h".toByteArray())

        tracking.reset()

        assertFalse(tracking.isEnabled)
        assertNull(tracking.encodeWheelEvent(MouseWheelDirection.DOWN, row = 1, column = 1))
    }

    @Test
    fun unrelatedPrivateModes_doNotEnableMouseTracking() {
        tracking.consume("\u001B[?25h\u001B[?1049h".toByteArray())

        assertFalse(tracking.isEnabled)
    }
}
