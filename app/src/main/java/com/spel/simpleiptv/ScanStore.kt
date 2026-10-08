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
            put("url", url); put("status", status.name); put("checked_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("scans", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun load(): Map<String, ChannelScanStatus> {
        val results = HashMap<String, ChannelScanStatus>()
        readableDatabase.rawQuery("SELECT url,status FROM scans", null).use { cursor ->
            while (cursor.moveToNext()) {
                val state = runCatching { ChannelScanStatus.valueOf(cursor.getString(1)) }.getOrNull()
                if (state != null) results[cursor.getString(0)] = state
            }
        }
        return results
    }
    @Synchronized fun clear() { writableDatabase.delete("scans", null, null) }
}
