package kr.ac.sejong.cedpt.cctvconsent;

import java.time.Duration;
import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

/**
 * 학생 화면 언어: 첫 화면에서 고른 언어(?lang=ko|en)를 쿠키에 1년 저장한다. 기본은 한국어.
 */
@Configuration(proxyBeanMethods = false)
class WebConfig implements WebMvcConfigurer {

	@Bean
	LocaleResolver localeResolver() {
		CookieLocaleResolver resolver = new CookieLocaleResolver("lang");
		resolver.setDefaultLocale(Locale.KOREAN);
		resolver.setCookieMaxAge(Duration.ofDays(365));
		return resolver;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
		interceptor.setParamName("lang");
		interceptor.setIgnoreInvalidLocale(true);
		registry.addInterceptor(interceptor);
	}

}
