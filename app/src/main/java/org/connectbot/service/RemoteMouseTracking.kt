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

import java.nio.charset.StandardCharsets

internal enum class MouseWheelDirection {
    UP,
    DOWN,
}

/**
 * Tracks DEC private mouse modes requested by the remote application and encodes
 * wheel events using the negotiated xterm mouse protocol.
 *
 * The terminal library already interprets these modes for display state but does
 * not currently expose its mouse state or mouse dispatch API. Tracking the small
 * subset of DEC modes here lets the Compose gesture layer forward vertical swipes
 * only while the remote application has explicitly requested mouse input.
 */
internal class RemoteMouseTracking {
    private enum class ParserState {
        GROUND,
        ESCAPE,
        CSI,
        PRIVATE_MODE,
    }

    private enum class Protocol {
        X10,
        UTF8,
        SGR,
        RXVT,
    }

    private data class Snapshot(
        val enabled: Boolean = false,
        val protocol: Protocol = Protocol.X10,
    )

    private var parserState = ParserState.GROUND
    private val privateModeParameters = StringBuilder()

    @Volatile
    private var snapshot = Snapshot()

    val isEnabled: Boolean
        get() = snapshot.enabled

    fun reset() {
        parserState = ParserState.GROUND
        privateModeParameters.setLength(0)
        snapshot = Snapshot()
    }

    fun consume(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        require(offset >= 0 && length >= 0 && offset + length <= data.size)

        for (index in offset until offset + length) {
            consumeByte(data[index].toInt() and 0xff)
        }
    }

    fun encodeWheelEvent(
        direction: MouseWheelDirection,
        row: Int,
        column: Int,
    ): ByteArray? {
        val current = snapshot
        if (!current.enabled) return null

        val code = when (direction) {
            MouseWheelDirection.UP -> WHEEL_UP_CODE
            MouseWheelDirection.DOWN -> WHEEL_DOWN_CODE
        }
        val safeRow = row.coerceAtLeast(0)
        val safeColumn = column.coerceAtLeast(0)

        return when (current.protocol) {
            Protocol.X10 -> encodeX10(code, safeRow, safeColumn)

            Protocol.UTF8 -> encodeUtf8(code, safeRow, safeColumn)

            Protocol.SGR ->
                "\u001B[<$code;${safeColumn + 1};${safeRow + 1}M"
                    .toByteArray(StandardCharsets.US_ASCII)

            Protocol.RXVT ->
                "\u001B[${code + X10_OFFSET};${safeColumn + 1};${safeRow + 1}M"
                    .toByteArray(StandardCharsets.US_ASCII)
        }
    }

    private fun consumeByte(value: Int) {
        when (parserState) {
            ParserState.GROUND -> {
                parserState = when (value) {
                    ESC -> ParserState.ESCAPE
                    C1_CSI -> ParserState.CSI
                    else -> ParserState.GROUND
                }
            }

            ParserState.ESCAPE -> {
                parserState = when (value) {
                    CSI_BRACKET -> ParserState.CSI
                    ESC -> ParserState.ESCAPE
                    else -> ParserState.GROUND
                }
            }

            ParserState.CSI -> {
                if (value == PRIVATE_MARKER) {
                    privateModeParameters.setLength(0)
                    parserState = ParserState.PRIVATE_MODE
                } else {
                    parserState = if (value == ESC) ParserState.ESCAPE else ParserState.GROUND
                }
            }

            ParserState.PRIVATE_MODE -> consumePrivateModeByte(value)
        }
    }

    private fun consumePrivateModeByte(value: Int) {
        when {
            value in DIGIT_ZERO..DIGIT_NINE || value == PARAMETER_SEPARATOR -> {
                if (privateModeParameters.length < MAX_PARAMETER_LENGTH) {
                    privateModeParameters.append(value.toChar())
                } else {
                    resetParser()
                }
            }

            value == MODE_SET || value == MODE_RESET -> {
                applyPrivateModes(enabled = value == MODE_SET)
                resetParser()
            }

            value == ESC -> {
                resetParser()
                parserState = ParserState.ESCAPE
            }

            else -> resetParser()
        }
    }

    private fun applyPrivateModes(enabled: Boolean) {
        var updated = snapshot
        privateModeParameters
            .split(PARAMETER_SEPARATOR.toChar())
            .mapNotNull(String::toIntOrNull)
            .forEach { mode ->
                updated = when (mode) {
                    MOUSE_CLICK_MODE,
                    MOUSE_DRAG_MODE,
                    MOUSE_MOVE_MODE,
                    -> updated.copy(enabled = enabled)

                    MOUSE_UTF8_PROTOCOL -> updated.copy(
                        protocol = if (enabled) Protocol.UTF8 else Protocol.X10,
                    )

                    MOUSE_SGR_PROTOCOL -> updated.copy(
                        protocol = if (enabled) Protocol.SGR else Protocol.X10,
                    )

                    MOUSE_RXVT_PROTOCOL -> updated.copy(
                        protocol = if (enabled) Protocol.RXVT else Protocol.X10,
                    )

                    else -> updated
                }
            }
        snapshot = updated
    }

    private fun resetParser() {
        privateModeParameters.setLength(0)
        parserState = ParserState.GROUND
    }

    private fun encodeX10(code: Int, row: Int, column: Int): ByteArray {
        val cappedColumn = column.coerceAtMost(MAX_X10_COORDINATE)
        val cappedRow = row.coerceAtMost(MAX_X10_COORDINATE)
        return byteArrayOf(
            ESC.toByte(),
            CSI_BRACKET.toByte(),
            MOUSE_MARKER.toByte(),
            (code + X10_OFFSET).toByte(),
            (cappedColumn + COORDINATE_OFFSET).toByte(),
            (cappedRow + COORDINATE_OFFSET).toByte(),
        )
    }

    private fun encodeUtf8(code: Int, row: Int, column: Int): ByteArray {
        val payload = buildString {
            appendCodePoint(code + X10_OFFSET)
            appendCodePoint(column + COORDINATE_OFFSET)
            appendCodePoint(row + COORDINATE_OFFSET)
        }.toByteArray(StandardCharsets.UTF_8)

        return byteArrayOf(ESC.toByte(), CSI_BRACKET.toByte(), MOUSE_MARKER.toByte()) + payload
    }

    private companion object {
        private const val ESC = 0x1b
        private const val C1_CSI = 0x9b
        private const val CSI_BRACKET = 0x5b
        private const val PRIVATE_MARKER = 0x3f
        private const val PARAMETER_SEPARATOR = 0x3b
        private const val MODE_SET = 0x68
        private const val MODE_RESET = 0x6c
        private const val MOUSE_MARKER = 0x4d
        private const val DIGIT_ZERO = 0x30
        private const val DIGIT_NINE = 0x39

        private const val MOUSE_CLICK_MODE = 1000
        private const val MOUSE_DRAG_MODE = 1002
        private const val MOUSE_MOVE_MODE = 1003
        private const val MOUSE_UTF8_PROTOCOL = 1005
        private const val MOUSE_SGR_PROTOCOL = 1006
        private const val MOUSE_RXVT_PROTOCOL = 1015

        private const val WHEEL_UP_CODE = 64
        private const val WHEEL_DOWN_CODE = 65
        private const val X10_OFFSET = 0x20
        private const val COORDINATE_OFFSET = 0x21
        private const val MAX_X10_COORDINATE = 0xff - COORDINATE_OFFSET
        private const val MAX_PARAMETER_LENGTH = 64
    }
}
