package com.example.maxassistant

import android.content.Context
import android.content.SharedPreferences

class DataManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("max_data", Context.MODE_PRIVATE)

    fun addNote(note: String) {
        val notes = getNotes().toMutableList()
        notes.add(note)
        prefs.edit().putStringSet("notes", notes.toSet()).apply()
    }

    fun getNotes(): List<String> {
        return prefs.getStringSet("notes", emptySet())?.toList() ?: emptyList()
    }

    fun clearNotes() {
        prefs.edit().remove("notes").apply()
    }

    fun addTask(task: String) {
        val tasks = getTasks().toMutableList()
        tasks.add(task)
        prefs.edit().putStringSet("tasks", tasks.toSet()).apply()
    }

    fun getTasks(): List<String> {
        return prefs.getStringSet("tasks", emptySet())?.toList() ?: emptyList()
    }

    fun clearTasks() {
        prefs.edit().remove("tasks").apply()
    }

    fun addShoppingItem(item: String) {
        val items = getShoppingList().toMutableList()
        items.add(item)
        prefs.edit().putStringSet("shopping", items.toSet()).apply()
    }

    fun getShoppingList(): List<String> {
        return prefs.getStringSet("shopping", emptySet())?.toList() ?: emptyList()
    }

    fun clearShoppingList() {
        prefs.edit().remove("shopping").apply()
    }
}
