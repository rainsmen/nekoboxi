package io.nekohasekai.sagernet.database.preference

import android.os.Parcel
import android.os.Parcelable
import androidx.room.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

@Entity
class KeyValuePair() : Parcelable {
    companion object {
        const val TYPE_UNINITIALIZED = 0
        const val TYPE_BOOLEAN = 1
        const val TYPE_FLOAT = 2

        @Deprecated("Use TYPE_LONG.")
        const val TYPE_INT = 3
        const val TYPE_LONG = 4
        const val TYPE_STRING = 5
        const val TYPE_STRING_SET = 6 // legacy lengths count UTF-16 characters
        const val TYPE_STRING_SET_UTF8 = 7

        @JvmField
        val CREATOR = object : Parcelable.Creator<KeyValuePair> {
            override fun createFromParcel(parcel: Parcel): KeyValuePair {
                return KeyValuePair(parcel)
            }

            override fun newArray(size: Int): Array<KeyValuePair?> {
                return arrayOfNulls(size)
            }
        }
    }

    @androidx.room.Dao
    interface Dao {

        @Query("SELECT * FROM `KeyValuePair`")
        fun all(): List<KeyValuePair>

        @Query("SELECT * FROM `KeyValuePair` WHERE `key` = :key")
        operator fun get(key: String): KeyValuePair?

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        fun put(value: KeyValuePair): Long

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        fun putAll(values: List<KeyValuePair>)

        @Query("SELECT * FROM `KeyValuePair` WHERE `key` IN (:keys)")
        fun getAll(keys: List<String>): List<KeyValuePair>

        @Query("DELETE FROM `KeyValuePair` WHERE `key` = :key")
        fun delete(key: String): Int

        @Query("DELETE FROM `KeyValuePair`")
        fun reset(): Int

        @Insert
        fun insert(list: List<KeyValuePair>)
    }

    @PrimaryKey
    var key: String = ""
    var valueType: Int = TYPE_UNINITIALIZED
    var value: ByteArray = ByteArray(0)

    val boolean: Boolean?
        get() = if (valueType == TYPE_BOOLEAN) ByteBuffer.wrap(value).get() != 0.toByte() else null
    val float: Float?
        get() = if (valueType == TYPE_FLOAT) ByteBuffer.wrap(value).float else null

    @Suppress("DEPRECATION")
    @Deprecated("Use long.", ReplaceWith("long"))
    val int: Int?
        get() = if (valueType == TYPE_INT) ByteBuffer.wrap(value).int else null
    val long: Long?
        get() = when (valueType) {
            @Suppress("DEPRECATION") TYPE_INT,
            -> ByteBuffer.wrap(value).int.toLong()
            TYPE_LONG -> ByteBuffer.wrap(value).long
            else -> null
        }
    val string: String?
        get() = if (valueType == TYPE_STRING) String(value) else null
    private fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    val stringSet: Set<String>?
        get() = if (valueType == TYPE_STRING_SET || valueType == TYPE_STRING_SET_UTF8) {
            val buffer = ByteBuffer.wrap(value)
            buildSet {
                while (buffer.hasRemaining()) {
                    require(buffer.remaining() >= 4) { "Truncated string-set length: $key" }
                    val length = buffer.int
                    require(length >= 0 && length <= buffer.remaining()) { "Invalid string-set length: $key" }
                    if (valueType == TYPE_STRING_SET_UTF8) {
                        add(decodeUtf8(ByteArray(length).also(buffer::get)))
                    } else {
                        // Old writers stored String.length but wrote UTF-8. Read that format explicitly.
                        val text = StringBuilder()
                        while (text.length < length) {
                            require(buffer.hasRemaining()) { "Truncated legacy string set: $key" }
                            val first = buffer.get().toInt() and 0xff
                            val size = when (first) {
                                in 0..0x7f -> 1
                                in 0xc2..0xdf -> 2
                                in 0xe0..0xef -> 3
                                in 0xf0..0xf4 -> 4
                                else -> error("Invalid UTF-8 in setting: $key")
                            }
                            require(buffer.remaining() >= size - 1) { "Truncated UTF-8 in setting: $key" }
                            val bytes = ByteArray(size)
                            bytes[0] = first.toByte()
                            buffer.get(bytes, 1, size - 1)
                            text.append(decodeUtf8(bytes))
                        }
                        require(text.length == length) { "Invalid legacy string-set length: $key" }
                        add(text.toString())
                    }
                }
            }
        } else null

    /** Validate storage before committing a restore; getters are not a format validator. */
    @Suppress("DEPRECATION")
    fun validateValue() {
        when (valueType) {
            TYPE_BOOLEAN -> require(value.size == 1 && value[0] in byteArrayOf(0, 1)) { "Invalid boolean setting: $key" }
            TYPE_INT, TYPE_FLOAT -> require(value.size == 4) { "Invalid numeric setting: $key" }
            TYPE_LONG -> require(value.size == 8) { "Invalid long setting: $key" }
            TYPE_STRING -> decodeUtf8(value)
            TYPE_STRING_SET, TYPE_STRING_SET_UTF8 -> stringSet
            else -> error("Unsupported setting type $valueType: $key")
        }
    }

    @Ignore
    constructor(key: String) : this() {
        this.key = key
    }

    // putting null requires using DataStore
    fun put(value: Boolean): KeyValuePair {
        valueType = TYPE_BOOLEAN
        this.value = ByteBuffer.allocate(1).put((if (value) 1 else 0).toByte()).array()
        return this
    }

    fun put(value: Float): KeyValuePair {
        valueType = TYPE_FLOAT
        this.value = ByteBuffer.allocate(4).putFloat(value).array()
        return this
    }

    @Suppress("DEPRECATION")
    @Deprecated("Use long.")
    fun put(value: Int): KeyValuePair {
        valueType = TYPE_INT
        this.value = ByteBuffer.allocate(4).putInt(value).array()
        return this
    }

    fun put(value: Long): KeyValuePair {
        valueType = TYPE_LONG
        this.value = ByteBuffer.allocate(8).putLong(value).array()
        return this
    }

    fun put(value: String): KeyValuePair {
        valueType = TYPE_STRING
        this.value = value.toByteArray()
        return this
    }

    fun put(value: Set<String>): KeyValuePair {
        valueType = TYPE_STRING_SET_UTF8
        val stream = ByteArrayOutputStream()
        val intBuffer = ByteBuffer.allocate(4)
        for (v in value) {
            intBuffer.rewind()
            val bytes = v.toByteArray(Charsets.UTF_8)
            stream.write(intBuffer.putInt(bytes.size).array())
            stream.write(bytes)
        }
        this.value = stream.toByteArray()
        return this
    }

    @Suppress("IMPLICIT_CAST_TO_ANY")
    override fun toString(): String {
        return when (valueType) {
            TYPE_BOOLEAN -> boolean
            TYPE_FLOAT -> float
            TYPE_INT, TYPE_LONG -> long
            TYPE_STRING -> string
            TYPE_STRING_SET, TYPE_STRING_SET_UTF8 -> stringSet
            else -> null
        }?.toString() ?: "null"
    }

    constructor(parcel: Parcel) : this() {
        key = parcel.readString()!!
        valueType = parcel.readInt()
        value = parcel.createByteArray()!!
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(key)
        parcel.writeInt(valueType)
        parcel.writeByteArray(value)
    }

    override fun describeContents(): Int {
        return 0
    }

}
