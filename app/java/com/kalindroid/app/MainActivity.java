package com.kalindroid.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity {
    private static final String TAG = "KalinDroid";
    private static final String PREFS = "kalindroid_ui";
    private static final int REQ_STORAGE = 10;

    private WebView webView;
    private RootfsManager rootfs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Object ptyLock = new Object();
    private final Object outputLock = new Object();
    private final StringBuilder pendingOutput = new StringBuilder();
    private int ptyFd = -1;
    private int ptyGeneration = 0;
    private boolean flushScheduled;
    private final CharsetDecoder utf8Decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
    private final byte[] utf8Carry = new byte[4];
    private int utf8CarryLength = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(11, 15, 20));
        window.setNavigationBarColor(Color.rgb(11, 15, 20));
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        rootfs = new RootfsManager(this);
        rootfs.prepareTmp();

        webView = new TerminalWebView(this);
        webView.setBackgroundColor(Color.rgb(11, 15, 20));
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(100);
        settings.setMediaPlaybackRequiresUserGesture(true);

        webView.addJavascriptInterface(new Bridge(), "Kalindroid");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null && url.startsWith("file:///android_asset/")) return false;
                openExternal(url);
                return true;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage message) {
                Log.d(TAG, "web: " + message.message() + " @" + message.lineNumber());
                return true;
            }
        });

        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");

        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQ_STORAGE);
        }
    }

    private void openExternal(String url) {
        if (url == null) return;
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            if ("http".equals(scheme) || "https".equals(scheme)) {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            }
        } catch (Exception ignored) {
        }
    }

    private boolean startPty(int cols, int rows) {
        synchronized (ptyLock) {
            if (ptyFd >= 0) return true;
            try {
                rootfs.ensureHost();
                rootfs.prepareTmp();
                if (!rootfs.installed()) return false;
                String[] argv = rootfs.buildProotCommand();
                String[] envp = rootfs.buildProotEnv();
                int fd = Pty.nativeOpen(argv, envp, rootfs.rootfsDir().getAbsolutePath(),
                        Math.max(20, cols), Math.max(5, rows));
                if (fd < 0) return false;
                ptyFd = fd;
                utf8CarryLength = 0;
                utf8Decoder.reset();
                int generation = ++ptyGeneration;
                Thread reader = new Thread(() -> readLoop(fd, generation), "kalindroid-pty-reader");
                reader.setDaemon(true);
                reader.start();
                return true;
            } catch (Throwable t) {
                Log.e(TAG, "startPty", t);
                return false;
            }
        }
    }

    private void readLoop(int fd, int generation) {
        byte[] buffer = new byte[8192];
        while (true) {
            int n;
            try {
                n = Pty.read(fd, buffer);
            } catch (Throwable t) {
                break;
            }
            if (n <= 0) break;
            synchronized (ptyLock) {
                if (generation != ptyGeneration) break;
            }
            emitBytes(buffer, 0, n);
        }
        boolean current;
        synchronized (ptyLock) {
            if (ptyFd == fd) ptyFd = -1;
            current = generation == ptyGeneration;
        }
        if (!current) return;
        ui.post(() -> {
            if (webView != null) {
                webView.evaluateJavascript(
                        "window.kalindroidExit && window.kalindroidExit()", null);
            }
        });
    }

    private void emitBytes(byte[] data, int offset, int length) {
        ByteBuffer input = ByteBuffer.allocate(utf8CarryLength + length);
        input.put(utf8Carry, 0, utf8CarryLength);
        input.put(data, offset, length);
        input.flip();
        CharBuffer output = CharBuffer.allocate(input.remaining() + 1);
        CoderResult result = utf8Decoder.decode(input, output, false);
        output.flip();
        if (output.hasRemaining()) postOutput(output.toString());
        utf8CarryLength = 0;
        if (result.isUnderflow() && input.hasRemaining()) {
            utf8CarryLength = Math.min(input.remaining(), utf8Carry.length);
            input.get(utf8Carry, 0, utf8CarryLength);
        }
    }

    private void postOutput(String text) {
        synchronized (outputLock) {
            pendingOutput.append(text);
        }
        if (!flushScheduled) {
            flushScheduled = true;
            ui.postDelayed(this::flushOutput, 16);
        }
    }

    private void flushOutput() {
        String text;
        synchronized (outputLock) {
            text = pendingOutput.toString();
            pendingOutput.setLength(0);
            flushScheduled = false;
        }
        if (text.isEmpty() || webView == null) return;
        webView.evaluateJavascript(
                "window.kalindroidData && window.kalindroidData(" + JSONObject.quote(text) + ")",
                null);
    }

    private void closePty() {
        synchronized (ptyLock) {
            ptyGeneration++;
            if (ptyFd >= 0) {
                try {
                    Pty.nativeClose(ptyFd);
                } catch (Throwable ignored) {
                }
                ptyFd = -1;
            }
        }
    }

    private String prefsJson() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        JSONObject out = new JSONObject();
        try {
            out.put("fontSize", prefs.getInt("font_size", 13));
            out.put("theme", prefs.getString("theme", "midnight"));
        } catch (JSONException ignored) {
        }
        return out.toString();
    }

    private String ok() {
        return "{\"ok\":true}";
    }

    private String error(String message) {
        try {
            return new JSONObject().put("ok", false)
                    .put("error", message == null ? "Error" : message).toString();
        } catch (JSONException e) {
            return "{\"ok\":false,\"error\":\"Error\"}";
        }
    }

    private final class Bridge {
        @JavascriptInterface
        public String status() {
            return rootfs.status().toString();
        }

        @JavascriptInterface
        public String install(String mode) {
            try {
                rootfs.startDownload(mode);
                return ok();
            } catch (Exception e) {
                return error(e.getMessage());
            }
        }

        @JavascriptInterface
        public String cancelInstall() {
            rootfs.cancelDownload();
            return ok();
        }

        @JavascriptInterface
        public String reset() {
            closePty();
            rootfs.reset();
            return ok();
        }

        @JavascriptInterface
        public String restartTerminal(int cols, int rows) {
            closePty();
            return startPty(cols, rows) ? ok() : error("No se pudo reiniciar Kali");
        }

        @JavascriptInterface
        public String retryExtract(String mode) {
            if (!RootfsManager.validMode(mode) || !rootfs.hasArchive(mode)) {
                return error("No hay una imagen descargada para reintentar");
            }
            rootfs.clearError();
            RootfsManager.startExtraction(MainActivity.this, mode);
            return ok();
        }

        @JavascriptInterface
        public String startTerminal(int cols, int rows) {
            return startPty(cols, rows) ? ok() : error("No se pudo iniciar Kali");
        }

        @JavascriptInterface
        public void input(String data) {
            if (data == null) return;
            synchronized (ptyLock) {
                if (ptyFd < 0) return;
                try {
                    Pty.write(ptyFd, data.getBytes(StandardCharsets.UTF_8));
                } catch (Throwable ignored) {
                }
            }
        }

        @JavascriptInterface
        public void resize(int cols, int rows) {
            synchronized (ptyLock) {
                if (ptyFd < 0) return;
                try {
                    Pty.nativeResize(ptyFd, cols, rows);
                } catch (Throwable ignored) {
                }
            }
        }

        @JavascriptInterface
        public String clipboard() {
            try {
                ClipboardManager cm =
                        (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null || !cm.hasPrimaryClip()) return "";
                ClipData clip = cm.getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0) return "";
                CharSequence text = clip.getItemAt(0).coerceToText(MainActivity.this);
                return text == null ? "" : text.toString();
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void copy(String text) {
            try {
                ClipboardManager cm =
                        (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("KalinDroid", text));
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public String prefs() {
            return prefsJson();
        }

        @JavascriptInterface
        public void setPref(String key, String value) {
            SharedPreferences.Editor editor =
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit();
            if ("font_size".equals(key)) {
                try {
                    int size = Integer.parseInt(value);
                    editor.putInt("font_size", Math.max(10, Math.min(22, size)));
                } catch (Exception ignored) {
                }
            } else if ("theme".equals(key)) {
                editor.putString("theme", value);
            }
            editor.apply();
        }

        @JavascriptInterface
        public void openUrl(String url) {
            ui.post(() -> openExternal(url));
        }

        @JavascriptInterface
        public void exitApp() {
            ui.post(() -> {
                closePty();
                finish();
            });
        }
    }

    private void showExitDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Cerrar KalinDroid")
                .setMessage("Se cerrará la sesión de Kali.")
                .setPositiveButton("Salir", (dialog, which) -> {
                    closePty();
                    finish();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        if (webView == null) {
            super.onBackPressed();
            return;
        }
        webView.evaluateJavascript(
                "window.kalindroidBack ? window.kalindroidBack() : false",
                value -> {
                    if (!"true".equals(value)) showExitDialog();
                });
    }

    @Override
    protected void onDestroy() {
        closePty();
        if (webView != null) {
            webView.removeJavascriptInterface("Kalindroid");
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
