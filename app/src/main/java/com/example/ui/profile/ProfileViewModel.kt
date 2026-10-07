package com.example.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.Device
import com.example.data.model.LoginHistoryItem
import com.example.data.model.User
import com.example.data.repository.DarkTalkRepository
import com.example.data.repository.Resource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileUiState(
    val user: User? = null,
    val devices: List<Device> = emptyList(),
    val loginHistory: List<LoginHistoryItem> = emptyList(),
    val twoFactorEnabled: Boolean = false,
    val isLoading: Boolean = false,
    val message: String? = null,
    val isLoggedOut: Boolean = false
)

class ProfileViewModel(private val repository: DarkTalkRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.cachedUser.collect { user ->
                if (_uiState.value.user == null && user != null) {
                    _uiState.update {
                        it.copy(
                            user = user,
                            twoFactorEnabled = user.twoFactorEnabled ?: it.twoFactorEnabled
                        )
                    }
                }
            }
        }
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, message = null) }
            val profileRes = repository.getProfile()
            val twoFaRes = repository.get2FaState()
            val devicesRes = repository.getDevices()
            val historyRes = repository.getLoginHistory()

            _uiState.update { state ->
                state.copy(
                    user = if (profileRes is Resource.Success) profileRes.data else state.user,
                    twoFactorEnabled = if (twoFaRes is Resource.Success) twoFaRes.data else repository.preferencesManager.twoFactorEnabled,
                    devices = if (devicesRes is Resource.Success) devicesRes.data else emptyList(),
                    loginHistory = if (historyRes is Resource.Success) historyRes.data else emptyList(),
                    isLoading = false
                )
            }
        }
    }

    fun toggle2Fa(enabled: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, message = null) }
            when (val result = repository.toggle2Fa(enabled)) {
                is Resource.Success -> {
                    _uiState.update {
                        it.copy(
                            twoFactorEnabled = result.data,
                            isLoading = false,
                            message = if (result.data) "Two-factor authentication enabled" else "Two-factor authentication disabled"
                        )
                    }
                }
                is Resource.Error -> {
                    _uiState.update { it.copy(isLoading = false, message = result.message) }
                }
                is Resource.Loading -> {}
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            repository.logout()
            _uiState.update { it.copy(isLoading = false, isLoggedOut = true) }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
