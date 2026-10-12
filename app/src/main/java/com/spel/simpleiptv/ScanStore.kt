package com.spel.simpleiptv

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ScanStore(context: Context) : SQLiteOpenHelper(context, "simpleiptv.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS scans (url TEXT PRIMARY KEY, status TEXT NOT NULL, checked_at INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    @Synchronized fun save(url: String, status: ChannelScanStatus) {
        val values = android.content.ContentValues().apply {
            put("url", url)
            put("status", status.name)
            put("checked_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("scans", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun load(maxAgeMillis: Long = 7L * 24 * 60 * 60 * 1000): Map<String, ChannelScanStatus> {
        val results = HashMap<String, ChannelScanStatus>()
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        readableDatabase.rawQuery("SELECT url,status FROM scans WHERE checked_at >= ?", arrayOf(cutoff.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                val state = runCatching { ChannelScanStatus.valueOf(cursor.getString(1)) }.getOrNull()
                if (state != null) results[cursor.getString(0)] = state
            }
        }
        return results
    }
    @Synchronized fun exportRows(): List<Pair<String, Pair<ChannelScanStatus, Long>>> {
        val rows = ArrayList<Pair<String, Pair<ChannelScanStatus, Long>>>()
        readableDatabase.rawQuery("SELECT url,status,checked_at FROM scans", null).use { cursor ->
            while (cursor.moveToNext()) {
                val status = runCatching { ChannelScanStatus.valueOf(cursor.getString(1)) }.getOrNull()
                if (status != null) rows.add(cursor.getString(0) to (status to cursor.getLong(2)))
            }
        }
        return rows
    }

    @Synchronized fun replaceRows(rows: List<Triple<String, ChannelScanStatus, Long>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("scans", null, null)
            rows.forEach { (url, status, checkedAt) ->
                val values = android.content.ContentValues().apply {
                    put("url", url)
                    put("status", status.name)
                    put("checked_at", checkedAt)
                }
                db.insertWithOnConflict("scans", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized fun clear() { writableDatabase.delete("scans", null, null) }
}
