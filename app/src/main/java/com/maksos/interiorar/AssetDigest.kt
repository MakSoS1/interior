package com.maksos.interiorar

import java.io.File
import java.security.MessageDigest

/** Bounded-heap content-addressed digest for models up to 200 MiB. */
object AssetDigest {
    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                if (size > 0) md.update(buffer, 0, size)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
