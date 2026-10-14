// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.map.packs

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.GZIPInputStream

/**
 * A reader for one PMTiles v3 archive on disk — what a map pack is.
 *
 * Written here rather than handed to MapLibre's own `pmtiles://` source because
 * that takes one file per source, and a phone holding a state has hundreds of
 * packs per layer (see [PackStore]). It does only what serving needs: find a
 * tile by id and return its bytes. Directories are parsed once and kept, and
 * reads are positional, so any number of threads can read one archive at once.
 *
 * Format: https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md
 */
class PmTiles(val file: File) : Closeable {

    private val raf = RandomAccessFile(file, "r")
    private val channel: FileChannel = raf.channel

    val header: Header = Header.parse(read(0, HEADER_BYTES))

    private val root: Directory = Directory.parse(decompress(read(header.rootOffset, header.rootLength.toInt()), header.internalCompression))
    private val leaves = object : LinkedHashMap<Long, Directory>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Directory>) = size > LEAF_CACHE
    }

    /** The tile's bytes as stored (see [Header.tileCompression]), or null. */
    fun tile(z: Int, x: Int, y: Int): ByteArray? = tile(tileId(z, x, y))

    fun tile(id: Long): ByteArray? {
        var dir = root
        repeat(4) {
            val entry = dir.find(id) ?: return null
            if (entry.runLength == 0L) {
                dir = leaf(header.leafDirectoryOffset + entry.offset, entry.length)
            } else {
                return read(header.tileDataOffset + entry.offset, entry.length)
            }
        }
        return null
    }

    /**
     * As [tile], with gzip undone: what MapLibre expects for a vector tile.
     * Decided by the bytes' own magic rather than the header, so a tile stored
     * uncompressed in a gzip archive (tile-join leaves some) still reads.
     */
    fun tileDecoded(z: Int, x: Int, y: Int): ByteArray? {
        val raw = tile(z, x, y) ?: return null
        return if (isGzip(raw)) gunzip(raw) else raw
    }

    private fun leaf(offset: Long, length: Int): Directory = synchronized(leaves) {
        leaves[offset]
    } ?: Directory.parse(decompress(read(offset, length), header.internalCompression)).also {
        synchronized(leaves) { leaves[offset] = it }
    }

    private fun read(offset: Long, length: Int): ByteArray {
        val buf = ByteBuffer.allocate(length)
        var pos = offset
        while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n < 0) throw java.io.EOFException("${file.name}: read past the end at $pos")
            pos += n
        }
        return buf.array()
    }

    override fun close() {
        raf.close()
    }

    data class Header(
        val rootOffset: Long,
        val rootLength: Long,
        val metadataOffset: Long,
        val metadataLength: Long,
        val leafDirectoryOffset: Long,
        val leafDirectoryLength: Long,
        val tileDataOffset: Long,
        val tileDataLength: Long,
        val addressedTiles: Long,
        val internalCompression: Int,
        val tileCompression: Int,
        val tileType: Int,
        val minZoom: Int,
        val maxZoom: Int,
    ) {
        companion object {
            fun parse(bytes: ByteArray): Header {
                require(bytes.size >= HEADER_BYTES && String(bytes, 0, 7, Charsets.US_ASCII) == "PMTiles" && bytes[7].toInt() == 3) {
                    "not a PMTiles v3 archive"
                }
                val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                return Header(
                    rootOffset = b.getLong(8), rootLength = b.getLong(16),
                    metadataOffset = b.getLong(24), metadataLength = b.getLong(32),
                    leafDirectoryOffset = b.getLong(40), leafDirectoryLength = b.getLong(48),
                    tileDataOffset = b.getLong(56), tileDataLength = b.getLong(64),
                    addressedTiles = b.getLong(72),
                    internalCompression = bytes[97].toInt(), tileCompression = bytes[98].toInt(),
                    tileType = bytes[99].toInt(), minZoom = bytes[100].toInt(), maxZoom = bytes[101].toInt(),
                )
            }
        }
    }

    class Entry(val tileId: Long, val offset: Long, val length: Int, val runLength: Long)

    class Directory(private val entries: Array<Entry>) {
        /** The entry holding `id` — exact, or a run or leaf that starts before it. */
        fun find(id: Long): Entry? {
            var lo = 0
            var hi = entries.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val c = entries[mid].tileId
                when {
                    c < id -> lo = mid + 1
                    c > id -> hi = mid - 1
                    else -> return entries[mid]
                }
            }
            if (hi < 0) return null
            val e = entries[hi]
            return when {
                e.runLength == 0L -> e                 // a leaf directory: look inside
                id - e.tileId < e.runLength -> e       // inside a run of repeated tiles
                else -> null
            }
        }

        companion object {
            fun parse(bytes: ByteArray): Directory {
                val r = Varints(bytes)
                val n = r.next().toInt()
                val ids = LongArray(n)
                var last = 0L
                for (i in 0 until n) { last += r.next(); ids[i] = last }
                val runs = LongArray(n) { r.next() }
                val lengths = IntArray(n) { r.next().toInt() }
                val offsets = LongArray(n)
                for (i in 0 until n) {
                    val v = r.next()
                    offsets[i] = if (v == 0L && i > 0) offsets[i - 1] + lengths[i - 1] else v - 1
                }
                return Directory(Array(n) { Entry(ids[it], offsets[it], lengths[it], runs[it]) })
            }
        }
    }

    private class Varints(private val bytes: ByteArray) {
        private var pos = 0
        fun next(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = bytes[pos++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
    }

    companion object {
        const val HEADER_BYTES = 127
        const val COMPRESSION_NONE = 1
        const val COMPRESSION_GZIP = 2
        private const val LEAF_CACHE = 64

        private fun decompress(bytes: ByteArray, compression: Int): ByteArray =
            if (compression == COMPRESSION_GZIP) gunzip(bytes) else bytes

        fun isGzip(b: ByteArray) = b.size > 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte()

        fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }

        /** The PMTiles id of a tile: zoom levels in order, Hilbert order within each. */
        fun tileId(z: Int, x: Int, y: Int): Long {
            var acc = 0L
            for (t in 0 until z) acc += 1L shl (2 * t)
            val n = 1L shl z
            var rx: Long
            var ry: Long
            var d = 0L
            var tx = x.toLong()
            var ty = y.toLong()
            var s = n / 2
            while (s > 0) {
                rx = if (tx and s > 0) 1 else 0
                ry = if (ty and s > 0) 1 else 0
                d += s * s * ((3 * rx) xor ry)
                // rotate
                if (ry == 0L) {
                    if (rx == 1L) {
                        tx = s - 1 - tx
                        ty = s - 1 - ty
                    }
                    val t = tx; tx = ty; ty = t
                }
                s /= 2
            }
            return acc + d
        }
    }
}
