package com.sdyl.patrol.core.network

import com.google.gson.GsonBuilder
import com.sdyl.patrol.core.data.SessionStore
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * 统一 HTTP 出口。
 *
 * 关键点（逆向自原 App）：
 *  - baseUrl = https://<host>:8445，随设置页改动而重建
 *  - 业务请求必须带 `Authorization: Bearer <ticket>` 和 `BapServer: <host:port>`
 *  - 登录接口反过来不能带这两个头
 *  - 企业内网是自签证书，原 App 直接放行，这里保持一致
 */
class ApiClient(private val session: SessionStore) {

    private val gson = GsonBuilder()
        .setLenient()
        .setDateFormat("yyyy-MM-dd'T'HH:mm:ssZ")
        .serializeNulls()
        // 服务端同一字段时而字符串时而对象（如 inputStandardCode.editType），不做容错会让整页同步失败
        .registerTypeAdapterFactory(StringCoercingFactory())
        .create()

    @Volatile
    private var retrofit: Retrofit? = null

    @Volatile
    private var builtBaseUrl: String = ""

    @Volatile
    private var retrofitLong: Retrofit? = null

    @Volatile
    private var builtBaseUrlLong: String = ""

    private val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun buildOkHttp(): OkHttpClient {
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), java.security.SecureRandom())
        }
        val logging = HttpLoggingInterceptor().apply {
            level = if (com.sdyl.patrol.BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .addInterceptor(HeaderInterceptor(session))
            .addInterceptor(logging)
            .build()
    }

    private fun retrofit(): Retrofit {
        val base = session.baseUrl.trimEnd('/') + "/"
        val cached = retrofit
        if (cached != null && builtBaseUrl == base) return cached
        synchronized(this) {
            val again = retrofit
            if (again != null && builtBaseUrl == base) return again
            val created = Retrofit.Builder()
                .baseUrl(base)
                .client(buildOkHttp())
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
            retrofit = created
            builtBaseUrl = base
            return created
        }
    }

    fun <T> create(service: Class<T>): T = retrofit().create(service)

    inline fun <reified T : Any> api(): T = create(T::class.java)

    /**
     * 上传通道：原版上传 zip 时把超时设成 600 秒（`Api.setTimeOut(600)`），
     * 普通 30 秒超时对大文件必然失败，所以单独建一个长超时的 Retrofit。
     */
    private fun retrofitLong(): Retrofit {
        val base = session.baseUrl.trimEnd('/') + "/"
        val cached = retrofitLong
        if (cached != null && builtBaseUrlLong == base) return cached
        synchronized(this) {
            val again = retrofitLong
            if (again != null && builtBaseUrlLong == base) return again
            val created = Retrofit.Builder()
                .baseUrl(base)
                .client(
                    buildOkHttp().newBuilder()
                        .readTimeout(UPLOAD_TIMEOUT_SEC, TimeUnit.SECONDS)
                        .writeTimeout(UPLOAD_TIMEOUT_SEC, TimeUnit.SECONDS)
                        .callTimeout(UPLOAD_TIMEOUT_SEC, TimeUnit.SECONDS)
                        .build(),
                )
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
            retrofitLong = created
            builtBaseUrlLong = base
            return created
        }
    }

    fun <T> createLong(service: Class<T>): T = retrofitLong().create(service)

    /** 服务器地址变更后调用 */
    fun invalidate() {
        synchronized(this) {
            retrofit = null
            builtBaseUrl = ""
            retrofitLong = null
            builtBaseUrlLong = ""
        }
    }

    companion object {
        const val UA_SUFFIX = "supMobile/6.2.8.35"

        /** 上传/下载 zip 的超时（秒），对齐原版 `Api.setTimeOut(600)` */
        const val UPLOAD_TIMEOUT_SEC = 600L

        fun defaultUserAgent(): String =
            (System.getProperty("http.agent") ?: "Android") + "/mesjg " + UA_SUFFIX
    }
}

private class HeaderInterceptor(private val session: SessionStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath
        val builder = request.newBuilder()
            .header("Accept", "application/json")
            .header("Accept-Language", "zh-CN")
            .header("User-Agent", ApiClient.defaultUserAgent())

        val isAuthEntry = path.startsWith("/inter-api/auth/login") ||
            path.contains("/auth/oauth/loginCheck")
        if (isAuthEntry) {
            builder.removeHeader("Authorization")
            builder.removeHeader("BapServer")
        } else {
            val ticket = session.ticket
            if (ticket.isNotEmpty()) builder.header("Authorization", "Bearer $ticket")
            builder.header("BapServer", session.bapServer)
        }
        return chain.proceed(builder.build())
    }
}
