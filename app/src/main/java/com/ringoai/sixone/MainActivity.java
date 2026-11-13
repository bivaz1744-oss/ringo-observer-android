package com.ringoai.sixone;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private SharedPreferences prefs;
    private TextView balanceView, statsView, statusView, historyView, adviceView;
    private Spinner numberSpinner;
    private EditText stakeInput, resultInput;
    private int balance, wins, losses, rounds;
    private int pendingNumber = -1, pendingStake = 0;
    private final List<String> history = new ArrayList<>();

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("ringo61", MODE_PRIVATE);
        balance = prefs.getInt("balance", 1000);
        wins = prefs.getInt("wins", 0);
        losses = prefs.getInt("losses", 0);
        rounds = prefs.getInt("rounds", 0);
        pendingNumber = prefs.getInt("pendingNumber", -1);
        pendingStake = prefs.getInt("pendingStake", 0);
        buildUi();
        refresh();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 32);
        root.setBackgroundColor(0xFFF4F6FA);
        scroll.addView(root);

        TextView title = text("RINGO AI 6.1", 27, true);
        title.setTextColor(0xFF18243A);
        root.addView(title);
        TextView subtitle = text("ДЕМО-ТРЕНАЖЁР • ТОЛЬКО ВИРТУАЛЬНЫЕ СТАВКИ", 12, true);
        subtitle.setTextColor(0xFF53627A);
        root.addView(subtitle);

        balanceView = text("", 22, true);
        balanceView.setTextColor(0xFF146C43);
        addGap(root, 12); root.addView(balanceView);

        root.addView(text("1. Выбери число (0–33)", 17, true));
        numberSpinner = new Spinner(this);
        String[] nums = new String[34];
        for (int i=0;i<34;i++) nums[i] = String.valueOf(i);
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

        Button bet = button("Сделать виртуальную ставку");
        root.addView(bet);
        bet.setOnClickListener(v -> placeVirtualBet());

        addGap(root, 16);
        root.addView(text("2. Внеси результат завершённого раунда", 17, true));
        resultInput = new EditText(this);
        resultInput.setInputType(2);
        resultInput.setSingleLine(true);
        resultInput.setHint("Результат от 0 до 33");
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

        TextView note = text("Важно: приложение не подключается к Ringo/Buleto, не размещает реальные ставки и не гарантирует прогнозы. Результаты вводятся вручную. Статистика прошлых раундов не предсказывает случайный следующий результат.", 12, false);
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
        catch (Exception e) { toast("Введи результат от 0 до 33."); return; }
        if (result < 0 || result > 33) { toast("Допустимы только числа от 0 до 33."); return; }
        rounds++;
        history.add(0, "Раунд " + rounds + ": " + result + (pendingNumber >= 0 ? " | выбор " + pendingNumber : ""));
        if (pendingNumber >= 0) {
            if (result == pendingNumber) {
                int payout = pendingStake * 34;
                balance += payout; wins++;
                statusView.setText("Совпадение! Виртуальное начисление: " + payout + ".");
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
                "\nТочность среди виртуальных ставок: " + String.format(java.util.Locale.US, "%.1f", accuracy) + "%" +
                "\nОжидающая ставка: " + (pendingNumber < 0 ? "нет" : ("число " + pendingNumber + ", сумма " + pendingStake)));
        adviceView.setText("Подсказка: каждый раунд независим, если игра использует случайный выбор. Частые/редкие числа в истории не дают надёжного прогноза следующего результата.");
        StringBuilder h = new StringBuilder();
        for (int i=0;i<history.size() && i<20;i++) h.append(history.get(i)).append("\n");
        historyView.setText(h.length()==0 ? "Пока нет результатов" : h.toString());
    }
    private void save() {
        prefs.edit().putInt("balance",balance).putInt("wins",wins).putInt("losses",losses)
                .putInt("rounds",rounds).putInt("pendingNumber",pendingNumber)
                .putInt("pendingStake",pendingStake).apply();
    }
    private void resetDemo() {
        balance=1000; wins=0; losses=0; rounds=0; pendingNumber=-1; pendingStake=0; history.clear();
        save(); statusView.setText("Демо-режим сброшен. Баланс снова 1000."); refresh();
    }
    private void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
}
