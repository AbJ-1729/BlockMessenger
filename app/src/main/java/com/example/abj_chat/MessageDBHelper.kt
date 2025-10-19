package com.example.abj_chat

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class MessageDbHelper(context: Context) : SQLiteOpenHelper(context, "messages_v2.db", null, 1) {
    companion object {
        const val TABLE_NAME = "messages"
        const val COL_SENDER = "sender"
        const val COL_RECEIVER = "receiver"
        const val COL_PAYLOAD = "payload"
        const val COL_TIMESTAMP = "timestamp"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE_NAME (" +
                    "$COL_SENDER TEXT NOT NULL, " +
                    "$COL_RECEIVER TEXT NOT NULL, " +
                    "$COL_PAYLOAD TEXT NOT NULL, " +
                    "$COL_TIMESTAMP INTEGER NOT NULL, " +
                    "PRIMARY KEY ($COL_SENDER, $COL_RECEIVER, $COL_TIMESTAMP))"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
    }
}