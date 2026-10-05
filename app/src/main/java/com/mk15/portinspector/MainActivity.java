package com.mk15.portinspector;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Диагностическое приложение для SIYI MK15.
 *
 * Основной безопасный путь чтения каналов: встроенный CP2102 (режим SIYI TX -> USB COM)
 * и публичный SIYI Datalink SDK. Приложение не требует root и не изменяет mapping.
 */
public final class MainActivity extends Activity implements SiyiProtocol.FrameListener {
    private static final String ACTION_USB_PERMISSION = "com.mk15.portinspector.USB_PERMISSION";
    private static final int CHANNEL_COUNT = 16;
    private static final int LOG_LIMIT = 128_000;
    private static final int REQUEST_CREATE_REPORT_FILE = 3101;
    private static final int REQUEST_WRITE_STORAGE = 3102;
    private static final String REPORT_ENDPOINT = "https://thesystem.pro/?action=siyi_receive";
    private static final String TRANSPORT_AUTO = "AUTO / все безопасные";
    private static final String TRANSPORT_USB = "USB COM / CP210x";
    private static final String TRANSPORT_UDP = "UDP";
    private static final String TRANSPORT_BLUETOOTH = "Bluetooth SPP";
    private static final String TRANSPORT_UART0 = "UART /dev/ttyHS0";
    private static final String TRANSPORT_UART1 = "UART /dev/ttyHS1";
    private static final String TRANSPORT_UART2 = "UART /dev/ttyHS2";
    private static final String TRANSPORT_INPUT = "Android Input (пассивный)";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger sequence = new AtomicInteger(0);
    private final SiyiProtocol.Parser parser = new SiyiProtocol.Parser(this);
    private final Map<String, SiyiProtocol.Parser> extraParsers = new ConcurrentHashMap<>();
    private final Map<String, int[]> rcBySource = new ConcurrentHashMap<>();
    private final Map<String, String> inputProbeState = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> inputProbeCounters = new ConcurrentHashMap<>();
    private final ProbeDiffEngine probeDiff = new ProbeDiffEngine();
    private final ChannelActivityTracker rcActivity = new ChannelActivityTracker();
    private final HardwareControlsResearch controlsResearch = new HardwareControlsResearch();

    private UsbManager usbManager;
    private UsbSerialCp210x serial;
    private UsbDevice pendingUsbDevice;

    private final int[] mappingType = new int[CHANNEL_COUNT];
    private final int[] mappingEntity = new int[CHANNEL_COUNT];
    private final int[] channelValue = new int[CHANNEL_COUNT];
    private final TextView[] channelRows = new TextView[CHANNEL_COUNT];
    private boolean mappingReceived;
    private volatile boolean streamEnabled;
    private volatile boolean finderActive;
    private int saChannelIndex = 4; // известное заводское значение до ответа 0x48

    private TextView statusText;
    private TextView saText;
    private TextView logText;
    private TextView scanText;
    private TextView finderText;
    private TextView reportText;
    private EditText baudEdit;
    private EditText udpHostEdit;
    private EditText udpPortEdit;
    private Spinner transportSpinner;
    private Spinner controlLabelSpinner;
    private Spinner controlKindSpinner;
    private EditText controlCustomLabelEdit;
    private TextView controlsResearchText;
    private volatile boolean controlsResearchActive;
    private volatile String currentTransport = TRANSPORT_AUTO;

    private ResearchTransports researchTransports;
    private final LinuxInputProbe linuxInputProbe = new LinuxInputProbe();

    private final StringBuilder sessionLog = new StringBuilder(16_384);
    private final StringBuilder finderHistory = new StringBuilder(8_192);
    private File runtimeLogFile;
    private volatile File lastReportZip;
    private volatile File pendingDownloadReport;
    private volatile File pendingPickerReport;
    private long usbRxBytes;
    private int usbRxChunks;
    private long usbTxBytes;
    private String usbLastHex = "";
    private int validSiyiFrames;
    private int channelFrameCount;
    private String lastRcSource = "none";

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (!granted || device == null) {
                appendLog("USB: разрешение не выдано.");
                setStatus("USB COM: нет разрешения");
                return;
            }
            appendLog("USB: разрешение получено для " + UsbSerialCp210x.describe(device));
            pendingUsbDevice = null;
            openUsbDevice(device);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Arrays.fill(mappingType, -1);
        Arrays.fill(mappingEntity, -1);
        Arrays.fill(channelValue, -1);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        registerReceiver(usbPermissionReceiver, new IntentFilter(ACTION_USB_PERMISSION));

        researchTransports = new ResearchTransports(new ResearchTransports.Listener() {
            @Override
            public void onBytes(String source, byte[] data, int len) {
                ingestBytes(source, data, len);
            }

            @Override
            public void onInfo(String source, String message) {
                appendLog(source + ": " + message);
                runOnUiThread(() -> setStatus(source + ": " + message));
            }

            @Override
            public void onError(String source, String message, Throwable error) {
                appendLog(source + ": " + message + (error == null ? "" : " — " + stackSummary(error)));
                runOnUiThread(() -> setStatus(source + ": ошибка"));
            }
        });

        setContentView(buildUi());
        initRuntimeLog();
        applyDefaultMappingPreview();
        appendLog("MK15 Port Inspector 1.5.2 запущен.");
        appendLog("Цель текущего исследования: полный пассивный инвентарь физических органов управления MK15.");
        appendLog("Режим исследования поддерживает USB COM, UDP, Bluetooth SPP, native ttyHS0, ttyHS1/2 и Android Input.");
        appendLog("Важно: активный поток 0x42 использует тот же канал связи, что телеметрия. Проверять только на столе, не в полёте.");
        linuxInputProbe.start();
        appendLog("Linux input probe: запущено пассивное чтение доступных /dev/input/event*.");
        scanPorts();
        statusText.postDelayed(() -> {
            appendLog("AUTO: пассивно подключаем доступные транспорты.");
            connectSelectedTransport();
        }, 500);
        statusText.postDelayed(() -> captureFinderBaseline("автоматическая база после подключения"), 1600);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.setBackgroundColor(Color.rgb(245, 245, 245));

        TextView title = text("SIYI MK15 — универсальный поиск органов управления", 22, true);
        root.addView(title, lpMatchWrap());

        LinearLayout researchPanel = new LinearLayout(this);
        researchPanel.setOrientation(LinearLayout.VERTICAL);
        researchPanel.setPadding(0, dp(4), 0, dp(8));
        researchPanel.setBackgroundColor(Color.rgb(232, 245, 233));

        researchPanel.addView(
                button("1. ЗАПУСТИТЬ ИССЛЕДОВАНИЕ", v -> startControlsResearch()),
                lpMatchWrap());

        LinearLayout researchSelectorRow = new LinearLayout(this);
        researchSelectorRow.setOrientation(LinearLayout.HORIZONTAL);
        researchSelectorRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView researchLabelCaption = text("Орган:", 13, true);
        researchSelectorRow.addView(researchLabelCaption);

        controlLabelSpinner = new Spinner(this);
        String[] researchLabels = {
                "A", "B", "SA", "SB", "SC", "LD", "RD",
                "C (контроль)", "D (контроль)",
                "J1", "J2", "J3", "J4", "OTHER"
        };
        ArrayAdapter<String> labelAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, researchLabels);
        labelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        controlLabelSpinner.setAdapter(labelAdapter);
        // The operator mission starts with the already verified C button as a control sample.
        controlLabelSpinner.setSelection(7);
        researchSelectorRow.addView(controlLabelSpinner,
                new LinearLayout.LayoutParams(dp(145), LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView researchKindCaption = text("  Вид:", 13, true);
        researchSelectorRow.addView(researchKindCaption);

        controlKindSpinner = new Spinner(this);
        String[] researchKinds = {"BUTTON", "SWITCH_3POS", "ANALOG", "STICK", "OTHER"};
        ArrayAdapter<String> kindAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, researchKinds);
        kindAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        controlKindSpinner.setAdapter(kindAdapter);
        researchSelectorRow.addView(controlKindSpinner,
                new LinearLayout.LayoutParams(dp(170), LinearLayout.LayoutParams.WRAP_CONTENT));

        controlCustomLabelEdit = new EditText(this);
        controlCustomLabelEdit.setSingleLine(true);
        controlCustomLabelEdit.setHint("Имя для OTHER");
        researchSelectorRow.addView(controlCustomLabelEdit,
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        controlLabelSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                String label = selectedResearchLabel();
                controlKindSpinner.setSelection(kindIndexForLabel(label));
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        researchPanel.addView(researchSelectorRow, lpMatchWrap());

        LinearLayout researchActionRow = new LinearLayout(this);
        researchActionRow.setOrientation(LinearLayout.HORIZONTAL);
        researchActionRow.setGravity(Gravity.CENTER_VERTICAL);

        Button beginResearchButton = button("2. Начать запись", v -> beginControlExperiment());
        Button finishResearchButton = button("3. Завершить запись", v -> finishControlExperiment());
        Button stopResearchButton = button("Остановить исследование", v -> stopControlsResearch(true));

        researchActionRow.addView(beginResearchButton,
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        researchActionRow.addView(finishResearchButton,
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        researchActionRow.addView(stopResearchButton,
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        researchPanel.addView(researchActionRow, lpMatchWrap());

        LinearLayout researchReportRow = new LinearLayout(this);
        researchReportRow.setOrientation(LinearLayout.HORIZONTAL);
        researchReportRow.setGravity(Gravity.CENTER_VERTICAL);
        researchReportRow.addView(
                button("4. ZIP → thesystem", v -> uploadReportToThesystem()),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.62f));
        researchReportRow.addView(
                button("Резерв: ZIP → Download", v -> saveReportToDownloads()),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.38f));
        researchPanel.addView(researchReportRow, lpMatchWrap());

        root.addView(researchPanel, lpMatchWrap());

        controlsResearchText = text(
                "Шаг 1: нажмите «1. ЗАПУСТИТЬ ИССЛЕДОВАНИЕ». По умолчанию выбран контрольный орган C. Режим только читает SIYI mapping 0x48 и RC-каналы 0x42.",
                14, true);
        controlsResearchText.setPadding(dp(8), dp(4), dp(8), dp(4));
        controlsResearchText.setBackgroundColor(Color.rgb(232, 245, 233));
        root.addView(controlsResearchText, lpMatchWrap());

        TextView warning = text(
                "Диагностика на столе: команда чтения RC-каналов 0x42 может мешать телеметрии. " +
                        "Перед запуском закройте QGroundControl/другую программу, занявшую USB COM. " +
                        "Приложение mapping не изменяет.", 14, false);
        warning.setTextColor(Color.rgb(150, 55, 0));
        root.addView(warning, lpMatchWrap());

        LinearLayout statusLine = new LinearLayout(this);
        statusLine.setOrientation(LinearLayout.HORIZONTAL);
        statusLine.setGravity(Gravity.CENTER_VERTICAL);
        statusLine.setPadding(0, dp(5), 0, dp(4));

        statusText = text("USB COM: не подключён", 15, true);
        statusLine.addView(statusText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView baudLabel = text("Скорость:", 14, false);
        statusLine.addView(baudLabel);
        baudEdit = new EditText(this);
        baudEdit.setSingleLine(true);
        baudEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        baudEdit.setText("57600");
        baudEdit.setSelectAllOnFocus(true);
        statusLine.addView(baudEdit, new LinearLayout.LayoutParams(dp(125), LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(statusLine, lpMatchWrap());

        HorizontalScrollView transportScroller = new HorizontalScrollView(this);
        LinearLayout transportRow = new LinearLayout(this);
        transportRow.setOrientation(LinearLayout.HORIZONTAL);
        transportRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView transportLabel = text("Транспорт:", 14, true);
        transportRow.addView(transportLabel);

        transportSpinner = new Spinner(this);
        String[] transportItems = {
                TRANSPORT_AUTO,
                TRANSPORT_USB,
                TRANSPORT_UDP,
                TRANSPORT_BLUETOOTH,
                TRANSPORT_UART0,
                TRANSPORT_UART1,
                TRANSPORT_UART2,
                TRANSPORT_INPUT
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, transportItems);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        transportSpinner.setAdapter(adapter);
        transportSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                Object item = parent.getItemAtPosition(position);
                currentTransport = item == null ? TRANSPORT_AUTO : String.valueOf(item);
                appendLog("Выбран транспорт: " + currentTransport);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
                currentTransport = TRANSPORT_AUTO;
            }
        });
        transportRow.addView(transportSpinner,
                new LinearLayout.LayoutParams(dp(230), LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView hostLabel = text(" UDP host:", 13, false);
        transportRow.addView(hostLabel);
        udpHostEdit = new EditText(this);
        udpHostEdit.setSingleLine(true);
        udpHostEdit.setText("192.168.144.12");
        transportRow.addView(udpHostEdit,
                new LinearLayout.LayoutParams(dp(165), LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView portLabel = text(" port:", 13, false);
        transportRow.addView(portLabel);
        udpPortEdit = new EditText(this);
        udpPortEdit.setSingleLine(true);
        udpPortEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        udpPortEdit.setText("19856");
        transportRow.addView(udpPortEdit,
                new LinearLayout.LayoutParams(dp(90), LinearLayout.LayoutParams.WRAP_CONTENT));

        transportRow.addView(button("Подключить", v -> connectSelectedTransport()));
        transportRow.addView(button("Отключить всё", v -> disconnectAllTransports()));
        transportScroller.addView(transportRow);
        root.addView(transportScroller, lpMatchWrap());

        HorizontalScrollView commandScroller = new HorizontalScrollView(this);
        commandScroller.setHorizontalScrollBarEnabled(true);
        LinearLayout commands = new LinearLayout(this);
        commands.setOrientation(LinearLayout.HORIZONTAL);

        commands.addView(button("Сканировать порты", v -> scanPorts()));
        commands.addView(button("Подключить выбранный", v -> connectSelectedTransport()));
        commands.addView(button("Mapping 0x48", v -> requestMapping()));
        commands.addView(button("RC 4 Гц 0x42", v -> startRcStream()));
        commands.addView(button("СТОП RC", v -> stopRcStream(true)));
        commands.addView(button("Версии 0x40/0x47", v -> requestInfo()));
        commands.addView(button("ZIP → Download", v -> saveReportToDownloads()));
        commands.addView(button("ZIP → флешка/файл…", v -> saveReportWithPicker()));
        commands.addView(button("ZIP → thesystem", v -> uploadReportToThesystem()));
        commands.addView(button("Очистить журнал", v -> clearLog()));
        commandScroller.addView(commands);
        root.addView(commandScroller, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));


        LinearLayout finderButtons = new LinearLayout(this);
        finderButtons.setOrientation(LinearLayout.HORIZONTAL);
        finderButtons.addView(button("АВТОПОИСК C/D (20 Гц)", v -> startActiveFinder()));
        finderButtons.addView(button("1. Снять базу", v -> captureFinderBaseline("ручная база")));
        finderButtons.addView(button("C → сравнить", v -> compareFinderSnapshot("C")));
        finderButtons.addView(button("D → сравнить", v -> compareFinderSnapshot("D")));
        finderButtons.addView(button("Другое → сравнить", v -> compareFinderSnapshot("OTHER")));
        finderButtons.addView(button("Сброс поиска", v -> resetFinder()));
        root.addView(finderButtons, lpMatchWrap());

        finderText = text("Поиск: AUTO подключится сам. Для кнопок C/D нажмите «АВТОПОИСК C/D (20 Гц)».", 14, true);
        finderText.setPadding(dp(8), dp(4), dp(8), dp(4));
        finderText.setBackgroundColor(Color.rgb(227, 242, 253));
        root.addView(finderText, lpMatchWrap());

        reportText = text("Отчёт ZIP: ещё не сформирован", 13, true);
        reportText.setPadding(dp(8), dp(4), dp(8), dp(4));
        reportText.setBackgroundColor(Color.rgb(232, 245, 233));
        root.addView(reportText, lpMatchWrap());

        saText = text("Каналы: ждём живые данные; C≈CH10, D≈CH11 по заводскому mapping", 20, true);
        saText.setPadding(dp(8), dp(5), dp(8), dp(5));
        saText.setBackgroundColor(Color.rgb(255, 248, 225));
        root.addView(saText, lpMatchWrap());

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);

        ScrollView channelsScroll = new ScrollView(this);
        LinearLayout channelBox = new LinearLayout(this);
        channelBox.setOrientation(LinearLayout.VERTICAL);
        TextView channelHeader = text("16 коммуникационных каналов", 16, true);
        channelBox.addView(channelHeader, lpMatchWrap());
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            TextView row = text("CH" + String.format(Locale.US, "%02d", i + 1) + "  —", 15, false);
            row.setPadding(dp(8), dp(4), dp(8), dp(4));
            row.setBackgroundColor((i % 2 == 0) ? Color.WHITE : Color.rgb(238, 238, 238));
            channelRows[i] = row;
            channelBox.addView(row, lpMatchWrap());
        }
        channelsScroll.addView(channelBox);
        content.addView(channelsScroll, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.40f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(10), 0, 0, 0);

        TextView scanHeader = text("Видимые системе порты / устройства", 16, true);
        right.addView(scanHeader, lpMatchWrap());
        ScrollView scanScroll = new ScrollView(this);
        scanText = text("Сканирование...", 12, false);
        scanText.setTextIsSelectable(true);
        scanText.setTypeface(android.graphics.Typeface.MONOSPACE);
        scanScroll.addView(scanText);
        right.addView(scanScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.54f));

        TextView logHeader = text("Журнал протокола / Android input", 16, true);
        right.addView(logHeader, lpMatchWrap());
        ScrollView logScroll = new ScrollView(this);
        logText = text("", 12, false);
        logText.setTextIsSelectable(true);
        logText.setTypeface(android.graphics.Typeface.MONOSPACE);
        logScroll.addView(logText);
        right.addView(logScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.46f));

        content.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.60f));
        root.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(Color.rgb(30, 30, 30));
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        return b;
    }

    private LinearLayout.LayoutParams lpMatchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(value * d);
    }

    private void applyDefaultMappingPreview() {
        String[] defaults = {"J1", "J2", "J3", "J4", "SA", "SB", "SC", "A", "B", "C", "D", "LD", "RD", "—", "—", "—"};
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            updateChannelRow(i, defaults[i] + " (заводской)", -1, i == 4);
        }
    }

    private void scanPorts() {
        if (scanText != null) scanText.setText("Сканирование...\n");
        appendLog("Сканирование Android input, USB, /dev, /sys/class/tty и сетевых интерфейсов...");
        worker.submit(() -> {
            String report;
            try {
                report = SystemScanner.collect(getApplicationContext());
            } catch (Throwable t) {
                report = "Ошибка сканирования: " + stackSummary(t);
            }
            final String finalReport = report;
            runOnUiThread(() -> {
                if (scanText != null) scanText.setText(finalReport);
                appendLog("Сканирование завершено. Видимые интерфейсы показаны справа.");
            });
        });
    }

    private void connectUsb() {
        if (usbManager == null) {
            setStatus("UsbManager недоступен");
            appendLog("UsbManager недоступен.");
            return;
        }
        if (serial != null && serial.isOpen()) {
            appendLog("USB COM уже открыт: " + UsbSerialCp210x.describe(serial.getDevice()));
            return;
        }
        List<UsbDevice> candidates = UsbSerialCp210x.findSerialCandidates(usbManager);
        if (candidates.isEmpty()) {
            setStatus("USB COM: устройство с BULK IN/OUT не найдено");
            appendLog("USB: не найден ни CP210x, ни другой интерфейс с парой BULK IN/OUT. Нажмите «Сканировать порты» и сохраните отчёт.");
            return;
        }

        UsbDevice selected = candidates.get(0); // CP210x всегда сортируется первым
        appendLog("USB: выбран кандидат " + UsbSerialCp210x.describe(selected));
        if (!usbManager.hasPermission(selected)) {
            pendingUsbDevice = selected;
            Intent permissionIntent = new Intent(ACTION_USB_PERMISSION);
            permissionIntent.setPackage(getPackageName());
            int flags = 0;
            if (Build.VERSION.SDK_INT >= 31) flags = PendingIntent.FLAG_MUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, permissionIntent, flags);
            setStatus("USB COM: ожидается разрешение Android");
            usbManager.requestPermission(selected, pi);
            return;
        }
        openUsbDevice(selected);
    }

    private void openUsbDevice(UsbDevice device) {
        int baud;
        try {
            baud = Integer.parseInt(baudEdit.getText().toString().trim());
            if (baud < 300 || baud > 3_000_000) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Введите корректную скорость порта, например 57600", Toast.LENGTH_LONG).show();
            return;
        }

        final int selectedBaud = baud;
        worker.submit(() -> {
            closeSerial();
            UsbSerialCp210x candidate = new UsbSerialCp210x(usbManager, device, new UsbSerialCp210x.Listener() {
                @Override
                public void onBytes(byte[] data, int len) {
                    ingestBytes("USB", data, len);
                }

                @Override
                public void onInfo(String message) {
                    appendLog("USB: " + message);
                }

                @Override
                public void onError(String message, Throwable error) {
                    appendLog("USB: " + message + (error == null ? "" : " — " + error));
                    runOnUiThread(() -> setStatus("USB COM: ошибка чтения"));
                }
            });
            try {
                candidate.open(selectedBaud);
                serial = candidate;
                appendLog("USB COM открыт: " + UsbSerialCp210x.describe(device) + ", " + selectedBaud + " бод.");
                runOnUiThread(() -> setStatus("USB COM: подключён, " + selectedBaud + " бод"));
                // Сразу читаем фактический mapping и версии. Mapping ничего не изменяет.
                sendFrame(SiyiProtocol.mappingRequest(nextSeq()), "0x48 запрос mapping");
                sendFrame(SiyiProtocol.request(SiyiProtocol.CMD_HARDWARE_ID, new byte[0], nextSeq()), "0x40 hardware ID");
                sendFrame(SiyiProtocol.request(SiyiProtocol.CMD_FIRMWARE_VERSION, new byte[0], nextSeq()), "0x47 версии");
                if (finderActive) {
                    appendLog("Switch Finder ACTIVE: USB подключился позже; поток RC запускает центральный сценарий опыта.");
                }
            } catch (Throwable t) {
                candidate.close();
                serial = null;
                appendLog("USB COM открыть не удалось: " + stackSummary(t));
                runOnUiThread(() -> setStatus("USB COM: подключение не удалось"));
            }
        });
    }


    private String selectedTransport() {
        return currentTransport;
    }

    private int udpPort() {
        try {
            int p = Integer.parseInt(udpPortEdit.getText().toString().trim());
            if (p < 1 || p > 65535) throw new NumberFormatException();
            return p;
        } catch (Throwable t) {
            return 19856;
        }
    }

    private void connectSelectedTransport() {
        String selected = selectedTransport();
        appendLog("Подключение транспорта: " + selected);

        if (TRANSPORT_USB.equals(selected)) {
            connectUsb();
            return;
        }

        if (TRANSPORT_INPUT.equals(selected)) {
            setStatus("Android Input: пассивное наблюдение включено");
            captureFinderBaseline("Android Input активирован");
            return;
        }

        if (TRANSPORT_AUTO.equals(selected)) {
            final String udpHost = udpHostEdit.getText().toString().trim();
            final int udpPortValue = udpPort();
            connectUsb();
            worker.submit(() -> {
                try {
                    researchTransports.connectUdp(
                            udpHost, udpPortValue, udpPortValue);
                } catch (Throwable t) {
                    appendLog("AUTO UDP: " + stackSummary(t));
                }
                try {
                    researchTransports.connectRaw(ResearchTransports.UART0, "/dev/ttyHS0");
                } catch (Throwable t) {
                    appendLog("AUTO UART0: " + stackSummary(t));
                }
                try {
                    researchTransports.connectRaw(ResearchTransports.UART1, "/dev/ttyHS1");
                } catch (Throwable t) {
                    appendLog("AUTO UART1: " + stackSummary(t));
                }
                try {
                    researchTransports.connectRaw(ResearchTransports.UART2, "/dev/ttyHS2");
                } catch (Throwable t) {
                    appendLog("AUTO UART2: " + stackSummary(t));
                }
                if (ResearchTransports.hasPairedSiyiDevice()) {
                    try {
                        researchTransports.connectBluetoothAsync();
                    } catch (Throwable t) {
                        appendLog("AUTO Bluetooth: " + stackSummary(t));
                    }
                } else {
                    appendLog("AUTO Bluetooth: SIYI-подобного спаренного устройства нет; случайное Bluetooth-устройство не трогаем.");
                }
                captureFinderBaseline("AUTO transports подключены");
            });
            return;
        }

        final String udpHost = udpHostEdit.getText().toString().trim();
        final int udpPortValue = udpPort();
        worker.submit(() -> {
            try {
                if (TRANSPORT_UDP.equals(selected)) {
                    researchTransports.connectUdp(
                            udpHost, udpPortValue, udpPortValue);
                } else if (TRANSPORT_BLUETOOTH.equals(selected)) {
                    researchTransports.connectBluetoothAsync();
                } else if (TRANSPORT_UART0.equals(selected)) {
                    researchTransports.connectRaw(ResearchTransports.UART0, "/dev/ttyHS0");
                } else if (TRANSPORT_UART1.equals(selected)) {
                    researchTransports.connectRaw(ResearchTransports.UART1, "/dev/ttyHS1");
                } else if (TRANSPORT_UART2.equals(selected)) {
                    researchTransports.connectRaw(ResearchTransports.UART2, "/dev/ttyHS2");
                }
                captureFinderBaseline("транспорт " + selected + " подключён");
            } catch (Throwable t) {
                appendLog(selected + ": подключение не удалось — " + stackSummary(t));
                runOnUiThread(() -> setStatus(selected + ": подключение не удалось"));
            }
        });
    }

    private void disconnectAllTransports() {
        if (streamEnabled) stopRcStream(false);
        closeSerial();
        if (researchTransports != null) researchTransports.stopAll();
        setStatus("Все активные транспорты отключены");
        appendLog("Все активные транспорты отключены.");
    }

    private boolean ensureSelectedTransport() {
        String selected = selectedTransport();
        if (TRANSPORT_INPUT.equals(selected)) {
            Toast.makeText(this, "Android Input — пассивный источник; команды SIYI ему не отправляются.",
                    Toast.LENGTH_LONG).show();
            return false;
        }
        if (TRANSPORT_AUTO.equals(selected)) {
            if (hasAnyWritableTransport()) return true;
            Toast.makeText(this, "В AUTO пока нет записываемого транспорта. Нажмите «Подключить».",
                    Toast.LENGTH_LONG).show();
            return false;
        }
        if (TRANSPORT_USB.equals(selected)) {
            if (serial != null && serial.isOpen()) return true;
            Toast.makeText(this, "Сначала подключите USB COM.", Toast.LENGTH_LONG).show();
            return false;
        }

        String source = sourceForSelection(selected);
        if (source != null && researchTransports != null && researchTransports.isWritable(source)) {
            return true;
        }

        Toast.makeText(this, "Сначала подключите выбранный транспорт.", Toast.LENGTH_LONG).show();
        return false;
    }

    private boolean hasAnyWritableTransport() {
        if (serial != null && serial.isOpen()) return true;
        if (researchTransports == null) return false;
        return researchTransports.isWritable(ResearchTransports.UDP)
                || researchTransports.isWritable(ResearchTransports.BLUETOOTH)
                || researchTransports.isWritable(ResearchTransports.UART0)
                || researchTransports.isWritable(ResearchTransports.UART1)
                || researchTransports.isWritable(ResearchTransports.UART2);
    }

    private String sourceForSelection(String selection) {
        if (TRANSPORT_UDP.equals(selection)) return ResearchTransports.UDP;
        if (TRANSPORT_BLUETOOTH.equals(selection)) return ResearchTransports.BLUETOOTH;
        if (TRANSPORT_UART0.equals(selection)) return ResearchTransports.UART0;
        if (TRANSPORT_UART1.equals(selection)) return ResearchTransports.UART1;
        if (TRANSPORT_UART2.equals(selection)) return ResearchTransports.UART2;
        return null;
    }

    private boolean sendSelectedFrame(byte[] frame, String label) {
        String selected = selectedTransport();
        boolean sent = false;

        if (TRANSPORT_USB.equals(selected)) {
            return sendFrame(frame, label);
        }

        if (TRANSPORT_AUTO.equals(selected)) {
            if (serial != null && serial.isOpen()) {
                sent |= sendFrame(frame, label == null ? null : label + " [USB]");
            }
            sent |= sendResearchFrame(ResearchTransports.UDP, frame, label);
            sent |= sendResearchFrame(ResearchTransports.BLUETOOTH, frame, label);
            sent |= sendResearchFrame(ResearchTransports.UART0, frame, label);
            return sent;
        }

        String source = sourceForSelection(selected);
        if (source != null) return sendResearchFrame(source, frame, label);
        return false;
    }

    private boolean sendResearchFrame(String source, byte[] frame, String label) {
        if (researchTransports == null || !researchTransports.isWritable(source)) return false;
        try {
            researchTransports.send(source, frame);
            if (label != null) appendLog("TX " + source + " " + label + ": " + SiyiProtocol.hex(frame));
            return true;
        } catch (Throwable t) {
            appendLog("TX " + source + ": " + stackSummary(t));
            return false;
        }
    }

    private void ingestBytes(String source, byte[] data, int len) {
        if (data == null || len <= 0) return;

        byte[] shown = data;
        if (len > 96) shown = Arrays.copyOf(data, 96);
        String hex = SiyiProtocol.hex(shown) + (len > shown.length ? " ..." : "");

        if ("USB".equals(source)) {
            usbRxBytes += len;
            usbRxChunks++;
            usbLastHex = hex;
            if (usbRxChunks <= 30 || (usbRxChunks % 100) == 0) {
                appendLog("USB RX chunk=" + usbRxChunks + " len=" + len
                        + " total=" + usbRxBytes + " hex=" + hex);
            }
            parser.append(data, len);
            return;
        }

        if (ResearchTransports.UART0.equals(source) && len >= 2
                && (data[0] & 0xFF) == 0x75 && (data[1] & 0xFF) == 0x66) {
            appendLog("UART0 RX: обнаружена сигнатура IUCLC 75 66; parser попытается восстановить исходный SIYI frame.");
        }

        SiyiProtocol.Parser p = extraParsers.get(source);
        if (p == null) {
            final String parserSource = source;
            SiyiProtocol.Parser created = new SiyiProtocol.Parser(new SiyiProtocol.FrameListener() {
                @Override
                public void onFrame(SiyiProtocol.Frame frame) {
                    handleSiyiFrame(parserSource, frame);
                }

                @Override
                public void onBadCrc(byte[] candidate) {
                    appendLog(parserSource + ": CRC не совпал: " + SiyiProtocol.hex(candidate));
                    if (ResearchTransports.UART0.equals(parserSource)
                            && candidate != null && candidate.length >= 10) {
                        int dataLen = SiyiProtocol.u16le(candidate, 3);
                        if (dataLen >= 0 && 8 + dataLen + 2 == candidate.length) {
                            int expected = SiyiProtocol.crc16(candidate, 0, 8 + dataLen);
                            int got = SiyiProtocol.u16le(candidate, 8 + dataLen);
                            int strippedExpected = (expected & 0x7F) | (((expected >> 8) & 0x7F) << 8);
                            if (got == strippedExpected && got != expected) {
                                appendLog("UART0: CRC точно соответствует ISTRIP-повреждению "
                                        + String.format(Locale.US, "expected=%04X got=%04X", expected, got));
                            }
                        }
                    }
                }
            });
            SiyiProtocol.Parser previous = extraParsers.putIfAbsent(source, created);
            p = previous == null ? created : previous;
        }
        p.append(data, len);
    }

    private AtomicLong counter(String key) {
        AtomicLong existing = inputProbeCounters.get(key);
        if (existing != null) return existing;
        AtomicLong created = new AtomicLong();
        AtomicLong previous = inputProbeCounters.putIfAbsent(key, created);
        return previous == null ? created : previous;
    }

    private Map<String, String> captureProbeSnapshot() {
        Map<String, String> out = new TreeMap<>();

        out.put("transport.selected", currentTransport);
        out.put("transport.usb.connected", String.valueOf(serial != null && serial.isOpen()));
        out.put("transport.usb.rxBytes", String.valueOf(usbRxBytes));
        out.put("transport.usb.rxChunks", String.valueOf(usbRxChunks));
        out.put("transport.usb.txBytes", String.valueOf(usbTxBytes));
        out.put("transport.usb.lastHex", usbLastHex);

        if (researchTransports != null) out.putAll(researchTransports.snapshot());
        out.putAll(rcActivity.snapshot());
        out.putAll(linuxInputProbe.snapshot());

        for (Map.Entry<String, String> e : inputProbeState.entrySet()) {
            out.put(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, AtomicLong> e : inputProbeCounters.entrySet()) {
            out.put(e.getKey(), String.valueOf(e.getValue().get()));
        }

        for (Map.Entry<String, int[]> e : rcBySource.entrySet()) {
            int[] values = e.getValue();
            for (int i = 0; i < values.length && i < CHANNEL_COUNT; i++) {
                out.put(String.format(Locale.US, "rc.%s.ch%02d", e.getKey(), i + 1),
                        String.valueOf(values[i]));
            }
        }

        for (int i = 0; i < CHANNEL_COUNT; i++) {
            if (channelValue[i] >= 0) {
                out.put(String.format(Locale.US, "rc.last.ch%02d", i + 1),
                        String.valueOf(channelValue[i]));
            }
        }
        out.put("rc.last.source", lastRcSource);
        out.put("rc.valid_siyi_frames", String.valueOf(validSiyiFrames));
        out.put("rc.channel_frame_count", String.valueOf(channelFrameCount));

        out.putAll(SystemProbe.collectDynamic());
        return out;
    }

    private void captureFinderBaseline(String reason) {
        worker.submit(() -> {
            try {
                Map<String, String> snapshot = captureProbeSnapshot();
                probeDiff.setBaseline(snapshot);
                appendLog("Switch Finder: база снята (" + reason + "), ключей=" + snapshot.size());
                appendFinderHistory("BASE " + reason + " keys=" + snapshot.size());
                runOnUiThread(() -> finderText.setText(
                        "База снята: " + snapshot.size()
                                + " источников/значений. Нажмите C/D, сдвиньте переключатель или измените исследуемый орган, затем нажмите «сравнить»."
                ));
            } catch (Throwable t) {
                appendLog("Switch Finder: база не снята — " + stackSummary(t));
            }
        });
    }

    private void compareFinderSnapshot() {
        compareFinderSnapshot("GENERIC");
    }

    private void compareFinderSnapshot(String actionLabel) {
        final String action = actionLabel == null ? "GENERIC" : actionLabel;
        worker.submit(() -> {
            try {
                Map<String, String> snapshot = captureProbeSnapshot();
                if (!probeDiff.hasBaseline()) {
                    probeDiff.setBaseline(snapshot);
                    appendFinderHistory("BASE implicit before action=" + action + " keys=" + snapshot.size());
                    runOnUiThread(() -> finderText.setText(
                            "Базы не было — текущий снимок сохранён как база. Повторите действие и сравнение."));
                    return;
                }

                ProbeDiffEngine.Result result = probeDiff.compare(snapshot);
                String formatted = "Действие " + action + ". " + formatFinderResult(result);
                String full = formatFinderFullResult(action, result);
                appendLog("Switch Finder action=" + action + " round " + result.round
                        + ": changed=" + result.changes.size());
                appendFinderHistory(full);
                runOnUiThread(() -> finderText.setText(formatted));
            } catch (Throwable t) {
                appendLog("Switch Finder: сравнение не удалось — " + stackSummary(t));
            }
        });
    }

    private String formatFinderResult(ProbeDiffEngine.Result result) {
        StringBuilder sb = new StringBuilder();
        sb.append("Раунд ").append(result.round)
                .append(": изменилось ").append(result.changes.size()).append(" значений. ");

        if (result.changes.isEmpty()) {
            sb.append("Явных изменений нет. Нажмите C/D или измените исследуемый орган и повторите.");
            return sb.toString();
        }

        sb.append("Лучшие кандидаты: ");
        int limit = Math.min(8, result.candidates.size());
        for (int i = 0; i < limit; i++) {
            ProbeDiffEngine.Candidate c = result.candidates.get(i);
            if (i > 0) sb.append(" | ");
            sb.append(c.key)
                    .append(" [").append(c.hits).append('/').append(c.rounds)
                    .append(", score=").append(String.format(Locale.US, "%.1f", c.score))
                    .append("] ")
                    .append(shortValue(c.before)).append("→").append(shortValue(c.after));
        }

        sb.append(". Измените орган управления ещё раз и снова нажмите «сравнить»; ")
                .append("источник, который меняется во всех раундах, поднимется наверх.");
        return sb.toString();
    }

    private String formatFinderFullResult(String action, ProbeDiffEngine.Result result) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ACTION ").append(action)
                .append(" ROUND ").append(result.round)
                .append(" CHANGED ").append(result.changes.size()).append(" ===\n");

        sb.append("CHANGES:\n");
        for (ProbeDiffEngine.Change change : result.changes) {
            sb.append(change.key)
                    .append(" weight=").append(String.format(Locale.US, "%.2f", change.weight))
                    .append(" | ").append(change.before == null ? "∅" : change.before)
                    .append(" -> ").append(change.after == null ? "∅" : change.after)
                    .append('\n');
        }

        sb.append("CANDIDATES:\n");
        int limit = Math.min(64, result.candidates.size());
        for (int i = 0; i < limit; i++) {
            ProbeDiffEngine.Candidate candidate = result.candidates.get(i);
            sb.append(i + 1).append(". ").append(candidate.key)
                    .append(" hits=").append(candidate.hits).append('/').append(candidate.rounds)
                    .append(" score=").append(String.format(Locale.US, "%.2f", candidate.score))
                    .append(" | ").append(candidate.before == null ? "∅" : candidate.before)
                    .append(" -> ").append(candidate.after == null ? "∅" : candidate.after)
                    .append('\n');
        }
        return sb.toString();
    }

    private void startControlsResearch() {
        if (controlsResearch.isRecording()) {
            Toast.makeText(this, "Сначала завершите текущую запись органа.", Toast.LENGTH_LONG).show();
            return;
        }

        final int framesBefore = channelFrameCount;
        controlsResearch.reset();
        if (mappingReceived) {
            byte[] raw = new byte[CHANNEL_COUNT * 2];
            for (int i = 0; i < CHANNEL_COUNT; i++) {
                raw[i * 2] = (byte) mappingType[i];
                raw[i * 2 + 1] = (byte) mappingEntity[i];
            }
            controlsResearch.setMapping(raw, mappingType, mappingEntity);
        }

        controlsResearchActive = true;
        finderActive = false;
        rcActivity.clear();

        currentTransport = TRANSPORT_UART0;
        if (transportSpinner != null) transportSpinner.setSelection(4);

        controlsResearchText.setBackgroundColor(Color.rgb(255, 243, 224));
        controlsResearchText.setText(
                "Подключаю официальный SIYI UART0, читаю mapping и запускаю поток RC 20 Гц. "
                        + "Никаких команд полётному контроллеру или изменения mapping приложение не выполняет.");
        appendLog("Hardware Controls Research: старт; UART0 /dev/ttyHS0, mapping 0x48, RC 20 Гц.");
        connectSelectedTransport();

        worker.submit(() -> {
            sleepQuiet(450);
            boolean mappingSent = sendResearchFrame(
                    ResearchTransports.UART0,
                    SiyiProtocol.mappingRequest(nextSeq()),
                    "HCR 0x48 mapping");
            sleepQuiet(350);

            byte[] stream = SiyiProtocol.channelStreamRequest(5, 0);
            boolean sent = false;
            for (int i = 0; i < 3; i++) {
                sent |= sendResearchFrame(
                        ResearchTransports.UART0,
                        stream,
                        i == 0 ? "HCR 0x42 RC 20Hz" : null);
                sleepQuiet(60);
            }
            streamEnabled = sent;

            final boolean finalMappingSent = mappingSent;
            final boolean finalSent = sent;
            runOnUiThread(() -> {
                if (finalSent) {
                    setStatus("Исследование органов: UART0, RC 20 Гц");
                    controlsResearchText.setBackgroundColor(Color.rgb(200, 230, 201));
                    controlsResearchText.setText(
                            "Исследование запущено. Дождитесь живых значений. Шаг 2: нажмите «2. Начать запись», "
                                    + "выполните не менее 5 циклов выбранного органа и нажмите «3. Завершить запись».");
                } else {
                    controlsResearchText.setBackgroundColor(Color.rgb(255, 205, 210));
                    controlsResearchText.setText(
                            "Не удалось отправить 0x42 через UART0. Проверьте журнал и состояние удалённого пульта.");
                }
            });

            appendLog("Hardware Controls Research: mappingSent=" + finalMappingSent
                    + " stream20HzSent=" + finalSent);

            sleepQuiet(1800);
            if (channelFrameCount <= framesBefore) {
                appendLog("Hardware Controls Research: после запуска нет новых 0x42 channel frames.");
                runOnUiThread(() -> {
                    controlsResearchText.setBackgroundColor(Color.rgb(255, 224, 178));
                    controlsResearchText.setText(
                            "Поток 0x42 запрошен, но живые каналы пока не получены. "
                                    + "Не начинайте физический опыт; сначала сохраните диагностический ZIP.");
                });
            }
        });
    }

    private void stopControlsResearch(boolean userInitiated) {
        if (controlsResearch.isRecording()) {
            try {
                HardwareControlsResearch.ExperimentResult result =
                        controlsResearch.finishExperiment(System.currentTimeMillis());
                appendLog("Hardware Controls Research: активный опыт автоматически завершён при остановке, №"
                        + result.number + " " + result.physicalLabel);
            } catch (Throwable ignored) {
            }
        }

        controlsResearchActive = false;
        if (researchTransports == null || !researchTransports.isWritable(ResearchTransports.UART0)) {
            if (userInitiated) {
                controlsResearchText.setText("Исследование остановлено; UART0 уже недоступен.");
            }
            return;
        }

        worker.submit(() -> {
            byte[] off = SiyiProtocol.channelStreamRequest(0, 0);
            for (int i = 0; i < 3; i++) {
                sendResearchFrame(ResearchTransports.UART0, off,
                        i == 0 ? "HCR 0x42 OFF" : null);
                sleepQuiet(60);
            }
            streamEnabled = false;
            runOnUiThread(() -> {
                if (userInitiated) {
                    controlsResearchText.setBackgroundColor(Color.rgb(232, 245, 233));
                    controlsResearchText.setText(
                            "Исследование остановлено. Записанные опыты сохранены в памяти и войдут в ZIP-отчёт.");
                }
                setStatus("RC-поток исследования выключен");
            });
        });
    }

    private void beginControlExperiment() {
        if (!controlsResearchActive) {
            Toast.makeText(this,
                    "Сначала нажмите «1. ЗАПУСТИТЬ ИССЛЕДОВАНИЕ».",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!mappingReceived || channelFrameCount <= 0) {
            Toast.makeText(this,
                    "Нужны живой mapping и RC-каналы. Дождитесь данных перед началом опыта.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (controlsResearch.isRecording()) {
            Toast.makeText(this,
                    "Запись уже идёт: " + controlsResearch.activeLabel(),
                    Toast.LENGTH_LONG).show();
            return;
        }

        String label = selectedResearchLabel();
        String kind = selectedResearchKind();
        try {
            int number = controlsResearch.beginExperiment(label, kind, System.currentTimeMillis());
            appendLog("Hardware Controls Research: BEGIN experiment #" + number
                    + " label=" + label + " kind=" + kind);
            controlsResearchText.setBackgroundColor(Color.rgb(255, 248, 225));
            controlsResearchText.setText(
                    "ЗАПИСЬ №" + number + ": " + label + " / " + kind
                            + ". Не трогайте остальные органы. Выполните требуемые циклы, затем нажмите «Завершить запись».");
        } catch (Throwable t) {
            appendLog("Hardware Controls Research: begin failed — " + stackSummary(t));
        }
    }

    private void finishControlExperiment() {
        if (!controlsResearch.isRecording()) {
            Toast.makeText(this, "Нет активной записи органа.", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            HardwareControlsResearch.ExperimentResult result =
                    controlsResearch.finishExperiment(System.currentTimeMillis());
            String candidates = formatCandidateChannels(result.candidateChannels);
            appendLog("Hardware Controls Research: END experiment #" + result.number
                    + " label=" + result.physicalLabel
                    + " candidates=" + candidates);
            controlsResearchText.setBackgroundColor(Color.rgb(200, 230, 201));
            controlsResearchText.setText(
                    "Опыт №" + result.number + " завершён: " + result.physicalLabel
                            + ". Изменявшиеся каналы: " + candidates
                            + ". Выберите следующий орган или после всей серии нажмите «4. ZIP → thesystem».");
        } catch (Throwable t) {
            appendLog("Hardware Controls Research: finish failed — " + stackSummary(t));
        }
    }

    private String selectedResearchLabel() {
        if (controlLabelSpinner == null || controlLabelSpinner.getSelectedItem() == null) return "UNKNOWN";
        String label = String.valueOf(controlLabelSpinner.getSelectedItem());
        if (label.startsWith("C ")) return "C";
        if (label.startsWith("D ")) return "D";
        if ("OTHER".equals(label) && controlCustomLabelEdit != null) {
            String custom = controlCustomLabelEdit.getText().toString().trim();
            if (!custom.isEmpty()) return custom;
        }
        return label;
    }

    private String selectedResearchKind() {
        if (controlKindSpinner == null || controlKindSpinner.getSelectedItem() == null) return "OTHER";
        return String.valueOf(controlKindSpinner.getSelectedItem());
    }

    private int kindIndexForLabel(String label) {
        if ("A".equals(label) || "B".equals(label) || "C".equals(label) || "D".equals(label)) return 0;
        if ("SA".equals(label) || "SB".equals(label) || "SC".equals(label)) return 1;
        if ("LD".equals(label) || "RD".equals(label)) return 2;
        if ("J1".equals(label) || "J2".equals(label) || "J3".equals(label) || "J4".equals(label)) return 3;
        return 4;
    }

    private String formatCandidateChannels(int[] channels) {
        if (channels == null || channels.length == 0) return "не обнаружено";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < channels.length; i++) {
            if (i > 0) sb.append(", ");
            int ch = channels[i];
            int idx = ch - 1;
            sb.append("CH").append(ch);
            if (idx >= 0 && idx < CHANNEL_COUNT && mappingType[idx] >= 0) {
                sb.append("=").append(mappedName(idx));
            }
        }
        return sb.toString();
    }

    private void updateResearchChannelRow(int index, long nowMs) {
        if (channelRows[index] == null) return;
        HardwareControlsResearch.RowSnapshot row = controlsResearch.rowSnapshot(index);
        if (!row.initialized) {
            channelRows[index].setText(String.format(Locale.US,
                    "CH%02d t=%d/id=%d %-7s  —",
                    row.channel, row.type, row.entity, row.name));
        } else {
            String last = row.lastChangeMs <= 0
                    ? "—"
                    : new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(row.lastChangeMs));
            channelRows[index].setText(String.format(Locale.US,
                    "CH%02d t=%d/id=%d %-7s cur=%4d min=%4d max=%4d Δ=%+4d %-6s chg=%d last=%s",
                    row.channel, row.type, row.entity, row.name,
                    row.current, row.min, row.max, row.delta(), row.state, row.changes, last));
        }

        if (row.changedRecently(nowMs, 900)) {
            channelRows[index].setBackgroundColor(Color.rgb(255, 235, 59));
            channelRows[index].setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            channelRows[index].setBackgroundColor((index % 2 == 0) ? Color.WHITE : Color.rgb(238, 238, 238));
            channelRows[index].setTypeface(null, android.graphics.Typeface.NORMAL);
        }
    }

    private String rcButtonState(int value) {
        if (value < 0) return "нет данных";
        if (value <= 1250) return "низкое";
        if (value >= 1750) return "высокое";
        return "среднее";
    }

    private String shortValue(String value) {
        if (value == null) return "∅";
        if (value.length() <= 28) return value;
        return value.substring(0, 25) + "...";
    }

    private void resetFinder() {
        probeDiff.reset();
        synchronized (finderHistory) {
            finderHistory.setLength(0);
        }
        finderText.setText("Поиск сброшен. Для C/D нажмите «АВТОПОИСК C/D (20 Гц)» или снимите базу вручную.");
        appendLog("Switch Finder: поиск сброшен.");
    }

    private void appendFinderHistory(String text) {
        String stamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        synchronized (finderHistory) {
            finderHistory.append(stamp).append("  ").append(text == null ? "" : text).append('\n');
            if (finderHistory.length() > 256_000) {
                finderHistory.delete(0, finderHistory.length() - 256_000);
            }
        }
    }

    private void startActiveFinder() {
        final int framesBefore = validSiyiFrames;
        final int channelFramesBefore = channelFrameCount;
        finderActive = true;
        streamEnabled = false;
        probeDiff.reset();
        rcActivity.clear();
        synchronized (finderHistory) {
            finderHistory.setLength(0);
        }

        currentTransport = TRANSPORT_AUTO;
        if (transportSpinner != null) transportSpinner.setSelection(0);

        finderText.setBackgroundColor(Color.rgb(255, 243, 224));
        finderText.setText("Активный поиск C/D: сначала читаю mapping, затем включаю чистый поток RC. Разрешите USB, если Android спросит. Нажмите C, затем «C → сравнить»; D — «D → сравнить».");
        appendLog("Switch Finder ACTIVE: C/D, AUTO transports. Сначала пробуем официальный RC 4 Гц, затем при необходимости 20 Гц.");
        connectSelectedTransport();

        worker.submit(() -> {
            sleepQuiet(1900);
            sendSelectedFrame(SiyiProtocol.mappingRequest(nextSeq()), "0x48 PRE-RC mapping");
            sleepQuiet(700);
            activateFinderStreamOnAllWritable(2, "4Hz");
            sleepQuiet(1400);

            if (channelFrameCount <= channelFramesBefore) {
                appendLog("RC 4 Гц: живых 0x42 кадров пока нет; пробуем 20 Гц.");
                activateFinderStreamOnAllWritable(5, "20Hz");
            } else {
                appendLog("RC 4 Гц: получены живые 0x42 кадры; оставляем документированный 4 Гц для чистого опыта.");
            }

            captureFinderBaseline("активный C/D после запуска RC");
            sleepQuiet(2200);
            if (validSiyiFrames <= framesBefore) {
                appendLog("SIYI SDK: после активной пробы нет ни одного валидного ответа. "
                        + "Проверьте SIYI TX -> Datalink -> Connection. "
                        + "Официальный SDK UART: /dev/ttyHS0, 115200.");
                runOnUiThread(() -> {
                    finderText.setBackgroundColor(Color.rgb(255, 224, 178));
                    finderText.setText("Нет ответа SIYI SDK. Откройте SIYI TX → Datalink и проверьте Connection. "
                            + "Для UART используйте /dev/ttyHS0; приложение 1.5.2 настраивает его после открытия на 115200 raw. "
                            + "После смены Connection снова нажмите «АВТОПОИСК C/D (20 Гц)».");
                });
            }
        });
    }

    private void activateFinderStreamOnAllWritable(int frequencyCode, String frequencyLabel) {
        // Use the exact documented SIYI request shape first: sequence 0.
        byte[] stream = SiyiProtocol.channelStreamRequest(frequencyCode, 0);
        boolean sent = false;
        for (int i = 0; i < 3; i++) {
            if (serial != null && serial.isOpen()) {
                sent |= sendFrame(stream, i == 0 ? "0x42 ACTIVE " + frequencyLabel + " [USB]" : null);
            }
            sent |= sendResearchFrame(ResearchTransports.UDP, stream,
                    i == 0 ? "0x42 ACTIVE " + frequencyLabel : null);
            sent |= sendResearchFrame(ResearchTransports.UART0, stream,
                    i == 0 ? "0x42 ACTIVE " + frequencyLabel + " [official UART0]" : null);
            if (ResearchTransports.hasPairedSiyiDevice()) {
                sent |= sendResearchFrame(ResearchTransports.BLUETOOTH, stream,
                        i == 0 ? "0x42 ACTIVE " + frequencyLabel : null);
            }
            sleepQuiet(60);
        }

        if (sent) {
            streamEnabled = true;
            appendLog("Switch Finder ACTIVE: RC " + frequencyLabel
                    + " отправлен три раза. Во время ожидания 0x42 другие SDK-команды не отправляем.");
            runOnUiThread(() -> {
                setStatus("Активный поиск C/D: RC " + frequencyLabel);
                finderText.setBackgroundColor(Color.rgb(232, 245, 233));
            });
        } else {
            appendLog("Switch Finder ACTIVE: пока нет записываемого транспорта; ждём USB permission/повторного запуска.");
            runOnUiThread(() -> finderText.setText(
                    "Активный поиск: транспорт пока не открылся. Если было окно USB — разрешите доступ и повторите автопоиск."));
        }
    }

    private void requestMapping() {
        if (!ensureSelectedTransport()) return;
        worker.submit(() -> sendSelectedFrame(SiyiProtocol.mappingRequest(nextSeq()), "0x48 запрос всех mapping"));
    }

    private void requestInfo() {
        if (!ensureSelectedTransport()) return;
        worker.submit(() -> {
            sendSelectedFrame(SiyiProtocol.request(SiyiProtocol.CMD_HARDWARE_ID, new byte[0], nextSeq()), "0x40 hardware ID");
            sleepQuiet(80);
            sendSelectedFrame(SiyiProtocol.request(SiyiProtocol.CMD_FIRMWARE_VERSION, new byte[0], nextSeq()), "0x47 версии прошивок");
            sleepQuiet(80);
            sendSelectedFrame(SiyiProtocol.request(SiyiProtocol.CMD_DATALINK_STATUS, new byte[0], nextSeq()), "0x43 состояние datalink");
            sleepQuiet(80);
            sendSelectedFrame(SiyiProtocol.request(SiyiProtocol.CMD_IMAGE_LINK_STATUS, new byte[0], nextSeq()), "0x44 состояние видеолинии");
        });
    }

    private void startRcStream() {
        if (!ensureSelectedTransport()) return;
        worker.submit(() -> {
            int seq = nextSeq();
            byte[] frame = SiyiProtocol.channelStreamRequest(2, seq);
            appendLog("RC: включаем поток 4 Гц командой 0x42 через выбранный транспорт (три отправки).\nTX: " + SiyiProtocol.hex(frame));
            boolean ok = true;
            for (int i = 0; i < 3; i++) {
                if (!sendSelectedFrame(frame, null)) ok = false;
                sleepQuiet(60);
            }
            streamEnabled = ok;
            if (ok) {
                runOnUiThread(() -> setStatus("RC-поток 4 Гц включён через " + selectedTransport()));
                sendSelectedFrame(SiyiProtocol.mappingRequest(nextSeq()), "0x48 контроль mapping после запуска");
            }
        });
    }

    private void stopRcStream(boolean userInitiated) {
        if (userInitiated) finderActive = false;
        if (!hasAnyWritableTransport()) {
            streamEnabled = false;
            if (userInitiated) appendLog("RC: нет подключённого записываемого транспорта.");
            return;
        }
        worker.submit(() -> {
            int seq = nextSeq();
            byte[] frame = SiyiProtocol.channelStreamRequest(0, 0);
            if (userInitiated) appendLog("RC: выключаем поток 0x42 (три отправки).\nTX: " + SiyiProtocol.hex(frame));
            for (int i = 0; i < 3; i++) {
                sendSelectedFrame(frame, null);
                sleepQuiet(60);
            }
            streamEnabled = false;
            runOnUiThread(() -> setStatus("RC-поток выключен"));
        });
    }

    private boolean ensureSerial() {
        if (serial != null && serial.isOpen()) return true;
        Toast.makeText(this, "Сначала нажмите «Подключить USB COM»", Toast.LENGTH_LONG).show();
        appendLog("Команда не отправлена: USB COM не открыт.");
        return false;
    }

    private boolean sendFrame(byte[] frame, String label) {
        UsbSerialCp210x s = serial;
        if (s == null || !s.isOpen()) {
            if (label != null) appendLog(label + ": USB COM не открыт.");
            return false;
        }
        try {
            s.write(frame);
            usbTxBytes += frame.length;
            if (label != null) appendLog("TX USB " + label + ": " + SiyiProtocol.hex(frame));
            return true;
        } catch (Throwable t) {
            appendLog((label == null ? "TX" : label) + ": ошибка отправки — " + stackSummary(t));
            return false;
        }
    }

    @Override
    public void onFrame(SiyiProtocol.Frame frame) {
        handleSiyiFrame("USB", frame);
    }

    private void handleSiyiFrame(String source, SiyiProtocol.Frame frame) {
        validSiyiFrames++;
        if (validSiyiFrames <= 40 || (validSiyiFrames % 100) == 0 || frame.ttyCaseRepaired) {
            appendLog("SIYI " + source + " frame #" + validSiyiFrames + " cmd=0x"
                    + String.format(Locale.US, "%02X", frame.cmdId)
                    + " seq=" + frame.seq + " dataLen=" + frame.data.length
                    + (frame.ttyCaseRepaired ? " [TTY IUCLC repaired]" : ""));
        }
        switch (frame.cmdId) {
            case SiyiProtocol.CMD_CHANNEL_DATA:
                handleChannelFrame(source, frame.data);
                break;
            case SiyiProtocol.CMD_ALL_CHANNEL_MAPPINGS:
                handleMappingFrame(frame.data);
                break;
            case SiyiProtocol.CMD_HARDWARE_ID:
                appendLog("RX 0x40 hardware ID: " + decodePrintableOrHex(frame.data));
                break;
            case SiyiProtocol.CMD_FIRMWARE_VERSION:
                appendLog("RX 0x47 версии: " + decodeFirmwareVersions(frame.data));
                break;
            case SiyiProtocol.CMD_DATALINK_STATUS:
                appendLog("RX 0x43 datalink: " + SiyiProtocol.hex(frame.data));
                break;
            case SiyiProtocol.CMD_IMAGE_LINK_STATUS:
                appendLog("RX 0x44 image link: " + SiyiProtocol.hex(frame.data));
                break;
            default:
                appendLog("RX " + source + " cmd=0x" + String.format(Locale.US, "%02X", frame.cmdId)
                        + " seq=" + frame.seq + " data=" + SiyiProtocol.hex(frame.data));
        }
    }

    @Override
    public void onBadCrc(byte[] candidate) {
        appendLog("RX: CRC не совпал, отброшен кадр: " + SiyiProtocol.hex(candidate));
    }

    private void handleChannelFrame(String source, byte[] data) {
        if (data.length < 32) {
            appendLog("RX 0x42: короткий ответ " + data.length + " байт: " + SiyiProtocol.hex(data));
            return;
        }
        channelFrameCount++;
        int[] values = new int[CHANNEL_COUNT];
        for (int i = 0; i < CHANNEL_COUNT; i++) values[i] = SiyiProtocol.u16le(data, i * 2);
        System.arraycopy(values, 0, channelValue, 0, CHANNEL_COUNT);
        rcBySource.put(source.toLowerCase(Locale.US), Arrays.copyOf(values, values.length));
        rcActivity.update(source, values);
        lastRcSource = source;

        final long researchNow = System.currentTimeMillis();
        if (controlsResearchActive) {
            controlsResearch.onChannels(values, researchNow);
        }

        runOnUiThread(() -> {
            for (int i = 0; i < CHANNEL_COUNT; i++) {
                if (controlsResearchActive) {
                    updateResearchChannelRow(i, researchNow);
                } else {
                    String name = mappedName(i);
                    boolean isSa = i == saChannelIndex;
                    updateChannelRow(i, name, values[i], isSa);
                }
            }
            int saValue = saChannelIndex >= 0 && saChannelIndex < CHANNEL_COUNT ? values[saChannelIndex] : -1;
            String mappingNote = mappingReceived ? "mapping 0x48 подтверждён" : "mapping пока заводской";
            String cState = rcButtonState(values[9]);
            String dState = rcButtonState(values[10]);
            saText.setText("Живые RC [" + source + "]: C/CH10=" + values[9] + " (" + cState + ")"
                    + "  D/CH11=" + values[10] + " (" + dState + ")"
                    + "  SA/CH" + (saChannelIndex + 1) + "=" + saValue
                    + " (" + mappingNote + ")");
            if (channelFrameCount <= 12 || (channelFrameCount % 20) == 0) {
                appendLog("RC LIVE #" + channelFrameCount + " [" + source + "] C/CH10="
                        + values[9] + " D/CH11=" + values[10]);
            }
            saText.setBackgroundColor(Color.rgb(200, 230, 201));
        });
    }

    private void handleMappingFrame(byte[] data) {
        if (data.length < 32) {
            appendLog("RX 0x48: короткий mapping " + data.length + " байт: " + SiyiProtocol.hex(data));
            return;
        }
        int foundSa = -1;
        StringBuilder decoded = new StringBuilder("RX 0x48 mapping:");
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            int type = data[i * 2] & 0xFF;
            int entity = data[i * 2 + 1] & 0xFF;
            mappingType[i] = type;
            mappingEntity[i] = entity;
            String name = SiyiProtocol.physicalChannelName(type, entity);
            decoded.append(" CH").append(i + 1).append('=').append(name).append(';');
            if (type == 5 && entity == 0) foundSa = i;
        }
        mappingReceived = true;
        if (foundSa >= 0) saChannelIndex = foundSa;
        controlsResearch.setMapping(data, mappingType, mappingEntity);
        appendLog(decoded.toString());
        final int finalFoundSa = foundSa;
        runOnUiThread(() -> {
            for (int i = 0; i < CHANNEL_COUNT; i++) {
                if (controlsResearchActive) {
                    updateResearchChannelRow(i, System.currentTimeMillis());
                } else {
                    updateChannelRow(i, mappedName(i), channelValue[i], i == saChannelIndex);
                }
            }
            if (finalFoundSa >= 0) {
                saText.setText("SA найден в mapping: физический type=5, entity_id=0 → CH" + (finalFoundSa + 1)
                        + (channelValue[finalFoundSa] >= 0 ? " = " + channelValue[finalFoundSa] : ""));
                saText.setBackgroundColor(Color.rgb(200, 230, 201));
            } else {
                saText.setText("В ответе 0x48 mapping SA (type=5, entity_id=0) не найден. Смотрите журнал.");
                saText.setBackgroundColor(Color.rgb(255, 205, 210));
            }
        });
    }

    private String mappedName(int index) {
        if (mappingReceived && mappingType[index] >= 0) {
            return SiyiProtocol.physicalChannelName(mappingType[index], mappingEntity[index]);
        }
        String[] defaults = {"J1", "J2", "J3", "J4", "SA", "SB", "SC", "A", "B", "C", "D", "LD", "RD", "—", "—", "—"};
        return defaults[index] + " (заводской)";
    }

    private void updateChannelRow(int index, String name, int value, boolean isSa) {
        if (channelRows[index] == null) return;
        String valueText = value >= 0 ? String.valueOf(value) : "—";
        channelRows[index].setText(String.format(Locale.US, "CH%02d  %-18s  %s", index + 1, name, valueText));
        if (isSa) {
            channelRows[index].setBackgroundColor(Color.rgb(200, 230, 201));
            channelRows[index].setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            channelRows[index].setBackgroundColor((index % 2 == 0) ? Color.WHITE : Color.rgb(238, 238, 238));
            channelRows[index].setTypeface(null, android.graphics.Typeface.NORMAL);
        }
    }

    private String decodePrintableOrHex(byte[] data) {
        boolean printable = data.length > 0;
        for (byte b : data) {
            int v = b & 0xFF;
            if (v != 0 && (v < 32 || v > 126)) {
                printable = false;
                break;
            }
        }
        if (printable) {
            String s = new String(data).replace("\u0000", "").trim();
            if (!s.isEmpty()) return s + " [" + SiyiProtocol.hex(data) + "]";
        }
        return SiyiProtocol.hex(data);
    }

    private String decodeFirmwareVersions(byte[] data) {
        if (data.length < 16) return SiyiProtocol.hex(data);
        String[] names = {"RC", "RF", "Ground FPV", "Sky FPV"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) sb.append("; ");
            int p = i * 4;
            int major = data[p + 2] & 0xFF;
            int minor = data[p + 1] & 0xFF;
            int patch = data[p] & 0xFF;
            int product = data[p + 3] & 0xFF;
            sb.append(names[i]).append('=').append(major).append('.').append(minor).append('.').append(patch)
                    .append(" product=0x").append(String.format(Locale.US, "%02X", product));
        }
        return sb.toString();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN || event.getAction() == KeyEvent.ACTION_UP) {
            String base = "input.key.dev" + event.getDeviceId() + ".scan" + event.getScanCode();
            inputProbeState.put(base + ".state",
                    event.getAction() + ":" + event.getKeyCode());
            counter(base + ".eventCount").incrementAndGet();
            appendLog("Android KeyEvent: action=" + event.getAction()
                    + " keyCode=" + event.getKeyCode()
                    + " scanCode=" + event.getScanCode()
                    + " deviceId=" + event.getDeviceId());
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        int source = event.getSource();
        if ((source & InputDevice.SOURCE_CLASS_JOYSTICK) != 0
                || (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
            InputDevice device = event.getDevice();
            StringBuilder sb = new StringBuilder("Android MotionEvent: dev=")
                    .append(event.getDeviceId()).append(" src=0x").append(Integer.toHexString(source));
            if (device != null) {
                for (InputDevice.MotionRange r : device.getMotionRanges()) {
                    if ((r.getSource() & source) == 0) continue;
                    float value = event.getAxisValue(r.getAxis());
                    String key = "input.motion.dev" + event.getDeviceId() + ".axis" + r.getAxis();
                    String formatted = String.format(Locale.US, "%.5f", value);
                    String previous = inputProbeState.put(key + ".value", formatted);
                    if (previous == null || !previous.equals(formatted)) {
                        counter(key + ".eventCount").incrementAndGet();
                    }
                    if (Math.abs(value) > 0.0001f) {
                        sb.append(" axis").append(r.getAxis()).append('=').append(String.format(Locale.US, "%.3f", value));
                    }
                }
            }
            appendLog(sb.toString());
        }
        return super.dispatchGenericMotionEvent(event);
    }

    private interface ReportReady {
        void onReady(File zip);
    }

    private void saveReportToDownloads() {
        buildReportAsync("save-to-download", this::copyReportToDownloads);
    }

    private void saveReportWithPicker() {
        buildReportAsync("save-with-file-picker", zip -> {
            pendingPickerReport = zip;
            try {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/zip");
                intent.putExtra(Intent.EXTRA_TITLE, zip.getName());
                startActivityForResult(intent, REQUEST_CREATE_REPORT_FILE);
            } catch (Throwable t) {
                pendingPickerReport = null;
                appendLog("Файловый выбор недоступен: " + stackSummary(t));
                reportText.setText("Не удалось открыть файловый выбор. Используйте ZIP → Download.");
                Toast.makeText(this,
                        "Файловый выбор недоступен. Используйте ZIP → Download.",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void uploadReportToThesystem() {
        buildReportAsync("upload-to-thesystem", zip -> {
            reportText.setText("Отчёт: отправляю " + zip.getName() + " в thesystem…");
            controlsResearchText.setBackgroundColor(Color.rgb(255, 248, 225));
            controlsResearchText.setText("Шаг 4: отправляю ZIP-отчёт в thesystem…");
            worker.submit(() -> {
                try {
                    Map<String, String> fields = new LinkedHashMap<>();
                    fields.put("report_id", zip.getName());
                    fields.put("app_version", "1.5.2");
                    fields.put("package", getPackageName());
                    fields.put("device", Build.MANUFACTURER + " " + Build.MODEL);
                    fields.put("android", Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT);
                    fields.put("transport", currentTransport);
                    fields.put("sa_channel", String.valueOf(saChannelIndex + 1));
                    fields.put("finder_rounds", String.valueOf(probeDiff.getRounds()));
                    fields.put("research_target", "hardware controls inventory");

                    ReportTools.UploadResult result =
                            ReportTools.uploadMultipart(REPORT_ENDPOINT, zip, fields);

                    String response = result.responseBody == null ? "" : result.responseBody.trim();
                    if (response.length() > 600) response = response.substring(0, 600) + "...";

                    final String visibleResponse = response;
                    appendLog("thesystem upload: HTTP " + result.statusCode
                            + (visibleResponse.isEmpty() ? "" : " response=" + visibleResponse));

                    runOnUiThread(() -> {
                        if (result.isSuccess()) {
                            reportText.setBackgroundColor(Color.rgb(200, 230, 201));
                            reportText.setText("Отчёт отправлен в thesystem: HTTP "
                                    + result.statusCode
                                    + (visibleResponse.isEmpty() ? "" : " — " + visibleResponse));
                            controlsResearchText.setBackgroundColor(Color.rgb(200, 230, 201));
                            controlsResearchText.setText(
                                    "ГОТОВО: ZIP-отчёт принят thesystem (HTTP " + result.statusCode
                                            + "). Исследование остановлено; миссия на пульте завершена.");
                            stopControlsResearch(false);
                        } else {
                            reportText.setBackgroundColor(Color.rgb(255, 224, 178));
                            reportText.setText("thesystem не принял отчёт: HTTP "
                                    + result.statusCode
                                    + (visibleResponse.isEmpty() ? "" : " — " + visibleResponse));
                            controlsResearchText.setBackgroundColor(Color.rgb(255, 224, 178));
                            controlsResearchText.setText(
                                    "thesystem не принял ZIP (HTTP " + result.statusCode
                                            + "). Используйте «Резерв: ZIP → Download» и передайте файл.");
                        }
                    });
                } catch (Throwable t) {
                    appendLog("Отправка в thesystem не удалась: " + stackSummary(t));
                    runOnUiThread(() -> {
                        reportText.setBackgroundColor(Color.rgb(255, 205, 210));
                        reportText.setText("Ошибка отправки в thesystem: " + stackSummary(t));
                        controlsResearchText.setBackgroundColor(Color.rgb(255, 205, 210));
                        controlsResearchText.setText(
                                "Ошибка отправки ZIP в thesystem. Используйте «Резерв: ZIP → Download» и передайте файл.");
                    });
                }
            });
        });
    }

    private void buildReportAsync(String reason, ReportReady callback) {
        final String scanOnScreen = scanText == null ? "" : scanText.getText().toString();
        final String finderCurrent = finderText == null ? "" : finderText.getText().toString();
        final String statusCurrent = statusText == null ? "" : statusText.getText().toString();
        final String saCurrent = saText == null ? "" : saText.getText().toString();
        final String channelTable = captureChannelTableText();

        final String session;
        synchronized (sessionLog) {
            session = sessionLog.toString();
        }

        final String finderLog;
        synchronized (finderHistory) {
            finderLog = finderHistory.toString();
        }

        reportText.setBackgroundColor(Color.rgb(232, 245, 233));
        reportText.setText("Отчёт: формирую ZIP…");

        worker.submit(() -> {
            try {
                Map<String, String> dynamic = captureProbeSnapshot();
                String fullScan;
                try {
                    fullScan = SystemScanner.collect(getApplicationContext());
                } catch (Throwable t) {
                    fullScan = "SystemScanner failed: " + stackSummary(t);
                }

                String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                String fileName = "MK15_Report_" + ts + "_v1.5.2.zip";

                File base = getExternalFilesDir(null);
                if (base == null) base = getFilesDir();
                File reportsDir = new File(base, "reports");

                Map<String, byte[]> entries = new LinkedHashMap<>();
                entries.put("README.txt", ReportTools.utf8(buildReportReadme()));
                entries.put("summary.txt", ReportTools.utf8(buildReportSummary(
                        reason, statusCurrent, saCurrent, fileName)));
                entries.put("screen/channels.txt", ReportTools.utf8(channelTable));
                entries.put("screen/switch_finder_current.txt", ReportTools.utf8(finderCurrent));
                entries.put("switch_finder/history.txt", ReportTools.utf8(finderLog));
                entries.put("scan/screen_scan.txt", ReportTools.utf8(scanOnScreen));
                entries.put("scan/full_scan.txt", ReportTools.utf8(fullScan));
                entries.put("state/dynamic_snapshot.txt", ReportTools.utf8(mapToText(dynamic)));
                entries.put("state/mapping.txt", ReportTools.utf8(buildMappingText()));
                entries.put("SUMMARY.md", ReportTools.utf8(controlsResearch.buildSummaryMarkdown()));
                entries.put("controls.json", ReportTools.utf8(controlsResearch.buildControlsJson()));
                entries.put("controls.csv", ReportTools.utf8(controlsResearch.buildControlsCsv()));
                entries.put("events.csv", ReportTools.utf8(controlsResearch.eventsCsv()));
                entries.put("mapping_raw.txt", ReportTools.utf8(controlsResearch.mappingRawText()));
                entries.put("logs/session.log", ReportTools.utf8(session));

                if (runtimeLogFile != null && runtimeLogFile.exists()) {
                    entries.put("logs/runtime.log", ReportTools.readFile(runtimeLogFile));
                }

                File zip = ReportTools.createZip(reportsDir, fileName, entries);
                lastReportZip = zip;

                appendLog("ZIP-отчёт сформирован: " + zip.getAbsolutePath()
                        + " size=" + zip.length());

                runOnUiThread(() -> {
                    reportText.setBackgroundColor(Color.rgb(200, 230, 201));
                    reportText.setText("ZIP готов: " + zip.getAbsolutePath()
                            + " (" + zip.length() + " байт)");
                    if (callback != null) callback.onReady(zip);
                });
            } catch (Throwable t) {
                appendLog("Не удалось сформировать ZIP-отчёт: " + stackSummary(t));
                runOnUiThread(() -> {
                    reportText.setBackgroundColor(Color.rgb(255, 205, 210));
                    reportText.setText("Ошибка ZIP-отчёта: " + stackSummary(t));
                });
            }
        });
    }

    private String captureChannelTableText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            TextView row = channelRows[i];
            sb.append(row == null ? ("CH" + (i + 1)) : row.getText().toString()).append('\n');
        }
        return sb.toString();
    }

    private String buildMappingText() {
        StringBuilder sb = new StringBuilder();
        sb.append("mapping_received=").append(mappingReceived).append('\n');
        sb.append("sa_channel=").append(saChannelIndex + 1).append('\n');
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            sb.append("CH").append(i + 1)
                    .append(" type=").append(mappingType[i])
                    .append(" entity=").append(mappingEntity[i])
                    .append(" name=").append(mappedName(i))
                    .append(" value=").append(channelValue[i])
                    .append('\n');
        }
        return sb.toString();
    }

    private String buildReportSummary(
            String reason, String status, String sa, String fileName) {
        return "report_format=2\n"
                + "app=MK15 Port Inspector\n"
                + "app_version=1.5.2\n"
                + "package=" + getPackageName() + "\n"
                + "created_at=" + new SimpleDateFormat(
                        "yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date()) + "\n"
                + "reason=" + reason + "\n"
                + "file_name=" + fileName + "\n"
                + "manufacturer=" + Build.MANUFACTURER + "\n"
                + "model=" + Build.MODEL + "\n"
                + "product=" + Build.PRODUCT + "\n"
                + "device=" + Build.DEVICE + "\n"
                + "hardware=" + Build.HARDWARE + "\n"
                + "android=" + Build.VERSION.RELEASE + "\n"
                + "api=" + Build.VERSION.SDK_INT + "\n"
                + "transport=" + currentTransport + "\n"
                + "status=" + status + "\n"
                + "research_target=C/D buttons plus generic controls\n"
                + "control_status=" + sa + "\n"
                + "finder_rounds=" + probeDiff.getRounds() + "\n"
                + "hardware_research_active=" + controlsResearchActive + "\n"
                + "hardware_research_experiments=" + controlsResearch.completedExperimentCount() + "\n"
                + "valid_siyi_frames=" + validSiyiFrames + "\n"
                + "channel_frame_count=" + channelFrameCount + "\n"
                + "upload_endpoint=" + REPORT_ENDPOINT + "\n";
    }

    private String buildReportReadme() {
        return "MK15 Port Inspector diagnostic report ZIP\n\n"
                + "Created entirely on the MK15 without ADB. Version 1.5.2 configures official UART0 (/dev/ttyHS0) to 115200 raw before SDK probing.\n"
                + "The working ZIP is kept under the app external files/reports directory.\n"
                + "ZIP → Download copies it to Download/MK15PortInspector for File Explorer and adb pull.\n"
                + "ZIP → флешка/файл opens Android's file picker; select a USB flash drive if it is mounted.\n"
                + "ZIP → thesystem POSTs multipart/form-data to " + REPORT_ENDPOINT + ".\n\n"
                + "Hardware Controls Research files: SUMMARY.md, controls.json, controls.csv, events.csv, mapping_raw.txt.\n\n"
                + "Upload contract:\n"
                + "  multipart file field: report (application/zip)\n"
                + "  text fields: report_id, app_version, package, device, android, transport, "
                + "sa_channel, finder_rounds, research_target\n";
    }

    private String mapToText(Map<String, String> map) {
        StringBuilder sb = new StringBuilder();
        if (map == null) return "";
        for (Map.Entry<String, String> e : new TreeMap<>(map).entrySet()) {
            sb.append(e.getKey()).append('=')
                    .append(e.getValue() == null ? "" : e.getValue())
                    .append('\n');
        }
        return sb.toString();
    }

    private void copyReportToDownloads(File zip) {
        if (zip == null || !zip.exists()) return;

        if (Build.VERSION.SDK_INT >= 29) {
            worker.submit(() -> copyReportToDownloadsMediaStore(zip));
            return;
        }

        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingDownloadReport = zip;
            requestPermissions(
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_WRITE_STORAGE);
            return;
        }

        worker.submit(() -> copyReportToDownloadsLegacy(zip));
    }

    private void copyReportToDownloadsLegacy(File zip) {
        try {
            File downloads = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            File dir = new File(downloads, "MK15PortInspector");
            File target = new File(dir, zip.getName());
            ReportTools.copyFile(zip, target);
            MediaScannerConnection.scanFile(
                    this,
                    new String[]{target.getAbsolutePath()},
                    new String[]{"application/zip"},
                    null);

            appendLog("ZIP скопирован в Download: " + target.getAbsolutePath());
            runOnUiThread(() -> {
                reportText.setBackgroundColor(Color.rgb(200, 230, 201));
                reportText.setText("Отчёт сохранён: " + target.getAbsolutePath());
                Toast.makeText(this,
                        "Готово:\n" + target.getAbsolutePath(),
                        Toast.LENGTH_LONG).show();
            });
        } catch (Throwable t) {
            appendLog("Не удалось сохранить ZIP в Download: " + stackSummary(t));
            runOnUiThread(() -> {
                reportText.setBackgroundColor(Color.rgb(255, 205, 210));
                reportText.setText("Ошибка сохранения в Download: " + stackSummary(t));
            });
        }
    }

    private void copyReportToDownloadsMediaStore(File zip) {
        Uri item = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, zip.getName());
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/MK15PortInspector");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            item = getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (item == null) throw new Exception("MediaStore insert returned null");

            try (OutputStream out = getContentResolver().openOutputStream(item, "w")) {
                if (out == null) throw new Exception("MediaStore output stream is null");
                ReportTools.copyToStream(zip, out);
            }

            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(item, done, null, null);

            final Uri saved = item;
            appendLog("ZIP сохранён в Download/MK15PortInspector через MediaStore: " + saved);
            runOnUiThread(() -> {
                reportText.setBackgroundColor(Color.rgb(200, 230, 201));
                reportText.setText("Отчёт сохранён в Download/MK15PortInspector: "
                        + zip.getName());
            });
        } catch (Throwable t) {
            if (item != null) {
                try { getContentResolver().delete(item, null, null); } catch (Throwable ignored) {}
            }
            appendLog("MediaStore Download failed: " + stackSummary(t));
            runOnUiThread(() -> {
                reportText.setBackgroundColor(Color.rgb(255, 205, 210));
                reportText.setText("Ошибка сохранения в Download: " + stackSummary(t));
            });
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_WRITE_STORAGE) return;

        File zip = pendingDownloadReport;
        pendingDownloadReport = null;

        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && zip != null) {
            worker.submit(() -> copyReportToDownloadsLegacy(zip));
        } else {
            reportText.setBackgroundColor(Color.rgb(255, 224, 178));
            reportText.setText("Нет разрешения на Download. Используйте «ZIP → флешка/файл…».");
            Toast.makeText(this,
                    "Нет разрешения на общую память. Можно сохранить через файловый выбор.",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CREATE_REPORT_FILE) return;

        final File zip = pendingPickerReport;
        pendingPickerReport = null;

        if (resultCode != RESULT_OK || data == null || data.getData() == null || zip == null) {
            appendLog("Сохранение ZIP через файловый выбор отменено.");
            return;
        }

        final Uri uri = data.getData();
        worker.submit(() -> {
            try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                if (out == null) throw new Exception("ContentResolver returned null stream");
                ReportTools.copyToStream(zip, out);
                appendLog("ZIP сохранён через файловый выбор: " + uri);
                runOnUiThread(() -> {
                    reportText.setBackgroundColor(Color.rgb(200, 230, 201));
                    reportText.setText("Отчёт сохранён в выбранное место: " + zip.getName());
                    Toast.makeText(this,
                            "Отчёт сохранён в выбранное место.",
                            Toast.LENGTH_LONG).show();
                });
            } catch (Throwable t) {
                appendLog("Сохранение ZIP через файловый выбор не удалось: " + stackSummary(t));
                runOnUiThread(() -> {
                    reportText.setBackgroundColor(Color.rgb(255, 205, 210));
                    reportText.setText("Ошибка сохранения файла: " + stackSummary(t));
                });
            }
        });
    }

    private void initRuntimeLog() {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            if (!dir.exists()) dir.mkdirs();
            runtimeLogFile = new File(dir, "MK15_PortInspector_runtime.log");
            try (FileWriter fw = new FileWriter(runtimeLogFile, false)) {
                fw.write("MK15 Port Inspector 1.5.2 runtime log\n");
                fw.write("Started: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()) + "\n");
                fw.write("Path: " + runtimeLogFile.getAbsolutePath() + "\n\n");
            }
        } catch (Throwable ignored) {
            runtimeLogFile = null;
        }
    }

    private void clearLog() {
        synchronized (sessionLog) {
            sessionLog.setLength(0);
        }
        if (logText != null) logText.setText("");
        appendLog("Журнал очищен.");
    }

    private void appendLog(String message) {
        if (message == null) return;
        String stamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        final String line = stamp + "  " + message + "\n";
        synchronized (sessionLog) {
            sessionLog.append(line);
            if (sessionLog.length() > LOG_LIMIT) {
                sessionLog.delete(0, sessionLog.length() - LOG_LIMIT);
            }
            if (runtimeLogFile != null) {
                try (FileWriter fw = new FileWriter(runtimeLogFile, true)) {
                    fw.write(line);
                } catch (Throwable ignored) {
                }
            }
        }
        runOnUiThread(() -> {
            if (logText != null) {
                String snapshot;
                synchronized (sessionLog) { snapshot = sessionLog.toString(); }
                logText.setText(snapshot);
            }
        });
    }

    private void setStatus(String text) {
        if (statusText != null) statusText.setText(text);
    }

    private int nextSeq() {
        return sequence.getAndUpdate(v -> (v + 1) & 0xFFFF);
    }

    private void closeSerial() {
        UsbSerialCp210x s = serial;
        serial = null;
        if (s != null) s.close();
        streamEnabled = false;
    }

    private static void sleepQuiet(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private static String stackSummary(Throwable t) {
        if (t == null) return "неизвестная ошибка";
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }

    @Override
    protected void onStop() {
        // Если пользователь уходит из приложения, не оставляем RC-поток включённым в фоне.
        if (streamEnabled) stopRcStream(false);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        // Синхронно пытаемся отключить 0x42 до закрытия порта: это важнее красивого завершения потока worker.
        UsbSerialCp210x s = serial;
        if (s != null && s.isOpen()) {
            try {
                byte[] off = SiyiProtocol.channelStreamRequest(0, nextSeq());
                for (int i = 0; i < 3; i++) {
                    s.write(off);
                    sleepQuiet(40);
                }
            } catch (Throwable ignored) {}
        }
        streamEnabled = false;
        closeSerial();
        if (researchTransports != null) researchTransports.stopAll();
        linuxInputProbe.stop();
        worker.shutdownNow();
        try { unregisterReceiver(usbPermissionReceiver); } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
