package com.mk15.portinspector;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ResearchTransports {
    public static final String UDP = "UDP";
    public static final String BLUETOOTH = "BLUETOOTH";
    public static final String UART0 = "UART0";
    public static final String UART1 = "UART1";
    public static final String UART2 = "UART2";
    public static final String UART = UART0;

    public interface Listener {
        void onBytes(String source, byte[] data, int len);
        void onInfo(String source, String message);
        void onError(String source, String message, Throwable error);
    }

    private interface Sender {
        void send(byte[] data) throws Exception;
    }

    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private static final class Session {
        final String source;
        final AtomicLong rxBytes = new AtomicLong();
        final AtomicLong rxChunks = new AtomicLong();
        final AtomicLong txBytes = new AtomicLong();
        volatile String status = "disconnected";
        volatile String config = "";
        volatile String lastHex = "";
        volatile boolean running;
        volatile Thread thread;
        volatile Sender sender;
        volatile Closeable closer;

        Session(String source) {
            this.source = source;
        }
    }

    private final Listener listener;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public ResearchTransports(Listener listener) {
        this.listener = listener;
    }

    public synchronized void connectUdp(final String host, final int localPort, final int remotePort)
            throws Exception {
        stop(UDP);

        final DatagramSocket socket = new DatagramSocket(null);
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(localPort));
        socket.setSoTimeout(500);

        final InetAddress remoteAddress = InetAddress.getByName(host);
        final Session session = new Session(UDP);
        session.running = true;
        session.status = "connected local=" + localPort + " remote=" + host + ":" + remotePort;
        session.closer = socket;
        session.sender = data -> {
            DatagramPacket packet = new DatagramPacket(data, data.length, remoteAddress, remotePort);
            socket.send(packet);
            session.txBytes.addAndGet(data.length);
        };
        sessions.put(UDP, session);

        session.thread = new Thread(() -> {
            byte[] buffer = new byte[4096];
            info(UDP, session.status);
            try {
                while (session.running && !socket.isClosed()) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        socket.receive(packet);
                        int len = packet.getLength();
                        if (len <= 0) continue;
                        byte[] copy = Arrays.copyOfRange(packet.getData(),
                                packet.getOffset(), packet.getOffset() + len);
                        recordRx(session, copy, len);
                        if (listener != null) listener.onBytes(UDP, copy, len);
                    } catch (SocketTimeoutException ignored) {
                    }
                }
            } catch (Throwable t) {
                if (session.running) error(UDP, "UDP receive failed", t);
            } finally {
                session.running = false;
                session.status = "disconnected";
                try { socket.close(); } catch (Throwable ignored) {}
            }
        }, "mk15-udp-reader");
        session.thread.start();
    }

    public synchronized void connectRaw(final String path) throws Exception {
        connectRaw(UART0, path);
    }

    public synchronized void connectRaw(final String source, final String path) throws Exception {
        stop(source);
        if (UART0.equals(source)) {
            connectNativeUart0(path);
            return;
        }

        final File file = new File(path);
        if (!file.exists()) throw new Exception(path + " does not exist");
        if (!file.canRead()) throw new SecurityException(path + " is not readable by this APK");

        final FileInputStream input = new FileInputStream(file);
        FileOutputStream output = null;
        if (file.canWrite()) {
            try {
                output = new FileOutputStream(file);
            } catch (Throwable t) {
                info(source, path + " is readable but write open failed: " + t.getClass().getSimpleName());
            }
        }

        final FileOutputStream finalOutput = output;
        final Session session = new Session(source);
        session.running = true;
        session.config = "direct file mode";
        session.status = "connected " + path + (finalOutput == null ? " read-only" : " read/write");
        session.closer = input;
        if (finalOutput != null) {
            session.sender = data -> {
                finalOutput.write(data);
                finalOutput.flush();
                session.txBytes.addAndGet(data.length);
            };
        }
        sessions.put(source, session);

        session.thread = new Thread(() -> {
            byte[] buffer = new byte[1024];
            info(source, session.status);
            try {
                while (session.running) {
                    int n = input.read(buffer);
                    if (n < 0) break;
                    if (n == 0) continue;
                    byte[] copy = Arrays.copyOf(buffer, n);
                    recordRx(session, copy, n);
                    if (listener != null) listener.onBytes(source, copy, n);
                }
            } catch (Throwable t) {
                if (session.running) error(source, "Raw UART read failed", t);
            } finally {
                session.running = false;
                session.status = "disconnected";
                try { input.close(); } catch (Throwable ignored) {}
                if (finalOutput != null) {
                    try { finalOutput.close(); } catch (Throwable ignored) {}
                }
            }
        }, "mk15-" + source.toLowerCase() + "-reader");
        session.thread.start();
    }

    private void connectNativeUart0(final String path) throws Exception {
        final File file = new File(path);
        if (!file.exists()) throw new Exception(path + " does not exist");
        if (!file.canRead() || !file.canWrite()) {
            throw new SecurityException(path + " must be readable and writable");
        }
        if (!"/dev/ttyHS0".equals(path)) {
            throw new IllegalArgumentException("Native UART0 only supports /dev/ttyHS0");
        }
        if (!NativeSerial.isAvailable()) {
            throw new Exception("Native serial library unavailable: " + NativeSerial.loadError());
        }

        final NativeSerial.Port port = NativeSerial.open(path, 115200);
        final Session session = new Session(UART0);
        session.running = true;
        session.status = "connected /dev/ttyHS0 native termios 115200 8N1";
        session.config = port.describe();
        session.closer = port;
        session.sender = data -> {
            port.write(data);
            session.txBytes.addAndGet(data.length);
        };
        sessions.put(UART0, session);

        info(UART0, session.status + "; " + session.config);

        session.thread = new Thread(() -> {
            byte[] buffer = new byte[2048];
            try {
                while (session.running) {
                    int n = port.read(buffer, 500);
                    if (n == 0) continue;
                    byte[] copy = Arrays.copyOf(buffer, n);
                    recordRx(session, copy, n);
                    if (listener != null) listener.onBytes(UART0, copy, n);
                }
            } catch (Throwable t) {
                if (session.running) error(UART0, "Native UART0 read failed", t);
            } finally {
                session.running = false;
                session.status = "disconnected";
                try { port.close(); } catch (Throwable ignored) {}
            }
        }, "mk15-uart0-native-reader");
        session.thread.start();
    }

    public synchronized void connectBluetoothAsync() throws Exception {
        stop(BLUETOOTH);
        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) throw new Exception("Bluetooth adapter is not available");
        if (!adapter.isEnabled()) throw new Exception("Bluetooth is disabled");

        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded == null || bonded.isEmpty()) {
            throw new Exception("No paired Bluetooth devices");
        }

        List<BluetoothDevice> devices = new ArrayList<>(bonded);
        Collections.sort(devices, new Comparator<BluetoothDevice>() {
            @Override
            public int compare(BluetoothDevice a, BluetoothDevice b) {
                return safeName(a).compareToIgnoreCase(safeName(b));
            }
        });

        BluetoothDevice chosen = null;
        for (BluetoothDevice d : devices) {
            String n = safeName(d).toUpperCase();
            if (n.contains("SIYI")) {
                chosen = d;
                break;
            }
        }
        if (chosen == null) chosen = devices.get(0);

        final BluetoothDevice device = chosen;
        final Session session = new Session(BLUETOOTH);
        session.running = true;
        session.status = "connecting " + safeName(device) + " " + device.getAddress();
        sessions.put(BLUETOOTH, session);
        info(BLUETOOTH, session.status + "; paired=" + pairedBluetoothSummary());

        session.thread = new Thread(() -> {
            BluetoothSocket socket = null;
            InputStream input = null;
            OutputStream output = null;
            try {
                adapter.cancelDiscovery();
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                session.closer = socket;
                socket.connect();

                input = socket.getInputStream();
                output = socket.getOutputStream();
                final OutputStream finalOutput = output;
                session.sender = data -> {
                    finalOutput.write(data);
                    finalOutput.flush();
                    session.txBytes.addAndGet(data.length);
                };
                session.status = "connected " + safeName(device) + " " + device.getAddress();
                info(BLUETOOTH, session.status);

                byte[] buffer = new byte[1024];
                while (session.running) {
                    int n = input.read(buffer);
                    if (n < 0) break;
                    if (n == 0) continue;
                    byte[] copy = Arrays.copyOf(buffer, n);
                    recordRx(session, copy, n);
                    if (listener != null) listener.onBytes(BLUETOOTH, copy, n);
                }
            } catch (Throwable t) {
                if (session.running) error(BLUETOOTH, "Bluetooth SPP failed", t);
            } finally {
                session.running = false;
                session.status = "disconnected";
                try { if (input != null) input.close(); } catch (Throwable ignored) {}
                try { if (output != null) output.close(); } catch (Throwable ignored) {}
                try { if (socket != null) socket.close(); } catch (Throwable ignored) {}
            }
        }, "mk15-bluetooth-reader");
        session.thread.start();
    }

    public synchronized boolean send(String source, byte[] data) throws Exception {
        Session session = sessions.get(source);
        if (session == null || !session.running) {
            throw new Exception(source + " is not connected");
        }
        if (session.sender == null) {
            throw new Exception(source + " is connected read-only");
        }
        session.sender.send(data);
        return true;
    }

    public synchronized boolean isConnected(String source) {
        Session session = sessions.get(source);
        return session != null && session.running;
    }

    public synchronized boolean isWritable(String source) {
        Session session = sessions.get(source);
        return session != null && session.running && session.sender != null;
    }

    public synchronized void stop(String source) {
        Session session = sessions.remove(source);
        if (session == null) return;
        session.running = false;
        Thread thread = session.thread;
        if (thread != null) thread.interrupt();
        Closeable closer = session.closer;
        if (closer != null) {
            try { closer.close(); } catch (Throwable ignored) {}
        }
        session.status = "disconnected";
        info(source, "disconnected");
    }

    public synchronized void stopAll() {
        stop(UDP);
        stop(BLUETOOTH);
        stop(UART0);
        stop(UART1);
        stop(UART2);
    }

    public Map<String, String> snapshot() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String source : Arrays.asList(UDP, BLUETOOTH, UART0, UART1, UART2)) {
            Session s = sessions.get(source);
            String p = "transport." + source.toLowerCase() + ".";
            if (s == null) {
                out.put(p + "status", "disconnected");
                continue;
            }
            out.put(p + "status", s.status);
            out.put(p + "config", s.config);
            out.put(p + "rxBytes", String.valueOf(s.rxBytes.get()));
            out.put(p + "rxChunks", String.valueOf(s.rxChunks.get()));
            out.put(p + "txBytes", String.valueOf(s.txBytes.get()));
            out.put(p + "lastHex", s.lastHex);
            out.put(p + "writable", String.valueOf(s.sender != null));
        }
        out.put("transport.bluetooth.paired", pairedBluetoothSummary());
        return out;
    }

    public static boolean hasPairedSiyiDevice() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) return false;
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null) return false;
            for (BluetoothDevice d : bonded) {
                String n = safeName(d).toUpperCase();
                if (n.contains("SIYI")) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static String pairedBluetoothSummary() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) return "adapter unavailable";
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null || bonded.isEmpty()) return "none";
            List<String> items = new ArrayList<>();
            for (BluetoothDevice d : bonded) {
                items.add(safeName(d) + "@" + d.getAddress());
            }
            Collections.sort(items, String.CASE_INSENSITIVE_ORDER);
            return items.toString();
        } catch (Throwable t) {
            return "unavailable: " + t.getClass().getSimpleName();
        }
    }

    private static String safeName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null ? "(unnamed)" : n;
        } catch (Throwable t) {
            return "(name unavailable)";
        }
    }

    private static String configureSerialRaw(String path, int baud) {
        String safePath = path == null ? "" : path;
        if (!safePath.matches("/dev/ttyHS[0-9]+")) {
            return "skipped: unsupported path";
        }

        String binaryFlags = "-echo -ixon -ixoff -ixany -icrnl -inlcr -opost -iuclc "
                + "-istrip -inpck -ignpar -parmrk -iutf8 cs8 -parenb -cstopb";
        String strongArgs = safePath + " " + baud + " raw " + binaryFlags;
        String fallbackArgs = safePath + " " + baud + " raw " + binaryFlags;
        String finalFix = "stty -F " + safePath
                + " -iuclc -ixon -ixoff -ixany -icrnl -inlcr -opost"
                + " -istrip -inpck -ignpar -parmrk -iutf8 cs8 -parenb -cstopb";
        String[] commands = {
                "stty -F " + strongArgs,
                "toybox stty -F " + strongArgs,
                "/system/bin/toybox stty -F " + strongArgs,
                "stty -F " + fallbackArgs
        };

        StringBuilder result = new StringBuilder();
        for (String command : commands) {
            ShellResult shell = runShell(command);
            if (result.length() > 0) result.append(" | ");
            result.append("[").append(command).append("] rc=").append(shell.code);
            if (!shell.output.isEmpty()) result.append(" out=").append(shell.output);
            if (shell.code == 0) {
                ShellResult fix = runShell(finalFix);
                result.append(" | [").append(finalFix).append("] rc=").append(fix.code);
                if (!fix.output.isEmpty()) result.append(" out=").append(fix.output);

                ShellResult effective = runShell("stty -F " + safePath + " -a");
                result.append(" | effective rc=").append(effective.code);
                if (!effective.output.isEmpty()) result.append(" ").append(effective.output);

                String eff = effective.output == null ? "" : effective.output;
                if (containsPositiveFlag(eff, "iuclc")) result.append(" | WARNING:iuclc=ON");
                if (containsPositiveFlag(eff, "istrip")) result.append(" | WARNING:istrip=ON");
                if (containsPositiveFlag(eff, "inpck")) result.append(" | WARNING:inpck=ON");
                if (containsPositiveFlag(eff, "ignpar")) result.append(" | WARNING:ignpar=ON");
                if (containsPositiveFlag(eff, "ixon")) result.append(" | WARNING:ixon=ON");
                if (containsPositiveFlag(eff, "ixoff")) result.append(" | WARNING:ixoff=ON");
                if (containsPositiveFlag(eff, "ixany")) result.append(" | WARNING:ixany=ON");
                if (containsPositiveFlag(eff, "icrnl")) result.append(" | WARNING:icrnl=ON");
                if (containsPositiveFlag(eff, "inlcr")) result.append(" | WARNING:inlcr=ON");
                return result.toString();
            }
        }
        return result.toString();
    }

    private static boolean containsPositiveFlag(String stty, String flag) {
        if (stty == null || stty.isEmpty()) return false;
        String normalized = " " + stty.replace(';', ' ').replace('\n', ' ') + " ";
        return normalized.contains(" " + flag + " ")
                && !normalized.contains(" -" + flag + " ");
    }

    private static final class ShellResult {
        final int code;
        final String output;

        ShellResult(int code, String output) {
            this.code = code;
            this.output = output == null ? "" : output;
        }
    }

    private static ShellResult runShell(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream in = process.getInputStream();
            byte[] buffer = new byte[512];
            int total = 0;
            int n;
            while ((n = in.read(buffer)) >= 0 && total < 4096) {
                if (n == 0) continue;
                int keep = Math.min(n, 4096 - total);
                out.write(buffer, 0, keep);
                total += keep;
            }
            int code = process.waitFor();
            String text = new String(out.toByteArray()).trim().replace('\n', ' ');
            return new ShellResult(code, text);
        } catch (Throwable t) {
            return new ShellResult(-999, t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (process != null) {
                try { process.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    private void recordRx(Session session, byte[] data, int len) {
        session.rxBytes.addAndGet(len);
        long chunks = session.rxChunks.incrementAndGet();
        // Formatting every 20 Hz RC packet into hex is expensive on MK15 and is
        // diagnostic-only. Keep an early sample and then one fresh sample/sec.
        if (chunks <= 4 || (chunks % 20) == 0) {
            byte[] shown = data;
            if (shown.length > 96) shown = Arrays.copyOf(shown, 96);
            session.lastHex = SiyiProtocol.hex(shown) + (len > shown.length ? " ..." : "");
        }
    }

    private void info(String source, String message) {
        if (listener != null) listener.onInfo(source, message);
    }

    private void error(String source, String message, Throwable error) {
        if (listener != null) listener.onError(source, message, error);
    }
}
