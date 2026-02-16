package com.example.ft_file_manager

data class DashboardItem(
    val id: Int, // 1: Internal, 2: SD, 3: Root, 4: FTP
    val title: String,
    val path: String?,
    val iconRes: Int,
    var totalSpace: String = "",
    var usedSpace: String = "",
    var percentage: Int = 0
)