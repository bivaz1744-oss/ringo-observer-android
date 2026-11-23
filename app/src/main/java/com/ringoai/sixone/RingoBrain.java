package com.ringoai.sixone;

import java.util.List;
import java.util.Locale;

/**
 * Transparent, history-based ranking. This is not a trained AI model and cannot
 * know the next outcome of an independent random game.
 */
public final class RingoBrain {
    private static final int OUTCOMES = 37;
    private RingoBrain() {}

    public static String buildInsight(List<Integer> newestFirst) {
        int total = newestFirst == null ? 0 : newestFirst.size();
        if (total == 0) {
            return "СТАТИСТИЧЕСКИЙ АНАЛИЗ\nПока нет сохранённых исходов. После подтверждения раундов появится рейтинг исторических кандидатов.\nВажно: история не гарантирует следующий результат.";
        }

        double[] weighted = new double[OUTCOMES + 1];
        double weightSum = 0.0;
        int used = Math.min(total, 150);
        for (int i = 0; i < used; i++) {
            int n = newestFirst.get(i);
            if (n < 1 || n > OUTCOMES) continue;
            double w = Math.exp(-i / 45.0);
            weighted[n] += w;
            weightSum += w;
        }

        // Smoothed empirical frequencies: 0.5 virtual observation per possible outcome.
        double[] global = new double[OUTCOMES + 1];
        double denominator = weightSum + OUTCOMES * 0.5;
        for (int n = 1; n <= OUTCOMES; n++) {
            global[n] = (weighted[n] + 0.5) / denominator;
        }

        // Very small, heavily shrunk transition adjustment based on past occurrences
        // of the most recent outcome. It is deliberately capped because samples are sparse.
        double[] transitions = new double[OUTCOMES + 1];
        int transitionCount = 0;
        int last = newestFirst.get(0);
        if (last >= 1 && last <= OUTCOMES) {
            for (int i = 1; i < total; i++) {
                int older = newestFirst.get(i);
                int next = newestFirst.get(i - 1);
                if (older == last && next >= 1 && next <= OUTCOMES) {
                    transitions[next]++;
                    transitionCount++;
                }
            }
        }
        double transitionWeight = 0.15 * transitionCount / (transitionCount + 30.0);
        double transitionDenominator = transitionCount + 8.0;
        double[] score = new double[OUTCOMES + 1];
        double sum = 0.0;
        for (int n = 1; n <= OUTCOMES; n++) {
            double conditional = (transitions[n] + 8.0 / OUTCOMES) / transitionDenominator;
            score[n] = (1.0 - transitionWeight) * global[n] + transitionWeight * conditional;
            sum += score[n];
        }
        for (int n = 1; n <= OUTCOMES; n++) score[n] /= sum;

        boolean[] usedRank = new boolean[OUTCOMES + 1];
        StringBuilder out = new StringBuilder();
        out.append("СТАТИСТИЧЕСКИЙ РЕЙТИНГ (не гарантия)\n");
        out.append("История: ").append(total).append(" раундов; модель использовала до ")
           .append(used).append(" последних с затуханием старых данных.\n");
        out.append("Топ-кандидаты: ");
        for (int rank = 0; rank < 5; rank++) {
            int best = -1;
            for (int n = 1; n <= OUTCOMES; n++) {
                if (!usedRank[n] && (best < 0 || score[n] > score[best])) best = n;
            }
            usedRank[best] = true;
            if (rank > 0) out.append(" • ");
            out.append(best == 37 ? "СТРЕЛКИ" : String.valueOf(best));
            out.append(" (").append(String.format(Locale.US, "%.2f%%", score[best] * 100.0)).append(")");
        }
        double baseline = 100.0 / OUTCOMES;
        out.append("\nРавномерный ориентир для 37 исходов: ")
           .append(String.format(Locale.US, "%.2f%%", baseline)).append(" на исход.");
        if (total < 30) out.append("\nНадёжность оценки: данных очень мало.");
        else if (total < 100) out.append("\nНадёжность оценки: ограниченная; рейтинг может сильно меняться.");
        else out.append("\nНадёжность оценки: всё ещё ограниченная — длинная история не предсказывает независимый случайный исход.");
        out.append("\nПроценты — сглаженный рейтинг по введённой истории, не реальные шансы и не совет ставить.");
        return out.toString();
    }
}
