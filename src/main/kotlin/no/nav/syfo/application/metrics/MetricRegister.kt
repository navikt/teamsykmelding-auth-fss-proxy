package no.nav.syfo.application.metrics

import io.prometheus.client.Histogram

const val METRICS_NS = "teamsykm_auth_fss_proxy"

val HTTP_HISTOGRAM: Histogram = Histogram.Builder()
    .labelNames("path")
    .name("requests_duration_seconds")
    .help("http requests durations for incoming requests in seconds")
    .register()
