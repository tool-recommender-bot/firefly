package com.firefly.kotlin.ext.http

import com.firefly.codec.http2.model.*
import com.firefly.kotlin.ext.common.AsyncPool
import com.firefly.kotlin.ext.common.CoroutineLocal
import com.firefly.kotlin.ext.common.Json
import com.firefly.kotlin.ext.log.Log
import com.firefly.server.http2.SimpleHTTPServer
import com.firefly.server.http2.SimpleHTTPServerConfiguration
import com.firefly.server.http2.SimpleRequest
import com.firefly.server.http2.router.Router
import com.firefly.server.http2.router.RouterManager
import com.firefly.server.http2.router.RoutingContext
import com.firefly.server.http2.router.handler.body.HTTPBodyConfiguration
import kotlinx.coroutines.experimental.NonCancellable
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.run
import java.io.Closeable
import java.net.InetAddress
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.function.Supplier
import kotlin.coroutines.experimental.CoroutineContext

/**
 * Firefly HTTP server extensions
 *
 * @author Pengtao Qiu
 */

val sysLogger = Log.getLogger("firefly-system")

// HTTP server API extensions

inline fun <reified T : Any> RoutingContext.getJsonBody(charset: String): T = Json.parse(getStringBody(charset))

inline fun <reified T : Any> RoutingContext.getJsonBody(): T = Json.parse(stringBody)

inline fun <reified T : Any> RoutingContext.getAttr(name: String): T? {
    val data = getAttribute(name) ?: return null
    if (data is T) {
        return data
    } else {
        throw ClassCastException("The attribute $name type is ${data::class.java}. It can't cast to ${T::class.java}")
    }
}

fun RoutingContext.getWildcardMatchedResult(index: Int): String = getRouterParameter("param$index")

fun RoutingContext.getRegexGroup(index: Int): String = getRouterParameter("group$index")

fun RoutingContext.getPathParameter(name: String): String = getRouterParameter(name)

fun RoutingContext.writeJson(obj: Any): RoutingContext = put(HttpHeader.CONTENT_TYPE, MimeTypes.Type.APPLICATION_JSON.asString()).write(Json.toJson(obj))

inline fun <reified T : Any> SimpleRequest.getJsonBody(charset: String): T = Json.parse(getStringBody(charset))

inline fun <reified T : Any> SimpleRequest.getJsonBody(): T = Json.parse(stringBody)

data class SuspendPromise<in C>(val succeeded: suspend (C) -> Unit, val failed: suspend (Throwable?) -> Unit)

val promiseQueueKey = "_promiseQueue"

fun <C> RoutingContext.getPromiseQueue(): Deque<SuspendPromise<C>>? = getAttr(promiseQueueKey)

@Suppress("UNCHECKED_CAST")
fun <C> RoutingContext.createPromiseQueueIfAbsent(): Deque<SuspendPromise<C>> = attributes.computeIfAbsent(promiseQueueKey) { ConcurrentLinkedDeque<SuspendPromise<C>>() } as Deque<SuspendPromise<C>>

fun <C> RoutingContext.promise(succeeded: suspend (C) -> Unit, failed: suspend (Throwable?) -> Unit): RoutingContext {
    val queue = createPromiseQueueIfAbsent<C>()
    queue.push(SuspendPromise<C>(succeeded = {
        succeeded.invoke(it)
        try {
            queue.pop().succeeded(it)
        } catch (e: NoSuchElementException) {
        }
    }, failed = {
        failed.invoke(it)
        try {
            queue.pop().failed(it)
        } catch (e: NoSuchElementException) {
        }
    }))
    return this
}

fun <C> RoutingContext.promise(succeeded: suspend (C) -> Unit): RoutingContext {
    promise(succeeded, {})
    return this
}

suspend fun <C> RoutingContext.succeed(result: C): Unit {
    getPromiseQueue<C>()?.pop()?.succeeded?.invoke(result)
}

suspend fun <C> RoutingContext.fail(x: Throwable? = null): Unit {
    getPromiseQueue<C>()?.pop()?.failed?.invoke(x)
}


// HTTP server DSL

/**
 * Response status line block
 *
 * @param block Response status line statement
 */
inline fun RoutingContext.statusLine(block: StatusLineBlock.() -> Unit): Unit = block.invoke(StatusLineBlock(this))

class StatusLineBlock(private val ctx: RoutingContext) {
    var status: Int = HttpStatus.OK_200
        set(value) {
            ctx.setStatus(value)
            field = value
        }

    var reason: String = HttpStatus.Code.OK.message
        set(value) {
            ctx.setReason(value)
            field = value
        }

    var httpVersion: HttpVersion = HttpVersion.HTTP_1_1
        set(value) {
            ctx.setHttpVersion(value)
            field = value
        }

    override fun toString(): String {
        return "StatusLineBlock(status=$status, reason='$reason', httpVersion=$httpVersion)"
    }

}

interface HttpFieldOperator {
    infix fun String.to(value: String): Unit

    infix fun HttpHeader.to(value: String): Unit

    operator fun HttpField.unaryPlus(): Unit
}

/**
 * Response HTTP header block
 *
 * @param block HTTP header statement
 */
inline fun RoutingContext.header(block: HeaderBlock.() -> Unit): Unit = block.invoke(HeaderBlock(this))

class HeaderBlock(ctx: RoutingContext) : HttpFieldOperator {

    val httpFields: HttpFields = ctx.response.fields

    override infix fun String.to(value: String): Unit {
        httpFields.put(this, value)
    }

    override infix fun HttpHeader.to(value: String): Unit {
        httpFields.put(this, value)
    }

    override operator fun HttpField.unaryPlus(): Unit {
        httpFields.add(this)
    }

    override fun toString(): String {
        return "HeaderBlock(httpFields=$httpFields)"
    }
}

/**
 * Response HTTP trailer block
 *
 * @param block HTTP trailer statement
 */
inline fun RoutingContext.trailer(block: TrailerBlock.() -> Unit): Unit = block.invoke(TrailerBlock(this))

class TrailerBlock(ctx: RoutingContext) : Supplier<HttpFields>, HttpFieldOperator {

    val httpFields: HttpFields = HttpFields()

    init {
        ctx.response.trailerSupplier = this
    }

    override fun get(): HttpFields = httpFields

    override infix fun String.to(value: String): Unit {
        httpFields.put(this, value)
    }

    override infix fun HttpHeader.to(value: String): Unit {
        httpFields.put(this, value)
    }

    override operator fun HttpField.unaryPlus(): Unit {
        httpFields.add(this)
    }

    override fun toString(): String {
        return "TrailerBlock(httpFields=$httpFields)"
    }

}

@HttpServerMarker
class RouterBlock(private val router: Router,
                  private val requestCtx: CoroutineLocal<RoutingContext>?) {

    var method: String = HttpMethod.GET.asString()
        set(value) {
            router.method(value)
            field = value
        }

    var methods: List<String> = listOf(HttpMethod.GET.asString(), HttpMethod.POST.asString())
        set(value) {
            value.forEach { router.method(it) }
            field = value
        }

    var httpMethod: HttpMethod = HttpMethod.GET
        set(value) {
            router.method(value)
            field = value
        }

    var httpMethods: List<HttpMethod> = listOf(HttpMethod.GET, HttpMethod.POST)
        set(value) {
            httpMethods.forEach { router.method(it) }
            field = value
        }

    var path: String = ""
        set(value) {
            router.path(value)
            field = value
        }

    var paths: List<String> = listOf()
        set(value) {
            router.paths(value)
            field = value
        }

    var regexPath: String = ""
        set(value) {
            router.pathRegex(value)
            field = value
        }

    var consumes: String = ""
        set(value) {
            router.consumes(value)
            field = value
        }

    var produces: String = ""
        set(value) {
            router.produces(value)
            field = value
        }

    fun asyncHandler(handler: suspend RoutingContext.(context: CoroutineContext) -> Unit): Unit {
        router.handler {
            it.response.isAsynchronous = true
            launch(requestCtx?.createContext(it) ?: AsyncPool) {
                handler.invoke(it, context)
            }
        }
    }

    fun handler(handler: RoutingContext.() -> Unit): Unit {
        router.handler(handler)
    }

    suspend fun <T : Closeable?, R> T.safeUse(block: suspend (T) -> R): R {
        var closed = false
        try {
            return block(this)
        } catch (e: Exception) {
            try {
                run(NonCancellable) {
                    closed = true
                    this?.close()
                }
            } catch (closeException: Exception) {
            }
            throw e
        } finally {
            if (!closed) {
                run(NonCancellable) {
                    this?.close()
                }
            }
        }
    }

    override fun toString(): String {
        return router.toString()
    }

}

interface HttpServerLifecycle {
    fun stop(): Unit

    fun listen(host: String, port: Int): Unit

    fun listen(port: Int): Unit

    fun listen(): Unit
}

@DslMarker
annotation class HttpServerMarker

/**
 * HTTP server DSL. It helps you write HTTP server elegantly.
 *
 * @param requestCtx
 * Maintain the routing context in the HTTP request lifecycle when you use the asynchronous handlers which run in the coroutine.
 * It visits RoutingContext in the external function conveniently. Usually, you can use it to trace HTTP request crossing all registered routers.
 *
 * @param serverConfiguration HTTP server configuration
 * @param httpBodyConfiguration HTTP body configuration
 * @param block HTTP server DSL block
 *
 */
@HttpServerMarker
class HttpServer(val requestCtx: CoroutineLocal<RoutingContext>? = null,
                 serverConfiguration: SimpleHTTPServerConfiguration = SimpleHTTPServerConfiguration(),
                 httpBodyConfiguration: HTTPBodyConfiguration = HTTPBodyConfiguration(),
                 block: HttpServer.() -> Unit) : HttpServerLifecycle {

    val server: SimpleHTTPServer = SimpleHTTPServer(serverConfiguration)
    val routerManager: RouterManager = RouterManager.create(httpBodyConfiguration)

    init {
        block.invoke(this)
    }

    constructor(coroutineLocal: CoroutineLocal<RoutingContext>?, block: HttpServer.() -> Unit)
            : this(coroutineLocal,
                   SimpleHTTPServerConfiguration(),
                   HTTPBodyConfiguration(),
                   block)

    constructor(block: HttpServer.() -> Unit) : this(null, block)

    constructor() : this({})

    override fun stop() = server.stop()

    override fun listen(host: String, port: Int) = server.headerComplete(routerManager::accept).listen(host, port)

    override fun listen(port: Int) = listen(InetAddress.getLocalHost().hostAddress, port)

    override fun listen() = server.headerComplete(routerManager::accept).listen()

    inline fun router(block: RouterBlock.() -> Unit): Unit {
        val r = RouterBlock(routerManager.register(), requestCtx)
        block.invoke(r)
        sysLogger.info("register $r")
    }

    inline fun addRouters(block: HttpServer.() -> Unit): Unit = block.invoke(this)
}