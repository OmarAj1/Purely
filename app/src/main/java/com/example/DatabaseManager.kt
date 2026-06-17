package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object DatabaseManager {

    private const val TAG = "DatabaseManager"
    private const val ASSETS_DB_DIR = "databases"

    val ALL_DATABASES = listOf(
        "AccessionNumber.db", "CompoundExternalDescriptor.db", "CompoundsFlavor.db",
        "CompoundsHealthEffect.db", "CompoundsPathway.db", "Enzyme.db", "Flavor.db",
        "Food.db", "FoodTaxonomy.db", "HealthEffect.db", "NcbiTaxonomyMap.db",
        "Nutrient.db", "OntologySynonym.db", "OntologyTerm.db", "Pathway.db",
        "food_additives.db", "Food_flavourings.db", "feed_additives_animal.db"
    )

    fun openDatabase(context: Context, dbName: String): SQLiteDatabase? {
        val dbFile = context.getDatabasePath(dbName)

        if (!dbFile.exists()) {
            dbFile.parentFile?.mkdirs()
            try {
                Log.d(TAG, "Copying database $dbName from assets...")
                context.assets.open("$ASSETS_DB_DIR/$dbName").use { inputStream ->
                    FileOutputStream(dbFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                Log.d(TAG, "Successfully copied $dbName")
            } catch (e: IOException) {
                Log.e(TAG, "Error copying database $dbName from assets", e)
                return null
            }
        }

        return try {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening database $dbName", e)
            null
        }
    }

    fun getTables(context: Context, dbName: String): List<String> {
        val tables = mutableListOf<String>()
        val db = openDatabase(context, dbName) ?: return tables
        try {
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null).use { cursor ->
                while (cursor.moveToNext()) {
                    tables.add(cursor.getString(0))
                }
            }
        } catch (e: Exception) {
             Log.e(TAG, "Error getting tables", e)
        } finally {
            db.close()
        }
        return tables
    }

    fun getTableData(context: Context, dbName: String, tableName: String, limit: Int = 100): Pair<List<String>, List<List<String>>> {
        val columns = mutableListOf<String>()
        val rows = mutableListOf<List<String>>()
        val db = openDatabase(context, dbName) ?: return Pair(columns, rows)
        
        try {
            db.rawQuery("SELECT * FROM $tableName LIMIT $limit", null).use { cursor ->
                for (i in 0 until cursor.columnCount) {
                    columns.add(cursor.getColumnName(i))
                }
                
                while (cursor.moveToNext()) {
                    val row = mutableListOf<String>()
                    for (i in 0 until cursor.columnCount) {
                        row.add(cursor.getString(i) ?: "NULL")
                    }
                    rows.add(row)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying table data", e)
        } finally {
            db.close()
        }
        return Pair(columns, rows)
    }

    fun searchAllDatabases(context: Context, query: String): List<com.example.data.ChemicalEntity> {
        val results = mutableListOf<com.example.data.ChemicalEntity>()
        if (query.isBlank() || query.length < 2) return results
        val q = "%$query%"

        // Search Food Additives
        openDatabase(context, "food_additives.db")?.use { db ->
            try {
                db.rawQuery("SELECT name, e_num, note FROM food_additives WHERE name LIKE ? OR e_num LIKE ? LIMIT 20", arrayOf(q, q)).use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(0) ?: ""
                        val eNum = cursor.getString(1) ?: ""
                        val note = cursor.getString(2) ?: ""
                        results.add(
                            com.example.data.ChemicalEntity(
                                name = name.lowercase(),
                                displayName = name,
                                plainEnglishName = "Food Additive ($eNum)",
                                purpose = note,
                                riskLevel = "UNKNOWN",
                                riskDescription = "Found in Food Additives Database",
                                dietarySafety = "vegan,gluten_free"
                            )
                        )
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "Search error in food_additives.db", e) }
        }

        // Search Food Flavourings
        openDatabase(context, "Food_flavourings.db")?.use { db ->
            try {
                db.rawQuery("SELECT chemical_name, fl_no FROM Food_flavourings WHERE chemical_name LIKE ? LIMIT 20", arrayOf(q)).use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(0) ?: ""
                        val flNo = cursor.getString(1) ?: ""
                        results.add(
                            com.example.data.ChemicalEntity(
                                name = name.lowercase(),
                                displayName = name,
                                plainEnglishName = "Food Flavouring (FL No: $flNo)",
                                purpose = "Used as a flavouring agent.",
                                riskLevel = "UNKNOWN",
                                riskDescription = "Found in Food Flavourings Database",
                                dietarySafety = "vegan,gluten_free"
                            )
                        )
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "Search error in Food_flavourings.db", e) }
        }

        // Search Feed Additives Animal
        openDatabase(context, "feed_additives_animal.db")?.use { db ->
            try {
                db.rawQuery("SELECT additive_name, code, catgrp FROM feed_additives_animal WHERE additive_name LIKE ? LIMIT 20", arrayOf(q)).use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(0) ?: ""
                        val code = cursor.getString(1) ?: ""
                        val cat = cursor.getString(2) ?: ""
                        results.add(
                            com.example.data.ChemicalEntity(
                                name = name.lowercase(),
                                displayName = name,
                                plainEnglishName = "Animal Feed Additive (Code: $code)",
                                purpose = cat,
                                riskLevel = "UNKNOWN",
                                riskDescription = "Found in Animal Feed Additives Database",
                                dietarySafety = "vegan,gluten_free"
                            )
                        )
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "Search error in feed_additives_animal.db", e) }
        }

        // Search FooDB: Food
        openDatabase(context, "Food.db")?.use { db ->
            try {
                // FooDB tables might have different schemas. Assuming 'foods' or 'food' table with 'name' or 'public_id'
                // Based on "Food" table name being "foods" or "food", let's try "foods" first.
                db.rawQuery("SELECT name FROM food WHERE name LIKE ? LIMIT 20", arrayOf(q)).use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(0) ?: ""
                        results.add(
                            com.example.data.ChemicalEntity(
                                name = name.lowercase(),
                                displayName = name,
                                plainEnglishName = "Food Item",
                                purpose = "Natural food component",
                                riskLevel = "LOW",
                                riskDescription = "Found in FooDB Database",
                                dietarySafety = "vegan,gluten_free"
                            )
                        )
                    }
                }
            } catch (e: Exception) { 
                try {
                     db.rawQuery("SELECT name FROM foods WHERE name LIKE ? LIMIT 20", arrayOf(q)).use { cursor ->
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(0) ?: ""
                            results.add(
                                com.example.data.ChemicalEntity(
                                    name = name.lowercase(),
                                    displayName = name,
                                    plainEnglishName = "Food Item",
                                    purpose = "Natural food component",
                                    riskLevel = "LOW",
                                    riskDescription = "Found in FooDB Database",
                                    dietarySafety = "vegan,gluten_free"
                                )
                            )
                        }
                    }
                } catch(e2: Exception) { Log.e(TAG, "Search error in Food.db", e2) }
            }
        }
        
        // Search FooDB: Flavor
        openDatabase(context, "Flavor.db")?.use { db ->
            try {
                db.rawQuery("SELECT name FROM flavor WHERE name LIKE ? LIMIT 20", arrayOf(q)).use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(0) ?: ""
                        results.add(
                            com.example.data.ChemicalEntity(
                                name = name.lowercase(),
                                displayName = name,
                                plainEnglishName = "Flavoring",
                                purpose = "Natural or artificial flavor",
                                riskLevel = "UNKNOWN",
                                riskDescription = "Found in FooDB Flavor Database",
                                dietarySafety = "vegan,gluten_free"
                            )
                        )
                    }
                }
            } catch (e: Exception) { 
                 try {
                     db.rawQuery("SELECT name FROM flavors WHERE name LIKE ? LIMIT 20", arrayOf(q)).use { cursor ->
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(0) ?: ""
                            results.add(
                                com.example.data.ChemicalEntity(
                                    name = name.lowercase(),
                                    displayName = name,
                                    plainEnglishName = "Flavoring",
                                    purpose = "Natural or artificial flavor",
                                    riskLevel = "UNKNOWN",
                                    riskDescription = "Found in FooDB Flavor Database",
                                    dietarySafety = "vegan,gluten_free"
                                )
                            )
                        }
                    }
                } catch(e2: Exception) { Log.e(TAG, "Search error in Flavor.db", e2) }
            }
        }

        return results
    }
}
