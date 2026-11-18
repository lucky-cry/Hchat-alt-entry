package android.content

class Context(val preferences: SharedPreferences)

interface SharedPreferences {
    fun getString(key: String, defaultValue: String?): String?
    fun edit(): Editor
    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun commit(): Boolean
    }
}
