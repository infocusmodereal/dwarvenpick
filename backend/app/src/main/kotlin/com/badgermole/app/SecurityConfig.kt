package com.badgermole.app

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
import org.springframework.http.MediaType

@Configuration
class SecurityConfig(
    private val objectMapper: ObjectMapper,
) {
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun securityContextRepository(): SecurityContextRepository = HttpSessionSecurityContextRepository()

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        securityContextRepository: SecurityContextRepository,
    ): SecurityFilterChain {
        http
            .csrf {
                it.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
            }
            .securityContext { context ->
                context.securityContextRepository(securityContextRepository)
            }
            .authorizeHttpRequests {
                it
                    .requestMatchers(
                        "/actuator/health",
                        "/api/health",
                        "/api/version",
                        "/api/auth/login",
                        "/api/auth/ldap/login",
                        "/api/auth/csrf",
                    ).permitAll()
                    .requestMatchers(
                        "/api/admin/**",
                        "/api/auth/admin/**",
                    ).hasRole("SYSTEM_ADMIN")
                    .requestMatchers(
                        "/api/datasources/*/test-connection",
                    ).hasRole("SYSTEM_ADMIN")
                    .anyRequest().authenticated()
            }
            .headers { headers ->
                headers.contentTypeOptions(Customizer.withDefaults())
                headers.referrerPolicy { referrerPolicy ->
                    referrerPolicy.policy(
                        ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN,
                    )
                }
            }
            .exceptionHandling {
                it.authenticationEntryPoint { _, response, _ ->
                    writeJsonError(
                        response = response,
                        status = HttpServletResponse.SC_UNAUTHORIZED,
                        message = "Authentication required.",
                    )
                }
                it.accessDeniedHandler { _, response, _ ->
                    writeJsonError(
                        response = response,
                        status = HttpServletResponse.SC_FORBIDDEN,
                        message = "Access denied or CSRF token is invalid.",
                    )
                }
            }
            .httpBasic { basic -> basic.disable() }
            .formLogin { form -> form.disable() }
            .logout { logout -> logout.disable() }

        return http.build()
    }

    private fun writeJsonError(
        response: HttpServletResponse,
        status: Int,
        message: String,
    ) {
        SecurityContextHolder.clearContext()
        response.status = status
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(objectMapper.writeValueAsString(mapOf("error" to message)))
    }
}
