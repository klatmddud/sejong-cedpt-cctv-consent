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
