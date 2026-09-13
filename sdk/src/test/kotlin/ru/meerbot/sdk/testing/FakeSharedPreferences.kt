package ru.meerbot.sdk.testing

import android.content.SharedPreferences

/**
 * `SharedPreferences` в памяти: в JVM-тестах настоящих нет, а Robolectric в проекте не
 * подключён. [failReads]/[failWrites] воспроизводят поведение `EncryptedSharedPreferences`
 * с повреждённым keyset — `SecurityException` на расшифровке и шифровании.
 */
class FakeSharedPreferences : SharedPreferences {

    val values = HashMap<String, Any?>()

    @Volatile
    var failReads = false

    @Volatile
    var failWrites = false

    /** Синхронные записи (`commit()`) и отложенные (`apply()`) — чтобы отличать одну от другой. */
    @Volatile
    var commits = 0

    @Volatile
    var applies = 0

    private fun <T> read(block: () -> T): T {
        if (failReads) throw SecurityException("Could not decrypt value")
        return synchronized(values) { block() }
    }

    override fun getAll(): MutableMap<String, *> = read { HashMap(values) }
    override fun getString(key: String, defValue: String?): String? = read { values[key] as String? ?: defValue }
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        read { values[key] as MutableSet<String>? ?: defValues }
    override fun getInt(key: String, defValue: Int): Int = read { values[key] as Int? ?: defValue }
    override fun getLong(key: String, defValue: Long): Long = read { values[key] as Long? ?: defValue }
    override fun getFloat(key: String, defValue: Float): Float = read { values[key] as Float? ?: defValue }
    override fun getBoolean(key: String, defValue: Boolean): Boolean = read { values[key] as Boolean? ?: defValue }
    override fun contains(key: String): Boolean = read { values.containsKey(key) }

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val changes = HashMap<String, Any?>()
        private val removals = HashSet<String>()
        private var clear = false

        private fun put(key: String, value: Any?): SharedPreferences.Editor {
            if (failWrites) throw SecurityException("Could not encrypt value")
            changes[key] = value
            return this
        }

        override fun putString(key: String, value: String?) = put(key, value)
        override fun putStringSet(key: String, values: MutableSet<String>?) = put(key, values)
        override fun putInt(key: String, value: Int) = put(key, value)
        override fun putLong(key: String, value: Long) = put(key, value)
        override fun putFloat(key: String, value: Float) = put(key, value)
        override fun putBoolean(key: String, value: Boolean) = put(key, value)
        override fun remove(key: String): SharedPreferences.Editor = apply { removals += key }
        override fun clear(): SharedPreferences.Editor = apply { clear = true }

        override fun commit(): Boolean {
            commits++
            return write()
        }

        override fun apply() {
            applies++
            write()
        }

        private fun write(): Boolean {
            synchronized(values) {
                if (clear) values.clear()
                removals.forEach { values.remove(it) }
                values.putAll(changes)
            }
            return true
        }
    }
}
