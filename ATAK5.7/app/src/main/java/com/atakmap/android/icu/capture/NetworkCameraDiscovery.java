package com.atakmap.android.icu.capture;

import android.content.Context;
import android.net.wifi.WifiManager;

import com.atakmap.coremap.log.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds RTSP cameras on the connected networks so the operator doesn't have to
 * hand-type a URL. Two phases:
 *
 * <ol>
 *   <li><b>Host discovery</b> — ONVIF WS-Discovery and SSDP multicast probes (cameras
 *       that announce themselves answer from ANY subnet the L2 network bridges — which
 *       is how a camera on a different /24 across a radio mesh is still found), plus a
 *       TCP port-554 sweep of each connected interface's own /24, plus the host of the
 *       currently configured URL (so "type just the IP, tap Find" always works).</li>
 *   <li><b>Path discovery</b> — for each host listening on 554, RTSP DESCRIBE across
 *       the paths cameras actually use, first 200-with-video wins. The same technique
 *       that located a MOHOC's {@code /stream1} in the field.</li>
 * </ol>
 *
 * <p>Runs entirely off the UI thread; budget is roughly 6–8 seconds. Results are full
 * {@code rtsp://host:port/path} URLs ready for {@link NetworkCameraSource}. A host that
 * answers 401 for every path is reported too — the operator adds credentials and the
 * path probe can then be re-run with them in the URL.</p>
 */
public final class NetworkCameraDiscovery {

    private static final String TAG = "ICU.NetCamDiscovery";

    /** Paths tried per host, most-likely first. stream1 leads because it's the
     *  field-verified MOHOC path; the rest cover the common vendor conventions. */
    private static final String[] COMMON_PATHS = {
            "stream1", "live", "stream", "h264", "video", "video1", "ch0", "ch1",
            "main", "cam", "live.sdp", "1", "11", "media/video1",
            "Streaming/Channels/101",                      // Hikvision
            "cam/realmonitor?channel=1&subtype=0",         // Dahua
            "axis-media/media.amp",                        // Axis
            "",                                            // bare root, last
    };

    private static final int SWEEP_TIMEOUT_MS    = 300;
    private static final int DESCRIBE_TIMEOUT_MS = 1500;
    private static final int MULTICAST_WINDOW_MS = 2000;
    private static final int MAX_RTSP_HOSTS      = 8;

    public static final class Result {
        public final String url;
        public final boolean needsAuth;
        Result(String url, boolean needsAuth) { this.url = url; this.needsAuth = needsAuth; }
    }

    public interface Listener {
        /** Called on the discovery thread with every camera found (may be empty). */
        void onDone(List<Result> results);
        /** Coarse progress ("Probing 172.20.1.1…") for the settings UI. */
        default void onProgress(String status) {}
    }

    private NetworkCameraDiscovery() {}

    public static Thread discover(Context ctx, String hintUrl, Listener listener) {
        Thread t = new Thread(() -> {
            List<Result> results = new ArrayList<>();
            try {
                results = run(ctx, hintUrl, listener);
            } catch (Throwable e) {
                Log.w(TAG, "discovery failed: " + e);
            }
            listener.onDone(results);
        }, "ICU-NetCamDiscovery");
        t.start();
        return t;
    }

    private static List<Result> run(Context ctx, String hintUrl, Listener listener) {
        Set<String> hosts = new LinkedHashSet<>();   // insertion order = probe priority

        // The host the operator already has configured always goes first.
        String hintHost = hostOf(hintUrl);
        if (hintHost != null) hosts.add(hintHost);

        // Multicast announcements cross bridged subnets — fire both probes together.
        listener.onProgress("Listening for camera announcements…");
        WifiManager.MulticastLock lock = null;
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                lock = wm.createMulticastLock("icu-netcam-discovery");
                lock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "multicast lock: " + e.getMessage());
        }
        try {
            hosts.addAll(wsDiscoveryProbe());
            hosts.addAll(ssdpProbe());
        } finally {
            try { if (lock != null && lock.isHeld()) lock.release(); } catch (Exception ignored) {}
        }

        // Port-554 sweep of each connected interface's own /24.
        listener.onProgress("Sweeping local subnets for RTSP…");
        hosts.addAll(sweepLocalSubnets());

        // Which of them actually serve RTSP?
        List<String> rtspHosts = new ArrayList<>();
        for (String h : hosts) {
            if (rtspHosts.size() >= MAX_RTSP_HOSTS) break;
            if (portOpen(h, 554, SWEEP_TIMEOUT_MS * 3)) rtspHosts.add(h);
        }
        Log.d(TAG, "candidates " + hosts.size() + " → rtsp hosts " + rtspHosts);

        List<Result> results = new ArrayList<>();
        for (String host : rtspHosts) {
            listener.onProgress("Probing " + host + "…");
            Result r = probePaths(host, credsOf(hintUrl, host));
            if (r != null) results.add(r);
        }
        return results;
    }

    // ── Host discovery ───────────────────────────────────────────────────────────

    /** ONVIF WS-Discovery: multicast Probe for NetworkVideoTransmitter, collect the
     *  responders' source addresses (parsing XAddrs is unnecessary — the camera's IP
     *  is the datagram's origin, and the RTSP port is probed separately anyway). */
    private static Set<String> wsDiscoveryProbe() {
        Set<String> out = new LinkedHashSet<>();
        String probe =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\""
                + " xmlns:w=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\""
                + " xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\""
                + " xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\">"
                + "<e:Header><w:MessageID>uuid:icu-" + System.nanoTime() + "</w:MessageID>"
                + "<w:To e:mustUnderstand=\"true\">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>"
                + "<w:Action e:mustUnderstand=\"true\">"
                + "http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header>"
                + "<e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body>"
                + "</e:Envelope>";
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setSoTimeout(500);
            byte[] payload = probe.getBytes(StandardCharsets.UTF_8);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            sock.send(new DatagramPacket(payload, payload.length, group, 3702));
            long end = System.currentTimeMillis() + MULTICAST_WINDOW_MS;
            byte[] buf = new byte[8192];
            while (System.currentTimeMillis() < end) {
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                try { sock.receive(resp); } catch (Exception timeout) { continue; }
                out.add(resp.getAddress().getHostAddress());
            }
        } catch (Exception e) {
            Log.w(TAG, "ws-discovery: " + e.getMessage());
        }
        if (!out.isEmpty()) Log.d(TAG, "ws-discovery responders: " + out);
        return out;
    }

    /** SSDP M-SEARCH (ssdp:all) — some cameras announce over UPnP instead of ONVIF. */
    private static Set<String> ssdpProbe() {
        Set<String> out = new LinkedHashSet<>();
        String search = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n"
                + "MAN: \"ssdp:discover\"\r\nMX: 1\r\nST: ssdp:all\r\n\r\n";
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setSoTimeout(500);
            byte[] payload = search.getBytes(StandardCharsets.UTF_8);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            sock.send(new DatagramPacket(payload, payload.length, group, 1900));
            long end = System.currentTimeMillis() + MULTICAST_WINDOW_MS;
            byte[] buf = new byte[4096];
            while (System.currentTimeMillis() < end) {
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                try { sock.receive(resp); } catch (Exception timeout) { continue; }
                out.add(resp.getAddress().getHostAddress());
            }
        } catch (Exception e) {
            Log.w(TAG, "ssdp: " + e.getMessage());
        }
        if (!out.isEmpty()) Log.d(TAG, "ssdp responders: " + out);
        return out;
    }

    /** TCP-554 sweep of each up, non-loopback interface's /24 (the interface's own
     *  slice even when the mask is wider, e.g. a /16 mesh — a full /16 sweep would
     *  be 65k hosts). ~64 parallel connects at 300ms each ≈ a second per subnet. */
    private static Set<String> sweepLocalSubnets() {
        Set<String> found = Collections.synchronizedSet(new LinkedHashSet<>());
        List<String> bases = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    String ip = a.getHostAddress();
                    if (ip == null || ip.startsWith("127.")) continue;
                    String base = ip.substring(0, ip.lastIndexOf('.') + 1);
                    if (!bases.contains(base)) bases.add(base);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "interface enumeration: " + e.getMessage());
        }
        if (bases.isEmpty()) return found;

        ExecutorService pool = Executors.newFixedThreadPool(64);
        AtomicInteger pending = new AtomicInteger(bases.size() * 254);
        for (String base : bases) {
            for (int i = 1; i <= 254; i++) {
                String host = base + i;
                pool.execute(() -> {
                    if (portOpen(host, 554, SWEEP_TIMEOUT_MS)) found.add(host);
                    pending.decrementAndGet();
                });
            }
        }
        pool.shutdown();
        try { pool.awaitTermination(8, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        pool.shutdownNow();
        if (!found.isEmpty()) Log.d(TAG, "554 open on: " + found);
        return found;
    }

    private static boolean portOpen(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Path discovery ───────────────────────────────────────────────────────────

    /** DESCRIBE each common path on one connection-per-attempt; the first 200 whose
     *  SDP carries an H.264 video track wins. All-401 = camera found, needs creds. */
    private static Result probePaths(String host, String userInfo) {
        boolean sawAuthDemand = false;
        for (String path : COMMON_PATHS) {
            String target = "rtsp://" + host + ":554/" + path;
            int verdict = describe(host, target, userInfo);
            if (verdict == 200) {
                String url = userInfo != null
                        ? "rtsp://" + userInfo + "@" + host + ":554/" + path : target;
                Log.d(TAG, "camera at " + target);
                return new Result(url, false);
            }
            if (verdict == 401) sawAuthDemand = true;
            if (verdict == -2) return null;   // host stopped answering — don't grind
        }
        if (sawAuthDemand)
            return new Result("rtsp://" + host + ":554/", true);
        return null;
    }

    /** @return 200 if the target serves H.264 video, 401 if it demands auth,
     *          -1 for any other refusal, -2 if the host is unresponsive. */
    private static int describe(String host, String target, String userInfo) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, 554), DESCRIBE_TIMEOUT_MS);
            s.setSoTimeout(DESCRIBE_TIMEOUT_MS);
            OutputStream out = s.getOutputStream();
            String auth = userInfo != null
                    ? "Authorization: Basic " + android.util.Base64.encodeToString(
                            userInfo.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP) + "\r\n"
                    : "";
            out.write(("DESCRIBE " + target + " RTSP/1.0\r\nCSeq: 1\r\n" + auth
                    + "Accept: application/sdp\r\nUser-Agent: ATAK-ICU\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            String head = readUpTo(s.getInputStream(), 4096);
            Matcher m = Pattern.compile("RTSP/1\\.0 (\\d{3})").matcher(head);
            if (!m.find()) return -1;
            int code = Integer.parseInt(m.group(1));
            if (code == 401) return 401;
            if (code != 200) return -1;
            String lower = head.toLowerCase(Locale.US);
            return (lower.contains("m=video") && lower.contains("h264")) ? 200 : -1;
        } catch (Exception e) {
            return -2;
        }
    }

    private static String readUpTo(InputStream in, int max) {
        byte[] buf = new byte[max];
        int total = 0;
        try {
            while (total < max) {
                int n = in.read(buf, total, max - total);
                if (n < 0) break;
                total += n;
                // Stop once the SDP body has arrived (headers + m=video is enough).
                if (new String(buf, 0, total, StandardCharsets.UTF_8).contains("m=")) break;
            }
        } catch (Exception ignored) {}   // timeout: return whatever arrived
        return new String(buf, 0, total, StandardCharsets.UTF_8);
    }

    // ── URL helpers ──────────────────────────────────────────────────────────────

    private static String hostOf(String url) {
        try {
            java.net.URI u = new java.net.URI(url.trim());
            return u.getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /** user:pass from the hint URL, kept only for the SAME host it was typed for. */
    private static String credsOf(String url, String host) {
        try {
            java.net.URI u = new java.net.URI(url.trim());
            if (u.getUserInfo() != null && host.equals(u.getHost())) return u.getUserInfo();
        } catch (Exception ignored) {}
        return null;
    }
}
