package com.badgermole.app.observability

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val incomingCorrelationId =
            request.getHeader(correlationIdHeader)?.trim()?.takeIf { it.isNotBlank() }
        val correlationId = incomingCorrelationId ?: UUID.randomUUID().toString()

        MDC.put(correlationIdMdcKey, correlationId)
        response.setHeader(correlationIdHeader, correlationId)
        request.setAttribute(correlationIdRequestAttribute, correlationId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(correlationIdMdcKey)
        }
    }

    companion object {
        const val correlationIdHeader = "X-Correlation-Id"
        const val correlationIdMdcKey = "correlationId"
        const val correlationIdRequestAttribute = "badgermole.correlationId"
    }
}
