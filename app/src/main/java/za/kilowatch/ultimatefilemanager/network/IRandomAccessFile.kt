package za.kilowatch.ultimatefilemanager.network

interface IRandomAccessFile {
    val size: Long
    fun read(offset: Long, buffer: ByteArray, length: Int): Int
    fun write(offset: Long, buffer: ByteArray, length: Int): Int
    fun close()
}
