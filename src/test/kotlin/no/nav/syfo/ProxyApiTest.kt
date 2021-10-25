package no.nav.syfo

import com.auth0.jwk.JwkProviderBuilder
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.auth.authenticate
import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import io.ktor.client.features.ClientRequestException
import io.ktor.client.features.ServerResponseException
import io.ktor.client.features.json.JacksonSerializer
import io.ktor.client.features.json.JsonFeature
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readText
import io.ktor.features.CallId
import io.ktor.features.ContentNegotiation
import io.ktor.features.StatusPages
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.jackson.jackson
import io.ktor.response.respond
import io.ktor.response.respondBytes
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import no.nav.syfo.application.ApplicationState
import no.nav.syfo.application.api.registerNaisApi
import no.nav.syfo.application.authentication.setupAuth
import no.nav.syfo.client.OidcToken
import no.nav.syfo.client.StsOidcClient
import no.nav.syfo.util.generateJWT
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe
import java.net.ServerSocket
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith

class ProxyApiTest : Spek({
    val oidcClient = mockk<StsOidcClient>()
    val issuer = "issuer"
    val httpClient = HttpClient(Apache) {
        install(JsonFeature) {
            serializer = JacksonSerializer {
                registerKotlinModule()
                registerModule(JavaTimeModule())
                configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            }
        }
    }
    val mockHttpServerPortTilbyder = ServerSocket(0).use { it.localPort }
    val mockHttpServerUrlTilbyder = "http://localhost:$mockHttpServerPortTilbyder"
    val mockServerTilbyder = embeddedServer(Netty, mockHttpServerPortTilbyder) {
        install(ContentNegotiation) {
            jackson {
                registerKotlinModule()
                registerModule(JavaTimeModule())
                configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            }
        }
        routing {
            get("/with/{id}") {
                val id = call.parameters["id"]
                val param = call.parameters["param"]
                val stringBuilder = StringBuilder()
                stringBuilder.append("OK")
                if (id != null) stringBuilder.append(" + $id")
                if (param != null) stringBuilder.append(" + $param")
                call.respondBytes(contentType = ContentType.Text.Plain, status = HttpStatusCode.OK, bytes = stringBuilder.toString().toByteArray())
            }
            get("/error") {
                call.respond(HttpStatusCode.InternalServerError)
            }
            get("/error-client") {
                call.respond(HttpStatusCode.BadRequest)
            }
            get("") {
                call.respond(HttpStatusCode.OK, GeneriskRespons(call.request.headers["Sykmeldt-Fnr"]!!))
            }
        }
    }.start()

    val proxyMapping = getProxyMappings(envVaribles = mapOf("PROXY_TEST_API" to mockHttpServerUrlTilbyder))
    val environment = Environment(
        clientId = "teamsykmelding-auth-fss-proxy",
        clientSecret = "supersecret",
        jwkKeysUrl = "https://keys.url",
        jwtIssuer = issuer,
        proxyMappings = proxyMapping
    )
    val path = "src/test/resources/jwkset.json"
    val uri = Paths.get(path).toUri().toURL()
    val jwkProvider = JwkProviderBuilder(uri).build()
    val mockHttpServerPortProxyApp = ServerSocket(0).use { it.localPort }
    val mockHttpServerUrlProxyApp = "http://localhost:$mockHttpServerPortProxyApp"
    val mockServerProxyApp = embeddedServer(Netty, mockHttpServerPortProxyApp) {
        setupAuth(jwkProvider, environment)
        install(ContentNegotiation) {
            jackson {
                registerKotlinModule()
                registerModule(JavaTimeModule())
                configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            }
        }
        install(CallId) {
            generate { UUID.randomUUID().toString() }
            verify { callId: String -> callId.isNotEmpty() }
            header(HttpHeaders.XCorrelationId)
        }
        install(StatusPages) {
            exception<Throwable> { cause ->
                log.error("Caught exception", cause)
                call.respond(HttpStatusCode.InternalServerError, cause.message ?: "Unknown error")
            }
        }
        routing {
            registerNaisApi(ApplicationState(alive = true, ready = true))
            authenticate("servicebrukerAAD") {
                registerProxyApi(oidcClient, httpClient, proxyMapping)
            }
        }
    }.start()

    beforeEachTest {
        coEvery { oidcClient.oidcToken() } returns OidcToken("token", "type", 10L)
    }

    afterGroup {
        mockServerTilbyder.stop(TimeUnit.SECONDS.toMillis(1), TimeUnit.SECONDS.toMillis(1))
        mockServerProxyApp.stop(TimeUnit.SECONDS.toMillis(1), TimeUnit.SECONDS.toMillis(1))
    }

    describe("ProxyApi") {
        it("Videresender til riktig api") {
            runBlocking {
                val response = httpClient.get<HttpResponse>("$mockHttpServerUrlProxyApp/test-api") {
                    headers.append("Sykmeldt-Fnr", "1234")
                    headers.append(
                        HttpHeaders.Authorization,
                        "Bearer ${generateJWT(
                            consumerClientId = "dinesykmeldte-backend",
                            audience = "teamsykmelding-auth-fss-proxy",
                            issuer = issuer
                        )}"
                    )
                }
                response.status shouldBeEqualTo HttpStatusCode.OK
                response.readText() shouldBeEqualTo "{\"fnr\":\"1234\"}"
            }
        }
        it("Videresender til riktig api med path param") {
            runBlocking {
                val response = httpClient.get<HttpResponse>("$mockHttpServerUrlProxyApp/test-api/with/1") {
                    headers.append("Sykmeldt-Fnr", "1234")
                    headers.append(
                        HttpHeaders.Authorization,
                        "Bearer ${generateJWT(
                            consumerClientId = "dinesykmeldte-backend",
                            audience = "teamsykmelding-auth-fss-proxy",
                            issuer = issuer
                        )}"
                    )
                }
                response.status shouldBeEqualTo HttpStatusCode.OK
                response.readText() shouldBeEqualTo "OK + 1"
            }
        }
        it("Videresender til riktig api med path param og query param") {
            runBlocking {
                val response = httpClient.get<HttpResponse>("$mockHttpServerUrlProxyApp/test-api/with/1?param=2") {
                    headers.append("Sykmeldt-Fnr", "1234")
                    headers.append(
                        HttpHeaders.Authorization,
                        "Bearer ${generateJWT(
                            consumerClientId = "dinesykmeldte-backend",
                            audience = "teamsykmelding-auth-fss-proxy",
                            issuer = issuer
                        )}"
                    )
                }
                response.status shouldBeEqualTo HttpStatusCode.OK
                response.readText() shouldBeEqualTo "OK + 1 + 2"
            }
        }
        it("Videresender feilrespons (server)") {
            runBlocking {
                try {
                    httpClient.get<HttpResponse>("$mockHttpServerUrlProxyApp/test-api/error") {
                        headers.append("Sykmeldt-Fnr", "1234")
                        headers.append(
                            HttpHeaders.Authorization,
                            "Bearer ${generateJWT(
                                consumerClientId = "dinesykmeldte-backend",
                                audience = "teamsykmelding-auth-fss-proxy",
                                issuer = issuer
                            )}"
                        )
                    }
                } catch (e: Exception) {
                    e shouldBeInstanceOf ServerResponseException::class.java
                    (e as ServerResponseException).response.status shouldBeEqualTo HttpStatusCode.InternalServerError
                }
            }
        }
        it("Videresender feilrespons (client)") {
            runBlocking {
                try {
                    httpClient.get<HttpResponse>("$mockHttpServerUrlProxyApp/test-api/error-client") {
                        headers.append("Sykmeldt-Fnr", "1234")
                        headers.append(
                            HttpHeaders.Authorization,
                            "Bearer ${generateJWT(
                                consumerClientId = "dinesykmeldte-backend",
                                audience = "teamsykmelding-auth-fss-proxy",
                                issuer = issuer
                            )}"
                        )
                    }
                } catch (e: Exception) {
                    e shouldBeInstanceOf ClientRequestException::class.java
                    (e as ClientRequestException).response.status shouldBeEqualTo HttpStatusCode.BadRequest
                }
            }
        }
        it("Returnerer BadGateway for ukjent api") {
            assertFailsWith<ServerResponseException> {
                runBlocking {
                    httpClient.get<HttpResponse>("$mockHttpServerUrlProxyApp/noe-annet") {
                        headers.append("Sykmeldt-Fnr", "1234")
                        headers.append(
                            HttpHeaders.Authorization,
                            "Bearer ${generateJWT(
                                consumerClientId = "dinesykmeldte-backend",
                                audience = "teamsykmelding-auth-fss-proxy",
                                issuer = issuer
                            )}"
                        )
                    }
                }
            }
        }
        it("Manglende token gir unauthorized") {
            assertFailsWith<ClientRequestException> {
                runBlocking {
                    httpClient.get<HttpResponse>("$mockHttpServerUrlProxyApp/test-api") {
                        headers.append("Sykmeldt-Fnr", "1234")
                    }
                }
            }
        }
        it("Feil audience gir unauthorized") {
            assertFailsWith<ClientRequestException> {
                runBlocking {
                    httpClient.get<HttpResponse>("$mockHttpServerUrlProxyApp/test-api") {
                        headers.append("Sykmeldt-Fnr", "1234")
                        headers.append(
                            HttpHeaders.Authorization,
                            "Bearer ${generateJWT(
                                consumerClientId = "dinesykmeldte-backend",
                                audience = "annen-proxy",
                                issuer = issuer
                            )}"
                        )
                    }
                }
            }
        }
    }
})

data class GeneriskRespons(
    val fnr: String
)
