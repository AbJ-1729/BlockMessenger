package com.example.abj_chat

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class MessageDbHelper(ctx: Context) : SQLiteOpenHelper(ctx, "mesh_messages.db", null, 1) {
    companion object {
        const val TABLE = "messages"
        const val COL_ID = "msg_id"
        const val COL_SENDER = "sender"
        const val COL_RECEIVER = "receiver"
        const val COL_FLAG = "flag"
        const val COL_PAYLOAD = "payload"          // raw (cipher or plain)
        const val COL_DECRYPTED = "decrypted"      // nullable
        const val COL_TIMESTAMP = "ts"
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE (" +
                    "$COL_ID TEXT PRIMARY KEY," +
                    "$COL_SENDER TEXT NOT NULL," +
                    "$COL_RECEIVER TEXT NOT NULL," +
                    "$COL_FLAG TEXT NOT NULL," +
                    "$COL_PAYLOAD TEXT NOT NULL," +
                    "$COL_DECRYPTED TEXT," +
                    "$COL_TIMESTAMP INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_ts ON $TABLE($COL_TIMESTAMP)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }
}