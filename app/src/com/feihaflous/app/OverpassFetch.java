package com.feihaflous.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Overpass API fetch with sequential endpoint fallback.
 *
 * Fair-use shape: at most one request in flight at a time, at most one
 * request per endpoint per call, no automatic retries. Each attempt is
 * hard-capped by a watchdog that disconnects the socket, so total worst
 * case is ENDPOINTS.length * ATTEMPT_CAP_MS (about 66s) and a healthy
 * endpoint answers in roughly a second.
 *
 * Endpoint order follows measured health (2026-11-23):
 *  - overpass.openstreetmap.fr answered a live query in ~1.3s (HTTP 200)
 *  - overpass-api.de returned HTTP 502 "server at capacity"
 *  - overpass.private.coffee answered /api/status but interpreter hung
 */
public final class OverpassFetch {

    public interface Callback {
        void onResult(String json);
        void onError(String reason); // "timeout" | "net" | "http-<code>"
    }

    public static final String[] ENDPOINTS = {
        "https://overpass.openstreetmap.fr/api/interpreter?data=",
        "https://overpass-api.de/api/interpreter?data=",
        "https://overpass.private.coffee/api/interpreter?data="
    };

    static final int CONNECT_MS = 6000;
    static final int READ_MS = 18000;
    static final int ATTEMPT_CAP_MS = 22000;
    public static final String USER_AGENT = "FeihaFlousApp/1.6.2";

    private OverpassFetch() {}

    public static String buildQuery(double lat, double lng, double radius) {
        int r = (int) Math.min(3000, Math.max(800, radius));
        return "[out:json][timeout:15];(node[\"amenity\"=\"atm\"](around:" + r + ","
                + lat + "," + lng + ");way[\"amenity\"=\"atm\"](around:" + r + ","
                + lat + "," + lng + "););out center 400;";
    }

    /** Sequential attempts; the callback fires exactly once. */
    public static void fetchAsync(final double lat, final double lng, final double radius,
                                  final Callback cb) {
        Thread t = new Thread(() -> {
            String best = null;
            for (String endpoint : ENDPOINTS) {
                String reason = attempt(endpoint, buildQuery(lat, lng, radius), cb);
                if (reason == null) return; // success; cb.onResult already fired
                best = pickMoreInformative(best, reason);
            }
            cb.onError(best == null ? "net" : best);
        });
        t.setDaemon(true);
        t.start();
    }

    /** @return null on success (cb.onResult fired), otherwise a failure reason */
    private static String attempt(String endpoint, String query, Callback cb) {
        final HttpURLConnection[] connBox = new HttpURLConnection[1];
        final String[] result = new String[1];
        final String[] fail = new String[1];
        Thread worker = new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(endpoint
                        + URLEncoder.encode(query, "UTF-8")).openConnection();
                connBox[0] = conn;
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setConnectTimeout(CONNECT_MS);
                conn.setReadTimeout(READ_MS);
                int code = conn.getResponseCode();
                if (code != 200) {
                    fail[0] = "http-" + code;
                    conn.disconnect();
                    return;
                }
                InputStream in = conn.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
                conn.disconnect();
                result[0] = bos.toString("UTF-8");
            } catch (SocketTimeoutException e) {
                fail[0] = "timeout";
            } catch (Exception e) {
                fail[0] = "net";
            }
        });
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(ATTEMPT_CAP_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            // Hard bound: kill the stuck socket and report a timeout.
            HttpURLConnection c = connBox[0];
            if (c != null) c.disconnect();
            worker.interrupt();
            return "timeout";
        }
        if (result[0] != null) {
            cb.onResult(result[0]);
            return null;
        }
        return fail[0] == null ? "net" : fail[0];
    }

    /** Prefer a concrete HTTP code, then a timeout, then a generic network error. */
    private static String pickMoreInformative(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        if (b.startsWith("http-")) return b;
        if (a.startsWith("http-")) return a;
        if ("timeout".equals(b)) return b;
        return a;
    }
}
