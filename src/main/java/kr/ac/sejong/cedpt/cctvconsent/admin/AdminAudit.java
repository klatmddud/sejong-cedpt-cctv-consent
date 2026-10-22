package kr.ac.sejong.cedpt.cctvconsent.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 관리자 작업 감사 로그("audit" 로거). 관리자 계정이 하나라 누가 했는지 대신 언제, 어디서(IP), 무엇을 했는지 남긴다.
 * 학번, 이름 같은 개인정보는 남기지 않는다.
 */
final class AdminAudit {

	private static final Logger log = LoggerFactory.getLogger("audit");

	private AdminAudit() {
	}

	/** @param message SLF4J 형식("{}" 자리표시자) */
	static void log(String message, Object... args) {
		Object[] withIp = new Object[args.length + 1];
		withIp[0] = clientIp();
		System.arraycopy(args, 0, withIp, 1, args.length);
		log.info("관리자 [{}] " + message, withIp);
	}

	private static String clientIp() {
		return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
				? attributes.getRequest().getRemoteAddr() : "-";
	}

}
