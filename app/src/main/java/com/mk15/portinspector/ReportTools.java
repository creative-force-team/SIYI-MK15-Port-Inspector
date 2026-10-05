package com.mk15.portinspector;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ReportTools {
    public static final class UploadResult {
        public final int statusCode;
        public final String responseBody;

        UploadResult(int statusCode, String responseBody) {
            this.statusCode = statusCode;
            this.responseBody = responseBody == null ? "" : responseBody;
        }

        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }
    }

    private ReportTools() {}

    public static File createZip(File outputDir, String fileName, Map<String, byte[]> entries)
            throws Exception {
        if (outputDir == null) throw new IllegalArgumentException("outputDir is null");
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new Exception("Cannot create report directory: " + outputDir);
        }

        String safeName = fileName == null ? "MK15_Report.zip" : fileName.trim();
        if (!safeName.toLowerCase().endsWith(".zip")) safeName += ".zip";
        safeName = safeName.replaceAll("[^A-Za-z0-9._-]", "_");

        File output = new File(outputDir, safeName);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(output))) {
            if (entries != null) {
                for (Map.Entry<String, byte[]> item : entries.entrySet()) {
                    String entryName = safeEntryName(item.getKey());
                    byte[] data = item.getValue() == null ? new byte[0] : item.getValue();
                    ZipEntry entry = new ZipEntry(entryName);
                    zip.putNextEntry(entry);
                    zip.write(data);
                    zip.closeEntry();
                }
            }
        }
        return output;
    }

    public static byte[] utf8(String text) {
        return (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] readFile(File file) throws Exception {
        if (file == null || !file.exists()) return new byte[0];
        try (InputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            copy(in, out);
            return out.toByteArray();
        }
    }

    public static void copyFile(File source, File target) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new Exception("Cannot create directory: " + parent);
        }
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            copy(in, out);
        }
    }

    public static void copyToStream(File source, OutputStream out) throws Exception {
        try (InputStream in = new FileInputStream(source)) {
            copy(in, out);
        }
    }

    public static UploadResult uploadMultipart(
            String endpoint,
            File zipFile,
            Map<String, String> fields) throws Exception {

        if (zipFile == null || !zipFile.exists()) {
            throw new Exception("Report ZIP does not exist");
        }

        String boundary = "----MK15PortInspector" + System.currentTimeMillis();
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(45000);
        connection.setUseCaches(false);
        connection.setDoInput(true);
        connection.setDoOutput(true);
        connection.setRequestMethod("POST");
        connection.setInstanceFollowRedirects(true);
        connection.setChunkedStreamingMode(16384);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        connection.setRequestProperty("User-Agent", "MK15-Port-Inspector/1.5.1");
        connection.setRequestProperty("Accept", "application/json, text/plain, */*");

        try (OutputStream out = connection.getOutputStream()) {
            Map<String, String> safeFields = fields == null
                    ? new LinkedHashMap<String, String>() : fields;

            for (Map.Entry<String, String> field : safeFields.entrySet()) {
                writeAscii(out, "--" + boundary + "\r\n");
                writeAscii(out, "Content-Disposition: form-data; name=\""
                        + escapeHeader(field.getKey()) + "\"\r\n");
                writeAscii(out, "Content-Type: text/plain; charset=UTF-8\r\n\r\n");
                out.write(utf8(field.getValue()));
                writeAscii(out, "\r\n");
            }

            writeAscii(out, "--" + boundary + "\r\n");
            writeAscii(out, "Content-Disposition: form-data; name=\"report\"; filename=\""
                    + escapeHeader(zipFile.getName()) + "\"\r\n");
            writeAscii(out, "Content-Type: application/zip\r\n");
            writeAscii(out, "Content-Transfer-Encoding: binary\r\n\r\n");
            copyToStream(zipFile, out);
            writeAscii(out, "\r\n--" + boundary + "--\r\n");
            out.flush();
        }

        int code = connection.getResponseCode();
        InputStream response = null;
        try {
            response = code >= 200 && code < 400
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = response == null ? "" : readLimitedUtf8(response, 16384);
            return new UploadResult(code, body);
        } finally {
            if (response != null) {
                try { response.close(); } catch (Throwable ignored) {}
            }
            connection.disconnect();
        }
    }

    private static String readLimitedUtf8(InputStream input, int maxBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int total = 0;
        while (total < maxBytes) {
            int want = Math.min(buffer.length, maxBytes - total);
            int n = input.read(buffer, 0, want);
            if (n < 0) break;
            if (n == 0) continue;
            out.write(buffer, 0, n);
            total += n;
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) >= 0) {
            if (n == 0) continue;
            out.write(buffer, 0, n);
        }
    }

    private static String safeEntryName(String name) {
        String value = name == null ? "unnamed.txt" : name.replace('\\', '/');
        while (value.startsWith("/")) value = value.substring(1);
        value = value.replace("../", "_").replace("..\\", "_");
        if (value.isEmpty()) value = "unnamed.txt";
        return value;
    }

    private static String escapeHeader(String value) {
        if (value == null) return "";
        return value.replace("\r", "_").replace("\n", "_").replace("\"", "_");
    }

    private static void writeAscii(OutputStream out, String text) throws Exception {
        out.write(text.getBytes(StandardCharsets.US_ASCII));
    }
}
