package com.enclavia.vault.crypto

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Chunked AES-256-GCM file format ("STREAM" construction).
 *
 * Layout:
 *   header  = "ENCV" | version(1) | chunkSize(int32) | salt(16)        (25 bytes)
 *   chunk_i = AES-GCM(fileKey, nonce_i, plaintext_i, aad = header | finalFlag) (len + 16 tag)
 *
 *   fileKey  = HMAC-SHA256(DEK, "enclavia-file-v1" | salt)   -> unique key per file
 *   nonce_i  = 0x00000000 | int64(i)                          -> chunk order is authenticated
 *   finalFlag marks the last chunk, so truncating the file is detected.
 *
 * Fixed-size chunks give random access, which lets the video player seek and stream
 * without ever writing a decrypted copy to disk.
 */
object EncryptedFile {
  private val MAGIC = byteArrayOf('E'.code.toByte(), 'N'.code.toByte(), 'C'.code.toByte(), 'V'.code.toByte())
  private const val VERSION: Byte = 1
  const val CHUNK_SIZE = 64 * 1024
  const val TAG_SIZE = 16
  const val HEADER_SIZE = 4 + 1 + 4 + 16
  private const val MIN_CHUNK = 4 * 1024
  private const val MAX_CHUNK = 4 * 1024 * 1024
  private val FILE_KEY_INFO = "enclavia-file-v1".toByteArray()
  private val rng = SecureRandom()

  internal fun deriveFileKey(dek: ByteArray, salt: ByteArray): SecretKeySpec {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(dek, "HmacSHA256"))
    mac.update(FILE_KEY_INFO)
    val keyBytes = mac.doFinal(salt)
    try {
      return SecretKeySpec(keyBytes, "AES")
    } finally {
      keyBytes.fill(0)
    }
  }

  internal fun nonce(index: Long): GCMParameterSpec {
    val iv = ByteBuffer.allocate(12).putInt(0).putLong(index).array()
    return GCMParameterSpec(TAG_SIZE * 8, iv)
  }

  /**
   * Encrypts [input] into [dest]. Returns the plaintext length.
   * [digest], when given, receives every plaintext byte (used to verify the copy afterwards).
   */
  fun encrypt(input: InputStream, dest: File, dek: ByteArray, digest: MessageDigest? = null): Long {
    val salt = ByteArray(16).also { rng.nextBytes(it) }
    val header = ByteBuffer.allocate(HEADER_SIZE).put(MAGIC).put(VERSION).putInt(CHUNK_SIZE).put(salt).array()
    val key = deriveFileKey(dek, salt)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    var cur = ByteArray(CHUNK_SIZE)
    var next = ByteArray(CHUNK_SIZE)
    val out = ByteArray(CHUNK_SIZE + TAG_SIZE)
    var total = 0L
    try {
      val fos = FileOutputStream(dest)
      BufferedOutputStream(fos, 256 * 1024).use { os ->
        os.write(header)
        var curLen = readFully(input, cur)
        var index = 0L
        while (true) {
          val nextLen = if (curLen < CHUNK_SIZE) 0 else readFully(input, next)
          val isFinal = nextLen == 0
          cipher.init(Cipher.ENCRYPT_MODE, key, nonce(index))
          cipher.updateAAD(header)
          cipher.updateAAD(byteArrayOf(if (isFinal) 1 else 0))
          val n = cipher.doFinal(cur, 0, curLen, out, 0)
          os.write(out, 0, n)
          digest?.update(cur, 0, curLen)
          total += curLen
          if (isFinal) break
          val tmp = cur; cur = next; next = tmp
          curLen = nextLen
          index++
        }
        os.flush()
        fos.fd.sync()
      }
    } finally {
      cur.fill(0); next.fill(0)
    }
    return total
  }

  /** Encrypts an in-memory buffer (thumbnails). */
  fun encryptBytes(data: ByteArray, dest: File, dek: ByteArray) {
    data.inputStream().use { encrypt(it, dest, dek) }
  }

  /** Decrypts the whole file into memory. Only for small/medium files (images, thumbnails). */
  fun decryptToBytes(file: File, dek: ByteArray, maxBytes: Long = 256L * 1024 * 1024): ByteArray {
    Reader(file, dek).use { r ->
      if (r.plainLength > maxBytes) throw IOException("File too large to load in memory")
      val result = ByteArray(r.plainLength.toInt())
      var pos = 0L
      while (pos < r.plainLength) {
        val n = r.read(pos, result, pos.toInt(), (r.plainLength - pos).toInt())
        if (n <= 0) break
        pos += n
      }
      return result
    }
  }

  /** Streams the decrypted contents into [out]. Returns bytes written. */
  fun decryptTo(file: File, dek: ByteArray, out: OutputStream, digest: MessageDigest? = null): Long {
    Reader(file, dek).use { r ->
      val buf = ByteArray(r.chunkSize)
      var pos = 0L
      while (pos < r.plainLength) {
        val n = r.read(pos, buf, 0, buf.size)
        if (n <= 0) break
        out.write(buf, 0, n)
        digest?.update(buf, 0, n)
        pos += n
      }
      buf.fill(0)
      return pos
    }
  }

  private fun readFully(input: InputStream, buf: ByteArray): Int {
    var off = 0
    while (off < buf.size) {
      val n = input.read(buf, off, buf.size - off)
      if (n < 0) break
      off += n
    }
    return off
  }

  /**
   * Random-access decrypting reader. Not thread-safe; use one per consumer.
   */
  class Reader(file: File, dek: ByteArray) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    private val header = ByteArray(HEADER_SIZE)
    private val key: SecretKeySpec
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val chunkSize: Int
    private val chunkCount: Long
    val plainLength: Long
    private val cipherBuf: ByteArray
    private val plainBuf: ByteArray
    private var cachedIndex = -1L
    private var cachedLen = 0

    init {
      try {
        raf.readFully(header)
        val bb = ByteBuffer.wrap(header)
        val magic = ByteArray(4).also { bb.get(it) }
        if (!magic.contentEquals(MAGIC)) throw IOException("Not a vault file")
        val version = bb.get()
        if (version != VERSION) throw IOException("Unsupported vault file version $version")
        chunkSize = bb.int
        if (chunkSize < MIN_CHUNK || chunkSize > MAX_CHUNK) throw IOException("Corrupt header")
        val salt = ByteArray(16).also { bb.get(it) }
        val dataLen = raf.length() - HEADER_SIZE
        val stride = (chunkSize + TAG_SIZE).toLong()
        if (dataLen < TAG_SIZE) throw IOException("Truncated vault file")
        chunkCount = (dataLen + stride - 1) / stride
        val lastLen = dataLen - (chunkCount - 1) * stride
        if (lastLen < TAG_SIZE) throw IOException("Truncated vault file")
        plainLength = dataLen - chunkCount * TAG_SIZE
        key = deriveFileKey(dek, salt)
        cipherBuf = ByteArray(chunkSize + TAG_SIZE)
        plainBuf = ByteArray(chunkSize)
      } catch (e: Throwable) {
        raf.close()
        throw e
      }
    }

    /** Reads up to [len] plaintext bytes at [position]. Returns -1 at end of file. */
    fun read(position: Long, dst: ByteArray, off: Int, len: Int): Int {
      if (position >= plainLength) return -1
      val index = position / chunkSize
      loadChunk(index)
      val inChunk = (position - index * chunkSize).toInt()
      val n = minOf(len, cachedLen - inChunk)
      System.arraycopy(plainBuf, inChunk, dst, off, n)
      return n
    }

    private fun loadChunk(index: Long) {
      if (index == cachedIndex) return
      val stride = (chunkSize + TAG_SIZE).toLong()
      val offset = HEADER_SIZE + index * stride
      val isFinal = index == chunkCount - 1
      val ctLen = if (isFinal) (raf.length() - offset).toInt() else chunkSize + TAG_SIZE
      raf.seek(offset)
      raf.readFully(cipherBuf, 0, ctLen)
      cipher.init(Cipher.DECRYPT_MODE, key, nonce(index))
      cipher.updateAAD(header)
      cipher.updateAAD(byteArrayOf(if (isFinal) 1 else 0))
      cachedLen = cipher.doFinal(cipherBuf, 0, ctLen, plainBuf, 0) // throws AEADBadTagException on tamper
      cachedIndex = index
    }

    override fun close() {
      plainBuf.fill(0)
      cachedIndex = -1
      raf.close()
    }
  }
}
