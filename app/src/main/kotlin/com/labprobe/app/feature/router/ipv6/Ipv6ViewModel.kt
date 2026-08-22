package com.labprobe.app.feature.router.ipv6

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class Ipv6UiState(
    val status: Ipv6Status? = null,
    val config: Ipv6Config? = null,
    val form: Ipv6FormState = Ipv6FormState(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val saving: Boolean = false,
    val error: String = "",
    val notice: String = "",
) {
    val dirty: Boolean get() = config?.let { form != Ipv6FormState.from(it) } == true
    val canSave: Boolean get() = dirty && !loading && !saving && form.validationError == null
}

data class Dhcpv6ClientUiState(
    val clients: List<Dhcpv6Client> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: String = "",
)

class Ipv6ViewModel(private val repository: Ipv6Repository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var generation = 0L

    private val _state = MutableStateFlow(Ipv6UiState())
    val state: StateFlow<Ipv6UiState> = _state.asStateFlow()

    private val _clients = MutableStateFlow(Dhcpv6ClientUiState())
    val clients: StateFlow<Dhcpv6ClientUiState> = _clients.asStateFlow()

    fun load(force: Boolean = false) {
        val old = _state.value
        if (old.loading || old.saving || (!force && old.config != null)) return
        val requestGeneration = ++generation
        _state.value = old.copy(
            loading = old.config == null,
            refreshing = old.config != null,
            error = "",
            notice = "",
        )
        scope.launch {
            val statusResult = safeCall { repository.status() }
            val configResult = safeCall { repository.config() }
            if (requestGeneration != generation) return@launch
            val latestConfig = configResult.getOrNull()
            val error = listOfNotNull(
                statusResult.exceptionOrNull()?.message,
                configResult.exceptionOrNull()?.message,
            ).firstOrNull().orEmpty()
            _state.value = _state.value.copy(
                status = statusResult.getOrNull() ?: old.status,
                config = latestConfig ?: old.config,
                form = latestConfig?.let(Ipv6FormState::from) ?: old.form,
                loading = false,
                refreshing = false,
                error = error.ifBlank { if (latestConfig == null) "IPv6 配置读取失败" else "" },
            )
        }
    }

    fun updateForm(transform: (Ipv6FormState) -> Ipv6FormState) {
        if (_state.value.saving) return
        _state.value = _state.value.copy(form = transform(_state.value.form), error = "", notice = "")
    }

    fun resetForm() {
        val config = _state.value.config ?: return
        _state.value = _state.value.copy(form = Ipv6FormState.from(config), error = "", notice = "")
    }

    fun save() {
        val old = _state.value
        val validation = old.form.validationError
        if (!old.dirty || old.saving || validation != null) {
            if (validation != null) _state.value = old.copy(error = validation)
            return
        }
        val requestGeneration = ++generation
        _state.value = old.copy(saving = true, error = "", notice = "")
        scope.launch {
            val saved = safeCall { repository.save(old.form) }
            if (requestGeneration != generation) return@launch
            val config = saved.getOrNull()
            if (config != null) {
                val refreshedStatus = safeCall { repository.status() }.getOrNull()
                _state.value = _state.value.copy(
                    status = refreshedStatus ?: _state.value.status,
                    config = config,
                    form = Ipv6FormState.from(config),
                    saving = false,
                    error = "",
                    notice = "IPv6 设置已保存并校验",
                )
            } else {
                _state.value = _state.value.copy(
                    saving = false,
                    error = saved.exceptionOrNull()?.message.orEmpty().ifBlank { "IPv6 设置保存失败" },
                )
            }
        }
    }

    fun loadClients(force: Boolean = false) {
        val old = _clients.value
        if (old.loading || (!force && old.loaded)) return
        _clients.value = old.copy(loading = true, error = "")
        scope.launch {
            safeCall { repository.clients() }
                .onSuccess { _clients.value = Dhcpv6ClientUiState(clients = it, loaded = true) }
                .onFailure {
                    _clients.value = old.copy(
                        loading = false,
                        error = it.message.orEmpty().ifBlank { "DHCPv6 客户端读取失败" },
                    )
                }
        }
    }

    fun close() {
        scope.cancel()
    }

    private suspend fun <T> safeCall(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        Result.failure(failure)
    }
}
