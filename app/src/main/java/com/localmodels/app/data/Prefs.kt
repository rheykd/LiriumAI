package com.localmodels.app.data

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("local", Context.MODE_PRIVATE)

    var selectedModelId: String?
        get() = sp.getString("selected_model", null)
        set(value) {
            sp.edit().putString("selected_model", value).apply()
        }

    // font: montserrat_bold | montserrat_regular | montserrat_light | strejen
    var fontKey: String
        get() = sp.getString("font", "montserrat_bold") ?: "montserrat_bold"
        set(value) {
            sp.edit().putString("font", value).apply()
        }

    var internetEnabled: Boolean
        get() = sp.getBoolean("internet", false)
        set(value) {
            sp.edit().putBoolean("internet", value).apply()
        }

    var detailedVision: Boolean
        get() = sp.getBoolean("detailed_vision", true)
        set(value) { sp.edit().putBoolean("detailed_vision", value).apply() }

    var filesEnabled: Boolean
        get() = sp.getBoolean("files_enabled", false)
        set(value) { sp.edit().putBoolean("files_enabled", value).apply() }

    var maxModelGb: Int
        get() = sp.getInt("max_model_gb", 4).coerceIn(1, Catalog.MAX_MODEL_GB)
        set(value) {
            sp.edit().putInt("max_model_gb", value.coerceIn(1, Catalog.MAX_MODEL_GB)).apply()
        }

    var customCatalogJson: String?
        get() = sp.getString("custom_catalog", null)
        set(value) {
            sp.edit().putString("custom_catalog", value).apply()
        }

    var apiEnabled: Boolean
        get() = sp.getBoolean("api_enabled", false)
        set(value) {
            sp.edit().putBoolean("api_enabled", value).apply()
        }

    var apiBaseUrl: String
        get() = sp.getString("api_url", "") ?: ""
        set(value) {
            sp.edit().putString("api_url", value.trim()).apply()
        }

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(value) {
            sp.edit().putString("api_key", value.trim()).apply()
        }

    var apiModel: String
        get() = sp.getString("api_model", "") ?: ""
        set(value) {
            sp.edit().putString("api_model", value.trim()).apply()
        }

    var apiVision: Boolean
        get() = sp.getBoolean("api_vision", false)
        set(value) {
            sp.edit().putBoolean("api_vision", value).apply()
        }

    var lanEnabled: Boolean
        get() = sp.getBoolean("lan_enabled", false)
        set(value) {
            sp.edit().putBoolean("lan_enabled", value).apply()
        }

    var lanPassword: String
        get() = sp.getString("lan_password", "123") ?: "123"
        set(value) {
            sp.edit().putString("lan_password", value.ifBlank { "123" }).apply()
        }

    var lanPort: Int
        get() = sp.getInt("lan_port", 8080)
        set(value) {
            sp.edit().putInt("lan_port", value.coerceIn(1024, 65535)).apply()
        }
}
