package com.ragqa.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.common.Result;
import com.ragqa.service.CurrentUserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfFilter;

import java.time.Clock;
import java.util.Map;

/** 员工账号密码登录；使用Spring会话和CSRF机制，不自己签发长期JWT。 */
@Configuration
public class SecurityConfig {
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper json, CurrentUserService users,
            @Value("${knowingorder.security.login-per-address-per-minute:10}") int perAddressLimit,
            @Value("${knowingorder.security.login-global-per-minute:120}") int globalLimit)
            throws Exception {
        Clock clock = Clock.systemUTC();
        http.cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/chat", "/api/products/search"))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/health", "/api/auth/csrf", "/api/auth/login",
                                "/api/chat", "/api/products/search").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, ex) -> SecurityJson.failure(json, response,
                                401, "请先登录或登录已失效"))
                        .accessDeniedHandler((request, response, ex) -> SecurityJson.failure(json, response,
                                403, "请求校验失败，请刷新登录凭据后重试")))
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .formLogin(form -> form.loginPage("/api/auth/login").loginProcessingUrl("/api/auth/login")
                        .successHandler((request, response, authentication) -> {
                            request.getSession().setAttribute(SessionIdentityFilter.ISSUED_AT, clock.millis());
                            var account = (AccountDetails) authentication.getPrincipal();
                            SecurityJson.write(json, response, Result.ok(Map.of("userId", account.getIdentity().userId())));
                        })
                        .failureHandler((request, response, ex) -> {
                            if (ex instanceof InternalAuthenticationServiceException) {
                                SecurityJson.failure(json, response, 503, "身份服务暂不可用，请稍后重试");
                            } else {
                                SecurityJson.failure(json, response, 401, "账号或密码错误，或账号不可用");
                            }
                        }))
                .logout(logout -> logout.logoutUrl("/api/auth/logout").invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) ->
                                SecurityJson.write(json, response, Result.ok())))
                .addFilterBefore(new LoginGuardFilter(json, clock, perAddressLimit, globalLimit), CsrfFilter.class)
                .addFilterAfter(new SessionIdentityFilter(users, json, clock), AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
