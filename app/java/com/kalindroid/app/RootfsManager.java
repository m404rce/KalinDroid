package com.kalindroid.app;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.StatFs;
import android.system.Os;
import android.system.OsConstants;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public final class RootfsManager {
    public static final String MODE_MINIMAL = "minimal";
    public static final String MODE_FULL = "full";
    public static final String FULL_URL =
            "https://kali.download/nethunter-images/kali-2026.3/rootfs/kali-nethunter-rootfs-full-arm64.tar.xz";
    public static final String MINIMAL_URL =
            "https://kali.download/nethunter-images/kali-2026.3/rootfs/kali-nethunter-rootfs-minimal-arm64.tar.xz";
    public static final long FULL_DOWNLOAD = 1837393488L;
    public static final long MINIMAL_DOWNLOAD = 125846088L;
    public static final long FULL_NEED = 11500L * 1024L * 1024L;
    public static final long MINIMAL_NEED = 1300L * 1024L * 1024L;
    public static final long FULL_EXTRACTED = 9104L * 1024L * 1024L;
    public static final long MINIMAL_EXTRACTED = 1000L * 1024L * 1024L;
    public static final String VERSION = "Kali NetHunter 2026.3";
    public static final String HOST_VERSION = "1";

    private static final String PREFS = "kalindroid";
    private static final String KEY_DOWNLOAD_ID = "download_id";
    private static final String KEY_DOWNLOAD_MODE = "download_mode";
    private static final String KEY_ERROR = "last_error";
    private static final String KEY_EXTRACT_PROGRESS = "extract_progress";
    private static final Object EXTRACT_LOCK = new Object();

    public interface ProgressListener {
        void onProgress(int percent, String message);
    }

    private final Context context;
    private final SharedPreferences prefs;

    public RootfsManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public File filesDir() {
        return context.getFilesDir();
    }

    public File hostDir() {
        return new File(filesDir(), "host");
    }

    public File rootfsDir() {
        return new File(filesDir(), "kali-arm64");
    }

    public File modeFile() {
        return new File(filesDir(), "rootfs.mode");
    }

    public File extractMarker() {
        return new File(filesDir(), ".extracting");
    }

    public File downloadsDir() {
        File external = context.getExternalFilesDir(null);
        File base = external != null ? external : filesDir();
        return new File(base, "downloads");
    }

    public File tarFile(String mode) {
        return new File(downloadsDir(), "kali-" + mode + "-arm64.tar.xz");
    }

    public static boolean validMode(String mode) {
        return MODE_MINIMAL.equals(mode) || MODE_FULL.equals(mode);
    }

    public static String urlFor(String mode) {
        return MODE_FULL.equals(mode) ? FULL_URL : MINIMAL_URL;
    }

    public static long downloadSize(String mode) {
        return MODE_FULL.equals(mode) ? FULL_DOWNLOAD : MINIMAL_DOWNLOAD;
    }

    public static long requiredSpace(String mode) {
        return MODE_FULL.equals(mode) ? FULL_NEED : MINIMAL_NEED;
    }

    public static String displayName(String mode) {
        return MODE_FULL.equals(mode) ? "Full" : "Minimal";
    }

    public long freeBytes() {
        try {
            StatFs stat = new StatFs(filesDir().getAbsolutePath());
            return stat.getAvailableBytes();
        } catch (Exception e) {
            return 0;
        }
    }

    public String installedMode() {
        try {
            if (!new File(rootfsDir(), "usr/bin/zsh").isFile()) return null;
            String mode = readText(modeFile()).trim();
            return validMode(mode) ? mode : null;
        } catch (Exception e) {
            return null;
        }
    }

    public boolean installed() {
        return installedMode() != null;
    }

    public boolean hostReady() {
        File marker = new File(hostDir(), ".version");
        return marker.isFile() && HOST_VERSION.equals(readTextSafe(marker).trim())
                && new File(hostDir(), "bin/proot").isFile()
                && new File(hostDir(), "bin/tar").isFile()
                && new File(hostDir(), "bin/xz").isFile();
    }

    public void ensureHost() throws IOException {
        if (hostReady()) return;
        File host = hostDir();
        deleteRecursive(host);
        if (!host.mkdirs() && !host.isDirectory()) {
            throw new IOException("No se pudo crear " + host);
        }
        copyAssetTree("host", host);
        File marker = new File(host, ".version");
        writeText(marker, HOST_VERSION);
        chmod(new File(host, "bin/proot"), 0755);
        chmod(new File(host, "bin/tar"), 0755);
        chmod(new File(host, "bin/xz"), 0755);
        chmod(new File(host, "libexec/proot/loader"), 0755);
        File[] libs = new File(host, "lib").listFiles();
        if (libs != null) {
            for (File lib : libs) chmod(lib, 0644);
        }
    }

    private void copyAssetTree(String assetPath, File destination) throws IOException {
        String[] children;
        try {
            children = context.getAssets().list(assetPath);
        } catch (IOException e) {
            throw new IOException("No se pudo leer " + assetPath, e);
        }
        if (children == null || children.length == 0) {
            copyAsset(assetPath, destination);
            return;
        }
        if (!destination.mkdirs() && !destination.isDirectory()) {
            throw new IOException("No se pudo crear " + destination);
        }
        for (String child : children) {
            copyAssetTree(assetPath + "/" + child, new File(destination, child));
        }
    }

    private void copyAsset(String assetPath, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("No se pudo crear " + parent);
        }
        try (InputStream in = new BufferedInputStream(context.getAssets().open(assetPath));
             FileOutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
    }

    public JSONObject status() {
        JSONObject state = new JSONObject();
        try {
            String installedMode = installedMode();
            state.put("installed", installedMode != null);
            state.put("mode", installedMode == null ? "" : installedMode);
            state.put("freeBytes", freeBytes());
            state.put("minimalNeed", MINIMAL_NEED);
            state.put("fullNeed", FULL_NEED);
            state.put("hostReady", hostReady());
            state.put("version", VERSION);
            state.put("extracting", extractMarker().isFile());
            state.put("extractProgress", prefs.getInt(KEY_EXTRACT_PROGRESS, 0));
            state.put("error", prefs.getString(KEY_ERROR, ""));
            String archive = "";
            if (hasArchive(MODE_MINIMAL)) archive = MODE_MINIMAL;
            else if (hasArchive(MODE_FULL)) archive = MODE_FULL;
            state.put("archive", archive);

            long downloadId = prefs.getLong(KEY_DOWNLOAD_ID, -1L);
            String downloadMode = prefs.getString(KEY_DOWNLOAD_MODE, "");
            JSONObject download = new JSONObject();
            download.put("id", downloadId);
            download.put("mode", downloadMode);
            download.put("downloaded", 0L);
            download.put("total", 0L);
            download.put("status", "idle");
            download.put("reason", "");
            if (downloadId > 0) {
                fillDownloadState(download, downloadId);
            }
            state.put("download", download);

            if (downloadId > 0 && "successful".equals(download.optString("status"))
                    && installedMode == null && validMode(downloadMode)) {
                maybeStartExtraction(downloadMode);
            }
        } catch (JSONException ignored) {
        }
        return state;
    }

    private void fillDownloadState(JSONObject out, long id) {
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return;
        Cursor cursor = null;
        try {
            cursor = dm.query(new DownloadManager.Query().setFilterById(id));
            if (cursor == null || !cursor.moveToFirst()) {
                out.put("status", "missing");
                return;
            }
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long downloaded = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long total = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
            out.put("downloaded", downloaded);
            out.put("total", total);
            switch (status) {
                case DownloadManager.STATUS_PENDING:
                    out.put("status", "pending");
                    break;
                case DownloadManager.STATUS_RUNNING:
                    out.put("status", "running");
                    break;
                case DownloadManager.STATUS_PAUSED:
                    out.put("status", "paused");
                    out.put("reason", downloadReason(reason));
                    break;
                case DownloadManager.STATUS_SUCCESSFUL:
                    out.put("status", "successful");
                    break;
                case DownloadManager.STATUS_FAILED:
                    out.put("status", "failed");
                    out.put("reason", downloadReason(reason));
                    break;
                default:
                    out.put("status", "unknown");
                    break;
            }
        } catch (Exception e) {
            try {
                out.put("status", "error");
                out.put("reason", e.getMessage() == null ? "Error de descarga" : e.getMessage());
            } catch (JSONException ignored) {
            }
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private static String downloadReason(int reason) {
        switch (reason) {
            case DownloadManager.ERROR_CANNOT_RESUME:
                return "No se pudo reanudar la descarga";
            case DownloadManager.ERROR_DEVICE_NOT_FOUND:
                return "Almacenamiento no disponible";
            case DownloadManager.ERROR_FILE_ALREADY_EXISTS:
                return "El archivo ya existe";
            case DownloadManager.ERROR_FILE_ERROR:
                return "Error de archivo";
            case DownloadManager.ERROR_HTTP_DATA_ERROR:
                return "Error de datos HTTP";
            case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                return "Espacio insuficiente";
            case DownloadManager.ERROR_TOO_MANY_REDIRECTS:
                return "Demasiadas redirecciones";
            case DownloadManager.ERROR_UNHANDLED_HTTP_CODE:
                return "Respuesta HTTP no válida";
            case DownloadManager.ERROR_UNKNOWN:
            default:
                return "Error desconocido (" + reason + ")";
        }
    }

    public void startDownload(String mode) throws IOException {
        if (!validMode(mode)) throw new IOException("Imagen desconocida");
        ensureHost();
        File external = context.getExternalFilesDir(null);
        if (external == null) throw new IOException("Almacenamiento externo no disponible");
        long free = freeBytes();
        long need = requiredSpace(mode);
        if (free > 0 && free < need) {
            throw new IOException("Espacio insuficiente: necesitas "
                    + human(need) + " y hay " + human(free));
        }

        cancelDownloadInternal(false);
        File downloads = downloadsDir();
        if (!downloads.mkdirs() && !downloads.isDirectory()) {
            throw new IOException("No se pudo crear la carpeta de descargas");
        }
        File target = tarFile(mode);
        deleteRecursive(target);
        deleteRecursive(tarFile(MODE_FULL.equals(mode) ? MODE_MINIMAL : MODE_FULL));

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(urlFor(mode)));
        request.setTitle("KalinDroid · Kali " + displayName(mode));
        request.setDescription("Descargando " + VERSION);
        request.setMimeType("application/x-xz");
        request.setAllowedOverMetered(true);
        request.setAllowedOverRoaming(false);
        request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalFilesDir(context, null,
                "downloads/" + target.getName());

        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) throw new IOException("DownloadManager no disponible");
        long id = dm.enqueue(request);
        if (id < 0) throw new IOException("No se pudo iniciar la descarga");

        prefs.edit()
                .putLong(KEY_DOWNLOAD_ID, id)
                .putString(KEY_DOWNLOAD_MODE, mode)
                .remove(KEY_ERROR)
                .apply();
    }

    public void cancelDownload() {
        cancelDownloadInternal(true);
    }

    private void cancelDownloadInternal(boolean removeFile) {
        long id = prefs.getLong(KEY_DOWNLOAD_ID, -1L);
        if (id > 0) {
            DownloadManager dm =
                    (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                try {
                    dm.remove(id);
                } catch (Exception ignored) {
                }
            }
        }
        String mode = prefs.getString(KEY_DOWNLOAD_MODE, "");
        if (removeFile && validMode(mode)) {
            deleteRecursive(tarFile(mode));
        }
        prefs.edit().remove(KEY_DOWNLOAD_ID).remove(KEY_DOWNLOAD_MODE).apply();
    }

    public void reset() {
        cancelDownloadInternal(true);
        deleteRecursive(downloadsDir());
        deleteRecursive(rootfsDir());
        deleteRecursive(modeFile());
        deleteRecursive(extractMarker());
        prefs.edit()
                .remove(KEY_EXTRACT_PROGRESS)
                .remove(KEY_ERROR)
                .apply();
    }

    public static void startExtraction(Context context, String mode) {
        Intent intent = new Intent(context, InstallService.class);
        intent.setAction(InstallService.ACTION_EXTRACT);
        intent.putExtra(InstallService.EXTRA_MODE, mode);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    private void maybeStartExtraction(String mode) {
        synchronized (EXTRACT_LOCK) {
            if (extractMarker().isFile() && InstallService.isRunning()) return;
            try {
                startExtraction(context, mode);
            } catch (RuntimeException ignored) {
            }
        }
    }

    public void extractSync(String mode, ProgressListener listener) throws Exception {
        if (!validMode(mode)) throw new IOException("Imagen desconocida");
        ensureHost();
        File tar = tarFile(mode);
        long size = tar.isFile() ? tar.length() : 0;
        long expected = downloadSize(mode);
        if (size < 1024 * 1024) {
            throw new IOException("La imagen descargada está incompleta o no existe");
        }
        if (size != expected) {
            throw new IOException("Tamaño inesperado: " + size + " de " + expected + " bytes");
        }

        long free = freeBytes();
        long extractNeed = MODE_FULL.equals(mode) ? FULL_EXTRACTED + 500L * 1024L * 1024L
                : MINIMAL_EXTRACTED + 150L * 1024L * 1024L;
        if (free > 0 && free < extractNeed) {
            throw new IOException("Espacio insuficiente para extraer: necesitas "
                    + human(extractNeed) + " y hay " + human(free));
        }

        File marker = extractMarker();
        writeText(marker, mode);
        prefs.edit().putInt(KEY_EXTRACT_PROGRESS, 0).remove(KEY_ERROR).apply();

        File root = rootfsDir();
        deleteRecursive(root);
        if (!root.mkdirs() && !root.isDirectory()) {
            throw new IOException("No se pudo crear " + root);
        }

        String host = hostDir().getAbsolutePath();
        String command = "set -o pipefail; "
                + "export LD_LIBRARY_PATH=" + shellQuote(host + "/lib") + "; "
                + shellQuote(host + "/bin/xz") + " -dc " + shellQuote(tar.getAbsolutePath())
                + " | env LD_PRELOAD=" + shellQuote(host + "/lib/liblinkcopy.so") + " "
                + shellQuote(host + "/bin/tar")
                + " --no-same-owner --no-acls --no-selinux --no-xattrs"
                + " --exclude='kali-arm64/dev/*' -xf - -C "
                + shellQuote(filesDir().getAbsolutePath());

        ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", "-c", command);
        builder.redirectErrorStream(true);
        builder.directory(filesDir());
        Process process = builder.start();

        StringBuilder log = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    synchronized (log) {
                        log.append(line).append('\n');
                        if (log.length() > 8192) log.delete(0, log.length() - 8192);
                    }
                }
            } catch (IOException ignored) {
            }
        }, "kalindroid-extract-log");
        reader.start();

        long startFree = freeBytes();
        long expectedExtract = MODE_FULL.equals(mode) ? FULL_EXTRACTED : MINIMAL_EXTRACTED;
        while (process.isAlive()) {
            long used = Math.max(0, startFree - freeBytes());
            int percent = (int)Math.min(99L, (used * 100L) / Math.max(1L, expectedExtract));
            if (listener != null) listener.onProgress(percent, "Extrayendo Kali...");
            prefs.edit().putInt(KEY_EXTRACT_PROGRESS, percent).apply();
            try {
                Thread.sleep(700L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        int exit = process.waitFor();
        reader.join(2000L);

        if (exit != 0 || !new File(root, "usr/bin/zsh").isFile()) {
            String details;
            synchronized (log) {
                details = log.toString().trim();
            }
            throw new IOException("La extracción falló (código " + exit + ")"
                    + (details.isEmpty() ? "" : ": " + tail(details, 400)));
        }

        configureRootfs(mode);
        writeText(modeFile(), mode);
        deleteRecursive(tar);
        deleteRecursive(marker);
        prefs.edit()
                .remove(KEY_DOWNLOAD_ID)
                .remove(KEY_DOWNLOAD_MODE)
                .putInt(KEY_EXTRACT_PROGRESS, 100)
                .remove(KEY_ERROR)
                .apply();
        if (listener != null) listener.onProgress(100, "Kali listo");
    }

    public void markExtractionFailed(String message) {
        deleteRecursive(rootfsDir());
        deleteRecursive(extractMarker());
        prefs.edit()
                .remove(KEY_DOWNLOAD_ID)
                .remove(KEY_DOWNLOAD_MODE)
                .putInt(KEY_EXTRACT_PROGRESS, 0)
                .putString(KEY_ERROR, message == null ? "Error de instalación" : message)
                .apply();
    }

    public void clearError() {
        prefs.edit().remove(KEY_ERROR).apply();
    }

    public boolean hasArchive(String mode) {
        return validMode(mode) && tarFile(mode).isFile()
                && tarFile(mode).length() == downloadSize(mode);
    }

    private void configureRootfs(String mode) throws IOException {
        File root = rootfsDir();
        File rootHome = new File(root, "root");
        File etc = new File(root, "etc");
        mkdirs(rootHome);
        mkdirs(etc);
        mkdirs(new File(root, "home"));
        mkdirs(new File(root, "tmp"));
        mkdirs(new File(root, "dev"));
        mkdirs(new File(root, "proc"));
        mkdirs(new File(root, "sys"));

        copyAsset("config/root/zshrc", new File(rootHome, ".zshrc"));
        copyAsset("config/root/bashrc", new File(rootHome, ".bashrc"));
        copyAsset("config/root/bash_profile", new File(rootHome, ".bash_profile"));
        copyAsset("config/root/profile", new File(rootHome, ".profile"));
        copyAsset("config/root/hushlogin", new File(rootHome, ".hushlogin"));
        chmod(new File(rootHome, ".zshrc"), 0644);
        chmod(new File(rootHome, ".bashrc"), 0644);
        chmod(new File(rootHome, ".bash_profile"), 0644);
        chmod(new File(rootHome, ".profile"), 0644);
        chmod(new File(rootHome, ".hushlogin"), 0644);

        writeText(new File(etc, "hostname"), "kalindroid\n");
        writeText(new File(etc, "hosts"),
                "127.0.0.1 localhost\n"
                        + "127.0.1.1 kalindroid\n"
                        + "::1 localhost ip6-localhost ip6-loopback\n");
        writeText(new File(etc, "resolv.conf"), buildResolvConf());
        chmod(new File(etc, "hostname"), 0644);
        chmod(new File(etc, "hosts"), 0644);
        chmod(new File(etc, "resolv.conf"), 0644);

        chmod(rootHome, 0700);
        chmod(etc, 0755);
        new File(rootHome, ".hushlogin").setLastModified(System.currentTimeMillis());
    }

    private String buildResolvConf() {
        List<String> dns = new ArrayList<>();
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = cm == null ? null : cm.getActiveNetwork();
            LinkProperties props = network == null ? null : cm.getLinkProperties(network);
            if (props != null) {
                for (InetAddress address : props.getDnsServers()) {
                    String host = address.getHostAddress();
                    if (host != null && host.indexOf(':') < 0 && !dns.contains(host)) {
                        dns.add(host);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (dns.isEmpty()) dns.add("1.1.1.1");
        if (!dns.contains("8.8.8.8")) dns.add("8.8.8.8");
        StringBuilder out = new StringBuilder();
        for (String server : dns) out.append("nameserver ").append(server).append('\n');
        return out.toString();
    }

    public String[] buildProotCommand() {
        String host = hostDir().getAbsolutePath();
        String root = rootfsDir().getAbsolutePath();
        List<String> args = new ArrayList<>();
        args.add(host + "/bin/proot");
        args.add("--link2symlink");
        args.add("-0");
        args.add("-r");
        args.add(root);
        args.add("-b");
        args.add("/dev");
        args.add("-b");
        args.add("/proc");
        args.add("-b");
        args.add("/sys");
        args.add("-b");
        args.add(root + "/home:/dev/shm");
        File shared = new File("/storage/emulated/0");
        if (shared.isDirectory() && shared.canRead()) {
            args.add("-b");
            args.add("/storage/emulated/0:/sdcard");
        }
        args.add("-w");
        args.add("/root");
        args.add("/usr/bin/env");
        args.add("-i");
        args.add("HOME=/root");
        args.add("USER=root");
        args.add("LOGNAME=root");
        args.add("SHELL=/usr/bin/zsh");
        args.add("TERM=xterm-256color");
        args.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        args.add("LANG=C.UTF-8");
        args.add("LC_ALL=C.UTF-8");
        args.add("/usr/bin/zsh");
        args.add("-l");
        return args.toArray(new String[0]);
    }

    public String[] buildProotEnv() {
        String host = hostDir().getAbsolutePath();
        return new String[] {
                "LD_LIBRARY_PATH=" + host + "/lib",
                "PROOT_TMP_DIR=" + new File(filesDir(), "tmp").getAbsolutePath(),
                "PROOT_LOADER=" + host + "/libexec/proot/loader"
        };
    }

    public void prepareTmp() {
        File tmp = new File(filesDir(), "tmp");
        if (!tmp.mkdirs() && !tmp.isDirectory()) {
            // Ignore here; proot will report a useful error.
        }
        chmod(tmp, 0700);
    }

    private static String tail(String text, int max) {
        if (text.length() <= max) return text;
        return text.substring(text.length() - max);
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String human(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(java.util.Locale.US, "%.1f GB",
                    bytes / (1024.0 * 1024.0 * 1024.0));
        }
        if (bytes >= 1024L * 1024L) {
            return String.format(java.util.Locale.US, "%.0f MB",
                    bytes / (1024.0 * 1024.0));
        }
        return bytes + " B";
    }

    private static void mkdirs(File dir) throws IOException {
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("No se pudo crear " + dir);
        }
    }

    private static void chmod(File file, int mode) {
        try {
            Os.chmod(file.getAbsolutePath(), mode);
        } catch (Exception ignored) {
        }
    }

    public static String readText(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String readTextSafe(File file) {
        try {
            return readText(file);
        } catch (Exception e) {
            return "";
        }
    }

    public static void writeText(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) mkdirs(parent);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        boolean symlink = false;
        try {
            symlink = Files.isSymbolicLink(file.toPath());
        } catch (Exception ignored) {
        }
        if (file.isDirectory() && !symlink) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
