package com.example.ft_file_manager

object TransferManager {
    var filesToMove: List<FileModel> = emptyList()
    var isCut: Boolean = false

    // Πηγές (Προσθέσαμε το SFTP flag)
    var sourceIsSmb: Boolean = false
    var sourceIsSftp: Boolean = false

    // Στοιχεία σύνδεσης SMB
    var smbHost: String = ""
    var smbUser: String = ""
    var smbPass: String = ""

    // Στοιχεία σύνδεσης SFTP (Προσθήκη αυτών των πεδίων)
    var sftpHost: String = ""
    var sftpUser: String = ""
    var sftpPass: String = ""
    var sftpPort: Int = 22
}