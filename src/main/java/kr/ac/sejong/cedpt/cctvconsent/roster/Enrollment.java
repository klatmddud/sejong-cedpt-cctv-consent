package kr.ac.sejong.cedpt.cctvconsent.roster;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

@Entity
public class Enrollment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "course_id")
	private Course course;

	@Column(nullable = false)
	private String studentId;

	@Column(nullable = false)
	private String studentName;

	private String department;

	private String grade;

	/** 가장 최근에 올린 명부에 있는지. 수강을 취소한 학생도 이미 받은 동의는 남긴다. */
	@Column(nullable = false)
	private boolean inLatestRoster;

	protected Enrollment() {
	}

	public Enrollment(Course course, String studentId) {
		this.course = course;
		this.studentId = studentId;
	}

	public void update(String studentName, String department, String grade) {
		this.studentName = studentName;
		this.department = department;
		this.grade = grade;
		this.inLatestRoster = true;
	}

	public void markRemovedFromRoster() {
		this.inLatestRoster = false;
	}

	public Long getId() {
		return id;
	}

	public Course getCourse() {
		return course;
	}

	public String getStudentId() {
		return studentId;
	}

	public String getStudentName() {
		return studentName;
	}

	public String getDepartment() {
		return department;
	}

	public String getGrade() {
		return grade;
	}

	public boolean isInLatestRoster() {
		return inLatestRoster;
	}

}
