package com.dwarvenpick.app.auth

import org.springframework.boot.autoconfigure.web.ServerProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.session.web.http.CookieSerializer
import org.springframework.session.web.http.DefaultCookieSerializer

class DeviceSessionCookieSerializer : DefaultCookieSerializer() {
    override fun writeCookieValue(cookieValue: CookieSerializer.CookieValue) {
        val maxAge = cookieValue.request.getAttribute(MAX_AGE_ATTRIBUTE) as? Int
        if (cookieValue.cookieValue.isNotEmpty() && maxAge != null) {
            cookieValue.cookieMaxAge = maxAge
        }
        super.writeCookieValue(cookieValue)
    }

    companion object {
        const val MAX_AGE_ATTRIBUTE = "com.dwarvenpick.auth.deviceCookieMaxAge"
    }
}

@Configuration
class DeviceSessionCookieConfiguration {
    @Bean
    fun cookieSerializer(serverProperties: ServerProperties): CookieSerializer =
        DeviceSessionCookieSerializer().apply {
            val cookie = serverProperties.servlet.session.cookie
            cookie.name?.let(::setCookieName)
            cookie.domain?.let(::setDomainName)
            cookie.path?.let(::setCookiePath)
            cookie.httpOnly?.let(::setUseHttpOnlyCookie)
            cookie.secure?.let(::setUseSecureCookie)
            cookie.sameSite?.let { setSameSite(it.attributeValue()) }
            cookie.partitioned?.let(::setPartitioned)
        }
}
