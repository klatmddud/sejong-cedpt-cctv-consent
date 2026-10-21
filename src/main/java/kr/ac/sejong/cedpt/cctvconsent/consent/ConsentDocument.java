package kr.ac.sejong.cedpt.cctvconsent.consent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;

/** 서명 이미지와 완성된 동의서 PDF. 서명 당시 모습 그대로 보관한다. */
@Entity
public class ConsentDocument {

	@Id
	private Long consentId;

	@MapsId
	@OneToOne(optional = false)
	@JoinColumn(name = "consent_id")
	private Consent consent;

	@Column(nullable = false)
	private byte[] signaturePng;

	@Column(nullable = false)
	private byte[] pdf;

	protected ConsentDocument() {
	}

	public ConsentDocument(Consent consent, byte[] signaturePng, byte[] pdf) {
		this.consent = consent;
		this.signaturePng = signaturePng;
		this.pdf = pdf;
	}

	public Consent getConsent() {
		return consent;
	}

	public byte[] getPdf() {
		return pdf;
	}

}
