package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A tiny web server so wallpapers can be added from a phone or a computer on the same network.
 *
 * The TV has no keyboard, but it is always on the same WiFi as whatever its owner is holding. Opening
 * {@code http://<tv-ip>:8080} on that device gives one page: pick pictures, they are scaled, turned the right way
 * up and dropped into the theme's {@code wallpapers/} folder, where Home's own wallpaper row already lists them.
 *
 * Security is "it is your LAN": no password, and the page only ever writes new files under wallpapers/ (and can
 * delete files the owner uploaded, never the ones a theme shipped). Requests are capped in size, the folder is
 * capped in count, and nothing outside that folder is reachable through it.
 */
final class UploadServer {

    private static final String TAG = "TvHome.Upload";
    /** The first port tried; the server walks up from here when it is taken. */
    private static final int PORT_FIRST = 8080;
    private static final int PORT_LAST = 8099;
    /** A single upload is capped by the phone's memory as much as by ours; 24 MB covers a 48 MP photo. */
    private static final long MAX_UPLOAD = 24L * 1024 * 1024;
    /** Pictures in the folder, all told: Home lists up to 100, and the TV has better uses for its storage. */
    private static final int MAX_IMAGES = 60;
    /** Stored pictures are scaled so the long edge fits the panel; anything larger only costs decode time. */
    private static final int MAX_EDGE = 1920;
    private static final int JPEG_QUALITY = 88;
    private static final Pattern SORT_PREFIX = Pattern.compile("^(\\d+)-");

    private static UploadServer instance;

    private final Context app;
    private final Object lock = new Object();
    private ServerSocket socket;
    private int port;
    private volatile boolean running;

    private UploadServer(Context app) {
        this.app = app;
    }

    static synchronized UploadServer get(Context ctx) {
        if (instance == null) instance = new UploadServer(ctx.getApplicationContext());
        return instance;
    }

    /** Starts listening if it is not already; safe to call on every Home creation. */
    void start() {
        synchronized (lock) {
            if (running) return;
            for (int p = PORT_FIRST; p <= PORT_LAST; p++) {
                try {
                    socket = new ServerSocket(p);
                    port = p;
                    break;
                } catch (IOException ignored) {
                    // taken, or not allowed: try the next one
                }
            }
            if (socket == null) {
                Log.w(TAG, "no free port between " + PORT_FIRST + " and " + PORT_LAST);
                return;
            }
            running = true;
            Thread accept = new Thread(this::acceptLoop, "upload-accept");
            accept.setDaemon(true);
            accept.start();
            Log.i(TAG, "listening on " + address());
        }
    }

    /** The page's address on this network, or null while the server is down. */
    String address() {
        if (!running || port == 0) return null;
        String ip = localIp();
        return ip == null ? null : "http://" + ip + ":" + port;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = socket.accept();
                Thread worker = new Thread(() -> handle(client), "upload-conn");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException e) {
                if (running) Log.w(TAG, "accept failed: " + e.getMessage());
                return;
            }
        }
    }

    // ---- one request ---------------------------------------------------------------------------------

    private void handle(Socket client) {
        try (Socket s = client) {
            s.setSoTimeout(20_000);
            InputStream in = new BufferedInputStream(s.getInputStream(), 32 * 1024);
            OutputStream out = s.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                respond(out, 400, "text/plain; charset=utf-8", "bad request".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String method = parts[0];
            String target = parts[1];
            Map<String, String> headers = readHeaders(in);

            String path = target;
            Map<String, String> query = new HashMap<>();
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                for (String pair : target.substring(q + 1).split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq <= 0) continue;
                    query.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
                }
            }

            if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
                respond(out, 200, "text/html; charset=utf-8", page().getBytes(StandardCharsets.UTF_8));
            } else if ("GET".equals(method) && "/thumb".equals(path)) {
                byte[] thumb = thumbnail(query.get("name"));
                if (thumb == null) respond(out, 404, "text/plain", "no such picture".getBytes(StandardCharsets.UTF_8));
                else respond(out, 200, "image/jpeg", thumb);
            } else if ("POST".equals(method) && "/upload".equals(path)) {
                upload(in, out, headers, query.get("name"));
            } else if ("POST".equals(method) && "/delete".equals(path)) {
                delete(out, query.get("name"));
            } else {
                respond(out, 404, "text/plain; charset=utf-8", "not found".getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            Log.w(TAG, "connection failed: " + e.getMessage());
        } catch (RuntimeException e) {
            Log.w(TAG, "request failed", e);
        }
    }

    private void upload(InputStream in, OutputStream out, Map<String, String> headers, String name) throws IOException {
        long length = parseLong(headers.get("content-length"));
        if (length <= 0) {
            text(out, 411, "缺少内容长度");
            return;
        }
        if (length > MAX_UPLOAD) {
            text(out, 413, "图片太大（上限 24MB）");
            return;
        }
        File dir = wallpaperDir();
        if (dir == null) {
            text(out, 500, "电视上的主题目录不可用");
            return;
        }
        if (countImages(dir) >= MAX_IMAGES) {
            text(out, 507, "已经有 " + MAX_IMAGES + " 张了，先在电视上删掉几张");
            return;
        }
        File temp = File.createTempFile("wallpaper-", ".tmp", app.getCacheDir());
        try {
            copy(in, temp, length);
            File saved = store(temp, dir, name);
            if (saved == null) {
                text(out, 415, "不是能识别的图片");
                return;
            }
            Log.i(TAG, "stored " + saved.getName() + " (" + saved.length() / 1024 + " KB)");
            text(out, 200, saved.getName());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    private void delete(OutputStream out, String name) throws IOException {
        File dir = wallpaperDir();
        File file = dir == null || name == null ? null : new File(dir, name);
        if (file == null || !file.isFile() || !dir.equals(file.getParentFile())) {
            text(out, 404, "没有这张图片");
            return;
        }
        if (bundled().contains(file.getName())) {
            text(out, 403, "主题自带的图片不能删");
            return;
        }
        if (!file.delete()) {
            text(out, 500, "删除失败");
            return;
        }
        Log.i(TAG, "deleted " + name);
        text(out, 200, "ok");
    }

    /**
     * Decodes the upload, turns it the right way up, scales it down to the panel and writes it as a JPEG named
     * the way Home numbers a wallpaper collection ({@code 07-beach.jpg}). Returns null when it is not an image.
     */
    private File store(File temp, File dir, String wanted) {
        Bitmap bitmap = decodeSampled(temp.getPath(), MAX_EDGE);
        if (bitmap == null) return null;

        int degrees = orientationDegrees(temp);
        if (degrees != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(degrees);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (rotated != bitmap) {
                bitmap.recycle();
                bitmap = rotated;
            }
        }
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        float scale = Math.min(1f, (float) MAX_EDGE / Math.max(w, h));
        if (scale < 1f) {
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(w * scale)),
                    Math.max(1, Math.round(h * scale)), true);
            bitmap.recycle();
            bitmap = scaled;
        }

        File target = new File(dir, nextName(dir, wanted));
        try (FileOutputStream fos = new FileOutputStream(target)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                return null;
            }
        } catch (IOException e) {
            Log.w(TAG, "could not write " + target.getName() + ": " + e.getMessage());
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            return null;
        } finally {
            bitmap.recycle();
        }
        return target;
    }

    /**
     * Bounds-decode first, then halve the sample size until the long edge fits maxEdge, then decode for real.
     * The one decode pipeline both the stored wallpaper and the page's thumbnails go through.
     */
    private static Bitmap decodeSampled(String path, int maxEdge) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2;
        options.inSampleSize = sample;
        return BitmapFactory.decodeFile(path, options);
    }

    /** A small JPEG for the page's grid; decoding the full picture for every tile would crawl on a phone too. */
    private byte[] thumbnail(String name) {
        File dir = wallpaperDir();
        if (dir == null || name == null) return null;
        File file = new File(dir, name);
        if (!file.isFile() || !dir.equals(file.getParentFile())) return null;
        Bitmap bitmap = decodeSampled(file.getPath(), 360);
        if (bitmap == null) return null;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 78, bytes);
        bitmap.recycle();
        return bytes.toByteArray();
    }

    // ---- the page ------------------------------------------------------------------------------------

    private String page() {
        File dir = wallpaperDir();
        List<File> images = dir == null ? new ArrayList<>() : WallpaperRotation.images(dir);
        List<String> builtIn = bundled();
        StringBuilder items = new StringBuilder();
        for (File image : images) {
            String name = image.getName();
            boolean removable = !builtIn.contains(name);
            items.append("<figure class=\"card\">")
                    .append("<img loading=\"lazy\" src=\"/thumb?name=").append(urlEncode(name)).append("\" alt=\"\">")
                    .append("<figcaption>").append(escape(prettyName(name))).append("</figcaption>");
            if (removable) {
                items.append("<button class=\"del\" data-name=\"").append(escape(name)).append("\">删除</button>");
            }
            items.append("</figure>");
        }
        if (images.isEmpty()) items.append("<p class=\"empty\">还没有图片</p>");

        String html = PAGE;
        html = html.replace("__COUNT__", String.valueOf(images.size()));
        html = html.replace("__MAX__", String.valueOf(MAX_IMAGES));
        html = html.replace("__ITEMS__", items.toString());
        return html;
    }

    private String nextName(File dir, String wanted) {
        int max = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                Matcher m = SORT_PREFIX.matcher(file.getName());
                if (!m.find()) continue;
                try {
                    max = Math.max(max, Integer.parseInt(m.group(1)));
                } catch (NumberFormatException ignored) {
                    // a name that only looks numeric: ignore it
                }
            }
        }
        return String.format(Locale.ROOT, "%02d-%s.jpg", max + 1, slug(wanted));
    }

    /** The file's stem with the sort prefix and extension off: what the TV shows on the wallpaper chip. */
    private static String prettyName(String fileName) {
        String name = fileName;
        Matcher m = SORT_PREFIX.matcher(name);
        if (m.find()) name = name.substring(m.end());
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String slug(String raw) {
        if (raw == null) return "photo";
        String base = raw.trim();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        base = base.replaceAll("[\\\\/:*?\"<>|\\s]+", "-").replaceAll("^-+|-+$", "");
        if (base.isEmpty()) base = "photo";
        return base.length() > 24 ? base.substring(0, 24) : base;
    }

    private int countImages(File dir) {
        return WallpaperRotation.images(dir).size();
    }

    /** Names the theme shipped; those are restored from the APK when missing, so they are not the owner's to delete. */
    private List<String> bundled() {
        try {
            String[] names = app.getAssets().list("defaults/wallpapers");
            if (names == null) return Collections.emptyList();
            List<String> list = new ArrayList<>();
            Collections.addAll(list, names);
            return list;
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    private File wallpaperDir() {
        File dir = Config.dir(app);
        if (dir == null) return null;
        File wallpapers = new File(dir, "wallpapers");
        //noinspection ResultOfMethodCallIgnored
        wallpapers.mkdirs();
        return wallpapers;
    }

    private static int orientationDegrees(File file) {
        try {
            ExifInterface exif = new ExifInterface(file.getPath());
            switch (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    // ---- HTTP plumbing -------------------------------------------------------------------------------

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') line.write(c);
            if (line.size() > 8192) return null;
        }
        if (c == -1 && line.size() == 0) return null;
        return line.toString("UTF-8");
    }

    private static Map<String, String> readHeaders(InputStream in) throws IOException {
        Map<String, String> headers = new HashMap<>();
        for (int i = 0; i < 64; i++) {
            String line = readLine(in);
            if (line == null || line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
        }
        return headers;
    }

    /** Writes exactly {@code length} bytes from the socket into the file, never trusting the client to stop. */
    private static void copy(InputStream in, File to, long length) throws IOException {
        try (FileOutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[64 * 1024];
            long left = length;
            while (left > 0) {
                int read = in.read(buffer, 0, (int) Math.min(buffer.length, left));
                if (read == -1) throw new IOException("connection ended after " + (length - left) + " of " + length);
                out.write(buffer, 0, read);
                left -= read;
            }
        }
    }

    private void text(OutputStream out, int code, String message) throws IOException {
        respond(out, code, "text/plain; charset=utf-8", message.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(OutputStream out, int code, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + code + " " + reason(code) + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 411: return "Length Required";
            case 413: return "Payload Too Large";
            case 415: return "Unsupported Media Type";
            case 500: return "Internal Server Error";
            case 507: return "Insufficient Storage";
            default: return "Error";
        }
    }

    private static long parseLong(String value) {
        try {
            return value == null ? -1 : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return value;
        }
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** The TV's address on the LAN; the first site-local IPv4 wins, which is the WiFi one on these boxes. */
    private static String localIp() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "no address: " + e.getMessage());
        }
        return null;
    }

    /**
     * The whole page, in one string: no template engine, no assets to keep in sync. Uploaded files go up one
     * request each, so the phone never has to build a multipart body and we never have to parse one.
     */
    private static final String PAGE = "<!doctype html>\n"
            + "<html lang=\"zh-CN\"><head><meta charset=\"utf-8\">\n"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n"
            + "<title>电视壁纸</title><style>\n"
            + "*{box-sizing:border-box}body{margin:0;padding:24px 18px 48px;font:16px/1.5 -apple-system,"
            + "BlinkMacSystemFont,\"Segoe UI\",\"PingFang SC\",\"Microsoft YaHei\",sans-serif;color:#1c1c1e;"
            + "background:#f5f5f7}h1{font-size:22px;margin:0 0 6px}.hint{color:#6b6b70;margin:0 0 22px;font-size:14px}\n"
            + ".pick{display:block;padding:26px;text-align:center;border:2px dashed #b9b9c0;border-radius:14px;"
            + "background:#fff;color:#0a84ff;font-weight:600;cursor:pointer}\n"
            + ".pick input{display:none}.pick:active{background:#eef4ff}\n"
            + "#log{margin:16px 0;font-size:14px;color:#3a3a3c;white-space:pre-wrap;min-height:1px}\n"
            + "h2{font-size:16px;margin:26px 0 12px;color:#3a3a3c}\n"
            + ".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:14px}\n"
            + ".card{margin:0;background:#fff;border-radius:12px;overflow:hidden;box-shadow:0 1px 3px rgba(0,0,0,.1);"
            + "display:flex;flex-direction:column}\n"
            + ".card img{width:100%;height:96px;object-fit:cover;display:block;background:#ddd}\n"
            + ".card figcaption{padding:8px 10px 0;font-size:13px;color:#3a3a3c;overflow:hidden;"
            + "text-overflow:ellipsis;white-space:nowrap}\n"
            + ".del{margin:6px 10px 10px;padding:6px;border:0;border-radius:8px;background:#ffe8e6;color:#d70015;"
            + "font-size:13px;cursor:pointer}.del:active{background:#ffd3cf}\n"
            + ".empty{color:#8e8e93;font-size:14px}.foot{margin-top:30px;color:#8e8e93;font-size:12px;line-height:1.7}\n"
            + "</style></head><body>\n"
            + "<h1>给电视加壁纸</h1>\n"
            + "<p class=\"hint\">手机或电脑和电视连同一个 WiFi，选好的图片会直接出现在电视的"
            + "「设置 → 壁纸样式」里，也能用作屏保的图片样式。</p>\n"
            + "<label class=\"pick\">选择图片<input id=\"f\" type=\"file\" accept=\"image/*\" multiple></label>\n"
            + "<div id=\"log\"></div>\n"
            + "<h2>电视上已有 __COUNT__ 张（上限 __MAX__）</h2>\n"
            + "<div class=\"grid\">__ITEMS__</div>\n"
            + "<p class=\"foot\">上传的图片会缩放到 1920 像素以内并转成 JPEG，原图不必压缩。"
            + "手机相册里直接选图即可，一次可以选多张。</p>\n"
            + "<script>\n"
            + "var logEl=document.getElementById('log'),input=document.getElementById('f');\n"
            + "function say(t){logEl.textContent=t}\n"
            + "input.addEventListener('change',function(){\n"
            + "  var files=Array.prototype.slice.call(input.files);if(!files.length)return;\n"
            + "  var i=0;\n"
            + "  function next(){\n"
            + "    if(i>=files.length){say('完成，共上传 '+files.length+' 张，正在刷新…');"
            + "setTimeout(function(){location.reload()},900);return}\n"
            + "    var f=files[i++];say('正在上传 '+i+'/'+files.length+'：'+f.name);\n"
            + "    var x=new XMLHttpRequest();\n"
            + "    x.open('POST','/upload?name='+encodeURIComponent(f.name));\n"
            + "    x.setRequestHeader('Content-Type',f.type||'application/octet-stream');\n"
            + "    x.onload=function(){\n"
            + "      if(x.status!==200){say('第 '+i+' 张失败：'+x.responseText);return}\n"
            + "      next()};\n"
            + "    x.onerror=function(){say('第 '+i+' 张失败：网络中断')};\n"
            + "    x.send(f)}\n"
            + "  next()});\n"
            + "Array.prototype.forEach.call(document.querySelectorAll('.del'),function(b){\n"
            + "  b.addEventListener('click',function(){\n"
            + "    if(!confirm('删除「'+b.dataset.name+'」？'))return;\n"
            + "    var x=new XMLHttpRequest();x.open('POST','/delete?name='+encodeURIComponent(b.dataset.name));\n"
            + "    x.onload=function(){location.reload()};x.send()})});\n"
            + "</script></body></html>\n";
}
