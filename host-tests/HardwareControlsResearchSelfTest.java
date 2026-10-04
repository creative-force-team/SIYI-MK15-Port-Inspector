import com.mk15.portinspector.HardwareControlsResearch;

public final class HardwareControlsResearchSelfTest {
    private static int[] values(int fill) {
        int[] v = new int[16];
        java.util.Arrays.fill(v, fill);
        return v;
    }

    private static void pulse(HardwareControlsResearch r, int channelOneBased, int low, int high, int cycles, long[] clock) {
        int[] v = values(low);
        r.onChannels(v, clock[0] += 50);
        for (int i = 0; i < cycles; i++) {
            v[channelOneBased - 1] = high;
            r.onChannels(v, clock[0] += 50);
            v[channelOneBased - 1] = low;
            r.onChannels(v, clock[0] += 50);
        }
    }

    public static void main(String[] args) {
        HardwareControlsResearch r = new HardwareControlsResearch();
        int[] type = {0,0,0,0,5,5,5,1,1,1,1,0,0,3,3,3};
        int[] entity = {0,1,2,3,0,1,2,0,1,2,3,4,5,0,0,0};
        byte[] raw = new byte[32];
        for (int i = 0; i < 16; i++) {
            raw[i * 2] = (byte) type[i];
            raw[i * 2 + 1] = (byte) entity[i];
        }
        r.setMapping(raw, type, entity);

        long[] clock = {1_000_000L};
        r.onChannels(values(1050), clock[0]);

        int n1 = r.beginExperiment("A", "BUTTON", clock[0] += 10);
        if (n1 != 1) throw new AssertionError("first experiment number");
        pulse(r, 8, 1050, 1950, 5, clock);
        HardwareControlsResearch.ExperimentResult a = r.finishExperiment(clock[0] += 10);
        if (a.topCandidateChannel() != 8) {
            throw new AssertionError("A candidate must be CH8, got " + a.topCandidateChannel());
        }

        int[] neutral = values(1050);
        neutral[4] = 1500;
        r.onChannels(neutral, clock[0] += 50);
        r.beginExperiment("SA", "SWITCH_3POS", clock[0] += 10);
        int[] switchValues = {1050,1500,1950,1500,1050,1500,1950,1500,1050,1500,1950,1500,1050,1500,1950};
        for (int value : switchValues) {
            neutral[4] = value;
            r.onChannels(neutral, clock[0] += 50);
        }
        HardwareControlsResearch.ExperimentResult sa = r.finishExperiment(clock[0] += 10);
        if (sa.topCandidateChannel() != 5) {
            throw new AssertionError("SA candidate must be CH5, got " + sa.topCandidateChannel());
        }

        HardwareControlsResearch.RowSnapshot row8 = r.rowSnapshot(7);
        if (row8.min != 1050 || row8.max != 1950 || row8.changes < 10) {
            throw new AssertionError("CH8 session evidence incomplete");
        }

        String summary = r.buildSummaryMarkdown();
        if (!summary.contains("A") || !summary.contains("CH8")
                || !summary.contains("SA") || !summary.contains("CH5")) {
            throw new AssertionError("summary lacks candidates\n" + summary);
        }

        String json = r.buildControlsJson();
        if (!json.contains("\"physical_label\": \"A\"")
                || !json.contains("\"channel\":8")
                || !json.contains("\"physical_label\": \"SA\"")
                || !json.contains("\"channel\":5")) {
            throw new AssertionError("controls.json evidence missing\n" + json);
        }

        String csv = r.buildControlsCsv();
        if (!csv.contains("\"A\",\"BUTTON\"") || !csv.contains(",8,")) {
            throw new AssertionError("controls.csv evidence missing");
        }

        String events = r.eventsCsv();
        int eventLines = events.split("\n").length - 1;
        if (eventLines < 20) {
            throw new AssertionError("events too short: " + eventLines);
        }

        String mapping = r.mappingRawText();
        if (!mapping.contains("CH8 type=1 entity=0 name=A")
                || !mapping.contains("CH10 type=1 entity=2 name=C")
                || !mapping.contains("CH11 type=1 entity=3 name=D")) {
            throw new AssertionError("mapping raw text incomplete\n" + mapping);
        }

        System.out.println("ALL HARDWARE CONTROLS RESEARCH SELF-TESTS PASSED");
    }
}
