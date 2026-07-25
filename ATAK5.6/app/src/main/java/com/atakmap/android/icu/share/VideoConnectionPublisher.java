package com.atakmap.android.icu.share;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.icu.serve.MediaServerConfig;
import com.atakmap.android.video.ConnectionEntry;
import com.atakmap.android.video.manager.VideoXMLHandler;
import com.atakmap.comms.TAKServer;
import com.atakmap.comms.http.TakHttpClient;
import com.atakmap.comms.http.TakHttpResponse;
import com.atakmap.coremap.log.Log;

import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;

import java.util.Collections;

/**
 * Registers the live stream as a <b>feed on the TAK Server's Video Feed Manager</b>
 * (the {@code /Marti/VideoManager.jsp} list), so operators can find it server-side —
 * not just via the CoT {@code <__video>} on the self marker.
 *
 * <p>That list is backed by the server's video-connection DB and is only populated by
 * POSTing {@code <videoConnections>} XML to the {@code /Marti/vcm} endpoint (the JSP
 * reads nothing from CoT). We reuse ATAK's {@link VideoXMLHandler} to serialize the
 * exact schema the server expects, and {@link TakHttpClient} so the EUD's existing
 * client cert/truststore authenticates the request.</p>
 *
 * <p>The server dedupes by {@code <uid>}: re-POSTing the same uuid updates the row
 * rather than duplicating it. DELETE requires {@code ROLE_ADMIN} (a field EUD won't
 * have), so {@link #unpublish} flips the feed inactive via another POST instead.</p>
 */
public final class VideoConnectionPublisher {

    private static final String TAG = "ICU.VideoPublisher";

    /** Add or update an active feed for this stream on the connected TAK Server. */
    public void publish(MediaServerConfig cfg, String uuid) {
        postFeed(cfg, uuid, true);
    }

    /** Flip the server feed inactive (EUD certs can't DELETE the row). */
    public void unpublish(MediaServerConfig cfg, String uuid) {
        postFeed(cfg, uuid, false);
    }

    private void postFeed(final MediaServerConfig cfg, final String uuid, final boolean active) {
        new Thread(() -> {
            try {
                TAKServer server = pickServer();
                if (server == null) {
                    Log.w(TAG, "no connected TAK Server; feed not published");
                    return;
                }

                ConnectionEntry ce = new ConnectionEntry(
                        cfg.alias == null || cfg.alias.trim().isEmpty() ? "ICU VideoStreamer" : cfg.alias.trim(),
                        cfg.viewUrl());          // 2-arg ctor parses address/port/path/protocol
                ce.setUID(uuid);

                // serialize() emits the server's <videoConnections><feed>… schema but has no
                // <active> element — inject it so publish/unpublish can toggle the feed.
                String xml = VideoXMLHandler.serialize(Collections.singletonList(ce))
                        .replace("<feed>\n", "<feed>\n<active>" + active + "</active>\n");

                // TakHttpClient itself appends the server API port (8443) + "/Marti",
                // so hand it ONLY the host — passing the full CoT URL (…:8089) yields a
                // broken "…:8089:8443/Marti/Marti/vcm". Request path is just "vcm".
                String host = hostOf(server);
                if (host == null) { Log.w(TAG, "no server host; feed not published"); return; }
                TakHttpClient client = TakHttpClient.GetHttpClient("https://" + host);
                try {
                    HttpPost post = new HttpPost(client.getUrl("vcm"));
                    post.setHeader("Content-Type", "application/xml");
                    post.setEntity(new StringEntity(xml, "UTF-8"));
                    TakHttpResponse resp = client.execute(post);
                    Log.d(TAG, "vcm POST (" + (active ? "publish" : "unpublish") + ") → "
                            + resp.getStatusCode());
                } finally {
                    client.shutdown();
                }
            } catch (Throwable t) {
                Log.w(TAG, "feed " + (active ? "publish" : "unpublish") + " failed: " + t.getMessage());
            }
        }, "ICU-VideoPublish").start();
    }

    /** First connected server, else the first configured one (still cert-authenticated). */
    private TAKServer pickServer() {
        CotMapComponent cmc = CotMapComponent.getInstance();
        if (cmc == null) return null;
        TAKServer[] servers = cmc.getServers();
        if (servers == null || servers.length == 0) return null;
        for (TAKServer s : servers) if (s.isConnected()) return s;
        return servers[0];
    }

    /** Bare hostname of the server (no scheme/port) — TakHttpClient adds the API port. */
    private static String hostOf(TAKServer server) {
        try {
            String url = server.getURL(true);           // e.g. https://host:8089
            if (url == null) return null;
            String host = java.net.URI.create(url).getHost();
            return (host == null || host.isEmpty()) ? null : host;
        } catch (Throwable t) {
            return null;
        }
    }
}
