package com.maksos.interiorar

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AssetDigestTest {
    @Test fun sha256MatchesKnownVector() {
        val file = File.createTempFile("digest-test", ".glb")
        try {
            file.writeText("abc")
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                AssetDigest.sha256(file)
            )
        } finally { file.delete() }
    }
    @Test fun largeStreamHasSameDigestAsIncrementalJavaReference() {
        val file = File.createTempFile("digest-large", ".glb")
        try {
            file.outputStream().use { out ->
                repeat(512) { out.write(ByteArray(4096) { (it % 251).toByte() }) }
            }
            val md = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { it.copyTo(object:java.io.OutputStream() {
                override fun write(b:Int) { md.update(b.toByte()) }
                override fun write(b:ByteArray,off:Int,len:Int) { md.update(b,off,len) }
            }) }
            val expected = md.digest().joinToString("") { "%02x".format(it) }
            assertEquals(expected, AssetDigest.sha256(file))
        } finally { file.delete() }
    }
}
