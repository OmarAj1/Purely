package com.example.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.io.FileOutputStream
import android.util.Log

class FoodDatabaseHelper(private val context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "food_database1.db"
        const val DB_VERSION = 1
        private const val TAG = "FoodDatabaseHelper"
    }

    private fun checkAndCopyDatabase() {
        val dbPath = context.getDatabasePath(DB_NAME)
        if (!dbPath.exists()) {
            dbPath.parentFile?.mkdirs()
            try {
                context.assets.open("databases/$DB_NAME").use { inputStream ->
                    FileOutputStream(dbPath).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                Log.d(TAG, "Successfully copied database from assets.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy database: ${e.message}", e)
            }
        }
    }

    override fun getReadableDatabase(): SQLiteDatabase {
        checkAndCopyDatabase()
        return super.getReadableDatabase()
    }

    override fun onCreate(db: SQLiteDatabase?) {
        // Handled by copy
    }

    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
        // Handled by copy if needed later
    }

    fun getNutrientsForFood(foodName: String): String? {
        val db = getReadableDatabase()
        var result: String? = null
        try {
            db.rawQuery("SELECT nutrients FROM foods WHERE name LIKE ? LIMIT 1", arrayOf("%$foodName%")).use { cursor ->
                if (cursor.moveToFirst()) {
                    result = cursor.getString(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying database for $foodName: ${e.message}")
        }
        return result
    }
}
