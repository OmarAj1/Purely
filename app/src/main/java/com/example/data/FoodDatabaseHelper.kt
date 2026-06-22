package com.example.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.io.FileOutputStream
import android.util.Log

class FoodDatabaseHelper(private val context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "MasterUnifiedDB.db"
        const val DB_VERSION = 1
        private const val TAG = "FoodDatabaseHelper"
    }

    private fun checkAndCopyDatabase() {
        val dbPath = context.getDatabasePath(DB_NAME)
        val prefs = context.getSharedPreferences("database_prefs", Context.MODE_PRIVATE)
        val copiedVersion = prefs.getInt("db_version", 0)

        if (!dbPath.exists() || copiedVersion < DB_VERSION) {
            if (dbPath.exists()) {
                dbPath.delete()
            }
            dbPath.parentFile?.mkdirs()
            try {
                context.assets.open("databases/$DB_NAME").use { inputStream ->
                    FileOutputStream(dbPath).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                prefs.edit().putInt("db_version", DB_VERSION).apply()
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

    fun getIngredientDetails(foodName: String): Result<ChemicalEntity?> {
        val queryWord = foodName.trim().lowercase()
        var lastException: Exception? = null
        for (attempt in 1..3) {
            try {
                val db = getReadableDatabase()
                db.rawQuery(
                    "SELECT name, description, category, dietary_safety, purpose, health_risks, risk_level, dietary_info, plain_english_name FROM UnifiedIngredients WHERE name LIKE ? OR ? LIKE '%' || name || '%' ORDER BY length(name) DESC LIMIT 1",
                    arrayOf("%$queryWord%", queryWord)
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        return Result.success(ChemicalEntity(
                            name = cursor.getString(0) ?: foodName,
                            displayName = (cursor.getString(8) ?: cursor.getString(0) ?: foodName).replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
                            plainEnglishName = cursor.getString(8) ?: "Food Component",
                            purpose = cursor.getString(4) ?: cursor.getString(1) ?: "Analyzed nutrient profile or food component.",
                            riskLevel = cursor.getString(6) ?: "LOW",
                            riskDescription = cursor.getString(5) ?: "Validated in local nutrition database.",
                            dietarySafety = cursor.getString(3) ?: cursor.getString(7) ?: ""
                        ))
                    }
                    return Result.success(null)
                }
            } catch (e: android.database.sqlite.SQLiteDatabaseLockedException) {
                Log.w(TAG, "Database locked, retrying attempt $attempt")
                lastException = e
                Thread.sleep(100L * attempt)
            } catch (e: Exception) {
                Log.e(TAG, "Error querying database for $foodName: ${e.message}")
                return Result.failure(e)
            }
        }
        return Result.failure(lastException ?: Exception("Database locked after 3 retries"))
    }

    fun searchFoods(query: String): List<ChemicalEntity> {
        val db = getReadableDatabase()
        val results = mutableListOf<ChemicalEntity>()
        try {
            db.rawQuery("SELECT name, description, category, dietary_safety, purpose, health_risks, risk_level, dietary_info, plain_english_name FROM UnifiedIngredients WHERE name LIKE ? LIMIT 50", arrayOf("%$query%")).use { cursor ->
                while (cursor.moveToNext()) {
                    val chem = ChemicalEntity(
                        name = cursor.getString(0) ?: query,
                        displayName = (cursor.getString(8) ?: cursor.getString(0) ?: query).replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
                        plainEnglishName = cursor.getString(8) ?: "Food Component",
                        purpose = cursor.getString(4) ?: cursor.getString(1) ?: "Analyzed nutrient profile or food component.",
                        riskLevel = cursor.getString(6) ?: "LOW",
                        riskDescription = cursor.getString(5) ?: "Validated in local nutrition database.",
                        dietarySafety = cursor.getString(3) ?: cursor.getString(7) ?: ""
                    )
                    results.add(chem)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching database for $query: ${e.message}")
        }
        return results
    }
}
