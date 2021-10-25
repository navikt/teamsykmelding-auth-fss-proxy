package no.nav.syfo

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

private const val PROXY_PREFIX = "PROXY_"

data class Environment(
    val applicationPort: Int = getEnvVar("APPLICATION_PORT", "8080").toInt(),
    val applicationName: String = getEnvVar("NAIS_APP_NAME", "teamsykmelding-auth-fss-proxy"),
    val clientId: String = getEnvVar("AZURE_APP_CLIENT_ID"),
    val clientSecret: String = getEnvVar("AZURE_APP_CLIENT_SECRET"),
    val jwkKeysUrl: String = getEnvVar("AZURE_OPENID_CONFIG_JWKS_URI"),
    val jwtIssuer: String = getEnvVar("AZURE_OPENID_CONFIG_ISSUER"),
    val securityTokenServiceUrl: String = getEnvVar("SECURITY_TOKEN_SERVICE_URL", "http://security-token-service.default/rest/v1/sts/token"),
    val proxyMappings: Map<String, URI> = getProxyMappings()
)

data class VaultCredentials(
    val serviceuserUsername: String,
    val serviceuserPassword: String
)

fun getProxyMappings(envVaribles: Map<String, String> = System.getenv()) =
    envVaribles.asSequence().filter { it.key.startsWith(PROXY_PREFIX) }.map { getKey(it.key) to getValue(it.value) }
        .toMap()

private fun getValue(value: String): URI {
    return URI(value)
}
private fun getKey(key: String): String {
    return key.substring(PROXY_PREFIX.length).lowercase().replace("_", "-")
}

fun getEnvVar(varName: String, defaultValue: String? = null) =
    System.getenv(varName) ?: defaultValue ?: throw RuntimeException("Missing required variable \"$varName\"")

fun getFileAsString(filePath: String) = String(Files.readAllBytes(Paths.get(filePath)), StandardCharsets.UTF_8)
