package com.example.smartcrutch.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SensorDatabaseHelper(context: Context, patientId: String, directory: java.io.File? = null) : 
    SQLiteOpenHelper(context, directory?.let { java.io.File(it, "${patientId}.db").absolutePath } ?: "${patientId}.db", null, 2) {

    companion object {
        // Layer 2: Days
        const val TABLE_DAYS = "days"
        const val COLUMN_DAY_ID = "id"
        const val COLUMN_DATE = "date" // YYYY-MM-DD

        // Layer 3: Sessions
        const val TABLE_SESSIONS = "sessions"
        const val COLUMN_SESSION_ID = "id"
        const val COLUMN_SESS_DAY_ID = "day_id"
        const val COLUMN_SESS_INDEX = "session_index"
        const val COLUMN_START_TIME = "start_timestamp"
        const val COLUMN_GAIT = "gait"
        const val COLUMN_TERRAIN = "terrain"

        // Layer 4: Readings
        const val TABLE_READINGS = "readings"
        const val COLUMN_READ_ID = "id"
        const val COLUMN_READ_SESS_ID = "session_id"
        const val COLUMN_TIMESTAMP = "timestamp"
        const val COLUMN_AX = "ax"
        const val COLUMN_AY = "ay"
        const val COLUMN_AZ = "az"
        const val COLUMN_GX = "gx"
        const val COLUMN_GY = "gy"
        const val COLUMN_GZ = "gz"
        const val COLUMN_MX = "mx"
        const val COLUMN_MY = "my"
        const val COLUMN_MZ = "mz"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_DAYS (
                $COLUMN_DAY_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_DATE TEXT UNIQUE
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE $TABLE_SESSIONS (
                $COLUMN_SESSION_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_SESS_DAY_ID INTEGER,
                $COLUMN_SESS_INDEX INTEGER,
                $COLUMN_START_TIME TEXT,
                $COLUMN_GAIT TEXT,
                $COLUMN_TERRAIN TEXT,
                FOREIGN KEY($COLUMN_SESS_DAY_ID) REFERENCES $TABLE_DAYS($COLUMN_DAY_ID)
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE $TABLE_READINGS (
                $COLUMN_READ_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_READ_SESS_ID INTEGER,
                $COLUMN_TIMESTAMP REAL,
                $COLUMN_AX REAL,
                $COLUMN_AY REAL,
                $COLUMN_AZ REAL,
                $COLUMN_GX REAL,
                $COLUMN_GY REAL,
                $COLUMN_GZ REAL,
                $COLUMN_MX REAL,
                $COLUMN_MY REAL,
                $COLUMN_MZ REAL,
                FOREIGN KEY($COLUMN_READ_SESS_ID) REFERENCES $TABLE_SESSIONS($COLUMN_SESSION_ID)
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_READINGS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SESSIONS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_DAYS")
        onCreate(db)
    }

    fun getOrInsertDay(date: String): Long {
        val db = this.writableDatabase
        val cursor = db.query(TABLE_DAYS, arrayOf(COLUMN_DAY_ID), "$COLUMN_DATE = ?", arrayOf(date), null, null, null)
        
        if (cursor.moveToFirst()) {
            val id = cursor.getLong(0)
            cursor.close()
            return id
        }
        cursor.close()

        val values = ContentValues().apply { put(COLUMN_DATE, date) }
        return db.insert(TABLE_DAYS, null, values)
    }

    fun getNextSessionIndex(dayId: Long): Int {
        val db = this.readableDatabase
        val query = "SELECT MAX($COLUMN_SESS_INDEX) FROM $TABLE_SESSIONS WHERE $COLUMN_SESS_DAY_ID = ?"
        val cursor = db.rawQuery(query, arrayOf(dayId.toString()))
        var nextIndex = 0
        if (cursor.moveToFirst()) {
            nextIndex = cursor.getInt(0) + 1
        }
        cursor.close()
        return nextIndex
    }

    fun insertSession(values: ContentValues): Long {
        val db = this.writableDatabase
        return db.insert(TABLE_SESSIONS, null, values)
    }

    fun insertReading(values: ContentValues) {
        val db = this.writableDatabase
        db.insert(TABLE_READINGS, null, values)
    }
}
