package kr.ac.sejong.cedpt.cctvconsent.roster;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.time.OffsetDateTime;

/** 명부 업로드 이력. */
@Entity
public class RosterUpload {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "semester_id")
	private Semester semester;

	@Column(nullable = false)
	private String fileName;

	@Column(nullable = false)
	private OffsetDateTime uploadedAt;

	@Column(nullable = false)
	private int courseCount;

	@Column(nullable = false)
	private int enrollmentCount;

	@Column(nullable = false)
	private int addedCount;

	@Column(nullable = false)
	private int removedCount;

	protected RosterUpload() {
	}

	public RosterUpload(Semester semester, String fileName, int courseCount, int enrollmentCount,
			int addedCount, int removedCount) {
		this.semester = semester;
		this.fileName = fileName;
		this.uploadedAt = OffsetDateTime.now();
		this.courseCount = courseCount;
		this.enrollmentCount = enrollmentCount;
		this.addedCount = addedCount;
		this.removedCount = removedCount;
	}

	public Long getId() {
		return id;
	}

	public String getFileName() {
		return fileName;
	}

	public OffsetDateTime getUploadedAt() {
		return uploadedAt;
	}

	public int getCourseCount() {
		return courseCount;
	}

	public int getEnrollmentCount() {
		return enrollmentCount;
	}

	public int getAddedCount() {
		return addedCount;
	}

	public int getRemovedCount() {
		return removedCount;
	}

}
