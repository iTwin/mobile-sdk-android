package com.github.itwin.mobilesdk

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.*
import kotlin.coroutines.resume

open class ActivityResultHandler<I, O>(resultCaller: ActivityResultCaller, owner: LifecycleOwner, contract: ActivityResultContract<I, O>) {
    private var cancellableContinuation: CancellableContinuation<O>? = null
    private val resultLauncher = resultCaller.registerForActivityResult(contract) { result ->
        cancellableContinuation?.resume(result)
        cancellableContinuation = null
    }

    init {
        owner.lifecycle.addObserver(object: DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                resultLauncher.unregister()
                //@todo: need these 2 lines?
                cancellableContinuation?.cancel()
                cancellableContinuation = null
            }
        })
    }

    suspend operator fun invoke(input: I) = suspendCancellableCoroutine { continuation ->
        cancellableContinuation = continuation
        resultLauncher.launch(input)
        continuation.invokeOnCancellation {
            cancellableContinuation = null
        }
    }
}

/**
 * Similar to [ActivityResultContracts.StartActivityForResult] except it returns the input intent
 * only if the resultCode is RESULT_OK
 */
class StartActivityForIntentResult: ActivityResultContract<Intent, Intent?>() {
    override fun createIntent(context: Context, input: Intent) = input

    override fun parseResult(resultCode: Int, intent: Intent?): Intent? {
        return intent.takeIf { resultCode == Activity.RESULT_OK }
    }
}

class ActivityResultData(resultCaller: ActivityResultCaller, owner: LifecycleOwner):
    ActivityResultHandler<Intent, Intent?>(resultCaller, owner, StartActivityForIntentResult())

@Suppress("unused")
class ActivityResultRequestPermission(resultCaller: ActivityResultCaller, owner: LifecycleOwner):
    ActivityResultHandler<String, Boolean>(resultCaller, owner, ActivityResultContracts.RequestPermission())

@Suppress("unused")
class ActivityResultIntentSender(resultCaller: ActivityResultCaller, owner: LifecycleOwner):
    ActivityResultHandler<IntentSenderRequest, ActivityResult>(resultCaller, owner, ActivityResultContracts.StartIntentSenderForResult())