package com.myclaud.audioverify.core.runner

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

enum class RelaxDecision { ACCEPT_RELAXED, MARK_FAIL, RETRY }

data class RelaxRequest(
    val caseId: String,
    val verdicts: List<AssertionVerdict>,
    val missingHints: List<String>,
)

/**
 * Suspends the runner whenever a verdict is NEEDS_HUMAN or a route is missing.
 * Plan invariant: the runner NEVER auto-relaxes. The only path past a failed
 * mode requirement is a human pressing one of [RelaxDecision].
 *
 * The gate also re-prompts on every run for the same case (per the user's
 * decision — methodology changes always require human approval).
 */
class RelaxationGate {

    private val _pending = MutableStateFlow<RelaxRequest?>(null)
    val pending: StateFlow<RelaxRequest?> = _pending

    private var pendingResolver: ((RelaxDecision) -> Unit)? = null

    suspend fun ask(caseId: String, verdicts: List<AssertionVerdict>, missingHints: List<String>): RelaxDecision =
        suspendCancellableCoroutine { cont ->
            pendingResolver = { decision ->
                _pending.value = null
                pendingResolver = null
                cont.resume(decision)
            }
            _pending.value = RelaxRequest(caseId, verdicts, missingHints)
        }

    fun resolve(decision: RelaxDecision) {
        pendingResolver?.invoke(decision)
    }
}
