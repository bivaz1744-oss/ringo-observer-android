package com.ringoai.sixone;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Typeface;
import android.view.View;
import android.widget.*;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.media.projection.MediaProjectionManager;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private SharedPreferences prefs;
    private TextView balanceView, statsView, statusView, historyView, adviceView;
    private Spinner numberSpinner;
    private EditText stakeInput, resultInput;
    private int balance, wins, losses, rounds;
    private int lastOcrNumber = -1;
    private static final int CAPTURE_REQUEST = 620;
    private BroadcastReceiver ocrReceiver;
    private int pendingNumber = -1, pendingStake = 0;
    private final List<Integer> history = new ArrayList<>();
    private static final int MAX_NUMBER = 36;
    private static final int OUTCOMES = 37;
    private static final double DEMO_MULTIPLIER = 5.2; // пример коэффициента из ранее предоставленных пользователем результатов

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("ringo62", MODE_PRIVATE);
        balance = prefs.getInt("balance", 1000);
        wins = prefs.getInt("wins", 0);
        losses = prefs.getInt("losses", 0);
        rounds = prefs.getInt("rounds", 0);
        pendingNumber = prefs.getInt("pendingNumber", -1);
        pendingStake = prefs.getInt("pendingStake", 0);
        loadHistory();
        buildUi();
        registerOcrReceiver();
        refresh();
    }

    private void registerOcrReceiver() {
        ocrReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                int n = intent.getIntExtra("number", -1);
                if (n >= 0 && n <= 36) {
                    lastOcrNumber = n;
                    resultInput.setText(String.valueOf(n));
                    recordResult();
                    statusView.setText("Автоматически распознано и сохранено число " + n + ".");
                }
            }
        };
        IntentFilter filter = new IntentFilter(ScreenOcrService.ACTION_NUMBER);
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(ocrReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(ocrReceiver, filter);
    }
    private void startScreenReader() {
        MediaProjectionManager manager = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), CAPTURE_REQUEST);
    }
    private void stopScreenReader() {
        Intent i = new Intent(this, ScreenOcrService.class); i.setAction(ScreenOcrService.ACTION_STOP); startService(i);
        toast("Распознавание остановлено.");
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CAPTURE_REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                Intent service = new Intent(this, ScreenOcrService.class);
                service.putExtra("resultCode", resultCode); service.putExtra("data", data);
                if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
                toast("Распознавание экрана включено. Открой Buleto и покажи результат раунда.");
            } else toast("Разрешение на запись экрана не предоставлено.");
        }
    }
    @Override protected void onDestroy() {
        if (ocrReceiver != null) { try { unregisterReceiver(ocrReceiver); } catch (Exception ignored) {} }
        super.onDestroy();
    }
    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 32);
        root.setBackgroundColor(0xFFF4F6FA);
        scroll.addView(root);

        TextView title = text("RINGO AI 6.4", 27, true);
        title.setTextColor(0xFF18243A);
        root.addView(title);
        TextView subtitle = text("ДЕМО-НАБЛЮДАТЕЛЬ • 0–36 • 37 РЕЗУЛЬТАТОВ", 12, true);
        subtitle.setTextColor(0xFF53627A);
        root.addView(subtitle);

        balanceView = text("", 22, true);
        balanceView.setTextColor(0xFF146C43);
        addGap(root, 12); root.addView(balanceView);

        root.addView(text("1. Выбери число (0–36)", 17, true));
        numberSpinner = new Spinner(this);
        String[] nums = new String[OUTCOMES];
        for (int i=0;i<OUTCOMES;i++) nums[i] = String.valueOf(i);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, nums);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        numberSpinner.setAdapter(adapter);
        root.addView(numberSpinner);

        root.addView(text("Размер виртуальной ставки", 15, true));
        stakeInput = new EditText(this);
        stakeInput.setInputType(2);
        stakeInput.setSingleLine(true);
        stakeInput.setHint("Например, 10");
        stakeInput.setText("10");
        root.addView(stakeInput);

        Button screenStart = button("Включить распознавание экрана Buleto");
        root.addView(screenStart);
        screenStart.setOnClickListener(v -> startScreenReader());
        Button screenStop = button("Остановить распознавание");
        root.addView(screenStop);
        screenStop.setOnClickListener(v -> stopScreenReader());
        Button acceptOcr = button("Сохранить найденное число вручную");
        root.addView(acceptOcr);
        acceptOcr.setOnClickListener(v -> {
            if (lastOcrNumber >= 0) {
                resultInput.setText(String.valueOf(lastOcrNumber));
                recordResult();
                lastOcrNumber = -1;
            } else toast("Пока число не распознано. Покажи результат раунда на экране.");
        });
        Button bet = button("Сделать виртуальную ставку");
        root.addView(bet);
        bet.setOnClickListener(v -> placeVirtualBet());

        addGap(root, 16);
        root.addView(text("2. Внеси результат завершённого раунда", 17, true));
        resultInput = new EditText(this);
        resultInput.setInputType(2);
        resultInput.setSingleLine(true);
        resultInput.setHint("Результат от 0 до 36");
        root.addView(resultInput);
        Button result = button("Сохранить результат и рассчитать");
        root.addView(result);
        result.setOnClickListener(v -> recordResult());

        statusView = text("Ожидание первого раунда", 15, true);
        statusView.setTextColor(0xFF34445C);
        root.addView(statusView);

        addGap(root, 16);
        root.addView(text("Аналитика", 20, true));
        statsView = text("", 15, false);
        root.addView(statsView);
        adviceView = text("", 14, false);
        adviceView.setTextColor(0xFF34445C);
        root.addView(adviceView);

        addGap(root, 16);
        root.addView(text("История результатов", 18, true));
        historyView = text("Пока нет результатов", 14, false);
        root.addView(historyView);

        Button reset = button("Сбросить демо-баланс и статистику");
        root.addView(reset);
        reset.setOnClickListener(v -> resetDemo());

        TextView note = text("Распознавание экрана экспериментальное: найденные отдельные числа автоматически добавляются в историю. Проверяй историю на точность распознавания. Прогноз не гарантирует выигрыш. Ставки только виртуальные.", 12, false);
        note.setTextColor(0xFF68758A);
        addGap(root, 12); root.addView(note);
        setContentView(scroll);
    }

    private TextView text(String value, int size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(size);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        t.setPadding(0, 8, 0, 8);
        return t;
    }
    private Button button(String label) {
        Button b = new Button(this); b.setText(label); return b;
    }
    private void addGap(LinearLayout root, int h) {
        View v = new View(this); root.addView(v, new LinearLayout.LayoutParams(1,h));
    }
    private void placeVirtualBet() {
        if (pendingNumber >= 0) { toast("Сначала внеси результат текущего раунда."); return; }
        int stake;
        try { stake = Integer.parseInt(stakeInput.getText().toString().trim()); }
        catch (Exception e) { toast("Введи размер ставки числом."); return; }
        if (stake <= 0 || stake > balance) { toast("Ставка должна быть больше 0 и не превышать баланс."); return; }
        pendingStake = stake;
        pendingNumber = Integer.parseInt(numberSpinner.getSelectedItem().toString());
        balance -= stake;
        save();
        statusView.setText("Виртуальный выбор: число " + pendingNumber + ", сумма " + pendingStake + ". Внеси результат раунда.");
        refresh();
    }
    private void recordResult() {
        int result;
        try { result = Integer.parseInt(resultInput.getText().toString().trim()); }
        catch (Exception e) { toast("Введи результат от 0 до 36."); return; }
        if (result < 0 || result > MAX_NUMBER) { toast("Допустимы только числа от 0 до 36."); return; }
        rounds++;
        history.add(0, result);
        if (history.size() > 500) history.remove(history.size()-1);
        if (pendingNumber >= 0) {
            if (result == pendingNumber) {
                int payout = (int)Math.round(pendingStake * DEMO_MULTIPLIER);
                balance += payout; wins++;
                statusView.setText("Совпадение! Демо-начисление по условному коэффициенту 5,2: " + payout + ".");
            } else {
                losses++;
                statusView.setText("Не совпало. Виртуальная ставка проиграна.");
            }
            pendingNumber = -1; pendingStake = 0;
        } else {
            statusView.setText("Результат сохранён. Ставки не было.");
        }
        resultInput.setText("");
        save(); refresh();
    }
    private void refresh() {
        balanceView.setText("Виртуальный баланс: " + balance);
        int settled = wins + losses;
        double accuracy = settled == 0 ? 0 : (wins * 100.0 / settled);
        statsView.setText("Раундов записано: " + rounds + "\nУгадано: " + wins + "\nНе угадано: " + losses +
                "\nТочность среди виртуальных ставок: " + String.format(Locale.US, "%.1f", accuracy) + "%" +
                "\nОжидающая ставка: " + (pendingNumber < 0 ? "нет" : ("число " + pendingNumber + ", сумма " + pendingStake)));
        int[] counts = new int[OUTCOMES];
        for (int n : history) if (n >= 0 && n <= MAX_NUMBER) counts[n]++;
        int least = 0;
        for (int i=1;i<OUTCOMES;i++) if (counts[i] < counts[least]) least = i;
        adviceView.setText("Частоты по сохранённой истории: " + history.size() + " результатов. Реже всего встречалось число " + least +
                " (" + counts[least] + " раз). Это только статистика прошлого, НЕ прогноз: при случайных независимых раундах у всех 37 чисел одинаковый шанс.");
        StringBuilder h = new StringBuilder();
        for (int i=0;i<history.size() && i<100;i++) h.append("Раунд ").append(rounds-i).append(": ").append(history.get(i)).append("\n");
        historyView.setText(h.length()==0 ? "Пока нет результатов" : h.toString());
    }
    private void loadHistory() {
        String saved = prefs.getString("history", "");
        if (saved == null || saved.isEmpty()) return;
        String[] values = saved.split(",");
        for (String value : values) {
            try { int n = Integer.parseInt(value); if (n >= 0 && n <= MAX_NUMBER) history.add(n); }
            catch (Exception ignored) { }
        }
    }
    private void save() {
        StringBuilder data = new StringBuilder();
        for (int n : history) { if (data.length() > 0) data.append(","); data.append(n); }
        prefs.edit().putInt("balance",balance).putInt("wins",wins).putInt("losses",losses)
                .putInt("rounds",rounds).putInt("pendingNumber",pendingNumber)
                .putInt("pendingStake",pendingStake).putString("history",data.toString()).apply();
    }
    private void resetDemo() {
        balance=1000; wins=0; losses=0; rounds=0; pendingNumber=-1; pendingStake=0; history.clear();
        save(); statusView.setText("Демо-режим сброшен. Баланс снова 1000."); refresh();
    }
    private void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
}
