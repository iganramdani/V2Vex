package com.v2vex.client.core

/**
 * Ultra-lightweight, zero-dependency ISO-18004 QR Code Encoder (Byte Mode, ECC Level L, Versions 1..10).
 * Supports up to 271 UTF-8 bytes — ideal for VLESS/Trojan/SS/Hysteria2/TUIC URIs with 0 KB external library bloat.
 */
object QrCodeEncoder {

    data class QrMatrix(val size: Int, val modules: BooleanArray) {
        fun get(x: Int, y: Int): Boolean = modules[y * size + x]
    }

    // Version 1..10 (ECC Level L): [totalCodewords, ecCodewordsPerBlock, numBlocks]
    private val VERSION_PARAMS = arrayOf(
        intArrayOf(0, 0, 0),       // 0 unused
        intArrayOf(26, 7, 1),      // V1:  21x21, 17 bytes
        intArrayOf(44, 10, 1),     // V2:  25x25, 32 bytes
        intArrayOf(70, 15, 1),     // V3:  29x29, 53 bytes
        intArrayOf(100, 20, 1),    // V4:  33x33, 78 bytes
        intArrayOf(134, 26, 1),    // V5:  37x37, 106 bytes
        intArrayOf(172, 18, 2),    // V6:  41x41, 134 bytes
        intArrayOf(196, 20, 2),    // V7:  45x45, 154 bytes
        intArrayOf(242, 24, 2),    // V8:  49x49, 192 bytes
        intArrayOf(292, 30, 2),    // V9:  53x53, 230 bytes
        intArrayOf(346, 18, 4)     // V10: 57x57, 271 bytes
    )

    private val ALIGN_POSITIONS = arrayOf(
        intArrayOf(),
        intArrayOf(),
        intArrayOf(6, 18),
        intArrayOf(6, 22),
        intArrayOf(6, 26),
        intArrayOf(6, 30),
        intArrayOf(6, 34),
        intArrayOf(6, 22, 38),
        intArrayOf(6, 24, 42),
        intArrayOf(6, 26, 46),
        intArrayOf(6, 28, 50)
    )

    fun encode(text: String): QrMatrix? {
        val dataBytes = text.toByteArray(Charsets.UTF_8)
        var version = -1
        for (v in 1..10) {
            val totalCw = VERSION_PARAMS[v][0]
            val ecPerBlock = VERSION_PARAMS[v][1]
            val blocks = VERSION_PARAMS[v][2]
            val dataCw = totalCw - ecPerBlock * blocks
            val headerBits = 4 + (if (v < 10) 8 else 16)
            if (headerBits + dataBytes.size * 8 <= dataCw * 8) {
                version = v
                break
            }
        }
        if (version == -1) return null

        val totalCw = VERSION_PARAMS[version][0]
        val ecPerBlock = VERSION_PARAMS[version][1]
        val numBlocks = VERSION_PARAMS[version][2]
        val dataCw = totalCw - ecPerBlock * numBlocks

        // 1. Build bit stream (Mode 0100 = Byte)
        val bits = ArrayList<Boolean>(dataCw * 8)
        fun appendBits(value: Int, count: Int) {
            for (i in count - 1 downTo 0) bits.add(((value ushr i) and 1) != 0)
        }
        appendBits(0b0100, 4)
        appendBits(dataBytes.size, if (version < 10) 8 else 16)
        for (b in dataBytes) appendBits(b.toInt() and 0xFF, 8)
        val terminator = minOf(4, dataCw * 8 - bits.size)
        repeat(terminator) { bits.add(false) }
        while (bits.size % 8 != 0) bits.add(false)
        val padBytes = intArrayOf(0xEC, 0x11)
        var padIdx = 0
        while (bits.size < dataCw * 8) {
            appendBits(padBytes[padIdx % 2], 8)
            padIdx++
        }

        val dataCodewords = ByteArray(dataCw) { i ->
            var v = 0
            for (b in 0 until 8) v = (v shl 1) or if (bits[i * 8 + b]) 1 else 0
            v.toByte()
        }

        // 2. Reed-Solomon ECC & Block Interleaving
        val baseBlockSize = dataCw / numBlocks
        val extraBlocks = dataCw % numBlocks
        val dataBlocks = ArrayList<ByteArray>(numBlocks)
        val ecBlocks = ArrayList<ByteArray>(numBlocks)
        val genPoly = rsGeneratorPoly(ecPerBlock)
        var offset = 0
        for (b in 0 until numBlocks) {
            val len = baseBlockSize + if (b >= numBlocks - extraBlocks) 1 else 0
            val block = dataCodewords.copyOfRange(offset, offset + len)
            offset += len
            dataBlocks.add(block)
            ecBlocks.add(rsComputeRemainder(block, genPoly))
        }

        val finalCw = ArrayList<Byte>(totalCw)
        val maxDataLen = dataBlocks.maxOf { it.size }
        for (i in 0 until maxDataLen) {
            for (blk in dataBlocks) if (i < blk.size) finalCw.add(blk[i])
        }
        for (i in 0 until ecPerBlock) {
            for (ec in ecBlocks) finalCw.add(ec[i])
        }

        // 3. Draw function patterns & data modules
        val size = version * 4 + 17
        val modules = BooleanArray(size * size)
        val isFunction = BooleanArray(size * size)

        fun setFunc(x: Int, y: Int, dark: Boolean) {
            if (x in 0 until size && y in 0 until size) {
                modules[y * size + x] = dark
                isFunction[y * size + x] = true
            }
        }

        // Finder patterns + separators
        fun drawFinder(cx: Int, cy: Int) {
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                    setFunc(cx + dx, cy + dy, dist != 2 && dist != 4)
                }
            }
        }
        drawFinder(3, 3)
        drawFinder(size - 4, 3)
        drawFinder(3, size - 4)

        // Timing patterns
        for (i in 0 until size) {
            if (!isFunction[6 * size + i]) setFunc(i, 6, i % 2 == 0)
            if (!isFunction[i * size + 6]) setFunc(6, i, i % 2 == 0)
        }

        // Alignment patterns
        val aligns = ALIGN_POSITIONS[version]
        for (ay in aligns) {
            for (ax in aligns) {
                if (isFunction[ay * size + ax]) continue
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                        setFunc(ax + dx, ay + dy, dist != 1)
                    }
                }
            }
        }

        // Reserve format info & dark module
        for (i in 0..8) {
            if (i != 6) {
                setFunc(8, i, false)
                setFunc(i, 8, false)
            }
        }
        for (i in 0..7) {
            setFunc(size - 1 - i, 8, false)
            setFunc(8, size - 1 - i, false)
        }
        setFunc(8, size - 8, true)

        // Reserve version info for V7..10
        if (version >= 7) {
            var rem = version
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            val vBits = (version shl 12) or rem
            for (i in 0 until 18) {
                val dark = ((vBits ushr i) and 1) != 0
                val a = size - 11 + i % 3
                val b = i / 3
                setFunc(a, b, dark)
                setFunc(b, a, dark)
            }
        }

        // Place data bits (zigzag) + apply Mask 0 ((x + y) % 2 == 0)
        var bitIdx = 0
        var upward = true
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right--
            for (vert in 0 until size) {
                val y = if (upward) size - 1 - vert else vert
                for (j in 0..1) {
                    val x = right - j
                    if (!isFunction[y * size + x]) {
                        val bit = if (bitIdx < finalCw.size * 8) {
                            ((finalCw[bitIdx / 8].toInt() ushr (7 - (bitIdx % 8))) and 1) != 0
                        } else false
                        val masked = bit xor (((x + y) % 2) == 0)
                        modules[y * size + x] = masked
                        bitIdx++
                    }
                }
            }
            upward = !upward
            right -= 2
        }

        // Write Format Info for ECC Level L (01) + Mask 0 (000) -> 01000 -> BCH 0x77C4
        val formatBits = 0x77C4
        for (i in 0..5) setFunc(8, i, ((formatBits ushr i) and 1) != 0)
        setFunc(8, 7, ((formatBits ushr 6) and 1) != 0)
        setFunc(8, 8, ((formatBits ushr 7) and 1) != 0)
        setFunc(7, 8, ((formatBits ushr 8) and 1) != 0)
        for (i in 9..14) setFunc(14 - i, 8, ((formatBits ushr i) and 1) != 0)
        for (i in 0..7) setFunc(size - 1 - i, 8, ((formatBits ushr i) and 1) != 0)
        for (i in 8..14) setFunc(8, size - 15 + i, ((formatBits ushr i) and 1) != 0)

        return QrMatrix(size, modules)
    }

    private fun gfMul(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z
    }

    private fun rsGeneratorPoly(degree: Int): ByteArray {
        val result = ByteArray(degree)
        result[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in 0 until degree) {
                result[j] = gfMul(result[j].toInt() and 0xFF, root).toByte()
                if (j + 1 < degree) {
                    result[j] = (result[j].toInt() xor (result[j + 1].toInt() and 0xFF)).toByte()
                }
            }
            root = gfMul(root, 0x02)
        }
        return result
    }

    private fun rsComputeRemainder(data: ByteArray, gen: ByteArray): ByteArray {
        val rem = ByteArray(gen.size)
        for (b in data) {
            val factor = (b.toInt() xor rem[0].toInt()) and 0xFF
            System.arraycopy(rem, 1, rem, 0, rem.size - 1)
            rem[rem.size - 1] = 0
            for (i in gen.indices) {
                rem[i] = (rem[i].toInt() xor gfMul(gen[i].toInt() and 0xFF, factor)).toByte()
            }
        }
        return rem
    }
}
