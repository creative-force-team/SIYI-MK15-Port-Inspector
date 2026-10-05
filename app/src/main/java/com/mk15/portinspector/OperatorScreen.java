package com.mk15.portinspector;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Operator-first layout. Existing controls and listeners are reused, not emulated. */
public final class OperatorScreen {
    private final Activity activity;
    private final int unit;

    private OperatorScreen(Activity activity) {
        this.activity = activity;
        this.unit = Math.round(activity.getResources().getDisplayMetrics().density * 48);
    }
    private int dp(int n) {
        return Math.round(n * activity.getResources().getDisplayMetrics().density);
    }
    private LinearLayout column() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        return box;
    }
    private LinearLayout row() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        return box;
    }
    private static View detach(View view) {
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
        return view;
    }
    private static List<View> children(ViewGroup parent) {
        List<View> result = new ArrayList<>();
        while (parent.getChildCount() > 0) {
            View view = parent.getChildAt(0);
            parent.removeViewAt(0);
            result.add(view);
        }
        return result;
    }
    private TextView label(String text, int sp, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(Color.rgb(30, 30, 30));
        if (bold) view.setTypeface(null, Typeface.BOLD);
        return view;
    }
    private Button tune(Button button, String text, String id) {
        button.setText(text);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(unit);
        button.setMinimumHeight(unit);
        button.setPadding(dp(3), dp(2), dp(3), dp(2));
        button.setContentDescription(id);
        return button;
    }
    private Button button(String text, String id, View.OnClickListener listener) {
        Button b = tune(new Button(activity), text, id);
        b.setOnClickListener(listener);
        return b;
    }
    private void weighted(LinearLayout parent, View view) {
        parent.addView(detach(view), new LinearLayout.LayoutParams(0, unit, 1f));
    }
    private ScrollView scroller(View content, String id) {
        ScrollView scroll = new ScrollView(activity);
        scroll.setContentDescription(id);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setScrollbarFadingEnabled(false);
        scroll.addView(detach(content), new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }
    private void twoPerRow(LinearLayout target, List<View> views, String prefix) {
        LinearLayout pair = null;
        int index = 0;
        for (View view : views) {
            if ((index % 2) == 0) {
                pair = row();
                target.addView(pair, new LinearLayout.LayoutParams(-1, unit));
            }
            if (view instanceof Button) {
                Button b = (Button) view;
                tune(b, b.getText().toString(), prefix + index);
            } else if (view instanceof TextView) {
                ((TextView) view).setTextSize(13);
            }
            weighted(pair, view);
            index++;
        }
    }

    public static View arrange(Activity activity, TextView title, TextView warning,
            LinearLayout statusLine, LinearLayout transportRow, LinearLayout commands,
            LinearLayout researchPanel, TextView researchStatus,
            LinearLayout finderButtons, TextView finderStatus, TextView reportStatus,
            TextView channelStatus, ScrollView channels, TextView scan, TextView log) {
        return new OperatorScreen(activity).build(title, warning, statusLine, transportRow,
                commands, researchPanel, researchStatus, finderButtons, finderStatus,
                reportStatus, channelStatus, channels, scan, log);
    }

    private View build(TextView title, TextView warning, LinearLayout statusLine,
            LinearLayout transportRow, LinearLayout commands, LinearLayout researchPanel,
            TextView researchStatus, LinearLayout finderButtons, TextView finderStatus,
            TextView reportStatus, TextView channelStatus, ScrollView channels,
            TextView scan, TextView log) {
        if (researchPanel.getChildCount() != 3) throw new IllegalStateException("Unexpected research panel");
        List<View> research = children(researchPanel);
        Button prepare = (Button) research.get(0);
        LinearLayout selectors = (LinearLayout) research.get(1);
        List<View> actions = children((ViewGroup) research.get(2));
        if (actions.size() != 3) throw new IllegalStateException("Unexpected recording actions");

        LinearLayout root = column();
        root.setPadding(dp(8), dp(4), dp(8), dp(4));
        root.setBackgroundColor(Color.rgb(245,245,245));
        root.setKeepScreenOn(true);
        root.setFocusableInTouchMode(true);
        root.setContentDescription("mk15_operator_root");

        String version = "";
        try { version = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName; }
        catch (Exception ignored) {}
        title.setText("SIYI MK15 · " + version);
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout header = row();
        header.addView(detach(title), new LinearLayout.LayoutParams(0, dp(32), 1f));
        TextView safety = label("Только на столе.\nБез работающих двигателей.", 11, true);
        safety.setTextColor(Color.rgb(145,55,0));
        safety.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        header.addView(safety, new LinearLayout.LayoutParams(0, dp(32), 1f));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(32)));

        LinearLayout navigation = row();
        root.addView(navigation, new LinearLayout.LayoutParams(-1, unit));
        FrameLayout pages = new FrameLayout(activity);
        root.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout researchPage = column();
        researchPage.setContentDescription("hcr_page_research");
        List<Spinner> spinners = new ArrayList<>();
        for (int i=0; i<selectors.getChildCount(); i++) {
            View child = selectors.getChildAt(i);
            if (child instanceof Spinner) spinners.add((Spinner) child);
            if (child instanceof TextView) ((TextView) child).setTextSize(13);
            if (child instanceof EditText) child.setContentDescription("hcr_custom_label");
        }
        if (spinners.size() != 2) throw new IllegalStateException("Unexpected research selectors");
        spinners.get(0).setContentDescription("hcr_control");
        spinners.get(1).setContentDescription("hcr_kind");
        spinners.get(0).setSelection(7);
        researchPage.addView(detach(selectors), new LinearLayout.LayoutParams(-1, unit));
        LinearLayout buttons = row();
        weighted(buttons, tune(prepare, "1. Подготовить", "hcr_prepare"));
        weighted(buttons, tune((Button)actions.get(0), "2. Записать", "hcr_begin"));
        weighted(buttons, tune((Button)actions.get(1), "3. Завершить", "hcr_finish"));
        weighted(buttons, tune((Button)actions.get(2), "Стоп", "hcr_stop"));
        researchPage.addView(buttons, new LinearLayout.LayoutParams(-1, unit));
        researchStatus.setTextSize(13);
        researchStatus.setContentDescription("hcr_status");
        researchPage.addView(scroller(researchStatus, "hcr_status_scroll"), new LinearLayout.LayoutParams(-1, dp(56)));
        channels.setContentDescription("hcr_channels_scroll");
        channels.setScrollbarFadingEnabled(false);
        channels.setFillViewport(false);
        ViewGroup channelBox = (ViewGroup) channels.getChildAt(0);
        for(int i=0;i<channelBox.getChildCount();i++) {
            View v=channelBox.getChildAt(i);
            if(v instanceof TextView) {
                ((TextView)v).setTextSize(i == 0 ? 12 : 13);
                if(i>0) v.setContentDescription("hcr_channel_"+i);
            }
        }
        researchPage.addView(detach(channels), new LinearLayout.LayoutParams(-1, 0, 1f));
        pages.addView(researchPage, new FrameLayout.LayoutParams(-1,-1));

        LinearLayout reportPage = column();
        reportPage.setContentDescription("hcr_page_reports");
        LinearLayout exports = row();
        List<View> diagnosticCommands = new ArrayList<>();
        for(View v:children(commands)) {
            if(!(v instanceof Button)) { diagnosticCommands.add(v); continue; }
            Button b=(Button)v;
            String text=b.getText().toString();
            if(text.equals("ZIP → Download")) weighted(exports,tune(b,"Сохранить ZIP","hcr_save_zip"));
            else if(text.startsWith("ZIP → флешка")) weighted(exports,tune(b,"Флешка / файл","hcr_file_picker"));
            else if(text.equals("ZIP → thesystem")) weighted(exports,tune(b,"Отправить ZIP","hcr_upload_zip"));
            else diagnosticCommands.add(b);
        }
        if(exports.getChildCount()!=3) throw new IllegalStateException("Missing export controls");
        reportPage.addView(exports,new LinearLayout.LayoutParams(-1,unit));
        TextView exportHelp=label("Завершите запись, затем сохраните или отправьте ZIP. Все опыты текущего сеанса входят в один отчёт. Не закрывайте приложение до сохранения.",14,false);
        exportHelp.setPadding(dp(4),dp(6),dp(4),dp(6));
        reportPage.addView(exportHelp,new LinearLayout.LayoutParams(-1,-2));
        reportStatus.setTextSize(14);
        reportStatus.setContentDescription("hcr_report_status");
        reportPage.addView(scroller(reportStatus,"hcr_report_scroll"),new LinearLayout.LayoutParams(-1,0,1f));
        pages.addView(reportPage,new FrameLayout.LayoutParams(-1,-1));

        LinearLayout diagnostics=column();
        diagnostics.setContentDescription("hcr_diagnostics_content");
        diagnostics.addView(detach(warning),new LinearLayout.LayoutParams(-1,-2));
        diagnostics.addView(detach(statusLine),new LinearLayout.LayoutParams(-1,unit));
        twoPerRow(diagnostics,children(transportRow),"diag_transport_");
        twoPerRow(diagnostics,diagnosticCommands,"diag_command_");
        twoPerRow(diagnostics,children(finderButtons),"diag_finder_");
        diagnostics.addView(detach(finderStatus),new LinearLayout.LayoutParams(-1,-2));
        channelStatus.setTextSize(14);
        diagnostics.addView(detach(channelStatus),new LinearLayout.LayoutParams(-1,-2));
        diagnostics.addView(label("Видимые порты и устройства",16,true),new LinearLayout.LayoutParams(-1,-2));
        diagnostics.addView(detach(scan),new LinearLayout.LayoutParams(-1,-2));
        diagnostics.addView(label("Журнал протокола и ввода",16,true),new LinearLayout.LayoutParams(-1,-2));
        diagnostics.addView(detach(log),new LinearLayout.LayoutParams(-1,-2));
        ScrollView diagnosticsPage=scroller(diagnostics,"hcr_page_diagnostics");
        pages.addView(diagnosticsPage,new FrameLayout.LayoutParams(-1,-1));

        View[] sections={researchPage,reportPage,diagnosticsPage};
        String[] titles={"Исследование","Отчёт","Диагностика"};
        String[] identifiers={"hcr_tab_research","hcr_tab_reports","hcr_tab_diagnostics"};
        Button[] tabs=new Button[3];
        for(int i=0;i<3;i++) {
            final int selected=i;
            tabs[i]=button(titles[i],identifiers[i],v->{
                for(int k=0;k<sections.length;k++) {
                    sections[k].setVisibility(k==selected?View.VISIBLE:View.GONE);
                    tabs[k].setSelected(k==selected);
                    tabs[k].setTypeface(null,k==selected?Typeface.BOLD:Typeface.NORMAL);
                }
                root.requestFocus();
                android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
                if(keyboard!=null) keyboard.hideSoftInputFromWindow(root.getWindowToken(),0);
            });
            weighted(navigation,tabs[i]);
            sections[i].setVisibility(i==0?View.VISIBLE:View.GONE);
        }
        tabs[0].setTypeface(null,Typeface.BOLD);
        root.requestFocus();
        return root;
    }
}
