package com.opencompares.opencompbrowser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SearchViewModel : ViewModel() {
    private val _currentQuery = MutableStateFlow<String>("")
    val currentQuery: StateFlow<String> = _currentQuery.asStateFlow()
    
    private val _marketplaces = MutableStateFlow<List<Marketplace>>(MarketplaceConfig.defaultMarketplaces)
    val marketplaces: StateFlow<List<Marketplace>> = _marketplaces.asStateFlow()
    
    private val _isSearching = MutableStateFlow<Boolean>(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()
    
    fun setQuery(query: String) {
        _currentQuery.value = query
    }
    
    fun updateMarketplaces(marketplaces: List<Marketplace>) {
        _marketplaces.value = marketplaces
    }
    
    fun setSearching(isSearching: Boolean) {
        _isSearching.value = isSearching
    }
    
    fun getSearchUrlForMarketplace(marketplaceId: String, query: String): String? {
        return _marketplaces.value.find { it.id == marketplaceId }?.getSearchUrl(query)
    }
}

