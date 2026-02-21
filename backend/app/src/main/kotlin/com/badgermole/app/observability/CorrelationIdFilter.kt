package com.badgermole.app.observability

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val incomingCorrelationId =
            request.getHeader(CORRELATION_ID_HEADER)?.trim()?.takeIf { it.isNotBlank() }
        val correlationId = incomingCorrelationId ?: UUID.randomUUID().toString()

        MDC.put(CORRELATION_ID_MDC_KEY, correlationId)
        response.setHeader(CORRELATION_ID_HEADER, correlationId)
        request.setAttribute(CORRELATION_ID_REQUEST_ATTRIBUTE, correlationId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(CORRELATION_ID_MDC_KEY)
        }
    }

    companion object {
        const val CORRELATION_ID_HEADER = "X-Correlation-Id"
        const val CORRELATION_ID_MDC_KEY = "correlationId"
        const val CORRELATION_ID_REQUEST_ATTRIBUTE = "badgermole.correlationId"
    }
}
