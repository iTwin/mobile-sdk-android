/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
package com.github.itwin.mobilesdk

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream

@RunWith(AndroidJUnit4::class)
class ITMMessengerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private class Fixture {
        val messenger = ITMMessenger()
        lateinit var view: WebView
    }

    private suspend fun fixture(): Fixture = withContext(Dispatchers.Main) {
        Fixture().also { fixture ->
            fixture.view = WebView(instrumentation.targetContext).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                        fixture.messenger.onPageStarted(view)
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        fixture.messenger.onPageFinished(view)
                    }

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        val html = if (request.url.path == "/frame") FRAME else MAIN
                        return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray()))
                    }
                }
            }
            fixture.messenger.attach(fixture.view)
        }
    }

    private suspend fun evaluate(fixture: Fixture, script: String): String = withContext(Dispatchers.Main) {
        val result = CompletableDeferred<String>()
        fixture.view.evaluateJavascript(script) { result.complete(it) }
        result.await()
    }

    private suspend fun waitFor(fixture: Fixture, script: String) {
        withTimeout(10_000) {
            while (evaluate(fixture, script) != "true") delay(10)
        }
    }

    private suspend fun load(fixture: Fixture, origin: String = "https://appassets.itwinjs.org") {
        val url = "$origin/main?run=${System.nanoTime()}"
        withContext(Dispatchers.Main) { fixture.view.loadUrl(url) }
        withTimeout(10_000) {
            while (withContext(Dispatchers.Main) { fixture.view.url != url || fixture.view.progress != 100 }) delay(10)
        }
        if (origin == "https://appassets.itwinjs.org") waitFor(fixture, "window.ready === true")
    }

    private suspend fun query(fixture: Fixture, name: String): String = withContext(Dispatchers.Main) {
        val result = CompletableDeferred<String>()
        fixture.messenger.query<Unit, String>(name, Unit, { result.complete(it) }, { result.completeExceptionally(it) })
        result.await()
    }

    private suspend fun dispose(fixture: Fixture) = withContext(Dispatchers.Main) {
        fixture.messenger.detach()
        fixture.view.destroy()
    }

    @Test
    fun bidirectionalQueriesAndDetach() = runBlocking {
        withTimeout(30_000) {
            val fixture = fixture()
            try {
                fixture.messenger.registerQueryHandler<String, String>("echo") { body, success, _ -> success?.invoke(body) }
                load(fixture)
                fixture.messenger.frontendLaunchSucceeded()
                assertEquals("h\u00e9llo", query(fixture, "native's-query"))
                evaluate(fixture, "send({operation:'query',queryId:0,name:'echo',message:'world'}); true")
                waitFor(fixture, "received.some(m => m.operation === 'queryResponse' && m.queryId === 0)")
                assertEquals("\"world\"", evaluate(fixture,
                    "JSON.parse(atob(received.find(m => m.operation === 'queryResponse' && m.queryId === 0).message)).response"))
                evaluate(fixture, "window.holdResponses = true; true")
                val pending = async { runCatching { query(fixture, "pending") }.isFailure }
                waitFor(fixture, "received.some(m => m.name === 'pending')")
                evaluate(fixture, """
                    (function() {
                        const queryId = received.find(m => m.name === 'pending').queryId;
                        const payload = {operation:'queryResponse',queryId:queryId,response:'forged',
                            sessionId:Bentley_ITMMessengerNativeSession,documentId:Bentley_ITMMessengerDocumentId};
                        Bentley_ITMMessenger.postMessage(JSON.stringify(Object.assign({}, payload, {sessionId:'wrong'})));
                        Bentley_ITMMessenger.postMessage(JSON.stringify(Object.assign({}, payload, {documentId:'wrong'})));
                        const frame = document.createElement('iframe');
                        frame.src = 'https://appassets.itwinjs.org/frame?payload=' + encodeURIComponent(JSON.stringify(payload));
                        document.body.appendChild(frame); return true;
                    })();
                """.trimIndent())
                waitFor(fixture, "attempts.length === 1")
                withContext(Dispatchers.Main) { fixture.messenger.detach() }
                assertTrue(pending.await())
                assertTrue(runCatching { query(fixture, "after-detach") }.isFailure)
                evaluate(fixture, "window.holdResponses = false; window.ready = false; true")
                withContext(Dispatchers.Main) { fixture.messenger.attach(fixture.view) }
                load(fixture)
                fixture.messenger.frontendLaunchSucceeded()
                assertEquals("h\u00e9llo", query(fixture, "reattached"))
            } finally {
                dispose(fixture)
            }
        }
    }

    @Test
    fun largePayloadsRoundTripInBothDirections() = runBlocking {
        withTimeout(30_000) {
            val fixture = fixture()
            try {
                fixture.messenger.registerQueryHandler<String, String>("echo") { body, success, _ -> success?.invoke(body) }
                load(fixture)
                fixture.messenger.frontendLaunchSucceeded()
                val message = "x".repeat(1_048_577)
                val response = withContext(Dispatchers.Main) {
                    val result = CompletableDeferred<String>()
                    fixture.messenger.query<String, String>("large", message, { result.complete(it) }, { result.completeExceptionally(it) })
                    result.await()
                }
                assertEquals(message, response)
                evaluate(fixture, "send({operation:'query',queryId:0,name:'echo',message:'x'.repeat(1048577)}); true")
                waitFor(fixture, "received.some(m => m.operation === 'queryResponse' && m.queryId === 0)")
                assertEquals("true", evaluate(fixture,
                    "JSON.parse(atob(received.find(m => m.operation === 'queryResponse' && m.queryId === 0).message)).response === 'x'.repeat(1048577)"))
            } finally {
                dispose(fixture)
            }
        }
    }

    @Test
    fun rejectsFramesAndInvalidEnvelopes() = runBlocking {
        withTimeout(30_000) {
            val fixture = fixture()
            try {
                var invocations = 0
                fixture.messenger.registerQueryHandler<Map<String, Any?>, Unit>("probe") { _, success, _ ->
                    invocations++
                    success?.invoke(Unit)
                }
                load(fixture)
                listOf("https://foreign.example" to false, "https://appassets.itwinjs.org" to false,
                    "https://appassets.itwinjs.org" to true).forEachIndexed { index, (origin, sandboxed) ->
                    evaluate(fixture, """
                        (function() {
                            const frame = document.createElement('iframe');
                            ${if (sandboxed) "frame.sandbox = 'allow-scripts';" else ""}
                            const payload = {operation:'query',queryId:$index,name:'probe',message:{},
                                sessionId:Bentley_ITMMessengerNativeSession,documentId:Bentley_ITMMessengerDocumentId};
                            frame.src = '$origin/frame?payload=' + encodeURIComponent(JSON.stringify(payload));
                            document.body.appendChild(frame); return true;
                        })();
                    """.trimIndent())
                    waitFor(fixture, "attempts.length === ${index + 1}")
                }
                evaluate(fixture, """
                    Bentley_ITMMessenger.postMessage(JSON.stringify({
                        operation:'query',queryId:14,name:'probe',message:{},
                        sessionId:Bentley_ITMMessengerNativeSession,documentId:Bentley_ITMMessengerDocumentId
                    }) + 'garbage');
                    send({operation:'query',queryId:10,name:'probe',message:{},sessionId:'wrong'});
                    send({operation:'query',queryId:true,name:'probe',message:{}});
                    send({operation:'query',queryId:11,message:{}});
                    send({operation:'unknown',queryId:12,name:'probe'});
                    send({operation:'query',queryId:13,name:'probe',message:{}});
                    true;
                """.trimIndent())
                waitFor(fixture, "received.some(m => m.queryId === 13)")
                assertEquals(1, withContext(Dispatchers.Main) { invocations })
            } finally {
                dispose(fixture)
            }
        }
    }

    @Test
    fun navigationFailsPendingQueriesAndResetsReadiness() = runBlocking {
        withTimeout(30_000) {
            val fixture = fixture()
            try {
                load(fixture)
                fixture.messenger.frontendLaunchSucceeded()
                evaluate(fixture, "window.holdResponses = true; true")
                val pending = async { runCatching { query(fixture, "old-document") }.isFailure }
                waitFor(fixture, "received.some(m => m.name === 'old-document')")
                load(fixture)
                assertTrue(pending.await())
                assertFalse(fixture.messenger.isFrontendLaunchComplete)
                fixture.messenger.frontendLaunchSucceeded()
                assertEquals("h\u00e9llo", query(fixture, "new-document"))
                assertEquals("false", evaluate(fixture, "received.some(m => m.name === 'old-document')"))
            } finally {
                dispose(fixture)
            }

        }
    }

    @Test
    fun lateHandlerCannotRespondToReusedIdInNextDocument() = runBlocking {
        withTimeout(30_000) {
            val fixture = fixture()
            try {
                val oldResponse = CompletableDeferred<ITMSuccessCallback<String>>()
                fixture.messenger.registerQueryHandler<Map<String, Any?>, String>("delayed") { _, success, _ ->
                    oldResponse.complete(requireNotNull(success))
                }
                fixture.messenger.registerQueryHandler<Map<String, Any?>, String>("echo") { _, success, _ -> success?.invoke("current") }
                load(fixture)
                evaluate(fixture, "send({operation:'query',queryId:7,name:'delayed',message:{}}); true")
                val complete = oldResponse.await()
                load(fixture)
                withContext(Dispatchers.Main) { complete("old sensitive result") }
                evaluate(fixture, "send({operation:'query',queryId:7,name:'echo',message:{}}); true")
                waitFor(fixture, "received.some(m => m.queryId === 7)")
                assertEquals("\"current\"", evaluate(fixture,
                    "JSON.parse(atob(received.find(m => m.queryId === 7).message)).response"))
                assertEquals("1", evaluate(fixture, "received.filter(m => m.queryId === 7).length"))
            } finally {
                dispose(fixture)
            }
        }
    }

    @Test
    fun untrustedStartupFailsWaitingQueriesAndTrustedNavigationRecovers() = runBlocking {
        withTimeout(30_000) {
            val fixture = fixture()
            try {
                val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { query(fixture, "startup") }.isFailure }
                load(fixture, "https://foreign.example")
                assertTrue(pending.await())
                load(fixture)
                fixture.messenger.frontendLaunchSucceeded()
                assertEquals("h\u00e9llo", query(fixture, "recovered"))
            } finally {
                dispose(fixture)
            }
        }
    }

    private companion object {
        val MAIN = """
            <html><body><script>
            window.received = []; window.attempts = []; window.ready = false;
            window.Bentley_ITMMessengerDocumentId = 'document-' + Math.random().toString(36).slice(2);
            window.send = function(message) {
                Bentley_ITMMessenger.postMessage(JSON.stringify(Object.assign({
                    sessionId:Bentley_ITMMessengerNativeSession,documentId:Bentley_ITMMessengerDocumentId
                }, message)));
            };
            window.Bentley_ITMMessengerInitialize = function(id) {
                window.Bentley_ITMMessengerNativeSession = id; send({operation:'ready'});
            };
            Bentley_ITMMessenger.onmessage = function(event) {
                const message = JSON.parse(event.data); received.push(message);
                if (message.operation === 'ready') ready = true;
                if (message.operation === 'query' && !window.holdResponses) {
                    Bentley_ITMMessenger.postMessage(JSON.stringify({
                        operation:'queryResponse',queryId:message.queryId,
                        response:message.name === 'large' ? JSON.parse(atob(message.message)) : 'h\u00e9llo',
                        sessionId:message.sessionId,documentId:message.documentId
                    }));
                }
            };
            window.addEventListener('message', function(event) {
                if (event.data === 'sent' || event.data === 'unavailable') attempts.push(event.data);
            });
            </script></body></html>
        """.trimIndent()

        val FRAME = """
            <script>
            const payload = JSON.parse(new URL(location.href).searchParams.get('payload'));
            try {
                Bentley_ITMMessenger.postMessage(JSON.stringify(payload));
                parent.postMessage('sent', '*');
            } catch (error) { parent.postMessage('unavailable', '*'); }
            </script>
        """.trimIndent()
    }
}
