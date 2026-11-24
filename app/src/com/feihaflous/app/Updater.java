package com.feihaflous.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import org.json.JSONObject;

/** In-app update: reads a small version file, notifies, downloads the APK and opens the installer. */
public class Updater {
    // Version file published with the release. Same signing key => installs over the old app.
    public static final String VERSION_URL = "https://github.com/Alifathy2/Fia-flose/releases/download/feiha/feiha-version.json";
    public static final String CHANNEL = "feiha_updates";

    public interface Callback { void onResult(String status, String json); }

    private final Activity act;
    private long downloadId = -1;
    private String pendingApkUrl;

    public Updater(Activity a) { this.act = a; }

    public static int currentCode(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode; }
        catch (Exception e) { return 0; }
    }

    public void check(final boolean notify, final Callback cb) {
        new Thread(() -> {
            try {
                HttpURLConnection con = (HttpURLConnection) new URL(VERSION_URL).openConnection();
                con.setConnectTimeout(8000);
                con.setReadTimeout(8000);
                con.setInstanceFollowRedirects(true);
                if (con.getResponseCode() != 200) { cb.onResult("error", "{}"); return; }
                InputStream in = con.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
                JSONObject o = new JSONObject(bos.toString("UTF-8"));
                int code = o.optInt("versionCode", 0);
                String apk = o.optString("apk", "");
                if (code > currentCode(act) && apk.startsWith("https://")) {
                    pendingApkUrl = apk;
                    if (notify) showNotification(o.optString("versionName", ""));
                    cb.onResult("available", o.toString());
                } else {
                    cb.onResult("latest", o.toString());
                }
            } catch (Exception e) {
                cb.onResult("error", "{}");
            }
        }).start();
    }

    private void showNotification(String name) {
        try {
            NotificationManager nm = (NotificationManager) act.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "تحديثات التطبيق", NotificationManager.IMPORTANCE_DEFAULT));
            }
            Intent open = new Intent(act, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(act, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(act, CHANNEL) : new Notification.Builder(act);
            b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("فيها فلوس")
                    .setContentText("فيه تحديث جديد " + name + " - افتح التطبيق وادوس تحديث")
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            nm.notify(7001, b.build());
        } catch (Exception ignored) {
        }
    }

    public void install() {
        if (pendingApkUrl == null) return;
        if (Build.VERSION.SDK_INT >= 26 && !act.getPackageManager().canRequestPackageInstalls()) {
            Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.getPackageName()));
            act.startActivity(s);
            return;
        }
        final DownloadManager dm = (DownloadManager) act.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Request r = new DownloadManager.Request(Uri.parse(pendingApkUrl));
        r.setTitle("تحديث فيها فلوس");
        r.setMimeType("application/vnd.android.package-archive");
        r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        r.setDestinationInExternalFilesDir(act, Environment.DIRECTORY_DOWNLOADS, "feiha-update.apk");
        downloadId = dm.enqueue(r);
        BroadcastReceiver rc = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                long id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != downloadId) return;
                try { c.unregisterReceiver(this); } catch (Exception ignored) {}
                Uri u = dm.getUriForDownloadedFile(id);
                if (u == null) return;
                Intent in = new Intent(Intent.ACTION_VIEW);
                in.setDataAndType(u, "application/vnd.android.package-archive");
                in.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(in);
            }
        };
        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) act.registerReceiver(rc, f, Context.RECEIVER_EXPORTED);
        else act.registerReceiver(rc, f);
    }
}
