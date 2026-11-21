package dev.ruimdetijd.aircav

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream

class VoiceRecorder(context: Context) : Closeable {
    private val sampleRate = 16000
    private val channel = AudioFormat.CHANNEL_IN_MONO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val tempFile = File(context.cacheDir, "voice.pcm")
    private val wavFile = File(context.cacheDir, "voice.wav")

    val isRecording: Boolean get() = thread?.isAlive == true

    @SuppressLint("MissingPermission")
    fun start(onTooLong: () -> Unit): Boolean {
        if (isRecording) return true
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channel,
            encoding,
            maxOf(minBuf, 4096),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        this.record = record
        record.startRecording()
        thread = Thread {
            val maxBytes = sampleRate * 2 * MAX_SECONDS
            var total = 0L
            var overrun = false
            FileOutputStream(tempFile).use { out ->
                val buf = ShortArray(2048)
                while (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val n = record.read(buf, 0, buf.size)
                    if (n <= 0) break
                    val bytes = ByteArray(n * 2)
                    for (i in 0 until n) {
                        val v = buf[i].toInt()
                        bytes[i * 2] = (v and 0xFF).toByte()
                        bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                    }
                    out.write(bytes)
                    total += bytes.size
                    if (total >= maxBytes) {
                        overrun = true
                        break
                    }
                }
            }
            if (overrun) onTooLong()
        }.also { it.start() }
        return true
    }

    fun stop(): File? {
        val r = record
        record = null
        r?.let {
            it.stop()
            it.release()
        }
        thread?.join(2000)
        thread = null
        if (!tempFile.exists() || tempFile.length() == 0L) return null
        return writeWav(tempFile, wavFile)
    }

    private fun writeWav(pcm: File, out: File): File {
        val data = pcm.readBytes()
        FileOutputStream(out).use { f ->
            val header = ByteArray(44)
            val totalDataLen = data.size + 36
            writeInt(header, 0, 0x46464952)
            writeInt(header, 4, totalDataLen)
            writeInt(header, 8, 0x45564157)
            writeInt(header, 12, 0x20746D66)
            writeInt(header, 16, 16)
            writeShort(header, 20, 1)
            writeShort(header, 22, 1)
            writeInt(header, 24, sampleRate)
            writeInt(header, 28, sampleRate * 2)
            writeShort(header, 32, 2)
            writeShort(header, 34, 16)
            writeInt(header, 36, 0x61746164) // "data"
            writeInt(header, 40, data.size)
            f.write(header)
            f.write(data)
        }
        return out
    }

    private fun writeInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
        b[off + 2] = ((v shr 16) and 0xFF).toByte()
        b[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun writeShort(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
    }

    /** Best-effort delete of any raw PCM/WAV left in cacheDir. */
    fun discard() {
        tempFile.delete()
        wavFile.delete()
    }

    override fun close() {
        record?.let {
            it.stop()
            it.release()
        }
        record = null
    }

    companion object {
        const val MAX_SECONDS = 60
    }
}
