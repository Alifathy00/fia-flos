package com.feihaflous.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private LocationManager locationManager;
    private Location lastLocation;
    private boolean listeningLocation = false;

    private static final int REQ_FILE = 1001;
    private static final int REQ_LOCATION = 1002;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(true);

        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                String[] accept = params.getAcceptTypes();
                boolean wantsJson = false;
                if (accept != null) {
                    for (String a : accept) {
                        if (a != null && (a.contains("json") || a.endsWith(".json"))) wantsJson = true;
                    }
                }
                intent.setType("*/*");
                if (accept != null && accept.length > 0) {
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, accept);
                }
                try {
                    startActivityForResult(Intent.createChooser(intent, wantsJson ? "اختار ملف البيانات" : "اختار صورة"), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        setContentView(web);
        web.getSettings().setUserAgentString(web.getSettings().getUserAgentString() + " FeihaFlousApp/1.2");
        web.loadUrl("file:///android_asset/app.html");
        updater = new Updater(this);
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 77);
        }
        web.postDelayed(() -> runUpdateCheck(true, false), 4000);
    }

    private Updater updater;

    void runUpdateCheck(final boolean notify, final boolean manual) {
        if (updater == null) return;
        updater.check(notify, (status, json) ->
            notifyJs("window.onUpdateResult && window.onUpdateResult('" + status + "'," + org.json.JSONObject.quote(json) + "," + manual + ")"));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            if (fileCallback != null) {
                Uri[] result = null;
                if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
                fileCallback.onReceiveValue(result);
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_LOCATION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates();
            } else {
                notifyJs("window.onNativeLocationError && window.onNativeLocationError('denied')");
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private void notifyJs(final String js) {
        if (web == null) return;
        web.post(() -> web.evaluateJavascript(js, null));
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startLocationUpdates() {
        if (!hasLocationPermission()) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        try {
            Location best = null;
            for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
                try {
                    Location l = locationManager.getLastKnownLocation(provider);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                } catch (SecurityException ignored) {}
            }
            if (best != null) {
                lastLocation = best;
                pushLocation(best);
            }
            if (!listeningLocation) {
                listeningLocation = true;
                locListener = new LocationListener() {
                    @Override
                    public void onLocationChanged(Location location) {
                        lastLocation = location;
                        pushLocation(location);
                        // One good fix is enough for a map app: stop GPS so Android does not count high battery use.
                        if (location.getAccuracy() <= 100) stopLocationUpdates();
                    }
                };
                if (web != null) web.postDelayed(() -> stopLocationUpdates(), 45000);
                try {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 15000, 25, locListener);
                } catch (Exception ignored) {}
                try {
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 15000, 25, locListener);
                } catch (Exception ignored) {}
            }
        } catch (SecurityException ignored) {}
    }

    private LocationListener locListener;

    private void stopLocationUpdates() {
        try {
            if (locationManager != null && locListener != null) locationManager.removeUpdates(locListener);
        } catch (Exception ignored) {}
        listeningLocation = false;
    }

    @Override
    protected void onPause() {
        stopLocationUpdates();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasLocationPermission() && web != null && lastLocation != null) startLocationUpdates();
    }

    @Override
    protected void onDestroy() {
        stopLocationUpdates();
        super.onDestroy();
    }

    private void pushLocation(Location l) {
        String js = "window.onNativeLocation && window.onNativeLocation({lat:" + l.getLatitude()
                + ",lng:" + l.getLongitude() + ",accuracy:" + l.getAccuracy() + "})";
        notifyJs(js);
    }

    private File dbFile() {
        return new File(getFilesDir(), "feihaflous_db.json");
    }

    class Bridge {
        @JavascriptInterface
        public String loadDb() {
            try {
                File f = dbFile();
                if (!f.exists()) return "";
                FileInputStream in = new FileInputStream(f);
                byte[] buf = new byte[(int) f.length()];
                int read = in.read(buf);
                in.close();
                if (read <= 0) return "";
                return new String(buf, 0, read, StandardCharsets.UTF_8);
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void saveDb(String json) {
            try {
                FileOutputStream out = new FileOutputStream(dbFile());
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.close();
            } catch (Exception ignored) {}
        }

        // ---- Account credentials: separate private store, excluded from backups and DB export ----
        @JavascriptInterface
        public void saveAuth(String json) {
            try {
                getSharedPreferences("feihaflous_auth", MODE_PRIVATE)
                        .edit().putString("json", json).apply();
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public String loadAuth() {
            try {
                return getSharedPreferences("feihaflous_auth", MODE_PRIVATE)
                        .getString("json", "");
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void clearAuth() {
            try {
                getSharedPreferences("feihaflous_auth", MODE_PRIVATE)
                        .edit().clear().apply();
            } catch (Exception ignored) {}
        }

        // ---- Generic backend HTTPS call (Firebase Auth + Firestore REST) ----
        @JavascriptInterface
        public void backendRequest(final String reqId, final String method, final String url,
                                   final String body, final String bearer, final String contentType) {
            new Thread(() -> {
                try {
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    conn.setRequestMethod(method);
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setRequestProperty("User-Agent", "FeihaFlousApp/1.7.0");
                    if (bearer != null && !bearer.isEmpty()) {
                        conn.setRequestProperty("Authorization", "Bearer " + bearer);
                    }
                    if (body != null && !body.isEmpty()) {
                        conn.setDoOutput(true);
                        conn.setRequestProperty("Content-Type",
                                (contentType == null || contentType.isEmpty() ? "application/json" : contentType) + "; charset=utf-8");
                        java.io.OutputStream os = conn.getOutputStream();
                        os.write(body.getBytes(StandardCharsets.UTF_8));
                        os.close();
                    }
                    int code = conn.getResponseCode();
                    java.io.InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                    String resp = "";
                    if (in != null) {
                        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                        in.close();
                        resp = bos.toString("UTF-8");
                    }
                    conn.disconnect();
                    notifyJs("window.onBackendResult && window.onBackendResult("
                            + org.json.JSONObject.quote(reqId) + "," + code + ","
                            + org.json.JSONObject.quote(resp) + ")");
                } catch (java.net.SocketTimeoutException e) {
                    notifyJs("window.onBackendError && window.onBackendError("
                            + org.json.JSONObject.quote(reqId) + ",'timeout')");
                } catch (Exception e) {
                    notifyJs("window.onBackendError && window.onBackendError("
                            + org.json.JSONObject.quote(reqId) + ",'net')");
                }
            }).start();
        }

        @JavascriptInterface
        public void startLocation() {
            startLocationUpdates();
        }

        @JavascriptInterface
        public String getLocation() {
            if (lastLocation == null) return "";
            return "{\"lat\":" + lastLocation.getLatitude()
                    + ",\"lng\":" + lastLocation.getLongitude()
                    + ",\"accuracy\":" + lastLocation.getAccuracy() + "}";
        }

        @JavascriptInterface
        public void checkUpdate() { runUpdateCheck(false, true); }

        @JavascriptInterface
        public void installUpdate() { runOnUiThread(() -> updater.install()); }

        @JavascriptInterface
        public void toast(String message) {
            Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
        }

        @JavascriptInterface
        public void loadOsmAtms(final double lat, final double lng, final double radius) {
            OverpassFetch.fetchAsync(lat, lng, radius, new OverpassFetch.Callback() {
                @Override
                public void onResult(String json) {
                    notifyJs("window.onOsmResult && window.onOsmResult(" + org.json.JSONObject.quote(json) + ")");
                }
                @Override
                public void onError(String reason) {
                    notifyJs("window.onOsmError && window.onOsmError('" + reason + "')");
                }
            });
        }

        @JavascriptInterface
        public void loadOsmSeed() {
            new Thread(() -> {
                try {
                    java.io.InputStream in = getAssets().open("osm_seed_eg.json");
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                    in.close();
                    String json = bos.toString("UTF-8");
                    notifyJs("window.onOsmSeed && window.onOsmSeed(" + org.json.JSONObject.quote(json) + ")");
                } catch (Exception ignored) {
                    // Seed is best-effort; the live fetch path runs regardless.
                }
            }).start();
        }

        @JavascriptInterface
        public void openUrl(String url) {
            if (!("https://www.banquemisr.com/ABOUT-US/Locations".equals(url) || "https://www.cibeg.com/en/branch-and-atm-finder".equals(url) || "https://www.bdc.com.eg/bdcwebsite/find-atm.html".equals(url) || "https://www.geonames.org/".equals(url) || "https://creativecommons.org/licenses/by/4.0/".equals(url))) return;
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.addCategory(Intent.CATEGORY_BROWSABLE);
                startActivity(i);
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public String exportDb(String json) {
            String name = "feiha-flous-data-" + System.currentTimeMillis() + ".json";
            try {
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    android.content.ContentValues values = new android.content.ContentValues();
                    values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name);
                    values.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/json");
                    Uri uri = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) return "err:no-uri";
                    java.io.OutputStream out = getContentResolver().openOutputStream(uri);
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                    out.close();
                    return "ok:Downloads/" + name;
                } else {
                    File dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) return "err:no-dir";
                    dir.mkdirs();
                    File f = new File(dir, name);
                    FileOutputStream out = new FileOutputStream(f);
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                    out.close();
                    return "ok:" + f.getAbsolutePath();
                }
            } catch (Exception e) {
                return "err:" + e.getClass().getSimpleName();
            }
        }
    }
}
