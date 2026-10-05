package com.seminario.legaladministrator.config.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import java.util.Arrays;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    private final JwtFilter jwtFilter;
    @Value("${app.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, ex) -> {
                            response.setStatus(401);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"status\":401,\"message\":\"Se requiere una sesión válida.\"}");
                        })
                        .accessDeniedHandler((request, response, ex) -> {
                            response.setStatus(403);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"status\":403,\"message\":\"No tienes permisos para este recurso.\"}");
                        }))
                .authorizeHttpRequests(auth -> auth
                        // 1. Permitir peticiones OPTIONS
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // 2. Permitir cualquier subruta de auth (login, refresh)
                        .requestMatchers("/api/v1/auth/**").permitAll()

                        // 3. Rutas específicas protegidas por roles
                        .requestMatchers("/api/v1/cash", "/api/v1/cash/**", "/api/v1/clients", "/api/v1/clients/**",
                                "/api/v1/legal-processes", "/api/v1/legal-processes/**", "/api/v1/catalogs/**")
                            .hasAnyAuthority("Administrador", "Abogada")
                        .requestMatchers(HttpMethod.POST, "/api/v1/users/register").hasAnyAuthority("Administrador", "Abogada")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/users/**").hasAnyAuthority("Administrador", "Abogada")
                        .requestMatchers(HttpMethod.POST, "/api/v1/requirements", "/api/v1/process-types")
                            .hasAnyAuthority("Administrador", "Abogada")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/requirements/**", "/api/v1/process-types/**")
                            .hasAnyAuthority("Administrador", "Abogada")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/requirements/**", "/api/v1/process-types/**")
                            .hasAnyAuthority("Administrador", "Abogada")

                        // 4. Todo lo demás requiere autenticación obligatoria
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::strip)
                .filter(origin -> !origin.isEmpty())
                .toList();
        if (origins.stream().anyMatch(origin -> origin.contains("*"))) {
            throw new IllegalStateException("Configura orígenes CORS explícitos, sin comodines.");
        }
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
        configuration.setAllowCredentials(false);
        configuration.setExposedHeaders(List.of("Idempotency-Replayed"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
