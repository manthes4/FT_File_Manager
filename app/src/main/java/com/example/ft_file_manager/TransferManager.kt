package com.example.ft_file_manager

object TransferManager {
    var filesToMove: List<FileModel> = emptyList()
    var isCut: Boolean = false
    var sourceIsSmb: Boolean = false // true αν έρχονται από CoreELEC, false από Κινητό

    // Στοιχεία σύνδεσης για να μπορούμε να τραβήξουμε δεδομένα από το παρασκήνιο
    var smbHost: String = ""
    var smbUser: String = ""
    var smbPass: String = ""
}