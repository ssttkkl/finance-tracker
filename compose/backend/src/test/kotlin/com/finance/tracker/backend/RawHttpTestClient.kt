package com.finance.tracker.backend

internal fun rawHttpGet(port: Int,path: String,headers: Map<String,String> = mapOf("Authorization" to "Bearer synthetic-admin")): Pair<Int,String> = java.net.Socket("127.0.0.1",port).use {socket ->
        socket.soTimeout=10000
        socket.getOutputStream().write(("GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n${headers.entries.joinToString("") {"${it.key}: ${it.value}\r\n"}}Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII));socket.getOutputStream().flush()
        val response=socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
        val headers=response.substringBefore("\r\n\r\n");val payload=response.substringAfter("\r\n\r\n")
        val body=if(headers.lowercase().contains("transfer-encoding: chunked")) {
            val output=java.io.ByteArrayOutputStream();var cursor=0
            while(true) {
                val end=payload.indexOf("\r\n",cursor);val size=payload.substring(cursor,end).substringBefore(';').toInt(16)
                if(size==0) break
                cursor=end+2;output.write(payload.substring(cursor,cursor+size).toByteArray(Charsets.ISO_8859_1));cursor+=size+2
            }
            output.toByteArray().toString(Charsets.UTF_8)
        } else payload.toByteArray(Charsets.ISO_8859_1).toString(Charsets.UTF_8)
        headers.substringBefore("\r\n").split(' ')[1].toInt() to body
    }
