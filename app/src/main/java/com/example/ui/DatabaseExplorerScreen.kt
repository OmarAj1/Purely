package com.example.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.DatabaseManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatabaseExplorerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var selectedDb by remember { mutableStateOf<String?>(null) }
    var selectedTable by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            selectedTable != null -> "Table: $selectedTable"
                            selectedDb != null -> "DB: $selectedDb"
                            else -> "Database Explorer"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedTable != null) selectedTable = null
                        else if (selectedDb != null) selectedDb = null
                        else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            if (selectedDb == null) {
                // List Databases
                LazyColumn {
                    items(DatabaseManager.ALL_DATABASES) { dbName ->
                        ListItem(
                            headlineContent = { Text(dbName) },
                            modifier = Modifier.clickable { selectedDb = dbName }
                        )
                        HorizontalDivider()
                    }
                }
            } else if (selectedTable == null) {
                // List Tables in DB
                var tables by remember { mutableStateOf<List<String>>(emptyList()) }
                LaunchedEffect(selectedDb) {
                    tables = DatabaseManager.getTables(context, selectedDb!!)
                }
                LazyColumn {
                    items(tables) { tableName ->
                        ListItem(
                            headlineContent = { Text(tableName) },
                            modifier = Modifier.clickable { selectedTable = tableName }
                        )
                        HorizontalDivider()
                    }
                }
            } else {
                // List Data in Table
                var tableData by remember { mutableStateOf<Pair<List<String>, List<List<String>>>>(Pair(emptyList(), emptyList())) }
                LaunchedEffect(selectedTable) {
                    tableData = DatabaseManager.getTableData(context, selectedDb!!, selectedTable!!, 50)
                }

                val (columns, rows) = tableData

                if (columns.isEmpty()) {
                    Text("Loading or Empty...", modifier = Modifier.padding(16.dp))
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(8.dp)) {
                            columns.forEach { col ->
                                Text(
                                    text = col,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(120.dp).padding(4.dp)
                                )
                            }
                        }
                        HorizontalDivider(thickness = 2.dp)
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(rows) { row ->
                                Row(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(8.dp)) {
                                    row.forEach { cell ->
                                        Text(
                                            text = cell,
                                            modifier = Modifier.width(120.dp).padding(4.dp)
                                        )
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}
