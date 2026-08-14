package com.atakmap.android.icu.serve;

import android.content.Context;
import android.net.wifi.WifiManager;

import com.atakmap.android.icu.capture.EncoderConfig;
import com.atakmap.android.icu.serve.ts.MpegTsMuxer;
import com.atakmap.coremap.log.Log;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.util.Collections;
import java.util.List;

/**
 * LAN one-to-many transport: mux H.264 (+AAC) into MPEG-TS and send it to a UDP multicast
 * group. A receiver plays it with a single URL — {@code udp://@<group>:<port>} — and, unlike
 * the on-device RTSP server, it carries audio and scales to any number of listeners without
 * per-viewer sockets. Sent to a private multicast group (224.0.0.0–239.255.255.255), which
 * only routes on the local link/mesh (multicast is not forwarded over cellular).
 *
 * <p>Registered alongside {@link OnDeviceRtspTransport} for the LAN destination, so existing
 * RTSP-pull viewers keep working while multicast-capable receivers use the TS feed.</p>
 */
public class MulticastTsTransport implements Transport {

    private static final String TAG = "ICU.MulticastTs";
    // 7 TS packets = 1316 bytes: the standard MPEG-TS-over-UDP payload (fits a 1500-byte MTU).
    private static final int PACKETS_PER_DATAGRAM = 7;

    private final String group;
    private final int    port;
    private final Context appContext;        // for the Wi-Fi multicast lock (may be null)

    private MulticastSocket socket;
    private InetAddress      groupAddr;
    private MpegTsMuxer      muxer;
    private WifiManager.MulticastLock multicastLock;

    private final byte[] datagram = new byte[PACKETS_PER_DATAGRAM * 188];
    private int packetsBuffered;

    private volatile boolean up;
    private volatile String  failReason;
    private volatile boolean audioEnabled;

    public MulticastTsTransport(MediaServerConfig cfg, Context appContext) {
        this.group = cfg.multicastGroup.trim();
        this.port  = cfg.multicastPort;
        this.appContext = appContext;
    }

    @Override public String name() { return "LAN multicast (MPEG-TS)"; }

    @Override
    public synchronized void start(EncoderConfig config) throws Exception {
        groupAddr = InetAddress.getByName(group);
        if (!groupAddr.isMulticastAddress())
            throw new IllegalArgumentException(group + " is not a multicast address (use 224–239.x.x.x)");

        acquireMulticastLock();
        socket = new MulticastSocket();
        socket.setTimeToLive(16);            // enough hops for a small mesh, still link-local-ish
        try { socket.setLoopbackMode(true); } catch (Exception ignored) {}  // don't echo to ourselves

        audioEnabled = config.streamAudio;
        muxer = new MpegTsMuxer(this::onTsPacket);
        packetsBuffered = 0;
        up = true;
        failReason = null;
        Log.d(TAG, "multicast TS → " + group + ":" + port + (audioEnabled ? " (with audio)" : ""));
    }

    @Override
    public synchronized void onFormat(byte[] sps, byte[] pps) {
        if (muxer != null) muxer.setVideoConfig(sps, pps);
    }

    @Override
    public synchronized void onNal(byte[] data, boolean keyFrame, long ptsUs) {
        if (!up || muxer == null) return;
        try {
            muxer.writeVideo(data, keyFrame, ptsUs);
            flush();                          // bound latency to one access unit
        } catch (Exception e) {
            noteFailure(e);
        }
    }

    @Override
    public synchronized void onAudioFormat(byte[] asc, int sampleRate, int channels) {
        if (muxer != null && audioEnabled) muxer.setAudioConfig(sampleRate, channels);
    }

    @Override
    public synchronized void onAudioSample(byte[] aac, long ptsUs) {
        if (!up || muxer == null || !audioEnabled) return;
        try {
            muxer.writeAudio(aac, ptsUs);
            flush();
        } catch (Exception e) {
            noteFailure(e);
        }
    }

    /** Batch TS packets into datagrams; a full group is sent immediately. */
    private void onTsPacket(byte[] pkt188) {
        System.arraycopy(pkt188, 0, datagram, packetsBuffered * 188, 188);
        if (++packetsBuffered == PACKETS_PER_DATAGRAM) sendDatagram(packetsBuffered * 188);
    }

    private void flush() {
        if (packetsBuffered > 0) sendDatagram(packetsBuffered * 188);
    }

    private void sendDatagram(int len) {
        try {
            socket.send(new DatagramPacket(datagram, len, groupAddr, port));
        } catch (Exception e) {
            noteFailure(e);
        } finally {
            packetsBuffered = 0;
        }
    }

    private void noteFailure(Exception e) {
        // Non-terminal: a transient send error (e.g. no route while a network change is in
        // flight) shouldn't kill the broadcast — log once and let the next datagram retry.
        if (failReason == null) {
            failReason = e.getMessage();
            Log.w(TAG, "multicast send: " + e.getMessage());
        }
    }

    @Override
    public synchronized void stop() {
        up = false;
        packetsBuffered = 0;
        if (socket != null) { try { socket.close(); } catch (Exception ignored) {} socket = null; }
        muxer = null;
        releaseMulticastLock();
    }

    @Override
    public List<StreamEndpoint> endpoints() {
        if (!up) return Collections.emptyList();
        return Collections.singletonList(new StreamEndpoint("UDP", "udp://@" + group + ":" + port));
    }

    @Override public int viewerCount() { return -1; }   // multicast has no per-viewer accounting

    @Override
    public String statusLine() {
        if (!up) return null;
        return "LAN multicast: udp://@" + group + ":" + port
                + (audioEnabled ? " (A/V)" : " (video)");
    }

    // failureReason() intentionally left as the default (null): send hiccups are transient and
    // must not tear down the whole broadcast the way a push-auth rejection does.

    private void acquireMulticastLock() {
        try {
            if (appContext == null) return;
            WifiManager wifi = (WifiManager) appContext.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return;
            multicastLock = wifi.createMulticastLock("ICU-multicast");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        } catch (Throwable t) {
            Log.w(TAG, "multicast lock: " + t.getMessage());
        }
    }

    private void releaseMulticastLock() {
        try { if (multicastLock != null && multicastLock.isHeld()) multicastLock.release(); }
        catch (Throwable ignored) {}
        multicastLock = null;
    }
}
