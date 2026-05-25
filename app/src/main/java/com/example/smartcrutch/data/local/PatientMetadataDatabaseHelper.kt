package com.example.smartcrutch.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class PatientMetadataDatabaseHelper(context: Context, directory: java.io.File? = null) : 
    SQLiteOpenHelper(context, directory?.let { java.io.File(it, "PatientMetadata.db").absolutePath } ?: "PatientMetadata.db", null, 2) {

    companion object {
        const val TABLE_METADATA = "patient_metadata"
        const val COLUMN_ID = "id"
        const val COLUMN_PATIENT_ID = "patient_id"
        const val COLUMN_NAME = "name"
        const val COLUMN_AGE = "age"
        const val COLUMN_GENDER = "gender"
        const val COLUMN_BODY_WEIGHT = "body_weight"
        const val COLUMN_INJURED_LEG = "injured_leg"
        const val COLUMN_TIMESTAMP = "record_timestamp"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = """
            CREATE TABLE $TABLE_METADATA (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_PATIENT_ID TEXT,
                $COLUMN_NAME TEXT,
                $COLUMN_AGE INTEGER,
                $COLUMN_GENDER TEXT,
                $COLUMN_BODY_WEIGHT REAL,
                $COLUMN_INJURED_LEG TEXT,
                $COLUMN_TIMESTAMP TEXT
            )
        """.trimIndent()
        db.execSQL(createTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_METADATA")
        onCreate(db)
    }

    fun insertMetadata(values: ContentValues) {
        val db = this.writableDatabase
        db.insert(TABLE_METADATA, null, values)
    }
}
