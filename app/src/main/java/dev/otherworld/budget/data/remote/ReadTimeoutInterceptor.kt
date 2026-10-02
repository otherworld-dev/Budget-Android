package dev.otherworld.budget.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Gives every GET a short read timeout, leaving the client's long default for POSTs.
 *
 * The long default exists for receipt extraction (`POST ocr/extract`) and for saving
 * (`POST transactions`), which can legitimately take a minute on a small server. Every GET is
 * a small read the server answers in well under a second. Under that same default, a request
 * sent down a dead pooled connection waited two full minutes before failing; that happens after
 * the phone's Wi-Fi has idled. Verified on a device: Overview and Activity sat blank for minutes
 * while the server itself answered in 0.6 s. Failing fast instead lets the screen show its cached
 * data or a Retry.
 */
class ReadTimeoutInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        if (chain.request().method == "GET") {
            chain.withReadTimeout(READ_SECONDS.toInt(), TimeUnit.SECONDS).proceed(chain.request())
        } else {
            chain.proceed(chain.request())
        }

    companion object {
        const val READ_SECONDS = 20L
    }
}
