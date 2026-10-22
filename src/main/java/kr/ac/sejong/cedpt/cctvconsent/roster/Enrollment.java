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

	/** 한 번이라도 명부에 있었는지. 명부로 확인된 적 없는 수강만 명부 업로드 때 삭제할 수 있다. */
	@Column(nullable = false)
	private boolean everInRoster;

	/** 학생이 마지막으로 로그인했을 때 sjpt에서 확인한 수강인지. 수강 중 = 명부 또는 sjpt에서 확인됨. */
	@Column(nullable = false)
	private boolean inSjpt;

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
		this.everInRoster = true;
	}

	public void markRemovedFromRoster() {
		this.inLatestRoster = false;
	}

	/** 로그인 때 sjpt에서 수강을 확인했다. 학과, 학년은 sjpt에 없으므로 명부 값을 남긴다. */
	public void confirmBySjpt(String studentName) {
		this.studentName = studentName;
		this.inSjpt = true;
	}

	public void markNotInSjpt() {
		this.inSjpt = false;
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

	public boolean isEverInRoster() {
		return everInRoster;
	}

	public boolean isInSjpt() {
		return inSjpt;
	}

}
