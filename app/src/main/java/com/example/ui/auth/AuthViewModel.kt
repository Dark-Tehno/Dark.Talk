package com.example.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.repository.DarkTalkRepository
import com.example.data.repository.Resource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val requires2Fa: Boolean = false,
    val challengeId: String? = null,
    val challengeExpiresAt: String? = null,
    val isSuccess: Boolean = false
)

class AuthViewModel(private val repository: DarkTalkRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun login(username: String, pass: String) {
        if (username.isBlank() || pass.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Please enter username and password")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = repository.login(username.trim(), pass)) {
                is Resource.Success -> {
                    val auth = result.data
                    if (auth.status == "two_factor_required" && auth.challengeId != null) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            requires2Fa = true,
                            challengeId = auth.challengeId,
                            challengeExpiresAt = auth.expiresAt,
                            error = null
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            isSuccess = true,
                            error = null
                        )
                    }
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = result.message)
                }
                is Resource.Loading -> {}
            }
        }
    }

    fun register(
        email: String,
        pass: String,
        username: String,
        language: String,
        dob: String?
    ) {
        if (email.isBlank() || pass.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Email and password are required")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val uName = username.takeIf { it.isNotBlank() }
            val birthDate = dob?.takeIf { it.isNotBlank() }
            when (val result = repository.register(email.trim(), pass, uName, language, birthDate)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isSuccess = true,
                        error = null
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = result.message)
                }
                is Resource.Loading -> {}
            }
        }
    }

    fun verify2Fa(code: String) {
        val cid = _uiState.value.challengeId ?: return
        if (code.isBlank() || code.length < 4) {
            _uiState.value = _uiState.value.copy(error = "Enter a valid verification code")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = repository.verify2Fa(cid, code.trim())) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isSuccess = true,
                        requires2Fa = false,
                        error = null
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = result.message)
                }
                is Resource.Loading -> {}
            }
        }
    }

    fun resend2Fa() {
        val cid = _uiState.value.challengeId ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = repository.resend2Fa(cid)) {
                is Resource.Success -> {
                    val auth = result.data
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        challengeId = auth.challengeId ?: cid,
                        challengeExpiresAt = auth.expiresAt,
                        error = "New code sent to your email"
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = result.message)
                }
                is Resource.Loading -> {}
            }
        }
    }

    fun cancel2Fa() {
        _uiState.value = _uiState.value.copy(
            requires2Fa = false,
            challengeId = null,
            challengeExpiresAt = null,
            error = null
        )
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
