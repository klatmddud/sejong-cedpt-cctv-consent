package kr.ac.sejong.cedpt.cctvconsent.consent;

import java.util.Locale;

public enum ConsentLanguage {

	KO("consent/consent_ko.pdf"),
	EN("consent/consent_en.pdf");

	private final String template;

	ConsentLanguage(String template) {
		this.template = template;
	}

	String template() {
		return template;
	}

	public String code() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** 알 수 없는 값은 한국어로 본다. */
	public static ConsentLanguage fromCode(String code) {
		return "en".equalsIgnoreCase(code) ? EN : KO;
	}

	public static ConsentLanguage fromLocale(Locale locale) {
		return fromCode(locale.getLanguage());
	}

}
