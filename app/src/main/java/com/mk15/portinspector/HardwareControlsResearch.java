package com.mk15.portinspector;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public final class HardwareControlsResearch {
    public static final int CHANNEL_COUNT = 16;

    public static final class RowSnapshot {
        public final int channel;
        public final int type;
        public final int entity;
        public final String name;
        public final boolean initialized;
        public final int baseline;
        public final int current;
        public final int min;
        public final int max;
        public final long changes;
        public final long samples;
        public final long lastChangeMs;
        public final String state;

        RowSnapshot(
                int channel,
                int type,
                int entity,
                String name,
                boolean initialized,
                int baseline,
                int current,
                int min,
                int max,
                long changes,
                long samples,
                long lastChangeMs,
                String state) {
            this.channel = channel;
            this.type = type;
            this.entity = entity;
            this.name = name;
            this.initialized = initialized;
            this.baseline = baseline;
            this.current = current;
            this.min = min;
            this.max = max;
            this.changes = changes;
            this.samples = samples;
            this.lastChangeMs = lastChangeMs;
            this.state = state;
        }

        public int delta() {
            return initialized ? current - baseline : 0;
        }

        public boolean changedRecently(long nowMs, long windowMs) {
            return lastChangeMs > 0 && nowMs - lastChangeMs >= 0 && nowMs - lastChangeMs <= windowMs;
        }
    }

    public static final class ChangeBatch {
        public final boolean[] changed;

        ChangeBatch(boolean[] changed) {
            this.changed = changed;
        }
    }

    public static final class ExperimentResult {
        public final int number;
        public final String physicalLabel;
        public final String kind;
        public final String classification;
        public final int[] candidateChannels;
        public final long durationMs;

        ExperimentResult(
                int number,
                String physicalLabel,
                String kind,
                String classification,
                int[] candidateChannels,
                long durationMs) {
            this.number = number;
            this.physicalLabel = physicalLabel;
            this.kind = kind;
            this.classification = classification;
            this.candidateChannels = candidateChannels;
            this.durationMs = durationMs;
        }

        public int topCandidateChannel() {
            return candidateChannels.length == 0 ? -1 : candidateChannels[0];
        }
    }

    private static final class ChannelState {
        boolean initialized;
        int baseline;
        int current;
        int min;
        int max;
        long changes;
        long samples;
        long lastChangeMs;
    }

    private static final class Experiment {
        final int number;
        final String physicalLabel;
        final String kind;
        final String classification;
        final long startedMs;
        long finishedMs;

        final int[] start = new int[CHANNEL_COUNT];
        final int[] min = new int[CHANNEL_COUNT];
        final int[] max = new int[CHANNEL_COUNT];
        final long[] changes = new long[CHANNEL_COUNT];
        final long[] samples = new long[CHANNEL_COUNT];
        final Map<Integer, Integer>[] counts;

        @SuppressWarnings("unchecked")
        Experiment(
                int number,
                String physicalLabel,
                String kind,
                String classification,
                long startedMs,
                ChannelState[] session) {
            this.number = number;
            this.physicalLabel = physicalLabel;
            this.kind = kind;
            this.classification = classification;
            this.startedMs = startedMs;
            this.counts = new Map[CHANNEL_COUNT];

            for (int i = 0; i < CHANNEL_COUNT; i++) {
                counts[i] = new TreeMap<>();
                if (session[i].initialized) {
                    start[i] = session[i].current;
                    min[i] = session[i].current;
                    max[i] = session[i].current;
                    samples[i] = 1;
                    counts[i].put(session[i].current, 1);
                } else {
                    start[i] = Integer.MIN_VALUE;
                    min[i] = Integer.MAX_VALUE;
                    max[i] = Integer.MIN_VALUE;
                }
            }
        }
    }

    private final ChannelState[] session = new ChannelState[CHANNEL_COUNT];
    private final int[] mappingType = new int[CHANNEL_COUNT];
    private final int[] mappingEntity = new int[CHANNEL_COUNT];
    private byte[] mappingRaw = new byte[0];

    private final List<Experiment> completed = new ArrayList<>();
    private final StringBuilder eventsCsv = new StringBuilder();

    private Experiment active;
    private int nextExperiment = 1;
    private long researchStartedMs;

    public HardwareControlsResearch() {
        for (int i = 0; i < CHANNEL_COUNT; i++) session[i] = new ChannelState();
        reset();
    }

    public synchronized void reset() {
        for (ChannelState s : session) {
            s.initialized = false;
            s.baseline = 0;
            s.current = 0;
            s.min = 0;
            s.max = 0;
            s.changes = 0;
            s.samples = 0;
            s.lastChangeMs = 0;
        }
        Arrays.fill(mappingType, -1);
        Arrays.fill(mappingEntity, -1);
        mappingRaw = new byte[0];
        completed.clear();
        active = null;
        nextExperiment = 1;
        researchStartedMs = System.currentTimeMillis();
        eventsCsv.setLength(0);
        eventsCsv.append("timestamp_ms,timestamp,experiment,physical_label,kind,entity_type,entity_id,channel,raw_value,previous_value,state\n");
    }

    public synchronized void setMapping(byte[] raw, int[] types, int[] entities) {
        mappingRaw = raw == null ? new byte[0] : Arrays.copyOf(raw, raw.length);
        if (types != null) {
            for (int i = 0; i < CHANNEL_COUNT && i < types.length; i++) mappingType[i] = types[i];
        }
        if (entities != null) {
            for (int i = 0; i < CHANNEL_COUNT && i < entities.length; i++) mappingEntity[i] = entities[i];
        }
    }

    public synchronized ChangeBatch onChannels(int[] values, long nowMs) {
        boolean[] changed = new boolean[CHANNEL_COUNT];
        if (values == null || values.length < CHANNEL_COUNT) return new ChangeBatch(changed);

        for (int i = 0; i < CHANNEL_COUNT; i++) {
            int value = values[i];
            ChannelState s = session[i];
            int previous = s.current;
            boolean hadPrevious = s.initialized;

            if (!s.initialized) {
                s.initialized = true;
                s.baseline = value;
                s.current = value;
                s.min = value;
                s.max = value;
                s.samples = 1;
            } else {
                if (value != s.current) {
                    changed[i] = true;
                    s.changes++;
                    s.lastChangeMs = nowMs;
                    s.current = value;
                }
                if (value < s.min) s.min = value;
                if (value > s.max) s.max = value;
                s.samples++;
            }

            if (active != null) {
                Experiment e = active;
                if (e.start[i] == Integer.MIN_VALUE) {
                    e.start[i] = value;
                    e.min[i] = value;
                    e.max[i] = value;
                }
                if (value < e.min[i]) e.min[i] = value;
                if (value > e.max[i]) e.max[i] = value;
                e.samples[i]++;
                e.counts[i].put(value, e.counts[i].getOrDefault(value, 0) + 1);

                if (hadPrevious && value != previous) {
                    e.changes[i]++;
                    appendEvent(nowMs, e, i, previous, value);
                }
            }
        }

        return new ChangeBatch(changed);
    }

    public synchronized int beginExperiment(String physicalLabel, String kind, long nowMs) {
        if (active != null) throw new IllegalStateException("Эксперимент уже идёт");
        String label = normalizeLabel(physicalLabel);
        String normalizedKind = normalizeKind(kind);
        active = new Experiment(
                nextExperiment++,
                label,
                normalizedKind,
                initialClassification(label, normalizedKind),
                nowMs,
                session);
        return active.number;
    }

    public synchronized ExperimentResult finishExperiment(long nowMs) {
        if (active == null) throw new IllegalStateException("Нет активного эксперимента");
        active.finishedMs = nowMs;
        int[] candidates = candidateChannels(active);
        ExperimentResult result = new ExperimentResult(
                active.number,
                active.physicalLabel,
                active.kind,
                active.classification,
                candidates,
                Math.max(0, active.finishedMs - active.startedMs));
        completed.add(active);
        active = null;
        return result;
    }

    public synchronized boolean isRecording() {
        return active != null;
    }

    public synchronized String activeLabel() {
        return active == null ? "" : active.physicalLabel;
    }

    public synchronized int completedExperimentCount() {
        return completed.size();
    }

    public synchronized RowSnapshot rowSnapshot(int zeroBasedChannel) {
        if (zeroBasedChannel < 0 || zeroBasedChannel >= CHANNEL_COUNT) {
            throw new IllegalArgumentException("channel must be 0..15");
        }
        ChannelState s = session[zeroBasedChannel];
        int type = mappingType[zeroBasedChannel];
        int entity = mappingEntity[zeroBasedChannel];
        String name = type >= 0
                ? SiyiProtocol.physicalChannelName(type, entity)
                : "не определён";
        return new RowSnapshot(
                zeroBasedChannel + 1,
                type,
                entity,
                name,
                s.initialized,
                s.baseline,
                s.current,
                s.min,
                s.max,
                s.changes,
                s.samples,
                s.lastChangeMs,
                stateBand(s.current));
    }

    public synchronized String buildSummaryMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Исследование органов управления SIYI MK15\n\n");
        sb.append("Режим: только чтение SIYI mapping/RC channel data; mapping и RC-конфигурация не изменяются.\n\n");
        sb.append("Завершённых опытов: **").append(completed.size()).append("**.\n\n");
        if (active != null) {
            sb.append("> Внимание: при формировании отчёта опыт №")
                    .append(active.number)
                    .append(" (").append(active.physicalLabel)
                    .append(") ещё не был завершён.\n\n");
        }

        sb.append("| № | Физический орган | Вид опыта | Кандидат(ы) entity/channel | Наблюдённые состояния / диапазон | Изменений | Классификация | Примечание |\n");
        sb.append("|---:|---|---|---|---|---:|---|---|\n");

        for (Experiment e : completed) {
            int[] candidates = candidateChannels(e);
            String candidateText = candidates.length == 0 ? "не обнаружено" : candidateText(e, candidates);
            String values = candidates.length == 0 ? "—" : candidateValuesText(e, candidates[0] - 1);
            long changes = candidates.length == 0 ? 0 : e.changes[candidates[0] - 1];
            String note;
            if (candidates.length == 0) {
                note = "Во время опыта ни один RC-канал не изменился.";
            } else if (candidates.length == 1) {
                note = "Один изменявшийся канал; требуется сверка повторяемости по событиям.";
            } else {
                note = "Изменялось несколько каналов — это обязательно проверить при анализе.";
            }

            sb.append('|').append(e.number)
                    .append('|').append(md(e.physicalLabel))
                    .append('|').append(md(e.kind))
                    .append('|').append(md(candidateText))
                    .append('|').append(md(values))
                    .append('|').append(changes)
                    .append('|').append(e.classification)
                    .append('|').append(md(note))
                    .append("|\n");
        }

        sb.append("\n## Живая сводка каналов за сеанс\n\n");
        sb.append("| Channel | Entity type | Entity ID | Имя | Baseline | Current | Min | Max | Delta | Changes | Samples |\n");
        sb.append("|---:|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|\n");
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            RowSnapshot row = rowSnapshot(i);
            sb.append('|').append(row.channel)
                    .append('|').append(row.type)
                    .append('|').append(row.entity)
                    .append('|').append(md(row.name))
                    .append('|').append(row.initialized ? row.baseline : "")
                    .append('|').append(row.initialized ? row.current : "")
                    .append('|').append(row.initialized ? row.min : "")
                    .append('|').append(row.initialized ? row.max : "")
                    .append('|').append(row.initialized ? row.delta() : "")
                    .append('|').append(row.changes)
                    .append('|').append(row.samples)
                    .append("|\n");
        }

        sb.append("\n## Ограничение автоматического вывода\n\n");
        sb.append("Классификация APP_CANDIDATE автоматически не назначается. ")
                .append("J1–J4 помечаются RC_CONTROL по условиям исследования; остальные новые органы остаются UNKNOWN ")
                .append("до анализа повторяемости, конфликтов и назначения штатного RC-контура.\n");

        return sb.toString();
    }

    public synchronized String buildControlsCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("experiment,physical_label,kind,entity_type,entity_id,channel,start,min,max,changes,samples,distinct_values,stable_values,classification,candidate_rank,notes\n");
        for (Experiment e : completed) {
            int[] candidates = candidateChannels(e);
            if (candidates.length == 0) {
                sb.append(e.number).append(',')
                        .append(csv(e.physicalLabel)).append(',')
                        .append(csv(e.kind))
                        .append(",,,,,,,,,,")
                        .append(csv(e.classification)).append(",,")
                        .append(csv("no_channel_change"))
                        .append('\n');
                continue;
            }
            for (int rank = 0; rank < candidates.length; rank++) {
                int ch = candidates[rank] - 1;
                sb.append(e.number).append(',')
                        .append(csv(e.physicalLabel)).append(',')
                        .append(csv(e.kind)).append(',')
                        .append(mappingType[ch]).append(',')
                        .append(mappingEntity[ch]).append(',')
                        .append(ch + 1).append(',')
                        .append(e.start[ch] == Integer.MIN_VALUE ? "" : e.start[ch]).append(',')
                        .append(e.min[ch] == Integer.MAX_VALUE ? "" : e.min[ch]).append(',')
                        .append(e.max[ch] == Integer.MIN_VALUE ? "" : e.max[ch]).append(',')
                        .append(e.changes[ch]).append(',')
                        .append(e.samples[ch]).append(',')
                        .append(csv(distinctValues(e, ch))).append(',')
                        .append(csv(stableValues(e, ch))).append(',')
                        .append(csv(e.classification)).append(',')
                        .append(rank + 1).append(',')
                        .append(csv(candidates.length > 1 ? "multiple_channels_changed" : "single_changed_channel"))
                        .append('\n');
            }
        }
        return sb.toString();
    }

    public synchronized String buildControlsJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"device\": \"SIYI MK15\",\n");
        sb.append("  \"research_started_ms\": ").append(researchStartedMs).append(",\n");
        sb.append("  \"experiments\": [\n");

        for (int ei = 0; ei < completed.size(); ei++) {
            Experiment e = completed.get(ei);
            int[] candidates = candidateChannels(e);
            sb.append("    {\n");
            sb.append("      \"number\": ").append(e.number).append(",\n");
            sb.append("      \"physical_label\": \"").append(json(e.physicalLabel)).append("\",\n");
            sb.append("      \"kind\": \"").append(json(e.kind)).append("\",\n");
            sb.append("      \"classification\": \"").append(json(e.classification)).append("\",\n");
            sb.append("      \"started_ms\": ").append(e.startedMs).append(",\n");
            sb.append("      \"finished_ms\": ").append(e.finishedMs).append(",\n");
            sb.append("      \"mismatches\": null,\n");
            sb.append("      \"candidate_channels\": [");

            for (int ci = 0; ci < candidates.length; ci++) {
                if (ci > 0) sb.append(',');
                int ch = candidates[ci] - 1;
                sb.append("{\"channel\":").append(ch + 1)
                        .append(",\"entity_type\":").append(mappingType[ch])
                        .append(",\"entity_id\":").append(mappingEntity[ch])
                        .append(",\"name\":\"").append(json(mappedName(ch))).append("\"")
                        .append(",\"start\":").append(e.start[ch] == Integer.MIN_VALUE ? "null" : e.start[ch])
                        .append(",\"min\":").append(e.min[ch] == Integer.MAX_VALUE ? "null" : e.min[ch])
                        .append(",\"max\":").append(e.max[ch] == Integer.MIN_VALUE ? "null" : e.max[ch])
                        .append(",\"changes\":").append(e.changes[ch])
                        .append(",\"samples\":").append(e.samples[ch])
                        .append(",\"distinct_values\":").append(jsonIntArray(e.counts[ch].keySet()))
                        .append(",\"stable_values\":").append(jsonIntArray(stableValueList(e, ch)))
                        .append('}');
            }

            sb.append("]\n");
            sb.append("    }");
            if (ei + 1 < completed.size()) sb.append(',');
            sb.append('\n');
        }

        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    public synchronized String eventsCsv() {
        return eventsCsv.toString();
    }

    public synchronized String mappingRawText() {
        StringBuilder sb = new StringBuilder();
        sb.append("mapping_raw_hex=").append(SiyiProtocol.hex(mappingRaw)).append('\n');
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            sb.append("CH").append(i + 1)
                    .append(" type=").append(mappingType[i])
                    .append(" entity=").append(mappingEntity[i])
                    .append(" name=").append(mappedName(i))
                    .append('\n');
        }
        return sb.toString();
    }

    private void appendEvent(long nowMs, Experiment e, int ch, int previous, int value) {
        eventsCsv.append(nowMs).append(',')
                .append(csv(formatTimestamp(nowMs))).append(',')
                .append(e.number).append(',')
                .append(csv(e.physicalLabel)).append(',')
                .append(csv(e.kind)).append(',')
                .append(mappingType[ch]).append(',')
                .append(mappingEntity[ch]).append(',')
                .append(ch + 1).append(',')
                .append(value).append(',')
                .append(previous).append(',')
                .append(stateBand(value))
                .append('\n');
    }

    private int[] candidateChannels(Experiment e) {
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            if (e.changes[i] > 0) list.add(i);
        }
        Collections.sort(list, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                int byChanges = Long.compare(e.changes[b], e.changes[a]);
                if (byChanges != 0) return byChanges;
                long rangeA = e.min[a] == Integer.MAX_VALUE ? 0 : (long) e.max[a] - e.min[a];
                long rangeB = e.min[b] == Integer.MAX_VALUE ? 0 : (long) e.max[b] - e.min[b];
                int byRange = Long.compare(rangeB, rangeA);
                if (byRange != 0) return byRange;
                return Integer.compare(a, b);
            }
        });

        int[] out = new int[list.size()];
        for (int i = 0; i < list.size(); i++) out[i] = list.get(i) + 1;
        return out;
    }

    private String candidateText(Experiment e, int[] candidates) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.length; i++) {
            if (i > 0) sb.append("; ");
            int ch = candidates[i] - 1;
            sb.append(mappedName(ch))
                    .append(" t=").append(mappingType[ch])
                    .append("/id=").append(mappingEntity[ch])
                    .append(" → CH").append(ch + 1);
        }
        return sb.toString();
    }

    private String candidateValuesText(Experiment e, int ch) {
        if ("ANALOG".equals(e.kind) || "STICK".equals(e.kind)) {
            return "neutral=" + nullable(e.start[ch])
                    + ", min=" + nullableMin(e.min[ch])
                    + ", max=" + nullableMax(e.max[ch])
                    + ", distinct=" + e.counts[ch].size();
        }
        return "raw={" + distinctValues(e, ch) + "}; stable={" + stableValues(e, ch) + "}";
    }

    private String distinctValues(Experiment e, int ch) {
        StringBuilder sb = new StringBuilder();
        for (Integer v : e.counts[ch].keySet()) {
            if (sb.length() > 0) sb.append('|');
            sb.append(v);
        }
        return sb.toString();
    }

    private String stableValues(Experiment e, int ch) {
        StringBuilder sb = new StringBuilder();
        for (Integer v : stableValueList(e, ch)) {
            if (sb.length() > 0) sb.append('|');
            sb.append(v);
        }
        return sb.toString();
    }

    private List<Integer> stableValueList(Experiment e, int ch) {
        List<Integer> stable = new ArrayList<>();
        for (Map.Entry<Integer, Integer> item : e.counts[ch].entrySet()) {
            if (item.getValue() >= 2) stable.add(item.getKey());
        }
        return stable;
    }

    private String mappedName(int ch) {
        return mappingType[ch] >= 0
                ? SiyiProtocol.physicalChannelName(mappingType[ch], mappingEntity[ch])
                : "не определён";
    }

    private static String normalizeLabel(String label) {
        String v = label == null ? "" : label.trim();
        return v.isEmpty() ? "UNKNOWN" : v;
    }

    private static String normalizeKind(String kind) {
        String v = kind == null ? "" : kind.trim().toUpperCase(Locale.US);
        if ("BUTTON".equals(v) || "SWITCH_3POS".equals(v)
                || "ANALOG".equals(v) || "STICK".equals(v) || "OTHER".equals(v)) {
            return v;
        }
        return "OTHER";
    }

    private static String initialClassification(String label, String kind) {
        if ("STICK".equals(kind)
                || "J1".equalsIgnoreCase(label)
                || "J2".equalsIgnoreCase(label)
                || "J3".equalsIgnoreCase(label)
                || "J4".equalsIgnoreCase(label)) {
            return "RC_CONTROL";
        }
        return "UNKNOWN";
    }

    private static String stateBand(int value) {
        if (value <= 1250) return "LOW";
        if (value >= 1750) return "HIGH";
        return "CENTER";
    }

    private static String formatTimestamp(long timeMs) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date(timeMs));
    }

    private static String nullable(int v) {
        return v == Integer.MIN_VALUE ? "—" : String.valueOf(v);
    }

    private static String nullableMin(int v) {
        return v == Integer.MAX_VALUE ? "—" : String.valueOf(v);
    }

    private static String nullableMax(int v) {
        return v == Integer.MIN_VALUE ? "—" : String.valueOf(v);
    }

    private static String md(String s) {
        if (s == null) return "";
        return s.replace("|", "\\|").replace("\n", " ");
    }

    private static String csv(String s) {
        if (s == null) return "";
        String v = s.replace("\"", "\"\"");
        return "\"" + v + "\"";
    }

    private static String json(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String jsonIntArray(Iterable<Integer> values) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Integer value : values) {
            if (!first) sb.append(',');
            sb.append(value == null ? "null" : value);
            first = false;
        }
        return sb.append(']').toString();
    }
}
