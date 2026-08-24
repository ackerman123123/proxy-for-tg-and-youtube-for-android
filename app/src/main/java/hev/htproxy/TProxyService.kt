package hev.htproxy

/**
 * JNI facade provided by the unmodified official heiher/hev-socks5-tunnel build.
 *
 * The method names and package deliberately match the engine defaults from
 * src/hev-jni.c. The native engine owns its worker thread and consumes a YAML
 * configuration file plus the TUN descriptor supplied by Android VpnService.
 */
object TProxyService {
    external fun TProxyStartService(configPath: String, fd: Int): Boolean
    external fun TProxyStopService(): Boolean
    external fun TProxyIsRunning(): Boolean
    external fun TProxyGetStats(): LongArray

    init {
        System.loadLibrary("hev-socks5-tunnel")
    }
}
