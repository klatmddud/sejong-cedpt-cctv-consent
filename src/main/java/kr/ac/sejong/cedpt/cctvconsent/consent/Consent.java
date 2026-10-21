package kr.ac.sejong.cedpt.cctvconsent.consent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import java.time.OffsetDateTime;
import kr.ac.sejong.cedpt.cctvconsent.roster.Enrollment;

@Entity
public class Consent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "enrollment_id")
	private Enrollment enrollment;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ConsentLanguage language;

	@Column(nullable = false)
	private String signerName;

	@Column(nullable = false)
	private OffsetDateTime signedAt;

	protected Consent() {
	}

	public Consent(Enrollment enrollment, ConsentLanguage language, String signerName, OffsetDateTime signedAt) {
		this.enrollment = enrollment;
		this.language = language;
		this.signerName = signerName;
		this.signedAt = signedAt;
	}

	public Long getId() {
		return id;
	}

	public Enrollment getEnrollment() {
		return enrollment;
	}

	public ConsentLanguage getLanguage() {
		return language;
	}

	public String getSignerName() {
		return signerName;
	}

	public OffsetDateTime getSignedAt() {
		return signedAt;
	}

}
