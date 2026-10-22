package kr.ac.sejong.cedpt.cctvconsent;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 예약 작업: 파기 예정일이 지난 학기 삭제(SemesterPurge), 오래된 로그인 실패 기록 정리. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfig {
}
