package com.ringoai.sixone;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** Durable, unbounded round history stored in the app's private SQLite database. */
public final class RingoStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "ringo_observer.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "rounds";

    public RingoStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
        migrateLegacy(context.getApplicationContext());
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "recorded_at INTEGER NOT NULL," +
            "outcome INTEGER NOT NULL," +
            "coefficient TEXT NOT NULL DEFAULT '-',"+
            "board_csv TEXT NOT NULL DEFAULT ''" +
            ")");
        db.execSQL("CREATE INDEX idx_rounds_recorded_at ON " + TABLE + "(recorded_at)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    private synchronized void migrateLegacy(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("ringo70", Context.MODE_PRIVATE);
        if (prefs.getBoolean("ringo_sqlite_migrated", false)) return;
        SQLiteDatabase db = getWritableDatabase();
        if (DatabaseUtils.queryNumEntries(db, TABLE) == 0) {
            String rawNumbers = prefs.getString("history", "");
            String rawCoefficients = prefs.getString("historyCoefficients", "");
            String[] numbers = rawNumbers.isEmpty() ? new String[0] : rawNumbers.split(",");
            String[] coefficients = rawCoefficients.isEmpty() ? new String[0] : rawCoefficients.split(",", -1);
            db.beginTransaction();
            try {
                long now = System.currentTimeMillis();
                for (int i = numbers.length - 1; i >= 0; i--) {
                    try {
                        int outcome = Integer.parseInt(numbers[i].trim());
                        if (outcome < 1 || outcome > 37) continue;
                        String coefficient = i < coefficients.length ? coefficients[i].trim() : "-";
                        insertRow(db, outcome, coefficient, "", now - (numbers.length - i) * 1000L);
                    } catch (Exception ignored) { }
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
        prefs.edit().putBoolean("ringo_sqlite_migrated", true).apply();
    }

    private long insertRow(SQLiteDatabase db, int outcome, String coefficient, String boardCsv, long time) {
        ContentValues values = new ContentValues();
        values.put("recorded_at", time);
        values.put("outcome", outcome);
        values.put("coefficient", coefficient == null || coefficient.trim().isEmpty() ? "-" : coefficient.trim());
        values.put("board_csv", boardCsv == null ? "" : boardCsv);
        return db.insertOrThrow(TABLE, null, values);
    }

    public synchronized void seedFromVisibleStrip(List<Integer> outcomes, List<String> coefficients) {
        if (outcomes == null || outcomes.isEmpty() || getRoundCount() > 0) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long now = System.currentTimeMillis();
            for (int i = outcomes.size() - 1; i >= 0; i--) {
                int n = outcomes.get(i);
                if (n < 1 || n > 37) continue;
                String c = coefficients != null && i < coefficients.size() ? coefficients.get(i) : "-";
                insertRow(db, n, c, "", now - (outcomes.size() - i) * 1000L);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void addRound(int outcome, String coefficient, String boardCsv) {
        if (outcome < 1 || outcome > 37) return;
        insertRow(getWritableDatabase(), outcome, coefficient, boardCsv, System.currentTimeMillis());
    }

    public synchronized ArrayList<Integer> readHistory(int limit) {
        ArrayList<Integer> result = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE, new String[]{"outcome"}, null, null, null, null,
            "id DESC", limit > 0 ? String.valueOf(limit) : null);
        try { while (c.moveToNext()) result.add(c.getInt(0)); }
        finally { c.close(); }
        return result;
    }

    public synchronized ArrayList<String> readCoefficients(int limit) {
        ArrayList<String> result = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE, new String[]{"coefficient"}, null, null, null, null,
            "id DESC", limit > 0 ? String.valueOf(limit) : null);
        try { while (c.moveToNext()) result.add(c.getString(0)); }
        finally { c.close(); }
        return result;
    }

    public synchronized String readBoardSamplesCsv(int limit) {
        StringBuilder out = new StringBuilder();
        Cursor c = getReadableDatabase().query(TABLE,
            new String[]{"recorded_at", "outcome", "board_csv"},
            "board_csv <> ''", null, null, null, "id DESC",
            limit > 0 ? String.valueOf(limit) : null);
        try {
            while (c.moveToNext()) {
                if (out.length() > 0) out.append('\n');
                out.append(c.getLong(0)).append('|')
                   .append(c.getInt(1)).append('|')
                   .append(c.getString(2));
            }
        } finally { c.close(); }
        return out.toString();
    }

    public synchronized long getBoardSampleCount() {
        return DatabaseUtils.longForQuery(getReadableDatabase(),
            "SELECT COUNT(*) FROM " + TABLE + " WHERE board_csv <> ''", null);
    }

    public synchronized long getRoundCount() {
        return DatabaseUtils.queryNumEntries(getReadableDatabase(), TABLE);
    }
}
