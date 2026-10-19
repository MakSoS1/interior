package com.maksos.interiorar

import java.io.InputStream
import java.io.ByteArrayOutputStream

/** API-29-compatible bounded read (does not depend on newer InputStream.readNBytes). */
fun InputStream.readAtMost(limit:Int):ByteArray {
    require(limit>=0)
    val out=ByteArrayOutputStream(minOf(limit,64*1024));val buffer=ByteArray(minOf(maxOf(limit,1),64*1024))
    while(out.size()<limit) {
        val n=read(buffer,0,minOf(buffer.size,limit-out.size()))
        if(n<0) break
        if(n==0) {val one=read();if(one<0) break;out.write(one)} else out.write(buffer,0,n)
    }
    return out.toByteArray()
}
