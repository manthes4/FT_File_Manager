package com.example.ft_file_manager

data class FileModel(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    var size: CharSequence,
    val isRoot: Boolean = false,
    var isSelected: Boolean = false // <--- Νέα προσθήκη
)