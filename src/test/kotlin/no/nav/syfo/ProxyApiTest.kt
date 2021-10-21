package no.nav.syfo

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.auth.authenticate
import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import io.ktor.client.features.json.JacksonSerializer
import io.ktor.client.features.json.JsonFeature
import io.ktor.features.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.jackson.jackson
import io.ktor.response.respond
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.testing.TestApplicationEngine
import io.ktor.server.testing.handleRequest
import io.mockk.coEvery
import io.mockk.mockk
import no.nav.syfo.application.ApplicationState
import no.nav.syfo.application.api.registerNaisApi
import no.nav.syfo.client.OidcToken
import no.nav.syfo.client.StsOidcClient
import no.nav.syfo.util.generateJWT
import no.nav.syfo.util.setUpTestApplicationWithAuth
import org.amshove.kluent.shouldBeEqualTo
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

class ProxyApiTest : Spek ({
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
    val mockHttpServerPort = ServerSocket(0).use { it.localPort }
    val mockHttpServerUrl = "http://localhost:$mockHttpServerPort"
    val mockServer = embeddedServer(Netty, mockHttpServerPort) {
        install(ContentNegotiation) {
            jackson {
                registerKotlinModule()
                registerModule(JavaTimeModule())
                configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            }
        }
        routing {
            get("/") {
                call.respond(HttpStatusCode.OK, GeneriskRespons("1234"))
            }
        }
    }.start()
    val proxyMapping = getProxyMappings(envVaribles = mapOf("PROXY_TEST_API" to mockHttpServerUrl))

    beforeEachTest {
        coEvery { oidcClient.oidcToken() } returns OidcToken("token", "type", 10L)
    }

    afterGroup {
        mockServer.stop(TimeUnit.SECONDS.toMillis(1), TimeUnit.SECONDS.toMillis(1))
    }

    describe("ProxyApi") {
        with(TestApplicationEngine()) {
            setUpTestApplicationWithAuth(proxyMapping, issuer)
            application.routing {
                registerNaisApi(ApplicationState(alive = true, ready = true))
                authenticate("servicebrukerAAD") {
                    registerProxyApi(oidcClient, httpClient, proxyMapping)
                }
            }
            it("Videresender til riktig api") {
                with(
                    handleRequest(HttpMethod.Get, "/test-api") {
                        addHeader("Sykmeldt-Fnr", "1234")
                        addHeader(
                            HttpHeaders.Authorization,
                            "Bearer ${generateJWT(
                                    consumerClientId = "dinesykmeldte-backend",
                                    audience = "teamsykmelding-auth-fss-proxy",
                                    issuer = issuer
                                )}"
                        )
                    }
                ) {
                    response.status() shouldBeEqualTo HttpStatusCode.OK
                    response.content?.shouldBeEqualTo("{\"fnr\":\"1234\"}")
                }

            }
            it("Videresender til riktig api med path param") {

            }
            it("Videresender til riktig api med path param og query param") {

            }
            it("Videresender feilrespons") {

            }
            it("Returnerer BadGateway for ukjent api") {

            }
            it("Manglende token gir unauthorized") {

            }
            it("Feil audience gir unauthorized") {

            }
        }
    }
})

data class GeneriskRespons(
    private val fnr: String
)
