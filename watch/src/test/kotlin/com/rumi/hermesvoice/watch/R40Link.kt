package com.rumi.hermesvoice.watch

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * R40 test double for one paired Wear channel: a PROSPECTIVE qualification envelope, never a measured link. Both directions share
 * [bytesPerSecond] of airtime, served byte-fair in [quantum]-byte turns and work conserving (an idle direction's share goes to the
 * other). Each direction keeps a [sendBuffer]-byte send buffer behind its output stream (Google documents ChannelClient output as
 * buffered; its real size is not known here) and a write blocks only while that buffer is full. Crossed bytes are readable at the far
 * end at once, possibly mid-frame. No per-call cost: the pinned ChannelClient streams are plain descriptor delegates (sdk-probe WEAR1).
 * The airtime clock is absolute, so a late host wake-up delays delivery but never loses capacity. [held] stops the air (a stall).
 */
internal class R40Link(private val bytesPerSecond: Long, private val sendBuffer: Int = 2048, private val quantum: Int = 256) : AutoCloseable {
    private val lock = ReentrantLock(); private val work = lock.newCondition(); private val space = lock.newCondition()
    @Volatile private var stopped = false
    @Volatile var held = false

    inner class Direction {
        private val queued = ArrayDeque<ByteArray>(); private var queuedBytes = 0; private var headAt = 0 // guarded by lock
        private val crossedChunks = LinkedBlockingQueue<ByteArray>()
        /** Content-free hop counters: bytes accepted by write calls, bytes that crossed, write calls. */
        val written = AtomicLong(); val crossed = AtomicLong(); val calls = AtomicLong()
        @Volatile var closed = false
        private var readHead: ByteArray? = null; private var readAt = 0
        val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                calls.incrementAndGet(); var at = off; val end = off + len
                while (at < end) lock.withLock {
                    while (queuedBytes >= sendBuffer && !closed && !stopped) space.await(20, TimeUnit.MILLISECONDS)
                    if (closed || stopped) throw IOException("link closed")
                    val n = minOf(end - at, sendBuffer - queuedBytes)
                    queued.addLast(b.copyOfRange(at, at + n)); queuedBytes += n; at += n; written.addAndGet(n.toLong()); work.signalAll()
                }
            }
            override fun close() = this@Direction.close()
        }
        val input: InputStream = object : InputStream() {
            override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255 }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                while (readHead == null) {
                    if ((closed || stopped) && crossedChunks.isEmpty()) return -1
                    readHead = crossedChunks.poll(20, TimeUnit.MILLISECONDS); readAt = 0
                }
                val h = readHead!!; val n = minOf(len, h.size - readAt); h.copyInto(b, off, readAt, readAt + n); readAt += n
                if (readAt == h.size) readHead = null
                return n
            }
            override fun close() = this@Direction.close()
        }
        fun close() { closed = true; lock.withLock { space.signalAll(); work.signalAll() } }
        /** The air only, under [lock]: the next up to [max] queued bytes, or null. */
        internal fun take(max: Int): ByteArray? {
            if (queuedBytes == 0 || closed) return null
            val out = ByteArrayOutputStream(max)
            while (out.size() < max && queued.isNotEmpty()) {
                val head = queued.first(); val n = minOf(max - out.size(), head.size - headAt)
                out.write(head, headAt, n); headAt += n; if (headAt == head.size) { queued.removeFirst(); headAt = 0 }
            }
            queuedBytes -= out.size(); space.signalAll(); return out.toByteArray()
        }
        internal fun arrive(bytes: ByteArray) { crossedChunks.put(bytes); crossed.addAndGet(bytes.size.toLong()) }
    }

    /** Watch to Phone, and Phone to Watch. */
    val up = Direction(); val down = Direction()
    private val air = Thread({
        var clock = System.nanoTime(); var turn = 0
        while (!stopped) {
            var from: Direction? = null; var bytes: ByteArray? = null
            lock.withLock {
                if (!held) for (i in 0..1) {
                    val d = if ((turn + i) % 2 == 0) up else down; val b = d.take(quantum)
                    if (b != null) { from = d; bytes = b; turn = (turn + i + 1) % 2; break }
                }
                if (bytes == null) { work.await(5, TimeUnit.MILLISECONDS); clock = maxOf(clock, System.nanoTime()) }
            }
            val b = bytes ?: continue
            clock += b.size * 1_000_000_000L / bytesPerSecond
            while (!stopped) { val left = clock - System.nanoTime(); if (left <= 0) break; if (left > 2_500_000) Thread.sleep(1) else Thread.onSpinWait() }
            from!!.arrive(b)
        }
    }, "r40-air").apply { isDaemon = true; start() }

    override fun close() { stopped = true; up.close(); down.close() }
}
