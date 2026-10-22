package kr.ac.sejong.cedpt.cctvconsent.roster;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
public class Semester {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private int year;

	@Column(nullable = false)
	private String term;

	@Column(nullable = false)
	private LocalDate endsOn;

	@Column(nullable = false)
	private LocalDate purgeOn;

	@Column(nullable = false)
	private OffsetDateTime createdAt;

	protected Semester() {
	}

	public Semester(int year, String term, LocalDate endsOn) {
		this.year = year;
		this.term = term;
		this.createdAt = OffsetDateTime.now();
		changeEndsOn(endsOn);
	}

	/**
	 * 학기 종료일: 1학기 그 해 6월 30일, 2학기 그 해 12월 31일. 명부 업로드와 학생 로그인 동기화가 같이 쓴다.
	 *
	 * @throws IllegalArgumentException 1학기, 2학기가 아님
	 */
	public static LocalDate endsOn(int year, String term) {
		return switch (term) {
			case "1" -> LocalDate.of(year, 6, 30);
			case "2" -> LocalDate.of(year, 12, 31);
			default -> throw new IllegalArgumentException("1학기, 2학기만 지원합니다: " + term);
		};
	}

	/** 동의서는 학기 종료 1년 뒤 일괄 파기한다. */
	public void changeEndsOn(LocalDate endsOn) {
		this.endsOn = endsOn;
		this.purgeOn = endsOn.plusYears(1);
	}

	public Long getId() {
		return id;
	}

	public int getYear() {
		return year;
	}

	public String getTerm() {
		return term;
	}

	public LocalDate getEndsOn() {
		return endsOn;
	}

	public LocalDate getPurgeOn() {
		return purgeOn;
	}

	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}

}
