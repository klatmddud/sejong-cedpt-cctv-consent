package kr.ac.sejong.cedpt.cctvconsent.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 관리자 페이지(/admin/**)는 공용 관리자 계정 하나로 로그인한다. 그 밖의 경로는 명시한 것만 연다.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http.authorizeHttpRequests(auth -> auth
						.requestMatchers("/", "/css/**", "/actuator/health", "/error").permitAll()
						.requestMatchers("/admin/login").permitAll()
						.requestMatchers("/admin/**").hasRole("ADMIN")
						.anyRequest().denyAll())
				.formLogin(form -> form
						.loginPage("/admin/login")
						.defaultSuccessUrl("/admin", true)
						.failureUrl("/admin/login?error"))
				.logout(logout -> logout
						.logoutUrl("/admin/logout")
						.logoutSuccessUrl("/admin/login?logout"));
		return http.build();
	}

	@Bean
	UserDetailsService adminAccount(@Value("${admin.username}") String username,
			@Value("${admin.password-hash}") String passwordHash) {
		if (!passwordHash.startsWith("$2")) {
			throw new IllegalStateException(
					"관리자 비밀번호 해시(ADMIN_PASSWORD_HASH, BCrypt)가 설정되지 않았습니다. deploy/README.md를 참고하세요.");
		}
		return new InMemoryUserDetailsManager(User.withUsername(username)
				.password(passwordHash)
				.roles("ADMIN")
				.build());
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

}
