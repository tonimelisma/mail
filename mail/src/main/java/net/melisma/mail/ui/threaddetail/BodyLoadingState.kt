package net.melisma.mail.ui.threaddetail

sealed interface BodyLoadingState {
    object NotLoadedYet : BodyLoadingState // Renamed from NotLoaded to be more explicit
    object Loading : BodyLoadingState
    data class Loaded(val htmlContent: String) : BodyLoadingState
    data class Error(val errorMessage: String) : BodyLoadingState
} 