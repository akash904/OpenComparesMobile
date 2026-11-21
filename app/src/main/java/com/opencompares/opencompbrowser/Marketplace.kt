package com.opencompares.opencompbrowser

data class Marketplace(
    val id: String,
    val name: String,
    val urlTemplate: String,
    val homepage: String,
    val defaultZoom: Int = 65,
    val isEnabled: Boolean = true
) {
    fun getSearchUrl(query: String): String {
        return urlTemplate.replace("{query}", java.net.URLEncoder.encode(query, "UTF-8"))
    }
}

