package dev.asambataiden.iam.bootstrap;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class BootstrapSecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // No browser sessions or cookie authentication: bearer-token API only.
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.csrf(csrf -> csrf.disable()); // Stateless bearer-only endpoints; never enable session auth without revisiting CSRF.
        http.httpBasic(basic -> basic.disable());
        http.formLogin(form -> form.disable());
        http.oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/api/v1/access-requests/**").hasAuthority("SCOPE_access:request")
                .anyRequest().denyAll());
        return http.build();
    }
}
