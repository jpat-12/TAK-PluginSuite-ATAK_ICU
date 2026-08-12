package com.atakmap.android.icu.serve;

import com.atakmap.android.icu.capture.EncoderConfig;
import com.atakmap.android.icu.util.NetworkUtils;
import com.atakmap.coremap.log.Log;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

/**
 * PHASE 2 — UDP multicast transport (pure Java, zero infrastructure).
 *
 * <p>Muxes the encoded H.264 stream into MPEG-TS (see {@link TsMuxer}) and blasts it to
 * a multicast group, e.g. {@code 239.255.0.1:5600}. Every peer on the segment that joins
 * the group receives the same feed with no per-viewer connection — the natural fit for a
 * squad on a shared LAN/mesh where {@code SERVER} (MediaMTX) infrastructure isn't present.
 * ATAK, VLC and ffmpeg all play the advertised {@code udp://group:port} URL directly.</p>
 *
 * <p>Multicast is best-effort UDP: there is no handshake and no viewer count, so late
 * joiners rely on the PAT/PMT + SPS/PPS that {@link TsMuxer} re-sends before every key
 * frame. Keep the encoder's key-frame (GOP) interval short for a quick lock-on.</p>
 */
public class MulticastTransport implements Transport {

    private static final String TAG = "ICU.Multicast";

    private final MediaServerConfig cfg;

    private volatile boolean started;
    private volatile String failure;

    // socket is swapped by reconnect() (callback thread) and read by the muxer's send
    // callback (encoder thread), so it must be volatile for visibility.
    private volatile MulticastSocket socket;
    private InetAddress group;
    private int port;
    private volatile TsMuxer muxer;

    public MulticastTransport(MediaServerConfig cfg) { this.cfg = cfg; }

    @Override public String name() { return "UDP multicast"; }

    @Override
    public void start(EncoderConfig config) throws Exception {
        port  = cfg.multicastPort;
        group = InetAddress.getByName(cfg.multicastGroup);
        if (!group.isMulticastAddress())
            throw new IllegalArgumentException(cfg.multicastGroup + " is not a multicast address");

        socket = new MulticastSocket();
        socket.setTimeToLive(Math.max(1, cfg.multicastTtl));
        // Pin egress to the reachable LAN interface so multicast doesn't leave on a
        // stale/virtual NIC (Android hosts often have several up interfaces).
        NetworkInterface nif = egressInterface();
        if (nif != null) {
            try { socket.setNetworkInterface(nif); }
            catch (Exception e) { Log.w(TAG, "setNetworkInterface: " + e.getMessage()); }
        }

        muxer = new TsMuxer((data, len) -> {
            try {
                socket.send(new DatagramPacket(data, len, group, port));
            } catch (Exception e) {
                if (failure == null) {
                    failure = "multicast send failed: " + e.getMessage();
                    Log.w(TAG, failure);
                }
            }
        });
        started = true;
        Log.d(TAG, "multicast up → " + cfg.multicastUrl() + " (ttl " + cfg.multicastTtl + ")");
    }

    @Override
    public void onFormat(byte[] sps, byte[] pps) {
        if (started && muxer != null) muxer.setConfig(sps, pps);
    }

    @Override
    public void onNal(byte[] data, boolean keyFrame, long ptsUs) {
        if (started && muxer != null) muxer.writeFrame(data, keyFrame, ptsUs);
    }

    @Override
    public void stop() {
        started = false;
        if (socket != null) { try { socket.close(); } catch (Exception ignored) {} socket = null; }
        muxer = null;
    }

    /**
     * Rebind the sending socket to the now-current LAN interface after a network change.
     * The MPEG-TS muxer is left untouched (it keeps running on the encoder thread and still
     * holds SPS/PPS); PAT/PMT + SPS/PPS are already re-sent on every key frame, so receivers
     * on the new segment resync within a GOP. Group/port are unchanged, so the advertised
     * URL stays valid. Swapping only the {@code socket} field (read by the muxer's send
     * callback) avoids mutating the muxer from this callback thread.
     */
    @Override
    public void reconnect() {
        if (!started) return;
        MulticastSocket old = socket;
        try {
            MulticastSocket ns = new MulticastSocket();
            ns.setTimeToLive(Math.max(1, cfg.multicastTtl));
            NetworkInterface nif = egressInterface();
            if (nif != null) {
                try { ns.setNetworkInterface(nif); }
                catch (Exception e) { Log.w(TAG, "setNetworkInterface: " + e.getMessage()); }
            }
            socket = ns;
            failure = null;
            Log.d(TAG, "multicast rebound to current interface after network change");
        } catch (Exception e) {
            failure = "multicast rebind failed: " + e.getMessage();
            Log.w(TAG, failure);
        } finally {
            if (old != null) { try { old.close(); } catch (Exception ignored) {} }
        }
    }

    @Override
    public List<StreamEndpoint> endpoints() {
        if (!started) return Collections.emptyList();
        return Collections.singletonList(new StreamEndpoint("UDP", cfg.multicastUrl()));
    }

    @Override public int viewerCount() { return -1; }  // multicast has no back-channel

    @Override
    public String statusLine() {
        if (!started) return null;
        if (failure != null) return "Multicast: " + failure;
        return "Multicast: " + cfg.multicastGroup + ":" + port;
    }

    // Multicast is a best-effort, additive LAN fan-out running alongside the on-device RTSP
    // server — a send error (e.g. the interface dropped on a handoff) must NOT tear the whole
    // broadcast down. It's rebound by reconnect() on the next network-change callback; here we
    // only surface it in the status line, never as a terminal failure.
    @Override public String failureReason() { return null; }

    /** The up, non-loopback interface owning our reachable IPv4, or null to let the OS pick. */
    private static NetworkInterface egressInterface() {
        try {
            String ip = NetworkUtils.reachableIpv4();
            if (ip == null) return null;
            return NetworkInterface.getByInetAddress(InetAddress.getByName(ip));
        } catch (Exception e) {
            return null;
        }
    }
}
