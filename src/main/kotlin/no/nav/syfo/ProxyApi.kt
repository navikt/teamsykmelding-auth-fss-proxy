package no.nav.syfo

import io.ktor.application.call
import io.ktor.client.HttpClient
import io.ktor.client.features.ResponseException
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.request.path
import io.ktor.request.uri
import io.ktor.response.respond
import io.ktor.response.respondBytes
import io.ktor.routing.Route
import io.ktor.routing.get
import io.ktor.util.filter
import no.nav.syfo.client.StsOidcClient
import java.net.URI

fun Route.registerProxyApi(oidcClient: StsOidcClient, httpClient: HttpClient, proxyMapping: Map<String, URI>) {
    get("/{...}") {
        val proxyApi = call.request.path().split("/")[1]
        val proxyPath = call.request.uri.substring(proxyApi.length + 1)
        if (proxyMapping.containsKey(proxyApi)) {
            val proxyHeaders = call.request.headers.filter { key, _ -> !HttpHeaders.isUnsafe(key) && key != HttpHeaders.Authorization }
            val url = proxyMapping[proxyApi].toString() + proxyPath
            val oidcToken = oidcClient.oidcToken()
            log.info("Videresender til $url")
            try {
                val response = httpClient.get<HttpResponse>(urlString = url) {
                    headers.appendAll(proxyHeaders)
                    headers.append(HttpHeaders.Authorization, "Bearer ${oidcToken.access_token}")
                }
                log.info("Mottok svar med kode ${response.status}")
                call.respondBytes(contentType = response.contentType(), status = response.status, bytes = response.readBytes())
            } catch (e: Exception) {
                if (e is ResponseException) {
                    log.warn("Mottok feilkode fra api: ${e.response.status}")
                    call.respond(e.response.status)
                } else {
                    log.error("Noe gikk galt", e)
                    throw e
                }
            }
        } else {
            call.respond(HttpStatusCode.BadGateway, "Application $proxyApi not configured")
        }
    }
}
