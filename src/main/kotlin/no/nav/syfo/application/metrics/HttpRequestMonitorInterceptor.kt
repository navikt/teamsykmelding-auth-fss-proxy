package no.nav.syfo.application.metrics

import io.ktor.application.ApplicationCall
import io.ktor.request.path
import io.ktor.util.pipeline.PipelineContext

val REGEX = """[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""".toRegex()
val REGEX_AKTORID = """[0-9]{13}""".toRegex()

fun monitorHttpRequests(): suspend PipelineContext<Unit, ApplicationCall>.(Unit) -> Unit {
    return {
        val path = context.request.path()
        val label = getLabel(path)
        val timer = HTTP_HISTOGRAM.labels(label).startTimer()
        proceed()
        timer.observeDuration()
    }
}

fun getLabel(path: String): String {
    val utenId = REGEX.replace(path, ":id")
    return REGEX_AKTORID.replace(utenId, ":aktorId")
}
