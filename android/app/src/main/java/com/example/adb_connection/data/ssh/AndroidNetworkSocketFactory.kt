package com.example.adb_connection.data.ssh

import android.net.Network
import com.jcraft.jsch.SocketFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** Routes every socket created by JSch through this specific Android [Network]. */
class AndroidNetworkSocketFactory(
    private val network: Network,
    private val connectTimeoutMs: Int
) : SocketFactory {
    override fun createSocket(host: String, port: Int): Socket {
        val socket = Socket()
        try {
            network.bindSocket(socket)
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            return socket
        } catch (e: Exception) {
            socket.close()
            throw e
        }
    }

    override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()

    override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
}
