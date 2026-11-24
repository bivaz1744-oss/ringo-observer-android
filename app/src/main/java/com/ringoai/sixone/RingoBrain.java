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

    public static String buildRecommendation(List<Integer> newestFirst, String currentBoardCsv, String samplesRaw) {
        String base = buildRecommendation(newestFirst);
        double[] current = parseBoardVector(currentBoardCsv);
        int currentCount = countValid(current);
        if (currentCount < 18) return base + "\nПоле: пока не считано 18+ ячеек из 36.";
        java.util.ArrayList<double[]> vectors = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> outcomes = new java.util.ArrayList<>();
        if (samplesRaw != null && !samplesRaw.isEmpty()) {
            for (String row : samplesRaw.split("\\n")) {
                String[] parts = row.split("\\|", 3);
                if (parts.length != 3) continue;
                int outcome;
                try { outcome = Integer.parseInt(parts[1]); } catch (Exception e) { continue; }
                if (outcome < 1 || outcome > OUTCOMES) continue;
                double[] v = parseBoardVector(parts[2]);
                if (countValid(v) >= 18) { vectors.add(normalizeBoard(v)); outcomes.add(outcome); }
            }
        }
        if (vectors.size() < 12) {
            return base + "\nАнализ 36 ячеек: считано " + currentCount + "/36. Связанных снимков с исходами: " +
                vectors.size() + "/12 — модель пока накапливает наблюдения.";
        }
        double[] normalizedCurrent = normalizeBoard(current);
        boolean[] used = new boolean[vectors.size()];
        double[] votes = new double[OUTCOMES + 1];
        int k = Math.min(30, vectors.size());
        double voteTotal = 0.0;
        for (int rank = 0; rank < k; rank++) {
            int best = -1; double bestDistance = Double.MAX_VALUE;
            for (int i = 0; i < vectors.size(); i++) {
                if (used[i]) continue;
                double distance = boardDistance(normalizedCurrent, vectors.get(i));
                if (distance < bestDistance) { bestDistance = distance; best = i; }
            }
            if (best < 0) break;
            used[best] = true;
            double weight = 1.0 / (0.01 + bestDistance);
            votes[outcomes.get(best)] += weight;
            voteTotal += weight;
        }
        int winner = 1;
        for (int n = 2; n <= OUTCOMES; n++) if (votes[n] > votes[winner]) winner = n;
        double share = voteTotal == 0 ? 0 : votes[winner] / voteTotal * 100.0;
        String label = winner == 37 ? "СТРЕЛКИ" : String.valueOf(winner);
        return "МОЯ ПОДСКАЗКА ПО 36 ЯЧЕЙКАМ: " + label +
            "\nСходные снимки: " + k + " из " + vectors.size() +
            "\nДоля этого исхода среди ближайших снимков: " + String.format(Locale.US, "%.1f%%", share) +
            "\nЭто экспериментальное сходство с прошлой историей, не вероятность и не гарантия. " +
            "Если исход независим и случаен, такая статистика не даёт надёжного преимущества.";
    }

    private static double[] parseBoardVector(String csv) {
        double[] out = new double[36];
        java.util.Arrays.fill(out, Double.NaN);
        if (csv == null) return out;
        String[] parts = csv.split(",", -1);
        for (int i = 0; i < Math.min(36, parts.length); i++) {
            try { out[i] = Double.parseDouble(parts[i].trim().replace(',', '.')); }
            catch (Exception ignored) { }
        }
        return out;
    }

    private static int countValid(double[] values) {
        int n = 0;
        for (double v : values) if (!Double.isNaN(v) && !Double.isInfinite(v) && v > 0) n++;
        return n;
    }

    private static double[] normalizeBoard(double[] values) {
        double sum = 0; int n = 0;
        for (double v : values) if (!Double.isNaN(v) && !Double.isInfinite(v) && v > 0) { sum += v; n++; }
        double mean = n == 0 ? 1 : sum / n;
        double[] out = new double[36];
        for (int i = 0; i < 36; i++) out[i] = (!Double.isNaN(values[i]) && values[i] > 0) ? values[i] / mean : Double.NaN;
        return out;
    }

    private static double boardDistance(double[] a, double[] b) {
        double sum = 0; int n = 0;
        for (int i = 0; i < 36; i++) if (!Double.isNaN(a[i]) && !Double.isNaN(b[i])) {
            sum += Math.abs(a[i] - b[i]); n++;
        }
        return n < 18 ? 10.0 : sum / n;
    }

    public static String buildRecommendation(List<Integer> newestFirst) {
        int total = newestFirst == null ? 0 : newestFirst.size();
        if (total == 0) return "МОЯ ПОДСКАЗКА: собираю историю игры…";
        double[] counts = new double[OUTCOMES + 1];
        double weightSum = 0.0;
        int used = Math.min(total, 150);
        for (int i = 0; i < used; i++) {
            int n = newestFirst.get(i);
            if (n < 1 || n > OUTCOMES) continue;
            double w = Math.exp(-i / 45.0);
            counts[n] += w; weightSum += w;
        }
        double[] transitions = new double[OUTCOMES + 1];
        int seen = 0; int latest = newestFirst.get(0);
        for (int i = 1; i < total; i++) if (newestFirst.get(i) == latest) {
            int next = newestFirst.get(i - 1);
            if (next >= 1 && next <= OUTCOMES) { transitions[next]++; seen++; }
        }
        int best = 1; double bestScore = -1.0;
        double base = weightSum + OUTCOMES * 0.5;
        double transitionWeight = 0.15 * seen / (seen + 30.0);
        for (int n = 1; n <= OUTCOMES; n++) {
            double historical = (counts[n] + 0.5) / base;
            double conditional = (transitions[n] + 8.0 / OUTCOMES) / (seen + 8.0);
            double score = (1.0 - transitionWeight) * historical + transitionWeight * conditional;
            if (score > bestScore) { bestScore = score; best = n; }
        }
        String choice = best == 37 ? "СТРЕЛКИ" : String.valueOf(best);
        double baseline = 100.0 / OUTCOMES;
        String signal = bestScore > (baseline / 100.0) * 1.15 ? "слабое отличие от среднего" : "выраженного преимущества не видно";
        return "МОЯ ПОДСКАЗКА: " + choice + "\nИстория: " + total +
            " • оценка модели: " + String.format(Locale.US, "%.2f%%", bestScore * 100.0) +
            "\n" + signal + ". Не гарантия результата.";
    }
}
