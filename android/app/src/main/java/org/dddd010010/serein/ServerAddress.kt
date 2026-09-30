package org.dddd010010.serein

import java.net.URI

object ServerAddress {
    fun normalize(value: String): String {
        val input=value.trim().trimEnd('/')
        val uri=URI(if(input.contains("://")) input else "https://$input")
        require(uri.scheme in listOf("http","https") && !uri.host.isNullOrBlank())
        require(uri.userInfo==null && uri.rawQuery==null && uri.rawFragment==null)
        val path=uri.rawPath.orEmpty().trimEnd('/')
        require(path.isEmpty() || path=="/music")
        return URI(uri.scheme.lowercase(),null,uri.host,uri.port,"/music",null,null).toASCIIString()
    }
}
