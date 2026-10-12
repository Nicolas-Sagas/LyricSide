package com.lyricside.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.webkit.MimeTypeMap;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends Activity {
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private static final int FILE_CHOOSER = 701;
    private static final int FOLDER_CHOOSER = 702;
    private static final int MAX_TRACKS = 1200;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(android.graphics.Color.rgb(9, 14, 26));
        getWindow().setNavigationBarColor(android.graphics.Color.rgb(9, 14, 26));

        web = new WebView(this);
        setContentView(web);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);

        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView webView,
                    ValueCallback<Uri[]> callback,
                    FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;

                try {
                    Intent intent = params.createIntent();
                    startActivityForResult(intent, FILE_CHOOSER);
                    return true;
                } catch (Exception error) {
                    fileCallback = null;
                    Toast.makeText(
                            MainActivity.this,
                            "Não foi possível abrir o seletor de arquivos.",
                            Toast.LENGTH_SHORT
                    ).show();
                    return false;
                }
            }
        });

        web.addJavascriptInterface(new AndroidBridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");
    }

    public class AndroidBridge {
        @JavascriptInterface
        public void pickMusicFolder() {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    intent.addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                    );
                    startActivityForResult(intent, FOLDER_CHOOSER);
                } catch (Exception error) {
                    Toast.makeText(
                            MainActivity.this,
                            "Não foi possível abrir o seletor de pasta.",
                            Toast.LENGTH_SHORT
                    ).show();
                }
            });
        }

        @JavascriptInterface
        public String importMusic(String uriString, String songId) {
            JSONObject result = new JSONObject();
            try {
                Uri uri = Uri.parse(uriString);
                String displayName = getDisplayName(uri);
                String mime = getContentResolver().getType(uri);
                String ext = "";

                if (mime != null) {
                    String guessed = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                    if (guessed != null) ext = "." + guessed;
                }

                if (ext.isEmpty() && displayName != null && displayName.contains(".")) {
                    ext = displayName.substring(displayName.lastIndexOf("."));
                    if (ext.length() > 8) ext = "";
                }

                File musicDir = new File(getFilesDir(), "music");
                if (!musicDir.exists()) musicDir.mkdirs();

                SharedPreferences prefs = getSharedPreferences("lyricside", MODE_PRIVATE);
                String oldPath = prefs.getString("audio_" + songId, null);
                if (oldPath != null) {
                    File old = new File(oldPath);
                    if (old.exists()) old.delete();
                }

                File output = new File(musicDir, safeFileName(songId) + ext);

                try (InputStream input = getContentResolver().openInputStream(uri);
                     FileOutputStream stream = new FileOutputStream(output)) {
                    if (input == null) throw new Exception("Arquivo de áudio indisponível");
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        stream.write(buffer, 0, read);
                    }
                }

                prefs.edit().putString("audio_" + songId, output.getAbsolutePath()).apply();

                result.put("ok", true);
                result.put("url", Uri.fromFile(output).toString());
                result.put("name", displayName == null ? "" : displayName);
            } catch (Exception error) {
                try {
                    result.put("ok", false);
                    result.put("error", error.getMessage() == null ? "Falha ao importar áudio" : error.getMessage());
                } catch (Exception ignored) {}
            }
            return result.toString();
        }

        @JavascriptInterface
        public String getImportedMusicUrl(String songId) {
            try {
                SharedPreferences prefs = getSharedPreferences("lyricside", MODE_PRIVATE);
                String path = prefs.getString("audio_" + songId, null);
                if (path == null) return "";
                File file = new File(path);
                if (!file.exists()) return "";
                return Uri.fromFile(file).toString();
            } catch (Exception error) {
                return "";
            }
        }

        @JavascriptInterface
        public void deleteImportedMusic(String songId) {
            try {
                SharedPreferences prefs = getSharedPreferences("lyricside", MODE_PRIVATE);
                String path = prefs.getString("audio_" + songId, null);
                if (path != null) {
                    File file = new File(path);
                    if (file.exists()) file.delete();
                }
                prefs.edit().remove("audio_" + songId).apply();
            } catch (Exception ignored) {}
        }
    }

    private void scanMusicFolder(Uri treeUri) {
        new Thread(() -> {
            try {
                DocumentFile root = DocumentFile.fromTreeUri(this, treeUri);
                if (root == null || !root.exists()) {
                    sendFolderError("Não consegui abrir esta pasta.");
                    return;
                }

                List<JSONObject> tracks = new ArrayList<>();
                collectAudioFiles(root, tracks);

                tracks.sort(Comparator.comparing(
                        item -> item.optString("title", item.optString("name", "")).toLowerCase()
                ));

                JSONArray array = new JSONArray();
                for (JSONObject item : tracks) array.put(item);

                String payload = JSONObject.quote(array.toString());
                runOnUiThread(() ->
                        web.evaluateJavascript(
                                "window.LyricSideNative && window.LyricSideNative.onFolderTracks(" + payload + ");",
                                null
                        )
                );
            } catch (Exception error) {
                sendFolderError(
                        error.getMessage() == null
                                ? "Falha ao ler a pasta."
                                : error.getMessage()
                );
            }
        }).start();
    }

    private void collectAudioFiles(DocumentFile folder, List<JSONObject> tracks) {
        if (tracks.size() >= MAX_TRACKS) return;

        DocumentFile[] children;
        try {
            children = folder.listFiles();
        } catch (Exception error) {
            return;
        }

        for (DocumentFile child : children) {
            if (tracks.size() >= MAX_TRACKS) return;

            if (child.isDirectory()) {
                collectAudioFiles(child, tracks);
                continue;
            }

            String type = child.getType();
            String name = child.getName() == null ? "" : child.getName();
            boolean audio = type != null && type.startsWith("audio/");
            if (!audio) {
                String lower = name.toLowerCase();
                audio = lower.endsWith(".mp3")
                        || lower.endsWith(".m4a")
                        || lower.endsWith(".aac")
                        || lower.endsWith(".ogg")
                        || lower.endsWith(".opus")
                        || lower.endsWith(".wav")
                        || lower.endsWith(".flac");
            }

            if (!audio) continue;

            Uri uri = child.getUri();
            JSONObject metadata = readMetadata(uri, name);
            tracks.add(metadata);
        }
    }

    private JSONObject readMetadata(Uri uri, String fallbackName) {
        JSONObject item = new JSONObject();
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();

        try {
            item.put("uri", uri.toString());
            item.put("name", fallbackName == null ? "" : fallbackName);

            String title = "";
            String artist = "";
            String album = "";
            long duration = 0;

            try {
                retriever.setDataSource(this, uri);
                title = safeMeta(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
                artist = safeMeta(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
                album = safeMeta(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM));

                String durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (durationMs != null) duration = Long.parseLong(durationMs) / 1000L;
            } catch (Exception ignored) {}

            if (title.isEmpty()) title = stripExtension(fallbackName);

            item.put("title", title);
            item.put("artist", artist);
            item.put("album", album);
            item.put("duration", duration);
        } catch (Exception ignored) {
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {}
        }

        return item;
    }

    private void sendFolderError(String message) {
        String payload = JSONObject.quote(message == null ? "Falha ao abrir pasta." : message);
        runOnUiThread(() ->
                web.evaluateJavascript(
                        "window.LyricSideNative && window.LyricSideNative.onFolderError(" + payload + ");",
                        null
                )
        );
    }

    private String getDisplayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return uri.getLastPathSegment();
    }

    private String safeMeta(String value) {
        return value == null ? "" : value.trim();
    }

    private String stripExtension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String safeFileName(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILE_CHOOSER) {
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            if (fileCallback != null) fileCallback.onReceiveValue(result);
            fileCallback = null;
            return;
        }

        if (requestCode == FOLDER_CHOOSER && resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri == null) return;

            try {
                int flags = data.getFlags()
                        & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(
                        treeUri,
                        flags & Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
            } catch (Exception ignored) {}

            scanMusicFolder(treeUri);
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.loadUrl("about:blank");
            web.stopLoading();
            web.setWebChromeClient(null);
            web.setWebViewClient(null);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
