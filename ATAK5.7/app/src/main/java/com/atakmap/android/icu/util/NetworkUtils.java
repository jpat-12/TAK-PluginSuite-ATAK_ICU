package com.atakmap.android.icu.util;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

/**
 * Helpers for building the reachable RTSP URL that gets advertised over CoT.
 *
 * <p>On a LAN/mesh deployment (our target), peers pull the stream directly from the
 * phone, so we need a non-loopback IPv4 address other EUDs can route to.</p>
 */
public final class NetworkUtils {

    private NetworkUtils() {}

    /**
     * @return the first site-local IPv4 address of an up, non-loopback interface,
     *         or {@code null} if none is found.
     */
    public static String reachableIpv4() {
        try {
            List<NetworkInterface> ifaces =
                    Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface nif : ifaces) {
                if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()) continue;
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr.isLoopbackAddress()) continue;
                    String host = addr.getHostAddress();
                    if (host != null && host.indexOf(':') < 0) { // IPv4 only
                        return host;
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    /** Build the RTSP URL peers will use, e.g. {@code rtsp://10.0.0.5:8554/live}. */
    public static String rtspUrl(int port, String path) {
        String ip = reachableIpv4();
        if (ip == null) ip = "0.0.0.0";
        if (path == null || path.isEmpty()) path = "/live";
        if (path.charAt(0) != '/') path = "/" + path;
        return "rtsp://" + ip + ":" + port + path;
    }
}
