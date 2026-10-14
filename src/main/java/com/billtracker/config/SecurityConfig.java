package com.billtracker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        var api = PathPatternRequestMatcher.withDefaults().matcher("/api/**");
        var unauthorized = new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);
        var login = new LoginUrlAuthenticationEntryPoint("/login");
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/register", "/error", "/css/**", "/js/**", "/favicon.svg",
                                "/actuator/health/**", "/join/**").permitAll()
                        // Authenticated by shared secret in the controller, not by a user session.
                        .requestMatchers("/api/inbound/**").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .usernameParameter("email")
                        .defaultSuccessUrl("/", true)
                        .permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
                // Lets scripts and curl use the JSON API with -u email:password.
                .httpBasic(Customizer.withDefaults())
                // API callers get a 401; browsers get sent to the login page.
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, e) -> {
                    if (api.matches(request)) {
                        unauthorized.commence(request, response, e);
                    } else {
                        login.commence(request, response, e);
                    }
                }))
                // Requests carrying explicit credentials (not the session cookie) can't be forged cross-site.
                .csrf(csrf -> csrf.ignoringRequestMatchers(
                        PathPatternRequestMatcher.withDefaults().matcher("/api/inbound/**"),
                        request -> request.getHeader("Authorization") != null))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; img-src 'self' data:; style-src 'self'; "
                                        + "script-src 'self'; frame-ancestors 'none'; form-action 'self'")));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
