package dev.asambataiden.iam.bootstrap;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class BootstrapSecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // Milestone 00 exposes no business endpoints or user-facing login mechanism.
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().denyAll());
        http.httpBasic(basic -> basic.disable());
        http.formLogin(form -> form.disable());
        // CSRF protection remains enabled by default. No public mutations are available.
        return http.build();
    }
}
